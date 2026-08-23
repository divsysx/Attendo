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
 * has just picked a PDF out of their Downloads folder cannot act on a character offset, and the
 * one thing they *can* act on — pick a different file — was buried behind it.
 *
 * So the mapping is deliberately **total**: every way a file can be unusable produces the same
 * two sentences, including the cases where the app knows more (a backup from a newer build, a
 * file whose checksum fails). The reasoning is that the action is the same in all of them, the
 * distinction is one nobody outside the code can check, and a message that is right nine times
 * out of ten teaches people to ignore it the tenth. The precise reason is not lost — it goes to
 * the log, which is what these tests check second.
 */
class BackupMessagesTest {

    private val good = encoded()

    // ---- the words themselves -----------------------------------------------

    @Test
    fun `the refusal says what is wrong and what to do about it`() {
        assertEquals("That file isn't a valid Attendo backup.", BackupMessages.NOT_A_BACKUP)
        assertEquals(
            "Choose an Attendo backup (.json) file and try again.",
            BackupMessages.CHOOSE_A_BACKUP,
        )
    }

    @Test
    fun `the two sentences are also available as one line`() {
        assertEquals(
            "That file isn't a valid Attendo backup. " +
                "Choose an Attendo backup (.json) file and try again.",
            BackupMessages.REFUSED,
        )
    }

    // ---- every way a file can be the wrong file ------------------------------

    @Test
    fun `every kind of unusable file is refused in the same words`() {
        unusableFiles().forEach { (what, text) ->
            val failed = failedOn(what, text)

            assertEquals(what, BackupMessages.NOT_A_BACKUP, failed.userMessage)
            assertEquals(what, BackupMessages.CHOOSE_A_BACKUP, failed.userHint)
        }
    }

    @Test
    fun `nothing the parser said reaches the student`() {
        unusableFiles().forEach { (what, text) ->
            val failed = failedOn(what, text)

            listOf(failed.userMessage, failed.userHint).forEach { shown ->
                JARGON.forEach { word ->
                    assertFalse("$what showed \"$word\": $shown", shown.contains(word))
                }
                // No offsets, no counts, no version numbers: the only digits anywhere near this
                // screen came out of the file, and none of them mean anything to the reader.
                assertFalse("$what showed a number: $shown", shown.any(Char::isDigit))
            }
        }
    }

    @Test
    fun `the message is two short sentences rather than a report`() {
        val failed = failedOn("a PDF", PDF)

        assertEquals(1, failed.userMessage.count { it == '.' })
        assertTrue(failed.userMessage.length < 60)
        assertTrue(failed.userHint.length < 60)
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
    fun `the log names every fault, not just the first`() {
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
        // And still one sentence on screen: the student's next move does not change with the
        // number of things wrong with the file.
        assertEquals(BackupMessages.NOT_A_BACKUP, failed.userMessage)
    }

    @Test
    fun `a refusal with nothing to say about it cannot be constructed`() {
        val thrown = runCatching { BackupReadResult.Failed(emptyList()) }.exceptionOrNull()

        assertTrue("expected a refusal to require a reason", thrown is IllegalArgumentException)
    }

    // ---- the mapping is total ------------------------------------------------

    @Test
    fun `every kind of problem maps to the same sentence`() {
        EVERY_PROBLEM.forEach { problem ->
            val failed = BackupReadResult.Failed(listOf(problem))

            assertEquals(problem.message, BackupMessages.NOT_A_BACKUP, failed.userMessage)
            assertEquals(problem.message, BackupMessages.CHOOSE_A_BACKUP, failed.userHint)
            assertEquals(problem.message, failed.diagnostics)
        }
    }

    @Test
    fun `every diagnostic still says which kind of fault it was`() {
        EVERY_PROBLEM.forEach { problem ->
            val subject = subjectOf(problem)

            assertTrue(
                "${problem::class.simpleName} never mentions $subject: ${problem.message}",
                problem.message.contains(subject),
            )
        }
    }

    // ---- fixtures ------------------------------------------------------------

    /**
     * One entry for every way an import can fail — as files, not as hand-made problems.
     *
     * The first four are the case that prompted all of this: a file that was never a backup,
     * picked out of a folder full of them.
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
        "a backup from a newer Attendo" to
            tamper(good) { it.withField("formatVersion", BackupCodec.FORMAT_VERSION + 1) },
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

        private const val PNG = "PNG\r\n\n   \rIHDR"

        /**
         * Everything the old message said that nobody choosing the wrong file could act on.
         *
         * `.json` is not on the list on purpose: a file extension is something a person can look
         * for in a file picker, which is the whole point of the second sentence.
         */
        private val JARGON = listOf(
            "JSON", "token", "Token", "offset", "Offset", "EOF", "eof", "Exception",
            "expected", "Expected", "\$", "at path", "parse", "Parse", "parsing",
            "serial", "kotlinx", "\n",
        )

        /**
         * An example of every kind of refusal there is.
         *
         * Hand-written, because listing them needs reflection this module deliberately does not
         * depend on. [subjectOf] is what keeps the list honest: it is an exhaustive `when`, so a
         * new [BackupProblem] stops this file compiling, and adding the branch without adding
         * the example here leaves that branch untested.
         */
        private val EVERY_PROBLEM: List<BackupProblem> = listOf(
            BackupProblem.NotJson(
                "Unexpected JSON token at offset 3: Expected EOF after parsing, but had { " +
                    "instead at path: \$",
            ),
            BackupProblem.NotABackup("it has no formatVersion"),
            BackupProblem.UnsupportedFormatVersion(
                found = BackupCodec.FORMAT_VERSION + 1,
                supported = BackupCodec.supportedFormatVersions,
            ),
            BackupProblem.ChecksumMismatch(expected = "a1b2c3", actual = "d4e5f6"),
            BackupProblem.UnknownChecksumAlgorithm("CRC-32"),
            BackupProblem.MalformedField(
                path = "sessions[s1].date",
                detail = "expected a date as YYYY-MM-DD",
            ),
            BackupProblem.DanglingReference(from = "sessions[s1].courseRef", ref = "c99"),
            BackupProblem.DuplicateReference(kind = "course", ref = "c1"),
            BackupProblem.InvalidValue(
                path = "sessions[s1].startHour",
                detail = "outside the teaching day",
            ),
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
