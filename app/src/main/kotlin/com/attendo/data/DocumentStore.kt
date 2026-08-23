package com.attendo.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads and writes the files the student picks with Android's own file picker.
 *
 * The picker hands back a `content://` URI, not a path — the app has no storage permissions
 * and wants none. Everything here is therefore about one URI at a time, granted by the
 * student for that one file.
 *
 * This is the only class in the backup feature that touches the Android framework; the format
 * itself lives in `:core` and the database work in [BackupRepository].
 */
class DocumentStore(private val resolver: ContentResolver) {

    /**
     * The whole file as text.
     *
     * The size cap is not about backups — a semester is a couple of hundred kilobytes — but
     * about the wrong file being picked. Without it, choosing a video from the picker would
     * read gigabytes into memory before anything noticed it was not JSON.
     */
    suspend fun read(uri: Uri): String =
        // A byte-order mark is invisible in an editor but is not JSON, and a file that has been
        // through a desktop text editor may well carry one. Dropping it turns a baffling "not
        // valid JSON" into a successful import.
        String(readBytes(uri, "an Attendo backup"), Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)

    /**
     * The whole file as bytes, backing [read].
     *
     * [expected] names what the picker was asked for, so a student who picked the wrong file
     * by mistake is told which file was wanted rather than being told a number of megabytes.
     */
    private suspend fun readBytes(
        uri: Uri,
        expected: String = "a file Attendo can read",
    ): ByteArray = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("That file could not be opened.")
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_BYTES) {
                    throw IOException(
                        "That file is larger than ${MAX_BYTES / (1024 * 1024)} MB, so it is not " +
                            "$expected.",
                    )
                }
            }
            buffer.toByteArray()
        }
    }

    /** Replaces the contents of [uri] with [text]. */
    suspend fun write(uri: Uri, text: String): Unit = write(uri, text.toByteArray(Charsets.UTF_8))

    /**
     * Replaces the contents of [uri] with [bytes].
     *
     * The text overload above delegates here. The two are split because a PDF is a binary format
     * and must not pass through the `String` path: encoding raw bytes to UTF-8 would mangle them
     * and produce a file no viewer can open, so the bytes are written exactly as given.
     */
    suspend fun write(uri: Uri, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        // "wt" truncates first. Without it, writing a short export over a longer file leaves the
        // tail of the old one behind — valid bytes on disk, an unreadable backup. Not every
        // provider implements the mode, so plain "w" is the fallback rather than the default.
        val stream = openForWriting(uri, "wt")
            ?: openForWriting(uri, "w")
            ?: throw IOException("That file could not be opened for writing.")
        stream.use { it.write(bytes) }
    }

    /** The name the picker shows for a file, for messages that name what was saved or read. */
    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun openForWriting(uri: Uri, mode: String) =
        runCatching { resolver.openOutputStream(uri, mode) }.getOrNull()

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val BYTE_ORDER_MARK = "\uFEFF"
    }
}
