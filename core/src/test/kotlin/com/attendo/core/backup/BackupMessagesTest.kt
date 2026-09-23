package com.attendo.core.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the student is shown when a file cannot be imported.
 *
 * The refusals themselves are tested next door, in [BackupRejectionTest] — what is under test
 * here is the sentence on the screen, which used to be the refusal itself:
 *
 * > This file is not a valid Attendo backup — it could not be read as JSON. Unexpected JSON
 * > token at offset 3: Expected EOF after parsing, but had { instead at path: $
 *
 * Every word after the first sentence is addressed to whoever wrote the parser. Somebody who
 * has just picked a PDF out of their Downloads folder cannot act on a character offset.
 *
 * For a while the answer was one message for every refusal, on the argument that the remedy is
 * the same in all of them: pick a different file. Field testing on a real phone showed the
 * cost of that — a backup damaged in transit and a backup from a future version of the app are
 * both dead ends, but only one of them can tell the student what to do next. So the screen now
 * gets one plain reason per fault family, under two rules that keep it human:
 *
 *  1. Nothing only a parser would know reaches the student — no tokens, no offsets, no field
 *     paths, no numbers. The reason names the *family* of fault, in words.
 *  2. One reason per file, the first one. The reader finds the earliest fault first (a file
 *     that is not JSON never gets as far as having a version), and a file with three things
 *     wrong with it does not need three sentences, because the student's next move does not
 *     change with the count.
 *
 * The precise reasons are not lost — they go to the log, which is what these tests check last.
 */
class BackupMessagesTest {

    private val good = encoded()

    // ---- the wrong file, the oldest case --------------------------------------

    @Test
    fun `the refusal says what is wrong and what to do about it`() {
        assertEquals("That file isn't a valid Attendo backup.", BackupMessages.NOT_A_BACKUP)
        assertEquals(
            "Choose an Attendo backup (.json) file and try again.",
            BackupMessages.CHOOSE_A_BACKUP,
        )
    }

    @Test
    fun `a file that is not a backup says so in those words`() {
        listOf("a PDF" to PDF, "a CSV" to CSV, "a photo" to PNG).forEach { (what, text) ->
            val failed = failedOn(what, text)

            assertEquals(what, BackupMessages.NOT_A_BACKUP, failed.userMessage)
            assertEquals(what, BackupMessages.CHOOSE_A_BACKUP, failed.userHint)
        }
    }

    // ---- the reasons differ where the remedy does -----------------------------

    @Test
    fun `every kind of problem has its own plain reason`() {
        reasons().forEach { (problem, expectedMessage, expectedHint) ->
            assertEquals(problem.message, expectedMessage, BackupMessages.message(problem))
            assertEquals(problem.message, expectedHint, BackupMessages.hint(problem))
        }
    }

    @Test
    fun `a refused file shows the reason for its first fault`() {
        unusableFiles().forEach { (what, text) ->
            val failed = failedOn(what, text)

            assertEquals(
                what,
                BackupMessages.message(failed.problems.first()),
                failed.userMessage,
            )
            assertEquals(what, BackupMessages.hint(failed.problems.first()), failed.userHint)
        }
    }

    @Test
    fun `a backup from a newer build says so and points at updating`() {
        val failed = failedOn(
            "a backup from a newer Attendo",
            tamper(good) { it.withField("formatVersion", BackupCodec.FORMAT_VERSION + 1) },
        )

        assertEquals(
            "This backup was saved by a newer version of Attendo.",
            failed.userMessage,
        )
        assertEquals("Update Attendo, then import it again.", failed.userHint)
    }

    @Test
    fun `a damaged backup says so and points at another copy`() {
        val failed = failedOn(
            "a damaged backup",
            tamperPayload(good) {
                it.withRecord("courses", "c1") { course -> course.withField("name", "Something else") }
            },
        )

        assertEquals(
            "This backup is damaged. Part of it is missing or was changed after it was saved.",
            failed.userMessage,
        )
        assertEquals("If you have another copy of the file, try that one.", failed.userHint)
    }

    // ---- the reasons stay human ------------------------------------------------

    @Test
    fun `nothing the parser said reaches the student`() {
        val shown = (unusableFiles().map { failedOn(it.first, it.second) } +
            EVERY_PROBLEM.map { BackupReadResult.Failed(listOf(it)) })
            .flatMap { listOf(it.userMessage, it.userHint) }

        shown.forEach { text ->
            JARGON.forEach { word ->
                assertFalse("showed \"$word\": $text", text.contains(word))
            }
            // No offsets, no counts, no version numbers: the only digits anywhere near this
            // screen came out of the file, and none of them mean anything to the reader.
            assertFalse("showed a number: $text", text.any(Char::isDigit))
        }
    }

    @Test
    fun `every reason is a short line, not a report`() {
        EVERY_PROBLEM.forEach { problem ->
            val message = BackupMessages.message(problem)
            val hint = BackupMessages.hint(problem)

            assertTrue("message ran on: $message", message.count { it == '.' } <= 2)
            assertTrue("message too long: $message", message.length < 90)
            assertTrue("hint too long: $hint", hint.length < 60)
        }
    }

    // ---- the reason is kept, for the log ------------------------------------

    @Test
    fun `the technical reason survives, for the log rather than the screen`() {
        // Exactly the file that produced the message quoted at the top of this class.
        val failed = failedOn("a CSV", CSV)

        val notJson = failed.problems.filterIsInstance<BackupProblem.NotJson>().single()
        assertTrue("the parser said nothing", notJson.detail.isNotBlank())
        assertTrue(failed.diagnostics.contains("could not be read as JSON"))
        assertTrue(failed.diagnostics.contains(notJson.detail))
        assertNotEquals(failed.diagnostics, failed.userMessage)
        assertEquals(failed.problems.first().message, failed.message)
    }

    @Test
    fun `the log names every fault while the screen names the first`() {
        val several = repack(good) { payload ->
            payload
                .withRecord("sessions", "s1") { it.withField("courseRef", "c99") }
                .withRecord("courses", "c1") { it.withField("name", " ") }
        }

        val failed = failedOn("a file with several faults", several)

        assertTrue("expected several faults, got ${failed.problems}", failed.problems.size >= 2)
        failed.problems.forEach { problem ->
            assertTrue(problem.message, failed.diagnostics.contains(problem.message))
        }
        // One reason on screen: the earliest fault, because that is the one the reader found
        // first and the only one the student can act on.
        assertEquals(BackupMessages.message(failed.problems.first()), failed.userMessage)
    }

    @Test
    fun `a refusal with nothing to say about it cannot be constructed`() {
        val thrown = runCatching { BackupReadResult.Failed(emptyList()) }.exceptionOrNull()

        assertTrue("expected a refusal to require a reason", thrown is IllegalArgumentException)
    }

    // ---- the mapping is total ------------------------------------------------

    /**
     * One entry for every kind of problem, with the exact words it must produce. Exhaustive
     * by construction: [reasons] lists every [BackupProblem] variant, and a new one has to be
     * added here to be given words at all — the `when` in [BackupMessages] will not compile
     * until it is.
     */
    private fun reasons(): List<Triple<BackupProblem, String, String>> = listOf(
        Triple(
            BackupProblem.NotJson("Unexpected JSON token at offset 3"),
            BackupMessages.NOT_A_BACKUP,
            BackupMessages.CHOOSE_A_BACKUP,
        ),
        Triple(
            BackupProblem.NotABackup("it has no formatVersion"),
            BackupMessages.NOT_A_BACKUP,
            BackupMessages.CHOOSE_A_BACKUP,
        ),
        Triple(
            BackupProblem.UnsupportedFormatVersion(
                found = BackupCodec.FORMAT_VERSION + 1,
                supported = BackupCodec.supportedFormatVersions,
            ),
            "This backup was saved by a newer version of Attendo.",
            "Update Attendo, then import it again.",
        ),
        Triple(
            BackupProblem.UnsupportedFormatVersion(
                found = 0,
                supported = BackupCodec.supportedFormatVersions,
            ),
            "This backup is too old to be read.",
            "You will need a backup saved by a newer version of the app.",
        ),
        Triple(
            BackupProblem.UnknownChecksumAlgorithm("CRC-32"),
            "This backup was saved by a newer version of Attendo.",
            "Update Attendo, then import it again.",
        ),
        Triple(
            BackupProblem.ChecksumMismatch(expected = "a1b2c3", actual = "d4e5f6"),
            "This backup is damaged. Part of it is missing or was changed after it was saved.",
            "If you have another copy of the file, try that one.",
        ),
        Triple(
            BackupProblem.MalformedField(path = "sessions[s1].date", detail = "expected a date"),
            "This backup is incomplete, so its contents cannot be trusted.",
            "Try exporting a new backup from the app it came from.",
        ),
        Triple(
            BackupProblem.DanglingReference(from = "sessions[s1].courseRef", ref = "c99"),
            "Parts of this backup disagree with each other.",
            "Try exporting a new backup from the app it came from.",
        ),
        Triple(
            BackupProblem.DuplicateReference(kind = "course", ref = "c1"),
            "Parts of this backup disagree with each other.",
            "Try exporting a new backup from the app it came from.",
        ),
        Triple(
            BackupProblem.InvalidValue(path = "sessions[s1].startHour", detail = "outside the day"),
            "This backup contains data that does not make sense.",
            "Try exporting a new backup from the app it came from.",
        ),
        Triple(
            BackupProblem.BrokenReschedule(ref = "s4", detail = "does not point back"),
            "This backup has a moved class whose records do not agree.",
            "Try exporting a new backup from the app it came from.",
        ),
    )

    @Test
    fun `every diagnostic still says which kind of fault it was`() {
        reasons().forEach { (problem, _, _) ->
            val subject = subjectOf(problem)

            assertTrue(
                "${problem::class.simpleName} never mentions $subject: ${problem.message}",
                problem.message.contains(subject),
            )
        }
    }

    // ---- fixtures ------------------------------------------------------------

    /**
     * Files, not hand-made problems, for the paths that go through the real reader: the case
     * that prompted all of this was a file that was never a backup, picked out of a folder
     * full of them.
     */
    private fun unusableFiles(): List<Pair<String, String>> = listOf(
        "a PDF" to PDF,
        "a CSV" to CSV,
        "a photo" to PNG,
        "a note to self" to "this is my attendance, please import it",
        "an empty file" to "",
        "a file of spaces" to "   ",
        "half a backup" to good.substring(0, good.length / 2),
        "someone else's JSON" to """{"items": [], "title": "shopping list"}""",
        "a JSON array" to "[1, 2, 3]",
        "an envelope with no payload" to tamper(good) { it.withoutField("payload") },
        "a checksum this build cannot verify" to tamper(good) {
            it.withField(
                "checksum",
                JsonObject(
                    mapOf(
                        "algorithm" to JsonPrimitive("CRC-32"),
                        "value" to JsonPrimitive("deadbeef"),
                    ),
                ),
            )
        },
        "a damaged backup" to tamperPayload(good) {
            it.withRecord("courses", "c1") { course -> course.withField("name", "Something else") }
        },
        "a backup missing a field" to repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withoutField("status") }
        },
        "a backup with an impossible value" to repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withField("startHour", 23) }
        },
        "a backup pointing at nothing" to repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withField("courseRef", "c99") }
        },
    )

    /** Decodes, insisting on a refusal, and saying which fixture failed to fail. */
    private fun failedOn(what: String, text: String): BackupReadResult.Failed =
        when (val result = BackupCodec.decode(text)) {
            is BackupReadResult.Ok -> throw AssertionError("$what was accepted as a backup")
            is BackupReadResult.Failed -> result
        }

    private companion object {

        private const val PDF = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"

        private const val CSV = "Subject,Room,Day,Hour\nADEC,204,MONDAY,9\n"

        private const val PNG = "PNG\r\n\n  \rIHDR"

        /**
         * Everything the old message said that nobody choosing the wrong file could act on.
         *
         * `.json` is not on the list on purpose: a file extension is something a person can look
         * for in a file picker, which is the whole point of the second sentence.
         */
        private val JARGON = listOf(
            "JSON", "token", "Token", "offset", "Offset", "EOF", "eof", "Exception",
            "expected", "Expected", "\$", "at path", "parse", "Parse", "parsing",
            "serial", "kotlinx", "checksum", "Checksum", "\n", "—",
        )

        /** Every kind of problem, for the tests that check all the words at once. */
        private val EVERY_PROBLEM: List<BackupProblem> = listOf(
            BackupProblem.NotJson("Unexpected JSON token at offset 3"),
            BackupProblem.NotABackup("it has no formatVersion"),
            BackupProblem.UnsupportedFormatVersion(
                found = BackupCodec.FORMAT_VERSION + 1,
                supported = BackupCodec.supportedFormatVersions,
            ),
            BackupProblem.UnsupportedFormatVersion(
                found = 0,
                supported = BackupCodec.supportedFormatVersions,
            ),
            BackupProblem.UnknownChecksumAlgorithm("CRC-32"),
            BackupProblem.ChecksumMismatch(expected = "a1b2c3", actual = "d4e5f6"),
            BackupProblem.MalformedField(path = "sessions[s1].date", detail = "expected a date"),
            BackupProblem.DanglingReference(from = "sessions[s1].courseRef", ref = "c99"),
            BackupProblem.DuplicateReference(kind = "course", ref = "c1"),
            BackupProblem.InvalidValue(path = "sessions[s1].startHour", detail = "outside the day"),
            BackupProblem.BrokenReschedule(ref = "s4", detail = "does not point back"),
        )

        /** What each diagnostic has to name, so no refusal can reach a log saying nothing. */
        private fun subjectOf(problem: BackupProblem): String = when (problem) {
            is BackupProblem.NotJson -> "could not be read as JSON"
            is BackupProblem.NotABackup -> "not an Attendo backup"
            is BackupProblem.UnsupportedFormatVersion -> "format"
            is BackupProblem.ChecksumMismatch -> "checksum"
            is BackupProblem.UnknownChecksumAlgorithm -> "checksum"
            is BackupProblem.MalformedField -> "incomplete or malformed"
            is BackupProblem.DanglingReference -> "internally inconsistent"
            is BackupProblem.DuplicateReference -> "both use the reference"
            is BackupProblem.InvalidValue -> "cannot accept"
            is BackupProblem.BrokenReschedule -> "rescheduled class"
        }
    }
}
