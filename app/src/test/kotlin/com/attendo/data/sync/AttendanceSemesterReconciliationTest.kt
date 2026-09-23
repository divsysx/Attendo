package com.attendo.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.attendo.core.model.SemesterType
import com.attendo.core.sync.SyncTable
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.CourseEntity
import com.attendo.data.db.SemesterEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * The semester reconciliation, against the real database.
 *
 * ### Why this is not a pure test
 *
 * Every rule [AttendanceSyncStore.applySemesters] follows is decided by a pure function in
 * `:core`, and those are tested there. This file exists for the one thing that cannot be:
 * the failure that took sync down in the field was not a wrong decision, it was a decision
 * that was never asked for. The store looked a semester up by cloud id, found nothing, and
 * inserted — and Room's unique index on `semesters(year, type)` refused the insert, which
 * aborted the page's transaction. The cursor only advances once a page commits, so the pass
 * could never get past the row: sync stopped on that device permanently, at the first table
 * in the order.
 *
 * A test against a hand-written fake would have agreed with the bug, because the fake would
 * have had no unique index to violate. So these tests open the production `AttendoDatabase`,
 * on an in-memory file, with its real schema, its real indices and its real generated DAOs —
 * `Room.inMemoryDatabaseBuilder` creates the schema from the entities, so
 * `index_semesters_year_type` is present exactly as it is on a phone.
 *
 * The exception itself is the assertion in the sharpest of them: a test that only checked the
 * resulting rows would pass on a build that threw and was caught somewhere above.
 */
@RunWith(RobolectricTestRunner::class)
// The production `AttendoApplication` builds the whole dependency graph in `onCreate`, which
// is not what any of this is about; a plain Application keeps the test to the database.
@Config(sdk = [34], application = Application::class)
class AttendanceSemesterReconciliationTest {

    private lateinit var db: AttendoDatabase
    private lateinit var store: AttendanceSyncStore

    /** The store's clock, pinned so a tombstone's timestamp is assertable. */
    private val deletedAt: Instant = Instant.parse("2026-09-23T09:30:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AttendoDatabase::class.java)
            // The production database is opened from a coroutine on a background dispatcher;
            // this test drives it from `runBlocking` on the test thread.
            .allowMainThreadQueries()
            .build()
        store = AttendanceSyncStore(db) { deletedAt }
    }

    @After
    fun tearDown() = db.close()

    // ---- the failure that was reported ---------------------------------------

    @Test
    fun `a term this device never pushed is reconciled, not inserted twice`() = runBlocking {
        // Exactly what the field log showed. `RolloverRepository` established the term locally
        // before the install was ever linked, so the row carries no cloud id and no timestamp;
        // the cloud holds the same term under an id this device has never seen. The old code
        // resolved nothing by cloud id, took the INSERT arm, and Room answered
        // `UNIQUE constraint failed: semesters.year, semesters.type (code 2067)`.
        val localId = db.semesterDao().insert(
            SemesterEntity(
                year = 2026,
                type = SemesterType.ODD,
                startDate = LocalDate.of(2026, 7, 1),
                endDate = LocalDate.of(2026, 12, 15),
                archived = false,
                cloudId = null,
                clientUpdatedAt = null,
                dirty = false,
            ),
        )

        // The call that used to throw. Not wrapped in `runCatching`: the failure *is* the
        // assertion, and a build that swallows it somewhere above must not read as a pass.
        store.applySemesters(listOf(semesterRow(id = CLOUD_ID, revision = 7L)))

        val rows = db.semesterDao().all()
        assertEquals("one term, one row", 1, rows.size)
        val row = rows.single()
        assertEquals("the local primary key survives, so courses naming it still name it", localId, row.id)
        assertEquals("the row takes the cloud identity it was reconciled with", CLOUD_ID, row.cloudId)
        assertEquals(LocalDate.of(2026, 7, 1), row.startDate)
        assertTrue("courses have to keep finding their term", db.semesterDao().byCloudId(CLOUD_ID) != null)
    }

    @Test
    fun `a semester the cloud already knows is updated rather than inserted`() = runBlocking {
        // The ordinary path, pinned so the reconciliation above cannot be made to work by
        // never inserting at all: a row resolved by cloud id is an update, and its content
        // moves.
        val localId = db.semesterDao().insert(localSemester(cloudId = CLOUD_ID, clientUpdatedAt = 1_000L))

        store.applySemesters(
            listOf(
                semesterRow(
                    id = CLOUD_ID,
                    start = "2026-08-01",
                    archived = true,
                    clientUpdatedAt = "2026-09-01T00:00:00Z",
                    revision = 9L,
                ),
            ),
        )

        val row = db.semesterDao().all().single()
        assertEquals(localId, row.id)
        assertEquals(LocalDate.of(2026, 8, 1), row.startDate)
        assertTrue("archiving is a column on this row and it travels", row.archived)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), row.clientUpdatedAt)
        assertTrue(db.attendanceSyncDao().pendingTombstonesForUser(USER).isEmpty())
    }

    @Test
    fun `an older cloud copy of a term does not overwrite a local edit`() = runBlocking {
        // LWW, unchanged by the reconciliation: the local row is newer, so the pull must not
        // touch it and must leave it owing a push.
        db.semesterDao().insert(
            localSemester(
                cloudId = CLOUD_ID,
                clientUpdatedAt = Instant.parse("2026-09-10T00:00:00Z").toEpochMilli(),
                start = LocalDate.of(2026, 7, 5),
                dirty = true,
            ),
        )

        store.applySemesters(
            listOf(semesterRow(id = CLOUD_ID, start = "2026-07-01", clientUpdatedAt = "2026-09-01T00:00:00Z")),
        )

        val row = db.semesterDao().all().single()
        assertEquals("the local edit stands", LocalDate.of(2026, 7, 5), row.startDate)
        assertTrue("and still owes the cloud a push", row.dirty)
    }

    // ---- two identities for one term -----------------------------------------

    @Test
    fun `the newer cloud identity supersedes the one this device holds`() = runBlocking {
        // Two devices each established this term offline, so there are two cloud rows for it.
        // The local row keeps its primary key — a course pointing at this term keeps pointing
        // at it — and takes the newer row's identity and content. The identity it used to carry
        // loses, and is tombstoned so the cloud converges to one row instead of the next pull
        // finding the duplicate again, forever.
        val localId = db.semesterDao().insert(
            localSemester(cloudId = "held-identity", clientUpdatedAt = 1_000L),
        )
        val courseId = db.courseDao().insert(
            CourseEntity(
                name = "Data Structures",
                code = "DSC",
                targetBasisPoints = 7_500,
                colorArgb = 0xFF0000,
                archived = false,
                semesterId = localId,
                cloudId = "course-cloud-id",
                clientUpdatedAt = 1_000L,
                dirty = false,
            ),
        )

        store.applySemesters(
            listOf(semesterRow(id = "newer-identity", start = "2026-08-01", clientUpdatedAt = "2026-09-01T00:00:00Z")),
        )

        val row = db.semesterDao().all().single()
        assertEquals("the term keeps its primary key", localId, row.id)
        assertEquals("and adopts the surviving identity", "newer-identity", row.cloudId)
        assertEquals(LocalDate.of(2026, 8, 1), row.startDate)
        assertEquals(
            "the course still names its term — a supersede must not orphan a term's history",
            localId,
            db.courseDao().byId(courseId)?.semesterId,
        )

        val tombstones = db.attendanceSyncDao().pendingTombstonesForUser(USER)
        assertEquals("the losing duplicate is deleted in the cloud, not here", 1, tombstones.size)
        assertEquals(SyncTable.SEMESTERS.wireName, tombstones.single().syncTable)
        assertEquals("held-identity", tombstones.single().cloudId)
        assertTrue("the tombstone names the account it belongs to", tombstones.single().userId == USER)
    }

    @Test
    fun `the newer local identity stands and the incoming duplicate is tombstoned`() = runBlocking {
        // The mirror image, and the reason the surviving identity has to be a function of the
        // two rows rather than of which one this device happens to hold: without it, the two
        // devices would tombstone each other's semester and the term would be deleted.
        db.semesterDao().insert(
            localSemester(
                cloudId = "held-identity",
                clientUpdatedAt = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli(),
                start = LocalDate.of(2026, 7, 5),
            ),
        )

        store.applySemesters(
            listOf(semesterRow(id = "staler-identity", start = "2026-07-01", clientUpdatedAt = "2026-09-01T00:00:00Z")),
        )

        val row = db.semesterDao().all().single()
        assertEquals("this device's identity holds", "held-identity", row.cloudId)
        assertEquals("and so does its content", LocalDate.of(2026, 7, 5), row.startDate)
        assertEquals(
            "the incoming duplicate is deleted in the cloud",
            setOf("staler-identity"),
            store.tombstonedIds(SyncTable.SEMESTERS),
        )
    }

    @Test
    fun `two devices that disagree about nothing still agree on one identity`() = runBlocking {
        // A tie on `client_updated_at` — the two rows were written in the same millisecond, or
        // the same edit was made twice. Whichever way round the two devices see them, the
        // larger cloud id has to win, or one device keeps a row the other has just tombstoned
        // and the term does not survive the next pull.
        val low = "00000000-0000-4000-8000-000000000001"
        val high = "ffffffff-ffff-4fff-bfff-ffffffffffff"
        val at = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli()

        db.semesterDao().insert(localSemester(cloudId = low, clientUpdatedAt = at))
        store.applySemesters(listOf(semesterRow(id = high, clientUpdatedAt = "2026-09-15T12:00:00Z")))
        assertEquals(high, db.semesterDao().all().single().cloudId)
        assertEquals(setOf(low), store.tombstonedIds(SyncTable.SEMESTERS))

        // The other device, holding `high` and being told about `low`.
        db.semesterDao().deleteAll()
        db.attendanceSyncDao().clearTombstones()
        db.semesterDao().insert(localSemester(cloudId = high, clientUpdatedAt = at))
        store.applySemesters(listOf(semesterRow(id = low, clientUpdatedAt = "2026-09-15T12:00:00Z")))
        assertEquals(high, db.semesterDao().all().single().cloudId)
        assertEquals(setOf(low), store.tombstonedIds(SyncTable.SEMESTERS))
    }

    @Test
    fun `a term established offline is adopted without losing its own newer content`() = runBlocking {
        // The two-device case the reconciliation exists for, in the shape the repository now
        // writes: a term established here while signed out has a timestamp and a dirty flag but
        // no cloud id. The local row takes the incoming identity — one term, one cloud row —
        // and keeps its own content because its edit is newer; it stays dirty, so its next push
        // uploads that content *under the identity it has just been given* rather than creating
        // a third row.
        val localId = db.semesterDao().insert(
            localSemester(
                cloudId = null,
                clientUpdatedAt = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli(),
                start = LocalDate.of(2026, 7, 8),
                dirty = true,
            ),
        )

        store.applySemesters(
            listOf(semesterRow(id = CLOUD_ID, start = "2026-07-01", clientUpdatedAt = "2026-09-01T00:00:00Z")),
        )

        val row = db.semesterDao().all().single()
        assertEquals(1, db.semesterDao().all().size)
        assertEquals(localId, row.id)
        assertEquals(CLOUD_ID, row.cloudId)
        assertEquals("the offline edit is newer, so it stands", LocalDate.of(2026, 7, 8), row.startDate)
        assertTrue("and it still owes the cloud a push", row.dirty)
        assertTrue("nothing was deleted — this was one row meeting another", store.tombstonedIds(SyncTable.SEMESTERS).isEmpty())
    }

    @Test
    fun `an adopted term takes the cloud's content when the cloud is newer`() = runBlocking {
        db.semesterDao().insert(
            localSemester(
                cloudId = null,
                clientUpdatedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
                start = LocalDate.of(2026, 7, 8),
                dirty = true,
            ),
        )

        store.applySemesters(
            listOf(semesterRow(id = CLOUD_ID, start = "2026-07-01", clientUpdatedAt = "2026-09-01T00:00:00Z")),
        )

        val row = db.semesterDao().all().single()
        assertEquals(CLOUD_ID, row.cloudId)
        assertEquals(LocalDate.of(2026, 7, 1), row.startDate)
        assertTrue("the cloud's copy is now what this device holds, so nothing is owed", !row.dirty)
    }

    // ---- a fresh device ------------------------------------------------------

    @Test
    fun `a fresh device takes the term from an empty database`() = runBlocking {
        assertEquals(0, db.semesterDao().all().size)

        store.applySemesters(
            listOf(
                semesterRow(id = CLOUD_ID, revision = 1L),
                semesterRow(id = OTHER_ID, year = 2027, kind = SemesterType.EVEN, start = "2027-01-05", end = "2027-05-20", archived = true, revision = 2L),
            ),
        )

        val rows = db.semesterDao().all()
        assertEquals(2, rows.size)
        assertEquals(setOf(CLOUD_ID, OTHER_ID), rows.mapNotNull { it.cloudId }.toSet())
        assertTrue("an archived term is history a fresh device has to receive", rows.single { it.cloudId == OTHER_ID }.archived)
    }

    @Test
    fun `a semester deleted in the cloud is removed here`() = runBlocking {
        db.semesterDao().insert(localSemester(cloudId = CLOUD_ID, clientUpdatedAt = 1_000L))

        store.applySemesters(
            listOf(semesterRow(id = CLOUD_ID, clientUpdatedAt = "2026-09-01T00:00:00Z", deletedAt = "2026-09-02T00:00:00Z")),
        )

        assertEquals(0, db.semesterDao().all().size)
        assertTrue(
            "a row the cloud deleted is not a duplicate — nothing is raised against it",
            store.tombstonedIds(SyncTable.SEMESTERS).isEmpty(),
        )
    }

    @Test
    fun `a row this client cannot read is stepped over rather than stalling the page`() = runBlocking {
        // An unreadable row is one the server's own constraints forbid, so meeting one means
        // the row is not what this client thinks the table is. Skipping it *and reporting its
        // revision* is what lets the cursor move past it; the alternative is a page that can
        // never commit, which stops the table for good.
        val applied = store.applySemesters(
            listOf(
                semesterRow(id = CLOUD_ID, kind = SemesterType.ODD, start = "2026-12-15", end = "2026-07-01"),
                semesterRow(id = OTHER_ID, clientUpdatedAt = "not-a-timestamp"),
            ),
        )

        assertEquals(0, db.semesterDao().all().size)
        assertEquals(
            "both revisions are reported as dealt with, so the cursor may pass them",
            listOf(1L, 1L).sorted(),
            applied.revisions.sorted(),
        )
        assertNull(applied.firstDeferred)
    }

    // ---- helpers -------------------------------------------------------------

    private fun localSemester(
        cloudId: String?,
        clientUpdatedAt: Long?,
        start: LocalDate = LocalDate.of(2026, 7, 1),
        dirty: Boolean = false,
    ) = SemesterEntity(
        year = 2026,
        type = SemesterType.ODD,
        startDate = start,
        endDate = LocalDate.of(2026, 12, 15),
        archived = false,
        cloudId = cloudId,
        clientUpdatedAt = clientUpdatedAt,
        dirty = dirty,
    )

    private fun semesterRow(
        id: String,
        year: Int = 2026,
        kind: SemesterType = SemesterType.ODD,
        start: String = "2026-07-01",
        end: String = "2026-12-15",
        archived: Boolean = false,
        clientUpdatedAt: String = "2026-09-01T00:00:00Z",
        revision: Long = 1L,
        deletedAt: String? = null,
    ) = SemesterRow(
        id = id,
        userId = USER,
        year = year,
        kind = kind.name,
        startDate = start,
        endDate = end,
        archived = archived,
        clientUpdatedAt = clientUpdatedAt,
        revision = revision,
        deletedAt = deletedAt,
    )

    private companion object {
        const val USER = "11111111-1111-4111-8111-111111111111"
        const val CLOUD_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
