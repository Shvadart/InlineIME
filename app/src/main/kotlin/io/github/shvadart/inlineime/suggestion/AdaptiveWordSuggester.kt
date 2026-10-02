package io.github.shvadart.inlineime.suggestion

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.min

data class WordCandidate(
    val word: String,
    val score: Int,
    val editDistance: Int,
    val prefixMatch: Boolean,
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
        val query = raw.lowercase(Locale.ROOT)
        if (!query.all { it.isLetter() }) return emptyList()

        val base = if (russian) RU_WORDS else EN_WORDS
        val pool = LinkedHashSet<String>().apply {
            addAll(base)
            addAll(usage.keys.filter { word -> word.any { it in 'а'..'я' || it == 'ё' } == russian })
        }

        return pool.asSequence()
            .filter { it != query }
            .mapNotNull { word ->
                val distance = if (word.startsWith(query)) 0 else boundedDistance(query, word, 2)
                val score = when {
                    word.startsWith(query) -> 10_000 - (word.length - query.length) * 20
                    distance == 1 -> 7_000
                    distance == 2 && query.length >= 5 -> 4_000
                    else -> return@mapNotNull null
                } + (usage[word] ?: 0) * 120
                WordCandidate(
                    word = matchCase(raw, word),
                    score = score,
                    editDistance = distance,
                    prefixMatch = word.startsWith(query),
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
        return suggest(contextBeforeCursor, russian, limit = 3)
            .firstOrNull { !it.prefixMatch && it.editDistance == 1 }
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
        text.takeLastWhile { it.isLetter() || it == '-' || it == '\'' }

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

        val RU_WORDS = setOf(
            "а","без","больше","будет","бы","был","была","были","быть","вам","вас","ваш","ведь","весь","вместе",
            "вот","время","все","всегда","всего","вы","где","да","давай","даже","делать","для","до","его","ее","если",
            "есть","еще","же","за","здесь","и","из","или","как","когда","который","кто","куда","ли","лучше","меня","мне",
            "может","можно","мой","мы","на","надо","нам","нас","наш","не","него","нее","нет","но","ну","о","он","она",
            "они","оно","от","очень","по","пока","после","почему","при","привет","просто","раз","с","сам","сейчас","сделать",
            "сказать","так","также","там","тебе","тебя","теперь","то","тоже","только","тут","ты","у","уже","хорошо","хочу",
            "чего","чем","что","чтобы","это","этого","этот","я","работает","работать","сервер","клавиатура","текст","слово",
            "слова","нужно","нормально","готово","отлично","спасибо","сегодня","завтра","потом","вопрос","ответ","проверить"
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
