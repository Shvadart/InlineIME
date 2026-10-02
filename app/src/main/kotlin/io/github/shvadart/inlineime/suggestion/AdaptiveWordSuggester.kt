package io.github.shvadart.inlineime.suggestion

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

class AdaptiveWordSuggester(private val prefs: SharedPreferences) {
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
        val query = raw.lowercase(Locale.ROOT)
        if (!query.all { it.isLetterOrDigit() }) return emptyList()

        val base = if (russian) RU_WORDS else EN_WORDS
        val pool = LinkedHashSet<String>().apply {
            addAll(base)
            addAll(usage.keys.filter { word -> word.any { it in 'а'..'я' || it == 'ё' } == russian })
        }

        return pool.asSequence()
            .filter { it != query }
            .mapNotNull { word ->
                val prefixMatch = word.startsWith(query)
                val typoCost = if (prefixMatch) 0 else keyboardAwareDistance(query, word, 24)
                val distance = if (prefixMatch) 0 else boundedDistance(query, word, 3)
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
        val normalized = word.lowercase(Locale.ROOT)
        val base = if (russian) RU_WORDS else EN_WORDS
        return normalized in base || normalized in usage
    }

    fun autocorrect(contextBeforeCursor: String, russian: Boolean): WordCandidate? {
        val raw = extractCurrentWord(contextBeforeCursor)
        if (raw.length < 3 || isTechnicalContext(contextBeforeCursor, raw)) return null
        if (isKnownWord(raw, russian)) return null
        val candidates = suggest(contextBeforeCursor, russian, limit = 3)
            .filter { !it.prefixMatch }
        val best = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)

        // Short words are extremely ambiguous ("а", "в", "на", "как", "так"...).
        // Never auto-replace them from fuzzy distance alone.
        if (raw.length <= 3) return null

        val threshold = when {
            raw.length == 4 -> 10
            raw.length == 5 -> 16
            else -> 22
        }
        if (best.typoCost > threshold) return null

        // For short/medium words require a clear lead over another plausible candidate.
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
            "исправлять","замена","заменить","автоматически","автоматическая","предложение","предложения","разделить"
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
