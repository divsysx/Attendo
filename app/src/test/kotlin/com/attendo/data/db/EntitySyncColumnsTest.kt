package com.attendo.data.db

import com.attendo.core.model.Semester
import com.attendo.core.model.SemesterType
import com.attendo.data.AppBackupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Phase 2 sync columns, pinned at the boundary where they live.
 *
 * Four nullable columns and one flag now sit on every attendance row: `cloudId` (the row's
 * identity in the cloud, which the local autoGenerate Long is *not* — a restore renumbers
 * every row), `clientUpdatedAt` (device-clock edit time), `deletedAt` (a tombstone), and
 * `dirty` (local edits the cloud has not confirmed). Stage 2A is additive only: no code
 * reads or writes them yet, so what there is to test is exactly the part the rest of
 * Phase 2 will stand on.
 *
 * The claims, as pure functions over the entities:
 *
 *  * A row constructed the way *every existing call site* constructs one — including every
 *    `toEntity()` from a `:core` model — reads as "never synced". After the 8→9
 *    auto-migration, every row an upgraded install already has reads the same way, because
 *    the new columns are nullable (or defaulted, for `dirty`) and Room fills them with
 *    NULL/false rather than guessing.
 *  * Sync state, once set, survives being carried around as a row (`copy`), which is how
 *    the sync engine will move rows between reads and writes.
 *  * Sync state deliberately does **not** survive the entity→model→entity round trip. The
 *    `:core` models are the domain's vocabulary and carry no cloud identity; a row rebuilt
 *    from a model is a row nobody has pushed. That is pinned here so a later stage cannot
 *    "fix" it by accident — the sync engine owns these columns and writes entities
 *    directly, and a mapping that laundered sync state into fresh rows would hand two
 *    local rows one cloud identity.
 */
class EntitySyncColumnsTest {

    // ---- a synced row stays synced --------------------------------------------

    @Test
    fun `a row with sync state keeps it, field for field`() {
        val synced = AppBackupFixtures.signals.toEntity().copy(
            cloudId = "6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1",
            clientUpdatedAt = 1_765_812_000_000L,
            deletedAt = null,
            dirty = true,
        )

        // Carried around as a row, as the sync engine will: read, copy, write.
        val moved = synced.copy()

        assertEquals("6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1", moved.cloudId)
        assertEquals(1_765_812_000_000L, moved.clientUpdatedAt)
        assertNull(moved.deletedAt)
        assertTrue(moved.dirty)
        assertEquals(synced, moved)
    }

    @Test
    fun `a tombstoned row keeps the fact that it was deleted`() {
        // A delete in the cloud sense is a date, not an absence: the row stays so other
        // devices can learn the deletion. Null must stay null the same way.
        val tombstone = AppBackupFixtures.attendedInFull.toEntity().copy(
            cloudId = "d4c0ffee-0000-4000-8000-000000000000",
            deletedAt = 1_765_812_000_000L,
        )

        assertEquals(1_765_812_000_000L, tombstone.deletedAt)
        assertNull(tombstone.copy(deletedAt = null).deletedAt)
    }

    @Test
    fun `the pattern and semester rows carry the same sync shape`() {
        // Four tables, one contract. If a column name or type drifted on one entity, the
        // sync engine would discover it at runtime against the cloud; pinned here instead.
        val pattern = AppBackupFixtures.lab.toEntity().copy(
            cloudId = "aaaaaaaa-0000-4000-8000-000000000001",
            clientUpdatedAt = 1L,
            dirty = true,
        )
        val semester = running.toEntity().copy(
            cloudId = "aaaaaaaa-0000-4000-8000-000000000002",
            clientUpdatedAt = 2L,
            dirty = true,
        )

        assertEquals("aaaaaaaa-0000-4000-8000-000000000001", pattern.cloudId)
        assertTrue(pattern.dirty)
        assertEquals("aaaaaaaa-0000-4000-8000-000000000002", semester.cloudId)
        assertTrue(semester.dirty)
    }

    // ---- what an upgraded install starts from ----------------------------------

    @Test
    fun `a row written the pre-Phase-2 way reads as never synced`() {
        // Every existing call site constructs entities without the sync arguments — this is
        // what `toEntity()` produces today, and what every row an upgraded install already
        // holds reads as after the 8→9 auto-migration fills the new columns with NULL.
        val course = AppBackupFixtures.signals.toEntity()
        val pattern = AppBackupFixtures.lab.toEntity()
        val session = AppBackupFixtures.attendedInFull.toEntity()
        val semester = running.toEntity()

        // The four entities share the columns but no interface, so each is read by name.
        assertNeverSynced(course.cloudId, course.clientUpdatedAt, course.deletedAt, course.dirty)
        assertNeverSynced(pattern.cloudId, pattern.clientUpdatedAt, pattern.deletedAt, pattern.dirty)
        assertNeverSynced(session.cloudId, session.clientUpdatedAt, session.deletedAt, session.dirty)
        assertNeverSynced(semester.cloudId, semester.clientUpdatedAt, semester.deletedAt, semester.dirty)
    }

    @Test
    fun `an accountless install's rows keep reading as never synced`() {
        // The columns exist so the sync engine can be built without another schema change;
        // until an install links an account, nothing may touch them. A fresh row written
        // after the upgrade therefore looks exactly like a row written before it.
        val fresh = AppBackupFixtures.movedTo.toEntity()

        assertNull(fresh.cloudId)
        assertNull(fresh.clientUpdatedAt)
        assertNull(fresh.deletedAt)
        assertFalse(fresh.dirty)
    }

    // ---- the mapping boundary ----------------------------------------------------

    @Test
    fun `sync state does not survive the domain round trip, by design`() {
        // `:core` models carry no cloud identity, so a row rebuilt from a model is a row
        // nobody has pushed. Pinned as intended: a mapping that smuggled sync state through
        // the domain would let a normal save resurrect a deleted row's cloud identity onto
        // a fresh local row — two rows, one identity.
        val synced = AppBackupFixtures.signals.toEntity().copy(
            cloudId = "6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1",
            clientUpdatedAt = 1_765_812_000_000L,
            deletedAt = 1_765_812_000_000L,
            dirty = true,
        )

        val rebuilt = synced.toModel().toEntity()

        assertNull(rebuilt.cloudId)
        assertNull(rebuilt.clientUpdatedAt)
        assertNull(rebuilt.deletedAt)
        assertFalse(rebuilt.dirty)
    }

    // ---- dirty --------------------------------------------------------------------

    @Test
    fun `dirty defaults to false and stays whatever it was set to`() {
        // The default is the upgrade's promise: no existing row starts its life in Phase 2
        // claiming unconfirmed edits. And the flag is a plain Boolean, not a one-way latch —
        // the sync engine will clear it when the cloud confirms, so false after true must be
        // reachable.
        assertFalse(AppBackupFixtures.signals.toEntity().dirty)
        assertFalse(AppBackupFixtures.lab.toEntity().dirty)
        assertFalse(AppBackupFixtures.attendedInFull.toEntity().dirty)
        assertFalse(running.toEntity().dirty)

        val edited = AppBackupFixtures.signals.toEntity().copy(dirty = true)
        assertTrue(edited.dirty)
        assertFalse(edited.copy(dirty = false).dirty)
    }

    private companion object {
        /** A running semester, for the one entity the fixtures object does not build. */
        val running = Semester(
            id = 42L,
            year = 2027,
            type = SemesterType.EVEN,
            startDate = LocalDate.of(2027, 1, 5),
            endDate = LocalDate.of(2027, 5, 15),
        )
    }

    /** The "never synced" reading, spelled out once: everything null, nothing pending. */
    private fun assertNeverSynced(
        cloudId: String?,
        clientUpdatedAt: Long?,
        deletedAt: Long?,
        dirty: Boolean,
    ) {
        assertNull(cloudId)
        assertNull(clientUpdatedAt)
        assertNull(deletedAt)
        assertFalse(dirty)
    }
}
