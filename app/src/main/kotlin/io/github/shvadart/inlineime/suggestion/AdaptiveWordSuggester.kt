package io.github.shvadart.inlineime.suggestion

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.min

data class WordCandidate(
    val word: String,
    val score: Int,
    val editDistance: Int,
    val prefixMatch: Boolean,
    val typoCost: Int = editDistance * 10,
)

class AdaptiveWordSuggester(context: Context, private val prefs: SharedPreferences) {
    private val russianDictionary = RussianBloomDictionary(context)
    private val usage = mutableMapOf<String, Int>()
    private val observations = mutableMapOf<String, Int>()

    init {
        prefs.getStringSet(KEY_USAGE, emptySet()).orEmpty().forEach { encoded ->
            val split = encoded.lastIndexOf(':')
            if (split > 0) usage[encoded.substring(0, split)] = encoded.substring(split + 1).toIntOrNull() ?: 1
        }
        prefs.getStringSet(KEY_OBSERVATIONS, emptySet()).orEmpty().forEach { encoded ->
            val split = encoded.lastIndexOf(':')
            if (split > 0) observations[encoded.substring(0, split)] = encoded.substring(split + 1).toIntOrNull() ?: 1
        }
    }

    fun suggest(contextBeforeCursor: String, russian: Boolean, limit: Int = 3): List<WordCandidate> {
        val raw = extractCurrentWord(contextBeforeCursor)
        if (raw.length < 2 || isTechnicalContext(contextBeforeCursor, raw)) return emptyList()
        // A correctly typed dictionary/personal word must not receive fuzzy "corrections".
        // This prevents valid words such as "потом" -> "потому" or "меня" -> another nearby word.
        if (isKnownWord(raw, russian)) return emptyList()
        val query = normalizeRussianYo(raw.lowercase(Locale.ROOT), russian)
        if (!query.all { it.isLetterOrDigit() }) return emptyList()

        val base = if (russian) RU_WORDS else EN_WORDS
        val pool = LinkedHashSet<String>().apply {
            addAll(base)
            addAll(usage.keys.filter { word -> word.any { it in 'а'..'я' || it == 'ё' } == russian })
        }

        return pool.asSequence()
            .filter { normalizeRussianYo(it, russian) != query }
            .mapNotNull { word ->
                val comparableWord = normalizeRussianYo(word, russian)
                val prefixMatch = comparableWord.startsWith(query)
                val typoCost = if (prefixMatch) 0 else keyboardAwareDistance(query, comparableWord, 24)
                val distance = if (prefixMatch) 0 else boundedDistance(query, comparableWord, 3)
                val score = when {
                    prefixMatch -> 10_000 - (word.length - query.length) * 20
                    typoCost <= 10 -> 8_200
                    typoCost <= 16 && query.length >= 4 -> 6_500
                    typoCost <= 24 && query.length >= 5 -> 4_800
                    else -> return@mapNotNull null
                } + (usage[word] ?: 0) * 120
                WordCandidate(
                    word = matchCase(raw, word),
                    score = score - typoCost * 20,
                    editDistance = distance,
                    prefixMatch = prefixMatch,
                    typoCost = typoCost,
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    fun observeCommittedWord(word: String) {
        val normalized = word.lowercase(Locale.ROOT)
        if (normalized.length < 2 || !normalized.all { it.isLetter() }) return
        val baseKnown = normalized in RU_WORDS || normalized in EN_WORDS
        if (baseKnown || normalized in usage) {
            usage[normalized] = (usage[normalized] ?: 0) + 1
            saveUsage()
            return
        }
        val seen = (observations[normalized] ?: 0) + 1
        observations[normalized] = seen
        if (seen >= TRUST_AFTER_OBSERVATIONS) {
            observations.remove(normalized)
            usage[normalized] = 1
            saveUsage()
        }
        saveObservations()
    }

    fun currentWord(context: String): String = extractCurrentWord(context)

    fun isKnownWord(word: String, russian: Boolean): Boolean {
        val normalized = normalizeRussianYo(word.lowercase(Locale.ROOT), russian)
        val base = if (russian) RU_WORDS else EN_WORDS
        return base.any { normalizeRussianYo(it, russian) == normalized } ||
            usage.keys.any { normalizeRussianYo(it, russian) == normalized } ||
            (russian && russianDictionary.contains(normalized))
    }

    fun autocorrect(contextBeforeCursor: String, russian: Boolean): WordCandidate? {
        val raw = extractCurrentWord(contextBeforeCursor)
        if (raw.length < 3 || isTechnicalContext(contextBeforeCursor, raw)) return null

        // Compound words are corrected one part at a time: "каких-тл" -> "каких-то".
        // This keeps the hyphen and avoids treating the whole compound as one huge typo.
        if ('-' in raw && !raw.startsWith('-') && !raw.endsWith('-')) {
            val parts = raw.split('-')
            if (parts.size in 2..3 && parts.all { it.isNotEmpty() }) {
                var changed = false
                val corrected = parts.map { part ->
                    val replacement = autocorrectSingleWord(part, russian)
                    if (replacement != null) {
                        changed = true
                        replacement.word
                    } else {
                        part
                    }
                }
                if (changed) {
                    return WordCandidate(
                        word = corrected.joinToString("-"),
                        score = 9_000,
                        editDistance = 1,
                        prefixMatch = false,
                        typoCost = 6,
                    )
                }
            }
            return null
        }

        return autocorrectSingleWord(raw, russian)
    }

    private fun autocorrectSingleWord(raw: String, russian: Boolean): WordCandidate? {
        if (raw.length < 4 || isKnownWord(raw, russian)) return null
        val syntheticContext = raw
        val candidates = suggest(syntheticContext, russian, limit = 3)
            .filter { !it.prefixMatch }
        val best = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)

        val threshold = when {
            raw.length == 4 -> 10
            raw.length == 5 -> 16
            else -> 22
        }
        if (best.typoCost > threshold) return null
        if (raw.length <= 5 && runnerUp != null && best.score - runnerUp.score < 500) return null
        return best
    }

    fun splitRunTogetherWord(contextBeforeCursor: String, russian: Boolean): String? {
        val raw = extractCurrentWord(contextBeforeCursor)
        if (raw.length < 6 || isTechnicalContext(contextBeforeCursor, raw)) return null
        val normalized = raw.lowercase(Locale.ROOT)
        if (isKnownWord(normalized, russian)) return null
        val base = if (russian) RU_WORDS else EN_WORDS

        var best: Pair<String, String>? = null
        var bestScore = Int.MIN_VALUE
        for (split in 2..normalized.length - 2) {
            val left = normalized.substring(0, split)
            val right = normalized.substring(split)
            if (left !in base && left !in usage) continue
            if (right !in base && right !in usage) continue
            val score = (usage[left] ?: 0) + (usage[right] ?: 0) + min(left.length, right.length)
            if (score > bestScore) {
                best = left to right
                bestScore = score
            }
        }
        val pair = best ?: return null
        val replacement = pair.first + " " + pair.second
        return matchCase(raw, replacement)
    }

    private fun extractCurrentWord(text: String): String =
        text.takeLastWhile { it.isLetterOrDigit() || it == '-' || it == '\'' }

    private fun isTechnicalContext(context: String, word: String): Boolean {
        val prefix = context.dropLast(word.length).takeLast(80)
        val token = prefix.takeLastWhile { !it.isWhitespace() }
        return token.any { it in "@/:._" } || token.contains("www", ignoreCase = true)
    }

    private fun normalizeRussianYo(value: String, russian: Boolean): String =
        if (russian) value.replace('ё', 'е') else value

    private fun matchCase(source: String, candidate: String): String = when {
        source.all { !it.isLetter() || it.isUpperCase() } -> candidate.uppercase(Locale.ROOT)
        source.firstOrNull()?.isUpperCase() == true ->
            candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        else -> candidate
    }

    private fun keyboardAwareDistance(a: String, b: String, maxCost: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > 3) return maxCost + 1
        val cols = b.length + 1
        var previousPrevious: IntArray? = null
        var previous = IntArray(cols) { it * 10 }

        for (i in a.indices) {
            val current = IntArray(cols)
            current[0] = (i + 1) * 10
            var rowMin = current[0]
            for (j in b.indices) {
                val substitution = previous[j] + substitutionCost(a[i], b[j])
                val insertion = current[j] + 10
                val deletion = previous[j + 1] + if (a[i].isDigit()) 4 else 10
                var best = min(min(insertion, deletion), substitution)

                // Adjacent transposition is a very common mobile typing error.
                if (i > 0 && j > 0 && a[i] == b[j - 1] && a[i - 1] == b[j]) {
                    val pp = previousPrevious
                    if (pp != null) best = min(best, pp[j - 1] + 6)
                }
                current[j + 1] = best
                rowMin = min(rowMin, best)
            }
            if (rowMin > maxCost + 10) return maxCost + 1
            previousPrevious = previous
            previous = current
        }
        return previous[b.length]
    }

    private fun substitutionCost(from: Char, to: Char): Int {
        if (from == to) return 0
        if (from.isDigit() && to.isLetter()) return 6
        if (from.isLetter() && to.isDigit()) return 6
        return if (areKeyboardNeighbors(from.lowercaseChar(), to.lowercaseChar())) 5 else 10
    }

    private fun areKeyboardNeighbors(a: Char, b: Char): Boolean {
        val rows = if (a in RU_KEY_POSITIONS || b in RU_KEY_POSITIONS) RU_KEY_ROWS else EN_KEY_ROWS
        val pa = keyPosition(rows, a) ?: return false
        val pb = keyPosition(rows, b) ?: return false
        val rowDelta = kotlin.math.abs(pa.first - pb.first)
        val colDelta = kotlin.math.abs(pa.second - pb.second)
        return rowDelta <= 1 && colDelta <= 1
    }

    private fun keyPosition(rows: List<String>, char: Char): Pair<Int, Int>? {
        rows.forEachIndexed { row, keys ->
            val col = keys.indexOf(char)
            if (col >= 0) return row to col
        }
        return null
    }

    private fun boundedDistance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            var rowMin = current[0]
            for (j in b.indices) {
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
                rowMin = min(rowMin, current[j + 1])
            }
            if (rowMin > max) return max + 1
            previous = current
        }
        return previous[b.length]
    }

    private fun saveUsage() {
        prefs.edit().putStringSet(KEY_USAGE, usage.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    private fun saveObservations() {
        prefs.edit().putStringSet(KEY_OBSERVATIONS, observations.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    private companion object {
        const val KEY_USAGE = "word_usage"
        const val KEY_OBSERVATIONS = "word_observations"
        const val TRUST_AFTER_OBSERVATIONS = 3
        val RU_KEY_ROWS = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        val EN_KEY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val RU_KEY_POSITIONS = RU_KEY_ROWS.joinToString("").toSet()

        val RU_WORDS = setOf(
            "а","без","больше","будет","бы","был","была","были","быть","вам","вас","ваш","ведь","весь","вместе",
            "вот","время","все","всегда","всего","вы","где","да","давай","даже","делать","для","до","его","ее","если",
            "есть","еще","же","за","здесь","и","из","или","как","когда","который","кто","куда","ли","лучше","меня","мне",
            "может","можно","мой","мы","на","надо","нам","нас","наш","не","него","нее","нет","но","ну","о","он","она",
            "они","оно","от","очень","по","пока","после","почему","при","привет","просто","раз","с","сам","сейчас","сделать",
            "сказать","так","также","там","тебе","тебя","теперь","то","тоже","только","тут","ты","у","уже","хорошо","хочу",
            "чего","чем","что","чтобы","это","этого","этот","я","работает","работать","сервер","клавиатура","текст","слово",
            "слова","нужно","нормально","готово","отлично","спасибо","сегодня","завтра","потом","вопрос","ответ","проверить",
            "работа","работы","работу","печатать","печатаю","печатал","вручную","ошибка","ошибки","ошибку","исправить",
            "исправлять","замена","заменить","автоматически","автоматическая","предложение","предложения","разделить",
            "потому","поэтому","которые","которая","которое","которые","которого","которой","которым","которых",
            "такой","такая","такое","такие","таких","такого","такую","какой","какая","какое","какие","каких",
            "этим","этом","этому","эти","этих","эту","того","тому","тем","тех","себя","себе","свой","свои","свою",
            "твой","твоя","твое","твои","ваша","ваше","ваши","моего","моей","мою","мои","наша","наше","наши",
            "человек","люди","день","дня","дней","год","года","лет","раза","разом","место","места","дело","дела",
            "работы","работе","работой","работаю","работает","работают","работал","работала","работали","сделал",
            "сделала","сделали","сделаю","делаю","делает","делают","делал","делала","делали","сделано","сделать",
            "хотел","хотела","хотим","хотите","хочешь","хочет","хотят","могу","можешь","можем","можете","мог",
            "было","буду","будешь","будем","будете","будут","стал","стала","стали","стало","стать","идти","иду",
            "идет","идут","пойти","пришел","пришла","пришли","приходит","приходят","знать","знаю","знаешь","знает",
            "видеть","вижу","видишь","видит","смотреть","смотрю","смотри","говорить","говорю","говорит","говорят",
            "написать","написал","написала","пишу","пишет","читать","читаю","получить","получил","получилось",
            "проверка","проверил","проверила","проверим","работоспособность","настройка","настройки","настроить",
            "проблема","проблемы","ошибок","ошибкой","ошибку","правильно","неправильно","верно","вариант","варианты",
            "например","сначала","снова","сразу","перед","через","между","рядом","внутри","вместо","вокруг","около",
            "сюда","туда","откуда","потом","раньше","позже","иногда","часто","редко","обычно","конечно","возможно",
            "точно","почти","совсем","немного","много","мало","достаточно","быстро","медленно","долго","легко","сложно",
            "новый","новая","новое","новые","старый","большой","маленький","хороший","плохой","первый","последний",
            "другой","другая","другое","другие","следующий","следующая","каждый","каждая","любой","нужный","нужная",
            "русский","русская","английский","буква","буквы","буквой","букве","словарь","словаря","словаре","словари",
            "слово","словом","словах","текста","тексте","писать","печатать","напечатать","исправление","исправления",
            "замены","заменяет","заменил","заменила","исправляет","исправил","исправила","автозамена","подсказка",
            "подсказки","клавиатуре","клавиатуры","клавиатурой","телефон","телефоне","приложение","приложения",
            "файл","файлы","папка","папки","команда","команды","код","сервере","сервера","сервис","сервисы",
            "система","системы","версия","версии","обновление","обновить","установить","установка","скачать","загрузить",
            "интернет","сеть","сети","адрес","домен","домены","почта","ссылка","ссылки","сайт","сайта","страница",
            "ещё","всё","идёт","пойдёт","найдёт","найти","нашёл","нашла","берёт","даёт","даём","моё","твоё","своё",
            "еще","все","идет","пойдет","найдет","берет","дает","даем","мое","твое","свое",
            "то","либо","нибудь","каких","какого","какому","каким","какую","какою","скачал","скачала","скачали",
            "скачать","живёт","живет","ждёт","ждет","дефис","дефиса","дефисом","учитывать","учитывает","учитывал"
        )
        val EN_WORDS = setOf(
            "a","about","after","again","all","also","and","any","are","as","at","back","be","because","been","before",
            "but","by","can","come","could","day","do","does","done","down","email","even","first","for","from","get","give",
            "go","good","great","had","has","have","he","hello","help","her","here","him","his","how","i","if","in","into",
            "is","it","just","know","like","make","me","more","my","need","new","no","not","now","of","on","one","only","or",
            "other","our","out","over","please","right","see","server","she","so","some","still","text","than","that","the",
            "their","them","then","there","these","they","thing","think","this","time","to","too","up","use","very","want",
            "was","way","we","well","were","what","when","where","which","who","why","will","with","word","work","would",
            "yes","you","your","keyboard","android","github","version","test","works","thanks","today","tomorrow"
        )
    }
}
