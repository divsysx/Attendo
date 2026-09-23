package com.attendo.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The golden fixture suite: the format pinned by files, not by code.
 *
 * `fixtures/backup/` holds versioned JSON files — a valid v1, a representative v2, the
 * subtle edge cases, and one file per rejection category — with the expected outcome of
 * each recorded in `fixtures/backup/index.json`. This test reads those actual files and
 * runs them through [BackupCodec], exactly as `docs/backup-format.md` §13 requires. The
 * future Web implementation runs the same files against its own decoder, which is what
 * makes the suite a contract between platforms rather than a Kotlin test detail.
 *
 * Nothing here re-encodes a fixture through the implementation first. A fixture that
 * passed through `BackupCodec` on its way into the test would agree with
 * `BackupCodec` about everything, including its mistakes — so the files are read
 * verbatim from the classpath and handed to `decode` as text, the same way a student's
 * file arrives.
 *
 * The accept fixtures double as a checksum conformance test: `decode` verifies each
 * file's checksum before anything else, so a suite where the checksums had been
 * computed by the very code under test would be circular. These were computed from the
 * specification's canonicalisation (§7) by an independent implementation; if the
 * reference decoder disagrees, one of the two is wrong and this test is where it
 * surfaces.
 */
class GoldenFixturesTest {

    // ---- the index, as data ---------------------------------------------------

    @Serializable
    private data class FixtureIndex(val fixtures: List<FixtureEntry>)

    @Serializable
    private data class FixtureEntry(
        val file: String,
        /** "accept" or "reject". */
        val expect: String,
        /** The version the file was written as, before any migration. */
        val originalFormatVersion: Int? = null,
        /** The spec §9 rejection category, for reject fixtures. */
        val rejection: String? = null,
        /** For version rejections: which side of the supported window the file falls on. */
        val direction: String? = null,
        val summary: ExpectedSummary? = null,
    )

    /**
     * The decoded outcome, in terms any implementation can compute: row counts, how many
     * courses carry a resolvable semester link, and the raw attendance sums. Percentages
     * are excluded on purpose — formatting them is a UI concern, and 4-of-7 checks the
     * same arithmetic with none of the localisation.
     */
    @Serializable
    private data class ExpectedSummary(
        val courses: Int,
        val patterns: Int,
        val sessions: Int,
        val semesters: Int,
        val coursesWithSemester: Int,
        val unitsHeld: Int,
        val unitsAttended: Int,
    )

    /**
     * Tolerant of unknown keys, so the index can grow a field (as the format itself may)
     * without every implementation's suite-runner breaking on the same day.
     */
    private val indexJson = Json { ignoreUnknownKeys = true }

    private val index: FixtureIndex = indexJson.decodeFromString(
        FixtureIndex.serializer(),
        fixture("index.json"),
    )

    // ---- accept fixtures ------------------------------------------------------

    @Test
    fun `every accept fixture decodes with its recorded summary`() {
        val accepts = index.fixtures.filter { it.expect == "accept" }
        assertTrue("the suite has no accept fixtures", accepts.isNotEmpty())

        accepts.forEach { entry ->
            val result = BackupCodec.decode(fixture(entry.file))

            val backup = when (result) {
                is BackupReadResult.Ok -> result.backup
                is BackupReadResult.Failed ->
                    return@forEach fail("${entry.file} was refused: ${result.diagnostics}")
            }

            assertEquals(
                "${entry.file}: originalFormatVersion",
                entry.originalFormatVersion,
                backup.meta.originalFormatVersion,
            )
            assertEquals(
                "${entry.file}: a file always lands at the reader's current version",
                BackupCodec.FORMAT_VERSION,
                backup.meta.formatVersion,
            )

            val summary = entry.summary
                ?: return@forEach fail("${entry.file}: the index records no expected summary")
            val snapshot = backup.snapshot
            assertEquals("${entry.file}: courses", summary.courses, snapshot.courses.size)
            assertEquals("${entry.file}: patterns", summary.patterns, snapshot.patterns.size)
            assertEquals("${entry.file}: sessions", summary.sessions, snapshot.sessions.size)
            assertEquals("${entry.file}: semesters", summary.semesters, snapshot.semesters.size)
            assertEquals(
                "${entry.file}: courses with a semester link",
                summary.coursesWithSemester,
                snapshot.courses.count { it.semesterId != null },
            )
            assertEquals(
                "${entry.file}: units held",
                summary.unitsHeld,
                snapshot.sessions
                    .filter { it.status == com.attendo.core.model.SessionStatus.HELD }
                    .sumOf { it.unitsPlanned },
            )
            assertEquals(
                "${entry.file}: units attended",
                summary.unitsAttended,
                snapshot.sessions
                    .filter { it.status == com.attendo.core.model.SessionStatus.HELD }
                    .sumOf { it.unitsMask.countAttended(it.unitsPlanned) },
            )
        }
    }

    @Test
    fun `the v1 fixture exercises the migration end to end`() {
        val entry = index.fixtures.first { it.file == "v1-valid.json" }
        val backup = (BackupCodec.decode(fixture(entry.file)) as BackupReadResult.Ok).backup

        // The v1 file has no semesters; the migration derives exactly one from the
        // calendar (spec §8.3) and links the live course to it, leaving the archived
        // one unlinked because the file does not say which term it belongs to.
        val summary = entry.summary!!
        assertEquals(1, backup.snapshot.semesters.size)
        assertEquals(1, backup.snapshot.courses.count { it.semesterId != null })
        assertEquals(1, backup.snapshot.courses.count { it.archived && it.semesterId == null })
        assertEquals(summary.semesters, backup.snapshot.semesters.size)
    }

    // ---- reject fixtures ------------------------------------------------------

    @Test
    fun `every reject fixture is refused with its documented case`() {
        val rejects = index.fixtures.filter { it.expect == "reject" }
        assertTrue("the suite has no reject fixtures", rejects.isNotEmpty())

        rejects.forEach { entry ->
            val category = entry.rejection
                ?: return@forEach fail("${entry.file}: the index records no rejection category")

            val result = BackupCodec.decode(fixture(entry.file))
            val failed = when (result) {
                is BackupReadResult.Ok -> return@forEach fail(
                    "${entry.file} was accepted (${result.backup.summary.courses} courses), " +
                        "expected a $category refusal",
                )

                is BackupReadResult.Failed -> result
            }

            assertTrue(
                "${entry.file}: refused, but not for the recorded reason — expected " +
                    "$category, got ${failed.diagnostics}",
                failed.problems.any { it.matches(category, entry.direction) },
            )
        }
    }

    /** Maps a spec §9 category name onto the [BackupProblem] case that reports it. */
    private fun BackupProblem.matches(category: String, direction: String?): Boolean =
        when (category) {
            "notJson" -> this is BackupProblem.NotJson
            "notABackup" -> this is BackupProblem.NotABackup
            "unsupportedFormatVersion" -> this is BackupProblem.UnsupportedFormatVersion &&
                when (direction) {
                    "future" -> found > supported.last
                    "retired" -> found < supported.first
                    else -> true
                }
            "unknownChecksumAlgorithm" -> this is BackupProblem.UnknownChecksumAlgorithm
            "checksumMismatch" -> this is BackupProblem.ChecksumMismatch
            "malformedField" -> this is BackupProblem.MalformedField
            "danglingReference" -> this is BackupProblem.DanglingReference
            "duplicateReference" -> this is BackupProblem.DuplicateReference
            "invalidValue" -> this is BackupProblem.InvalidValue
            "brokenReschedule" -> this is BackupProblem.BrokenReschedule
            else -> false
        }

    // ---- the suite guards itself ----------------------------------------------

    /**
     * A suite that has quietly lost a rejection category, or the v1 fixture, still passes
     * its remaining files — so this test holds the suite to the minimum §13 defines:
     * every documented rejection category present (both directions of the version gate),
     * the two required valid fixtures, and the edge cases.
     */
    @Test
    fun `the suite covers everything the specification requires`() {
        val files = index.fixtures.map { it.file }

        assertTrue("v1-valid.json is missing from the suite", "v1-valid.json" in files)
        assertTrue("v2-valid.json is missing from the suite", "v2-valid.json" in files)

        val requiredCategories = setOf(
            "notJson", "notABackup", "unsupportedFormatVersion", "unknownChecksumAlgorithm",
            "checksumMismatch", "malformedField", "danglingReference", "duplicateReference",
            "invalidValue", "brokenReschedule",
        )
        val presentCategories = index.fixtures
            .filter { it.expect == "reject" }
            .mapNotNull { it.rejection }
            .toSet()
        assertEquals(
            "the suite does not cover every documented rejection category",
            requiredCategories,
            presentCategories,
        )

        val versionDirections = index.fixtures
            .filter { it.rejection == "unsupportedFormatVersion" }
            .mapNotNull { it.direction }
            .toSet()
        assertEquals(
            "the version gate must be pinned from both sides",
            setOf("future", "retired"),
            versionDirections,
        )

        val edgeCases = index.fixtures.count { it.file.startsWith("v2-edge-") }
        assertTrue(
            "the suite has $edgeCases edge-case fixtures; the specification calls for the " +
                "dangling patternRef, the unlinked reschedule, the unclaimed archived course, " +
                "the personal basis with no date, and the empty semesters array",
            edgeCases >= 5,
        )
    }

    @Test
    fun `every file in the index exists on the classpath`() {
        index.fixtures.forEach { entry ->
            assertNotNull(
                "${entry.file} is named in the index but missing from the suite",
                javaClass.getResourceAsStream("/backup/${entry.file}"),
            )
        }
    }

    // ---- reading the actual files ----------------------------------------------

    /** The fixture verbatim, the way a student's file arrives — never re-encoded. */
    private fun fixture(file: String): String =
        javaClass.getResourceAsStream("/backup/$file")!!
            .readBytes().decodeToString()
}
