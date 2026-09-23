package com.attendo.core.backup

/**
 * What a student is told when a backup file cannot be used, and what is written to the log
 * instead of to them.
 *
 * [BackupProblem] describes each fault exactly, and it should: a file truncated in transit and
 * a file that was never a backup fail for genuinely different reasons, and whoever reads a bug
 * report needs to know which. But precision in a *dialog* is not the same as help. Picking a
 * photo by mistake used to produce "it could not be read as JSON. Unexpected JSON token at
 * offset 3, expected EOF" — a sentence naming a parser, a byte position and a grammar rule,
 * none of which the student chose and none of which they can do anything about.
 *
 * So the screen gets one plain reason per fault family, chosen by the **first** problem in the
 * list. The reader finds the earliest fault first (a file that is not JSON never gets as far
 * as having a version), so the first problem is the one worth explaining, and a file with
 * three things wrong with it still shows one reason, because the student's next move does not
 * change with the count.
 *
 * It used to be one message for every refusal, on the argument that the remedy is the same in
 * all of them: pick a different file. Field testing on a real phone showed the cost of that —
 * a backup damaged in transit and a backup from a future version of the app are both dead
 * ends, but only one of them can tell the student what to do next. So the families that point
 * somewhere different now say so: update the app, or try another copy of the file.
 *
 * None of this weakens a check. The checksum still runs before the payload is trusted, the
 * schema is still validated field by field, the migrations still refuse a version they cannot
 * read, and exactly the same files are refused as before. Only the wording changed.
 */
object BackupMessages {

    /** What is wrong with a file that is not a backup at all. */
    const val NOT_A_BACKUP: String = "That file isn't a valid Attendo backup."

    /** What to do about it — shown as the second line, under [NOT_A_BACKUP]. */
    const val CHOOSE_A_BACKUP: String = "Choose an Attendo backup (.json) file and try again."

    /**
     * What is wrong, for one refused file, in words that name the fault family without naming
     * anything only its parser knows.
     */
    fun message(problem: BackupProblem): String = when (problem) {
        is BackupProblem.NotJson -> NOT_A_BACKUP
        is BackupProblem.NotABackup -> NOT_A_BACKUP
        is BackupProblem.UnsupportedFormatVersion -> if (problem.found > problem.supported.last) {
            "This backup was saved by a newer version of Attendo."
        } else {
            "This backup is too old to be read."
        }
        is BackupProblem.UnknownChecksumAlgorithm ->
            "This backup was saved by a newer version of Attendo."
        is BackupProblem.ChecksumMismatch ->
            "This backup is damaged. Part of it is missing or was changed after it was saved."
        is BackupProblem.MalformedField ->
            "This backup is incomplete, so its contents cannot be trusted."
        is BackupProblem.DanglingReference,
        is BackupProblem.DuplicateReference,
        -> "Parts of this backup disagree with each other."
        is BackupProblem.InvalidValue ->
            "This backup contains data that does not make sense."
        is BackupProblem.BrokenReschedule ->
            "This backup has a moved class whose records do not agree."
    }

    /** What to do about it, shown as the second line under [message]. */
    fun hint(problem: BackupProblem): String = when (problem) {
        is BackupProblem.NotJson, is BackupProblem.NotABackup -> CHOOSE_A_BACKUP
        is BackupProblem.UnsupportedFormatVersion -> if (problem.found > problem.supported.last) {
            "Update Attendo, then import it again."
        } else {
            "You will need a backup saved by a newer version of the app."
        }
        is BackupProblem.UnknownChecksumAlgorithm -> "Update Attendo, then import it again."
        is BackupProblem.ChecksumMismatch ->
            "If you have another copy of the file, try that one."
        is BackupProblem.MalformedField,
        is BackupProblem.DanglingReference,
        is BackupProblem.DuplicateReference,
        is BackupProblem.InvalidValue,
        is BackupProblem.BrokenReschedule,
        -> "Try exporting a new backup from the app it came from."
    }

    /**
     * Every reason a file was refused, on one line, for a log.
     *
     * Not for a dialog: these are [BackupProblem.message] strings, and they name field paths
     * and parser positions on purpose.
     */
    fun diagnostics(problems: List<BackupProblem>): String =
        problems.joinToString(" | ") { it.message }
}
