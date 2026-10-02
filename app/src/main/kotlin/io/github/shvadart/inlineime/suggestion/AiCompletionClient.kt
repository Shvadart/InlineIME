package io.github.shvadart.inlineime.suggestion

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small transport-only client for InlineIME's own completion backend.
 *
 * The APK never contains a model-provider API key. The backend is responsible
 * for authentication towards whichever model it uses.
 */
class AiCompletionClient(
    private val endpointProvider: () -> String,
) {
    fun complete(context: String, language: String): String? {
        val endpoint = endpointProvider().trim()
        if (endpoint.isEmpty()) return null

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val body = JSONObject()
                .put("context", context)
                .put("language", language)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(response)
                .optString("completion")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.take(MAX_COMPLETION_LENGTH)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2500
        const val READ_TIMEOUT_MS = 6000
        const val MAX_COMPLETION_LENGTH = 240
    }
}
