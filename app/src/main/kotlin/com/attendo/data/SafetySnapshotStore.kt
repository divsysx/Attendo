package com.attendo.data

import java.io.File

/**
 * The copy of an install taken immediately before a restore replaces it.
 *
 * One file, overwritten each time, in the app's own private storage. That is deliberately
 * modest: it is an undo for the minute after an import, not a backup history. A student who
 * wants copies keeps exported files, which is what the export button is for.
 *
 * Written to a temporary file and then renamed, so a crash — or a phone that runs out of
 * storage — half way through cannot leave a truncated snapshot standing where a complete one
 * used to be. The one thing worse than having no way back is believing there is one.
 *
 * Not included in the Android Auto Backup rules: it is a local undo, and shipping a second
 * copy of the whole database to the cloud on every import would double what those rules
 * upload for no gain.
 */
class SafetySnapshotStore(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)

    private val temporary: File get() = File(directory, "$FILE_NAME.part")

    fun exists(): Boolean = file.isFile && file.length() > 0L

    /** Null when there is nothing saved, or what is saved is unreadable. */
    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    /** Throws if the snapshot cannot be written — the caller must not restore without one. */
    fun write(text: String) {
        directory.mkdirs()
        temporary.writeText(text)
        // Delete-then-rename: File.renameTo does not replace an existing file on every
        // filesystem, and a rename that quietly failed would leave the previous snapshot in
        // place while the caller believed it had saved a new one.
        if (file.exists() && !file.delete()) {
            error("could not replace the previous safety snapshot")
        }
        if (!temporary.renameTo(file)) {
            error("could not put the safety snapshot in place")
        }
    }

    fun clear() {
        file.delete()
        temporary.delete()
    }

    private companion object {
        const val FILE_NAME = "previous-data.json"
    }
}
