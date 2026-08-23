package com.attendo.core.backup

/**
 * Why a backup file was refused.
 *
 * Every rejection is a named case with a sentence saying exactly what was wrong, because
 * something has to: a file truncated in transit and a file that was never a backup fail for
 * genuinely different reasons. The cases are ordered by how early they are detected: a file
 * that is not JSON never gets as far as having a version, and a file whose checksum fails is
 * never decoded.
 *
 * These sentences are diagnostics, not dialog copy. What the student is shown is the same
 * either way — see [BackupMessages] for why — and it is [BackupReadResult.Failed.userMessage]
 * that a screen reaches for, with these going to the log through
 * [BackupReadResult.Failed.diagnostics].
 */
sealed interface BackupProblem {

    /** Precise, and technical where being precise requires it. Written for a log. */
    val message: String

    /** The file could not be parsed as JSON at all — wrong file, or truncated mid-write. */
    data class NotJson(val detail: String) : BackupProblem {
        override val message: String =
            "This file is not a valid Attendo backup — it could not be read as JSON. $detail"
    }

    /** Valid JSON, but not an Attendo backup: no envelope, or the wrong shape entirely. */
    data class NotABackup(val detail: String) : BackupProblem {
        override val message: String =
            "This file is valid JSON but not an Attendo backup. $detail"
    }

    /**
     * Written by a newer Attendo than this one.
     *
     * Refused rather than guessed at: a format this build has never seen may carry fields
     * that change what the data means, and a partial reading of someone's semester is worse
     * than an honest refusal to read it.
     */
    data class UnsupportedFormatVersion(val found: Int, val supported: IntRange) : BackupProblem {
        override val message: String = if (found > supported.last) {
            "This backup was made by a newer version of Attendo (format $found; this build " +
                "reads up to ${supported.last}). Update the app and try again."
        } else {
            "This backup uses format version $found, which this build no longer reads " +
                "(the oldest supported is ${supported.first})."
        }
    }

    /**
     * The contents do not match the checksum recorded with them.
     *
     * Means the file was truncated, corrupted in transit, or edited by hand. Importing it
     * would restore something other than what was exported.
     */
    data class ChecksumMismatch(val expected: String, val actual: String) : BackupProblem {
        override val message: String =
            "This backup is damaged — its contents do not match its checksum, so part of " +
                "the file is missing or has been altered. Use another copy."
    }

    /** The checksum algorithm named in the file is not one this build can compute. */
    data class UnknownChecksumAlgorithm(val algorithm: String) : BackupProblem {
        override val message: String =
            "This backup records its checksum as \"$algorithm\", which this build cannot " +
                "verify. Update the app and try again."
    }

    /** A field was missing, of the wrong type, or an enum value this build does not know. */
    data class MalformedField(val path: String, val detail: String) : BackupProblem {
        override val message: String = "This backup is incomplete or malformed at $path: $detail"
    }

    /** A reference points at a course, pattern or session the file does not contain. */
    data class DanglingReference(val from: String, val ref: String) : BackupProblem {
        override val message: String =
            "This backup is internally inconsistent: $from refers to \"$ref\", which is not " +
                "in the file."
    }

    /** Two records claim the same reference, so references are ambiguous. */
    data class DuplicateReference(val kind: String, val ref: String) : BackupProblem {
        override val message: String =
            "This backup is internally inconsistent: two ${kind}s both use the reference " +
                "\"$ref\"."
    }

    /**
     * A value the domain model would reject — a class outside the teaching day, a term
     * ending before it starts, an attendance mask claiming more hours than were planned.
     */
    data class InvalidValue(val path: String, val detail: String) : BackupProblem {
        override val message: String = "This backup contains data Attendo cannot accept at " +
            "$path: $detail"
    }

    /**
     * A reschedule whose two halves disagree.
     *
     * Rescheduling is two linked rows, and the link is the only thing that keeps the
     * cancelled original and its replacement from being counted as separate classes. A
     * one-sided link is not something to repair silently — the student would have no way of
     * knowing which of their classes the app had quietly rewritten.
     */
    data class BrokenReschedule(val ref: String, val detail: String) : BackupProblem {
        override val message: String =
            "This backup has a rescheduled class whose records do not agree ($ref): $detail"
    }
}

/** The outcome of reading a backup file: understood, or refused with reasons. */
sealed interface BackupReadResult {

    data class Ok(val backup: Backup) : BackupReadResult

    /** Never empty — a failure always says at least one thing about why. */
    data class Failed(val problems: List<BackupProblem>) : BackupReadResult {
        init {
            require(problems.isNotEmpty()) { "a failed read must carry at least one problem" }
        }

        /** The first reason, in full. For a log or a test, not for a dialog. */
        val message: String get() = problems.first().message

        /**
         * What the student is shown — the same sentence however the file failed.
         *
         * A total mapping, on purpose: see [BackupMessages].
         */
        val userMessage: String get() = BackupMessages.NOT_A_BACKUP

        /** The line under [userMessage], saying what to do instead. */
        val userHint: String get() = BackupMessages.CHOOSE_A_BACKUP

        /** Every reason, joined, for the log line that accompanies the refusal. */
        val diagnostics: String get() = BackupMessages.diagnostics(problems)
    }
}
