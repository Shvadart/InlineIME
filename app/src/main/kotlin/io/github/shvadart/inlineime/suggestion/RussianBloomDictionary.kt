package io.github.shvadart.inlineime.suggestion

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * Compact membership index generated from Goudron/ru-spelling-dictionary (MPL-2.0).
 * It answers "is this a real Russian dictionary form?" without keeping millions of
 * Strings in RAM. ё and е are intentionally equivalent for normal typing.
 */
class RussianBloomDictionary(context: Context) {
    private val bits: ByteArray? = runCatching {
        context.assets.open(ASSET).use { it.readBytes() }
    }.getOrNull()

    fun contains(value: String): Boolean {
        val data = bits ?: return false
        val word = value.lowercase(Locale.ROOT).replace('ё', 'е')
        if (word.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(word.toByteArray(Charsets.UTF_8))
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

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { i ->
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xffL)
        }
        return value
    }

    private companion object {
        const val ASSET = "dictionaries/ru_words.bloom"
        const val HASHES = 10
    }
}
