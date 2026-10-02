package io.github.shvadart.inlineime.suggestion

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

class RussianBloomDictionary(context: Context) {
    private val bits: ByteArray? = runCatching {
        context.assets.open(ASSET).use { it.readBytes() }
    }.getOrNull()
    private val sha256 = MessageDigest.getInstance("SHA-256")

    fun contains(value: String): Boolean {
        val data = bits ?: return false
        val word = normalize(value)
        if (word.isEmpty()) return false
        val digest = sha256.digest(word.toByteArray(Charsets.UTF_8))
        val h1 = readLong(digest, 0)
        val h2 = readLong(digest, 8) or 1L
        val mask = (data.size.toLong() * 8L) - 1L
        repeat(HASHES) { i ->
            val bit = (h1 + i.toLong() * h2) and mask
            val byteIndex = (bit ushr 3).toInt()
            val bitMask = 1 shl (bit.toInt() and 7)
            if ((data[byteIndex].toInt() and bitMask) == 0) return false
        }
        return true
    }

    fun candidates(value: String, limit: Int = 8): List<String> {
        val source = normalize(value)
        if (source.length !in 3..24 || bits == null) return emptyList()
        val found = LinkedHashSet<String>()
        fun accept(candidate: String) {
            if (candidate != source && candidate.length in 2..25 && contains(candidate)) found += candidate
        }

        source.indices.filter { source[it].isDigit() }.forEach { i -> accept(source.removeRange(i, i + 1)) }
        source.indices.forEach { i -> accept(source.removeRange(i, i + 1)) }

        for (i in 0 until source.lastIndex) {
            if (source[i] != source[i + 1]) {
                val chars = source.toCharArray()
                val t = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = t
                accept(String(chars))
            }
        }

        source.indices.forEach { i ->
            keyboardNeighbors(source[i]).forEach { replacement ->
                accept(source.replaceRange(i, i + 1, replacement.toString()))
            }
        }

        for (i in 0..source.length) {
            RUSSIAN_ALPHABET.forEach { ch ->
                accept(source.substring(0, i) + ch + source.substring(i))
            }
        }

        source.indices.forEach { i ->
            if (!source[i].isDigit()) {
                RUSSIAN_ALPHABET.forEach { ch ->
                    if (ch != source[i]) accept(source.replaceRange(i, i + 1, ch.toString()))
                }
            }
        }

        if (found.size >= limit) return found.take(limit)

        for (variant in cheapVariants(source).take(MAX_SECOND_LEVEL_BASES)) {
            for (candidate in cheapVariants(variant).take(MAX_SECOND_LEVEL_PER_BASE)) {
                accept(candidate)
                if (found.size >= limit) return found.toList()
            }
        }
        return found.take(limit)
    }

    private fun cheapVariants(word: String): Sequence<String> = sequence {
        word.indices.forEach { i -> yield(word.removeRange(i, i + 1)) }
        for (i in 0 until word.lastIndex) {
            if (word[i] != word[i + 1]) {
                val chars = word.toCharArray()
                val t = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = t
                yield(String(chars))
            }
        }
        word.indices.forEach { i ->
            keyboardNeighbors(word[i]).forEach { ch ->
                yield(word.replaceRange(i, i + 1, ch.toString()))
            }
        }
    }

    private fun keyboardNeighbors(ch: Char): CharArray {
        val normalized = ch.lowercaseChar()
        val result = LinkedHashSet<Char>()
        for (rowIndex in RU_ROWS.indices) {
            val col = RU_ROWS[rowIndex].indexOf(normalized)
            if (col < 0) continue
            for (r in (rowIndex - 1)..(rowIndex + 1)) {
                if (r !in RU_ROWS.indices) continue
                for (c in (col - 1)..(col + 1)) {
                    if (c in RU_ROWS[r].indices) result += RU_ROWS[r][c]
                }
            }
        }
        result.remove(normalized)
        return result.toCharArray()
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е')

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { i -> value = (value shl 8) or (bytes[offset + i].toLong() and 0xffL) }
        return value
    }

    private companion object {
        const val ASSET = "dictionaries/ru_words.bloom"
        const val HASHES = 10
        const val MAX_SECOND_LEVEL_BASES = 96
        const val MAX_SECOND_LEVEL_PER_BASE = 96
        const val RUSSIAN_ALPHABET = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        val RU_ROWS = arrayOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
    }
}
