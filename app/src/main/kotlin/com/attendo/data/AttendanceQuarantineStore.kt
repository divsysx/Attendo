package com.attendo.data

import java.io.File

/**
 * The copy of a deleted account's attendance, kept on the phone after its rows are cleared
 * away for the account that signs in next.
 *
 * ### Why this exists
 *
 * When an account is deleted from the server by an administrator, the student is signed out
 * with no warning and no chance to export. Their attendance is the only copy of a term's
 * marks that exists — the cloud copy went with the account — so the one thing the app must
 * not do is clear the rows on the way to the next sign-in. The rows cannot simply stay either:
 * the next account claims this phone's attendance, and a claim with rows underneath it
 * uploads them. So they are written here first, then cleared. See
 * [com.attendo.data.sync.AttendanceSyncStore.releaseTerminatedClaim], which is the only
 * caller and does nothing else before it.
 *
 * ### What it is not
 *
 * Not part of the backup feature: no screen offers it and nothing in the app reads it back.
 * It is written in the backup file's format anyway, because that format is this app's one
 * complete, validated encoding of a body of attendance — if a way to recover the copy is ever
 * built, it should be the restore path reading a file that path already knows how to check,
 * rather than a second format invented for a file nobody can open.
 *
 * One file, overwritten each time. A second deletion replaces the first set-aside copy rather
 * than accumulating them: the phone holds one body of attendance at a time and the storage
 * cost of keeping every previous one is not the student's to pay. It is deleted with
 * everything else by `AppReset`, which clears by directory, and it is not in the Android Auto
 * Backup include-list — that list names files, and this one is not on it.
 *
 * Written to a temporary file and then renamed, exactly as [SafetySnapshotStore] is: a crash
 * or a full disk part way through must not leave a truncated file standing where a complete
 * one used to be. The caller relies on this — the copy is written *before* the rows are
 * cleared, so a failure here leaves the phone exactly as it was and the next pass tries again.
 */
class AttendanceQuarantineStore(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)

    private val temporary: File get() = File(directory, "$FILE_NAME.part")

    /**
     * Whether a set-aside copy is on the phone.
     *
     * Read by the Account screen, which is where a student would look for it: the sentence it
     * drives says the previous account's attendance was kept here rather than deleted, and a
     * file that is not there must not be promised.
     */
    fun exists(): Boolean = file.isFile && file.length() > 0L

    /** Throws if the copy cannot be written — the caller must not clear anything without one. */
    fun write(text: String) {
        directory.mkdirs()
        temporary.writeText(text)
        // Delete-then-rename: File.renameTo does not replace an existing file on every
        // filesystem, and a rename that quietly failed would leave the previous account's
        // copy in place while the caller believed it had saved this one.
        if (file.exists() && !file.delete()) {
            error("could not replace the previous set-aside copy")
        }
        if (!temporary.renameTo(file)) {
            error("could not put the set-aside copy in place")
        }
    }

    companion object {
        /**
         * The directory under `filesDir`. Named for what is in it rather than for the event
         * that put it there, because the file outlives the event by design.
         */
        const val DIRECTORY_NAME: String = "quarantine"

        private const val FILE_NAME = "removed-account-attendance.json"

        fun inFilesDir(filesDir: File): AttendanceQuarantineStore =
            AttendanceQuarantineStore(File(filesDir, DIRECTORY_NAME))
    }
}
