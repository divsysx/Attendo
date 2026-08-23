package com.attendo.core.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format-version migration mechanism.
 *
 * There is one version today, so most of what these tests can do is exercise the walk with
 * invented steps. That is deliberate rather than a placeholder: the day a real step is written,
 * the format change will be the interesting part and the machinery carrying it should already be
 * known to work.
 *
 * The one test here that earns its keep immediately is
 * [the chain from the oldest supported format to the current one is unbroken] — it fails the
 * build if [BackupMigrations.CURRENT] is ever bumped without a step behind it, which is exactly
 * the mistake that turns "we can always migrate old backups" into a file nobody can read.
 */
class BackupMigrationsTest {

    private val empty = JsonObject(emptyMap())

    // ---- the guard that matters on every build -------------------------------

    @Test
    fun `the chain from the oldest supported format to the current one is unbroken`() {
        assertTrue(
            "format versions ${BackupMigrations.OLDEST_SUPPORTED} to ${BackupMigrations.CURRENT} " +
                "must each have a migration step",
            BackupMigrations.chainIsComplete(),
        )
    }

    @Test
    fun `the supported range is sane`() {
        assertTrue(BackupMigrations.OLDEST_SUPPORTED >= 1)
        assertTrue(BackupMigrations.CURRENT >= BackupMigrations.OLDEST_SUPPORTED)
        assertEquals(
            BackupMigrations.OLDEST_SUPPORTED..BackupMigrations.CURRENT,
            BackupMigrations.supported,
        )
    }

    @Test
    fun `only the versions in the range are supported`() {
        assertTrue(BackupMigrations.isSupported(BackupMigrations.CURRENT))
        assertTrue(BackupMigrations.isSupported(BackupMigrations.OLDEST_SUPPORTED))
        assertFalse(BackupMigrations.isSupported(BackupMigrations.CURRENT + 1))
        assertFalse(BackupMigrations.isSupported(BackupMigrations.OLDEST_SUPPORTED - 1))
        assertFalse(BackupMigrations.isSupported(0))
        assertFalse(BackupMigrations.isSupported(-1))
    }

    @Test
    fun `a payload already at the current version is handed back untouched`() {
        val payload = JsonObject(mapOf("courses" to JsonPrimitive("unchanged")))

        assertSame(payload, BackupMigrations.migrate(payload, BackupMigrations.CURRENT))
    }

    // ---- the walk, with invented steps --------------------------------------

    @Test
    fun `steps run in ascending order`() {
        val steps = mapOf(1 to trail("a"), 2 to trail("b"), 3 to trail("c"))

        val result = BackupMigrations.walk(empty, from = 1, to = 4, steps = steps)

        assertEquals("abc", trailOf(result))
    }

    @Test
    fun `only the steps from the file's version onwards run`() {
        val steps = mapOf(1 to trail("a"), 2 to trail("b"), 3 to trail("c"))

        assertEquals("bc", trailOf(BackupMigrations.walk(empty, from = 2, to = 4, steps = steps)))
        assertEquals("c", trailOf(BackupMigrations.walk(empty, from = 3, to = 4, steps = steps)))
    }

    @Test
    fun `walking nowhere changes nothing`() {
        val steps = mapOf(1 to trail("a"))

        assertSame(empty, BackupMigrations.walk(empty, from = 2, to = 2, steps = steps))
        assertSame(empty, BackupMigrations.walk(empty, from = 1, to = 1, steps = steps))
    }

    @Test
    fun `each step sees what the one before it produced`() {
        val steps = mapOf(
            1 to { payload: JsonObject -> JsonObject(payload + ("n" to JsonPrimitive(1))) },
            2 to { payload: JsonObject ->
                val previous = (payload.getValue("n") as JsonPrimitive).content.toInt()
                JsonObject(payload + ("n" to JsonPrimitive(previous * 10)))
            },
        )

        val result = BackupMigrations.walk(empty, from = 1, to = 3, steps = steps)

        assertEquals("10", (result.getValue("n") as JsonPrimitive).content)
    }

    @Test
    fun `a gap in the chain is reported as a programming error, not a bad file`() {
        // By the time the walk runs, the version has already been checked as supported. A
        // missing step therefore means this build shipped with a hole in its chain, and
        // returning an unmigrated payload would hand the decoder the wrong shape to read.
        val gappy = mapOf(1 to trail("a"), 3 to trail("c"))

        val thrown = runCatching { BackupMigrations.walk(empty, from = 1, to = 4, steps = gappy) }
            .exceptionOrNull()

        assertTrue("expected an IllegalStateException, got $thrown", thrown is IllegalStateException)
        assertTrue(
            "the message should name the version that has no way forward: ${thrown?.message}",
            thrown?.message?.contains("2") == true,
        )
    }

    @Test
    fun `chain completeness is exactly whether every version has a step`() {
        val complete = mapOf(1 to trail("a"), 2 to trail("b"))

        assertTrue(BackupMigrations.chainIsComplete(1, 3, complete))
        assertTrue(BackupMigrations.chainIsComplete(2, 3, complete))
        assertFalse(BackupMigrations.chainIsComplete(1, 4, complete))
        assertFalse(BackupMigrations.chainIsComplete(1, 3, mapOf(1 to trail("a"))))

        // Nothing to do is trivially complete, which is the state this build is in.
        assertTrue(BackupMigrations.chainIsComplete(1, 1, emptyMap()))
    }

    // ---- what the codec does with a version ---------------------------------

    @Test
    fun `a file's original version is remembered after it is migrated`() {
        // Today the two are equal; the field exists so that a v1 file restored by a v3 build
        // still reports "made by format 1" on the import screen.
        val meta = readOk(encoded()).meta

        assertEquals(BackupMigrations.CURRENT, meta.formatVersion)
        assertEquals(BackupMigrations.CURRENT, meta.originalFormatVersion)
        assertEquals(BackupCodec.FORMAT_VERSION, BackupMigrations.CURRENT)
    }

    // ---- helpers ------------------------------------------------------------

    /** A step that leaves a mark, so the order the steps ran in is visible in the result. */
    private fun trail(mark: String): (JsonObject) -> JsonObject = { payload ->
        JsonObject(payload + ("trail" to JsonPrimitive(trailOf(payload) + mark)))
    }

    private fun trailOf(payload: JsonObject): String =
        (payload["trail"] as? JsonPrimitive)?.content.orEmpty()
}
