package com.attendo.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One place that talks HTTP, and only ever small requests.
 *
 * Deliberately `HttpURLConnection` rather than OkHttp: the update system is the app's
 * first and only network client, and a dependency would be a second HTTP stack to keep
 * half of it unused. Text and file downloads are the only shapes needed, and the platform
 * client is fine at both.
 */
class HttpClient {

    /** GETs [url] as text. Null on anything but 200 — a failed check is "no update". */
    suspend fun getText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Downloads [url] to [target], reporting progress as bytes read. Returns false on any
     * failure — a half-written [target] is deleted before returning, so an interrupted
     * download never leaves a file that looks finished.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false
                val total = connection.contentLengthLong.takeIf { it > 0 }
                target.outputStream().use { out ->
                    connection.inputStream.buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var soFar = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            soFar += read
                            onProgress(soFar, total)
                        }
                    }
                }
                true
            } catch (t: Throwable) {
                target.delete()
                false
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // GitHub's API refuses requests without a User-Agent; this identifies the app
        // without saying anything about the student using it.
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private companion object {
        val TIMEOUT_MS: Int = 15_000
        const val USER_AGENT: String = "Attendo-Android"
    }
}
