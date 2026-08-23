package com.attendo.core.backup

/**
 * What a student is told when a backup file cannot be used, and what is written to the log
 * instead of to them.
 *
 * ### Why every refusal reads the same
 *
 * [BackupProblem] describes each fault exactly, and it should: a file truncated in transit and
 * a file that was never a backup fail for genuinely different reasons, and whoever reads a bug
 * report needs to know which. But precision in a *dialog* is not the same as help. Picking a
 * photo by mistake used to produce "it could not be read as JSON. Unexpected JSON token at
 * offset 3, expected EOF" — a sentence naming a parser, a byte position and a grammar rule,
 * none of which the student chose and none of which they can do anything about. Every one of
 * those faults has the same remedy, which is to pick a different file.
 *
 * So the mapping is total and deliberately lossy. Anything that stops a file being read — not
 * JSON, malformed JSON, JSON that is not a backup, a failed checksum, a structure this build
 * cannot use — becomes [NOT_A_BACKUP] followed by [CHOOSE_A_BACKUP]. Nothing is lost that
 * mattered: [diagnostics] renders the full reasons for a log line, where they cost nobody
 * anything.
 *
 * None of this weakens a check. The checksum still runs before the payload is trusted, the
 * schema is still validated field by field, the migrations still refuse a version they cannot
 * read, and exactly the same files are refused as before. Only the wording changed.
 */
object BackupMessages {

    /** What is wrong, in the only terms that matter to the person holding the phone. */
    const val NOT_A_BACKUP: String = "That file isn't a valid Attendo backup."

    /** What to do about it — shown as the second line, under [NOT_A_BACKUP]. */
    const val CHOOSE_A_BACKUP: String = "Choose an Attendo backup (.json) file and try again."

    /** Both sentences, for anywhere with one string to fill rather than two lines. */
    const val REFUSED: String = "$NOT_A_BACKUP $CHOOSE_A_BACKUP"

    /**
     * Every reason a file was refused, on one line, for a log.
     *
     * Not for a dialog: these are [BackupProblem.message] strings, and they name field paths
     * and parser positions on purpose.
     */
    fun diagnostics(problems: List<BackupProblem>): String =
        problems.joinToString(" | ") { it.message }
}
