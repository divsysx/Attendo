package com.attendo.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.Percent
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionStatus
import com.attendo.core.sync.AttendanceSyncPolicy
import com.attendo.core.sync.SyncTable
import com.attendo.data.AppSettings
import com.attendo.data.SettingsStore
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.CourseEntity
import com.attendo.data.db.PatternEntity
import com.attendo.data.db.SemesterEntity
import com.attendo.data.db.SessionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The dataset, whole, in both directions.
 *
 * Supabase is the shared canonical copy of a student's attendance — the phone is one client
 * of it and the web version is another — so a record that exists on one device and cannot be
 * reconstructed from the cloud is a record the web client will never see, and an archived or
 * historical row that a pull quietly drops is attendance that disappears. These tests are
 * about coverage rather than about any one decision: every persistent attendance-domain
 * field, both ways, including the rows that are archived, historical, or already deleted.
 *
 * They run against the production database — see
 * [AttendanceSemesterReconciliationTest] for why the real schema is the point — and against
 * the production [SettingsStore], because the account half of this data lives in a
 * preferences file and a fake of it would be a second implementation to keep in step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttendanceSyncDatasetTest {

    private lateinit var context: Context
    private lateinit var db: AttendoDatabase
    private lateinit var store: AttendanceSyncStore
    private lateinit var settings: SettingsStore

    private val at: Instant = Instant.parse("2026-09-23T11:15:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AttendoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AttendanceSyncStore(db) { at }
        settings = SettingsStore(context)
    }

    @After
    fun tearDown() = db.close()

    // ---- archived courses ----------------------------------------------------

    @Test
    fun `an archived course arrives archived and is not deleted by the next sync`() = runBlocking {
        // Archiving is how a student puts a subject away without losing its attendance. The
        // row is still the record of a term's classes, so it has to travel — and, having
        // travelled, it must not be mistaken for a row the cloud has stopped mentioning and
        // swept away. Nothing in the pull deletes a row the cloud did not tombstone; this
        // pins it for the one row shape most likely to invite an "only sync live rows"
        // optimisation.
        store.applySemesters(listOf(semesterRow(CLOUD_ID, revision = 1L)))
        val semesterId = db.semesterDao().byCloudId(CLOUD_ID)!!.id

        store.applyCourses(
            listOf(
                courseRow(id = COURSE_ID, semesterId = CLOUD_ID, name = "Data Structures", targetBp = 7_500, revision = 1L),
                courseRow(id = ARCHIVED_ID, semesterId = CLOUD_ID, name = "Discrete Maths", archived = true, revision = 2L),
            ),
        )

        val archived = db.courseDao().byCloudId(ARCHIVED_ID)!!
        assertTrue("the archived flag is data, not a filter", archived.archived)
        assertEquals(semesterId, archived.semesterId)
        assertEquals(2, db.courseDao().all().size)

        // A second pass over the same rows — the archived one is re-read and rejected as
        // stale rather than skipped for being archived, and then survives untouched.
        store.applyCourses(
            listOf(
                courseRow(id = COURSE_ID, semesterId = CLOUD_ID, name = "Data Structures", targetBp = 7_500, revision = 1L),
                courseRow(id = ARCHIVED_ID, semesterId = CLOUD_ID, name = "Discrete Maths", archived = true, revision = 2L),
            ),
        )
        assertEquals(2, db.courseDao().all().size)
        assertTrue(db.courseDao().byCloudId(ARCHIVED_ID)!!.archived)
    }

    @Test
    fun `a fresh device receives active and archived courses together`() = runBlocking {
        store.applySemesters(listOf(semesterRow(CLOUD_ID, revision = 1L)))
        store.applyCourses(
            listOf(
                courseRow(id = COURSE_ID, semesterId = CLOUD_ID, revision = 1L),
                courseRow(id = ARCHIVED_ID, semesterId = CLOUD_ID, archived = true, revision = 2L),
            ),
        )

        val archived = db.courseDao().byCloudId(ARCHIVED_ID)
        assertNotNull("an archived course is part of the account a new phone has to reconstruct", archived)
        assertTrue(archived!!.archived)
        assertEquals(
            "and it still points at its term, which arrived in the same pull",
            db.semesterDao().byCloudId(CLOUD_ID)!!.id,
            db.courseDao().byCloudId(COURSE_ID)!!.semesterId,
        )
    }

    @Test
    fun `a course target travels from the cloud to the local course`() = runBlocking {
        // `courses.target_bp` is a per-course figure the student set on the other device, and
        // it is what every percentage for that subject is judged against. It is a column like
        // any other here, which is exactly why it is worth pinning: a target that did not
        // travel would leave the second phone measuring the same classes against the default.
        store.applySemesters(listOf(semesterRow(CLOUD_ID, revision = 1L)))
        val localId = db.courseDao().insert(
            course(localSemesterId = null, cloudId = COURSE_ID, clientUpdatedAt = 1_000L, targetBp = 7_500),
        )

        store.applyCourses(
            listOf(courseRow(id = COURSE_ID, semesterId = CLOUD_ID, targetBp = 8_250, clientUpdatedAt = "2026-09-20T00:00:00Z")),
        )

        val row = db.courseDao().byId(localId)!!
        assertEquals("the target is the point of this row", 8_250, row.targetBasisPoints)
        assertEquals("and so is everything else on it", "Data Structures", row.name)
    }

    @Test
    fun `a course the cloud deleted removes its patterns and sessions here`() = runBlocking {
        // The local half of a cloud deletion: `courses` is a parent with `ON DELETE CASCADE`,
        // and Room enforces the foreign keys, so the course's whole teaching history goes with
        // it. Pinned because the alternative — a dangling pattern — is a row nothing can name
        // and no future pull would ever clean up.
        val courseId = db.courseDao().insert(course(cloudId = COURSE_ID, clientUpdatedAt = 1_000L))
        val patternId = db.patternDao().insert(pattern(courseId = courseId, cloudId = PATTERN_ID, clientUpdatedAt = 1_000L))
        db.sessionDao().insert(session(courseId = courseId, patternId = patternId, cloudId = SESSION_ID, clientUpdatedAt = 1_000L))

        store.applyCourses(listOf(courseRow(id = COURSE_ID, deletedAt = "2026-09-21T00:00:00Z")))

        assertEquals(0, db.courseDao().all().size)
        assertEquals("the patterns went with the course", 0, db.patternDao().all().size)
        assertEquals("and so did the classes", 0, db.sessionDao().all().size)
    }

    // ---- historical rows -----------------------------------------------------

    @Test
    fun `a past semester with its patterns and classes is reconstructed whole`() = runBlocking {
        // The web client's requirement, stated as a test: a finished term has to be
        // reconstructible from the cloud alone, not merely present in the account's history
        // somewhere. Everything below was written by another device and arrives in one pull,
        // in the engine's order.
        store.applySemesters(
            listOf(
                semesterRow(PAST_SEMESTER_ID, year = 2026, kind = SemesterType.EVEN, start = "2026-01-05", end = "2026-05-20", archived = true, revision = 1L),
                semesterRow(CLOUD_ID, revision = 2L),
            ),
        )
        store.applyCourses(
            listOf(courseRow(id = ARCHIVED_ID, semesterId = PAST_SEMESTER_ID, archived = true, revision = 1L)),
        )
        store.applyPatterns(
            listOf(
                patternRow(id = PATTERN_ID, courseId = ARCHIVED_ID, effectiveFrom = "2026-01-05", effectiveTo = "2026-05-20", revision = 1L),
            ),
        )
        store.applySessions(
            listOf(
                sessionRow(id = SESSION_ID, courseId = ARCHIVED_ID, patternId = PATTERN_ID, date = "2026-03-04", status = SessionStatus.HELD, maskBits = 0b11, revision = 1L),
                sessionRow(id = CANCELLED_ID, courseId = ARCHIVED_ID, patternId = PATTERN_ID, date = "2026-03-11", status = SessionStatus.CANCELLED, reason = CancellationReason.HOLIDAY, revision = 2L),
            ),
        )

        val semester = db.semesterDao().byCloudId(PAST_SEMESTER_ID)!!
        assertTrue("a finished term is archived, and that state is history a web client needs", semester.archived)
        assertEquals(2, db.semesterDao().all().size)

        val pattern = db.patternDao().byCloudId(PATTERN_ID)!!
        assertEquals("a retired slot keeps its dates — they are what makes it historical", LocalDate.of(2026, 5, 20), pattern.effectiveTo)
        assertEquals(pattern.id, db.sessionDao().byCloudId(SESSION_ID)!!.patternId)

        val held = db.sessionDao().byCloudId(SESSION_ID)!!
        assertEquals(SessionStatus.HELD, held.status)
        assertEquals("the attendance itself: which hours the student was in", 0b11, held.unitsMaskBits)

        val cancelled = db.sessionDao().byCloudId(CANCELLED_ID)!!
        assertEquals(SessionStatus.CANCELLED, cancelled.status)
        assertEquals(CancellationReason.HOLIDAY, cancelled.cancellationReason)
    }

    @Test
    fun `a moved class keeps both ends of the move`() = runBlocking {
        // A rescheduled class is two rows and a pair of ids pointing at each other: the
        // original is cancelled with `RESCHEDULED` as the reason — that reason is what tells
        // the record why it did not happen — and both rows name the other. Losing either end is
        // a class that reads as having vanished rather than moved.
        store.applySemesters(listOf(semesterRow(CLOUD_ID, revision = 1L)))
        store.applyCourses(listOf(courseRow(id = COURSE_ID, semesterId = CLOUD_ID, revision = 1L)))
        store.applySessions(
            listOf(
                sessionRow(id = ORIGINAL_ID, courseId = COURSE_ID, date = "2026-09-08", status = SessionStatus.CANCELLED, reason = CancellationReason.RESCHEDULED, revision = 1L),
                sessionRow(id = REPLACEMENT_ID, courseId = COURSE_ID, date = "2026-09-09", status = SessionStatus.SCHEDULED, revision = 1L),
            ),
        )
        store.applySessions(
            listOf(
                sessionRow(id = ORIGINAL_ID, courseId = COURSE_ID, date = "2026-09-08", status = SessionStatus.CANCELLED, reason = CancellationReason.RESCHEDULED, movedTo = REPLACEMENT_ID, clientUpdatedAt = "2026-09-02T00:00:00Z", revision = 2L),
                sessionRow(id = REPLACEMENT_ID, courseId = COURSE_ID, date = "2026-09-09", status = SessionStatus.SCHEDULED, movedFrom = ORIGINAL_ID, clientUpdatedAt = "2026-09-02T00:00:00Z", revision = 2L),
            ),
        )

        val original = db.sessionDao().byCloudId(ORIGINAL_ID)!!
        val replacement = db.sessionDao().byCloudId(REPLACEMENT_ID)!!
        assertEquals("the link is a *local* id, resolved through the cloud id", replacement.id, original.movedToSessionId)
        assertEquals(original.id, replacement.movedFromSessionId)
        assertEquals(CancellationReason.RESCHEDULED, original.cancellationReason)
    }

    @Test
    fun `a class with notes and approval metadata keeps all of it`() = runBlocking {
        store.applySemesters(listOf(semesterRow(CLOUD_ID, revision = 1L)))
        store.applyCourses(listOf(courseRow(id = COURSE_ID, semesterId = CLOUD_ID, revision = 1L)))
        store.applyPatterns(listOf(patternRow(id = PATTERN_ID, courseId = COURSE_ID, revision = 1L)))

        store.applySessions(
            listOf(
                sessionRow(
                    id = SESSION_ID,
                    courseId = COURSE_ID,
                    patternId = PATTERN_ID,
                    date = "2026-09-10",
                    status = SessionStatus.HELD,
                    unitsPlanned = 3,
                    maskBits = 0b101,
                    note = "Left after the second hour",
                    approvedAt = "2026-09-10T18:00:00Z",
                    lastEditedAt = "2026-09-11T09:00:00Z",
                    revision = 1L,
                ),
            ),
        )

        val row = db.sessionDao().byCloudId(SESSION_ID)!!
        assertEquals("Left after the second hour", row.note)
        assertEquals(Instant.parse("2026-09-10T18:00:00Z"), row.approvedAt)
        assertEquals(Instant.parse("2026-09-11T09:00:00Z"), row.lastEditedAt)
        assertEquals(0b101, row.unitsMaskBits)
        assertEquals(db.patternDao().byCloudId(PATTERN_ID)!!.id, row.patternId)
    }

    @Test
    fun `a class deleted in the cloud is removed and does not come back`() = runBlocking {
        val courseId = db.courseDao().insert(course(cloudId = COURSE_ID, clientUpdatedAt = 1_000L))
        db.sessionDao().insert(session(courseId = courseId, patternId = null, cloudId = SESSION_ID, clientUpdatedAt = 1_000L))

        store.applySessions(listOf(sessionRow(id = SESSION_ID, courseId = COURSE_ID, status = SessionStatus.HELD, deletedAt = "2026-09-22T00:00:00Z")))

        assertEquals(0, db.sessionDao().all().size)
        assertNull(db.sessionDao().byCloudId(SESSION_ID))
    }

    // ---- the push half -------------------------------------------------------

    @Test
    fun `a first push uploads every persisted field of every row`() = runBlocking {
        // The claim "the server dataset suffices for a web client" is a claim about *this*
        // body. Every column that persists on the phone is asserted here by wire name, so a
        // field added to an entity and forgotten at the row boundary fails this test rather
        // than quietly never leaving the device.
        val semesterId = db.semesterDao().insert(
            SemesterEntity(
                year = 2026, type = SemesterType.ODD,
                startDate = LocalDate.of(2026, 7, 1), endDate = LocalDate.of(2026, 12, 15),
                archived = true, cloudId = null, clientUpdatedAt = at.toEpochMilli(), dirty = true,
            ),
        )
        val courseId = db.courseDao().insert(course(localSemesterId = semesterId, cloudId = null, clientUpdatedAt = at.toEpochMilli(), targetBp = 8_250, archived = true))
        val patternId = db.patternDao().insert(
            PatternEntity(
                courseId = courseId, dayOfWeek = DayOfWeek.TUESDAY, startHour = 11, units = 2,
                kind = SessionKind.PRACTICAL, room = "Lab 3",
                effectiveFrom = LocalDate.of(2026, 7, 1), effectiveTo = LocalDate.of(2026, 10, 31),
                cloudId = null, clientUpdatedAt = at.toEpochMilli(), dirty = true,
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                courseId = courseId, patternId = patternId, date = LocalDate.of(2026, 9, 15),
                startHour = 11, unitsPlanned = 2, unitsMaskBits = 0b10,
                status = SessionStatus.CANCELLED, cancellationReason = CancellationReason.FACULTY_CANCELLED,
                kind = SessionKind.PRACTICAL, room = "Lab 3", note = "Faculty away",
                approvedAt = at, lastEditedAt = at, movedToSessionId = null, movedFromSessionId = null,
                cloudId = null, clientUpdatedAt = at.toEpochMilli(), dirty = true,
            ),
        )
        settings.replaceAll(
            AppSettings(
                overallTarget = Percent.ofPercent(80.0),
                courseTarget = Percent.ofPercent(70.0),
                calendar = AcademicCalendar(
                    termStart = LocalDate.of(2026, 7, 1),
                    termEnd = LocalDate.of(2026, 12, 15),
                    holidays = setOf(LocalDate.of(2026, 8, 15)),
                    workingSaturdays = setOf(LocalDate.of(2026, 9, 19)),
                ),
                section = "A", batch = "A1", displayName = "Divyansh",
                attendanceStart = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = LocalDate.of(2026, 7, 20)),
            ),
        )

        val batch = store.preparePush(USER, settings.current, at)

        val semester = batch.bodies.getValue(SyncTable.SEMESTERS).single().body
        assertEquals(2026, semester.int("year"))
        assertEquals("ODD", semester.str("kind"))
        assertEquals("2026-07-01", semester.str("start_date"))
        assertEquals("2026-12-15", semester.str("end_date"))
        assertTrue("archived is a column, and it is uploaded like any other", semester.bool("archived"))
        assertTrue(semester.str("client_updated_at").isNotEmpty())

        val course = batch.bodies.getValue(SyncTable.COURSES).single().body
        assertEquals("Data Structures", course.str("name"))
        assertEquals("DSC", course.str("code"))
        assertEquals(8_250, course.int("target_bp"))
        assertEquals(0xFF3366, course.int("color_argb"))
        assertTrue(course.bool("archived"))
        assertEquals(
            "a course names its term by the term's cloud id, not by the local row id",
            semester.str("id"),
            course.str("semester_id"),
        )

        val pattern = batch.bodies.getValue(SyncTable.PATTERNS).single().body
        assertEquals(DayOfWeek.TUESDAY.value, pattern.int("day_of_week"))
        assertEquals(11, pattern.int("start_hour"))
        assertEquals(2, pattern.int("units"))
        assertEquals("PRACTICAL", pattern.str("kind"))
        assertEquals("Lab 3", pattern.str("room"))
        assertEquals("2026-07-01", pattern.str("effective_from"))
        assertEquals("2026-10-31", pattern.str("effective_to"))
        assertEquals("the pattern names its course by cloud id", course.str("id"), pattern.str("course_id"))

        val session = batch.bodies.getValue(SyncTable.SESSIONS).single().body
        assertEquals("2026-09-15", session.str("date"))
        assertEquals(11, session.int("start_hour"))
        assertEquals(2, session.int("units_planned"))
        assertEquals("which hours were attended is attendance, not bookkeeping", 0b10, session.int("units_mask_bits"))
        assertEquals("CANCELLED", session.str("status"))
        assertEquals("FACULTY_CANCELLED", session.str("cancellation_reason"))
        assertEquals("Faculty away", session.str("note"))
        assertEquals("PRACTICAL", session.str("kind"))
        assertEquals("Lab 3", session.str("room"))
        assertNotNull(session["approved_at"])
        assertNotNull(session["last_edited_at"])
        assertEquals(pattern.str("id"), session.str("pattern_id"))

        assertTrue("a first push is the pass that uploads an install's whole history", batch.initial)
        assertNotNull("the account row is part of that history", batch.accounts)
        assertNotNull("with the fingerprint that records what this device agreed", batch.accountsFingerprint)

        val accounts = batch.accounts!!
        assertEquals("2026-07-01", accounts.str("term_start"))
        assertEquals("2026-12-15", accounts.str("term_end"))
        assertEquals(listOf("2026-08-15"), accounts.array("holidays"))
        assertEquals(listOf("2026-09-19"), accounts.array("working_saturdays"))
        assertEquals(8_000, accounts.int("overall_target_bp"))
        assertEquals(7_000, accounts.int("default_target_bp"))
        assertEquals("A", accounts.str("section"))
        assertEquals("A1", accounts.str("batch"))
        assertEquals("Divyansh", accounts.str("display_name"))
        assertEquals("PERSONAL", accounts.str("attendance_basis"))
        assertEquals("2026-07-20", accounts.str("joined_on"))
    }

    @Test
    fun `a local delete becomes a tombstone the cloud is told about`() = runBlocking {
        // The client never issues a SQL delete to the cloud — migration 0020 grants no role
        // one; deleting is an UPDATE that sets `deleted_at`. Locally the row is gone, so the
        // only thing that can carry the deletion is the tombstone, written *before* the row is
        // removed from the database and complete enough to create the cloud row if the cloud
        // never had it.
        val courseId = db.courseDao().insert(course(cloudId = COURSE_ID, clientUpdatedAt = 1_000L))
        val rows = db.courseDao().all()
        // The owner claim the engine makes at the top of every pass — a tombstone is scoped to
        // the account it belongs to, and a delete on a linked phone belongs to that account.
        store.claimAttendance(USER, at)

        store.recordDeleted(AttendanceSyncStore.DeletedRows(courses = rows), at)
        db.courseDao().deleteById(courseId)

        val pending = db.attendanceSyncDao().pendingTombstonesForUser(USER)
        assertEquals(1, pending.size)
        assertEquals(SyncTable.COURSES.wireName, pending.single().syncTable)
        assertEquals(COURSE_ID, pending.single().cloudId)
        assertEquals("scoped to the account whose row it deletes", USER, pending.single().userId)

        val batch = store.preparePush(USER, settings.current, at)
        val tombstone = batch.tombstones.single()
        assertEquals(SyncTable.COURSES, tombstone.table)
        assertEquals("Data Structures", tombstone.body.str("name"))
        assertTrue("the payload is the row itself, marked deleted", tombstone.body.str("deleted_at").isNotEmpty())
    }

    @Test
    fun `a tombstone is pushed once and stays as the local record of the delete`() = runBlocking {
        db.courseDao().insert(course(cloudId = COURSE_ID, clientUpdatedAt = 1_000L))
        store.recordDeleted(AttendanceSyncStore.DeletedRows(courses = db.courseDao().all()), at)
        val key = db.attendanceSyncDao().pendingTombstonesForUser(USER).single().key

        store.markPushed(
            userId = USER,
            accepted = emptyMap(),
            acceptedTombstones = listOf(key),
            accountsAgreed = null,
            initialDone = false,
            at = at,
        )

        assertTrue("nothing left to send", db.attendanceSyncDao().pendingTombstonesForUser(USER).isEmpty())
        assertTrue(
            "but the record of the delete survives — the pull path asks it whether a row was deleted here",
            store.tombstonedIds(SyncTable.COURSES).contains(COURSE_ID),
        )
    }

    // ---- the account row -----------------------------------------------------

    @Test
    fun `every account field arrives from the cloud`() = runBlocking {
        // The settings a student changes on one phone and expects on the other. Each of these
        // is a separate column on `attendance.accounts` and a separate field in the preferences
        // file on the other side, so each is asserted by name rather than as "settings synced".
        val row = accountRow(
            clientUpdatedAt = "2026-09-22T10:00:00Z",
            termStart = "2026-07-01",
            termEnd = "2026-12-15",
            holidays = listOf("2026-08-15", "2026-10-02"),
            workingSaturdays = listOf("2026-09-19"),
            overallTargetBp = 8_000,
            defaultTargetBp = 7_000,
            section = "A",
            batch = "A1",
            displayName = "Divyansh",
            basis = "PERSONAL",
            joinedOn = "2026-07-20",
        )

        val applied = store.settingsFromAccount(USER, row, settings.current)
        assertNotNull("the row is newer than nothing, so it applies", applied)

        val out = applied!!
        assertEquals(LocalDate.of(2026, 7, 1), out.calendar.termStart)
        assertEquals(LocalDate.of(2026, 12, 15), out.calendar.termEnd)
        assertEquals(setOf(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 10, 2)), out.calendar.holidays)
        assertEquals(setOf(LocalDate.of(2026, 9, 19)), out.calendar.workingSaturdays)
        assertEquals(8_000, out.overallTarget.basisPoints)
        assertEquals(7_000, out.courseTarget.basisPoints)
        assertEquals("A", out.section)
        assertEquals("A1", out.batch)
        assertEquals("Divyansh", out.displayName)
        assertEquals(AttendanceBasis.PERSONAL, out.attendanceStart.basis)
        assertEquals(LocalDate.of(2026, 7, 20), out.attendanceStart.joinedOn)
    }

    @Test
    fun `a local settings change is not overwritten by the copy the cloud still holds`() = runBlocking {
        // The student picked a new target on this phone while offline; the cloud still holds
        // the old one and is not newer than what this device last agreed. Applying it would
        // silently undo the change. Same rule as `remoteWins`, with the local timestamp being
        // the fingerprint of what is on the device right now.
        store.ensureState(USER)
        store.recordAccountsApplied(USER, accountRow(clientUpdatedAt = "2026-09-22T10:00:00Z", overallTargetBp = 7_500))
        settings.setOverallTarget(Percent.ofPercent(85.0))

        val stale = store.settingsFromAccount(
            USER,
            accountRow(clientUpdatedAt = "2026-09-22T09:00:00Z", overallTargetBp = 6_000),
            settings.current,
        )

        assertNull("an older row loses to a local edit waiting to be pushed", stale)
        assertEquals(8_500, settings.current.overallTarget.basisPoints)

        val unknown = store.settingsFromAccount(
            USER,
            accountRow(clientUpdatedAt = "2026-09-22T09:00:00Z", basis = "SOMETHING_ELSE", overallTargetBp = 6_000),
            settings.current,
        )
        assertNull("a row this client cannot read leaves the settings alone", unknown)
    }

    @Test
    fun `a settings row the server has marked deleted is not applied`() = runBlocking {
        // The account row is the one table a tombstone cannot arrive from this client — the
        // client never soft-deletes its settings, and the row cascades away with the auth user.
        // It can still arrive from the server, where `deleted_at` is a column an operator sets.
        // Read as content, a deleted row would overwrite a live one on every device that pulled
        // it, and the settings would be gone with nothing left to say why.
        settings.setOverallTarget(Percent.ofPercent(85.0))

        val applied = store.settingsFromAccount(
            USER,
            accountRow(
                clientUpdatedAt = "2026-09-22T10:00:00Z",
                overallTargetBp = 6_000,
                deletedAt = "2026-09-22T11:00:00Z",
            ),
            settings.current,
        )

        assertNull("a deletion is not content", applied)
        assertEquals("the local settings stand", 8_500, settings.current.overallTarget.basisPoints)
    }

    // ---- the cursor, and what a manual sync is for ---------------------------

    @Test
    fun `a manual sync reconciles a row the cursor says was already read`() = runBlocking {
        // The requirement this exists for: Sync now must not answer from the cursor. Here the
        // device's cursor claims to have passed revision 5 — but the row at revision 3 is not
        // what this phone holds, because its local copy was replaced by a restore. An
        // incremental read starts above it and would never look; the reconcile read starts at
        // 0, finds it, and applies it.
        //
        // The read floor is the engine's (it calls `pullFloor` and passes the result to the
        // page loop), so this asserts the floor and what a page from each floor does.
        // The device has been syncing for a while, so its cursor is well above this row.
        store.ensureState(USER)
        store.advanceCursor(USER, cursor = 1_000_000L, at = at)
        val state = store.ensureState(USER)
        val courseId = db.courseDao().insert(course(cloudId = COURSE_ID, clientUpdatedAt = 1_000L, targetBp = 7_500))

        // The cheap read: nothing, because every row of this account is numbered below the cursor.
        val incrementalFloor = AttendanceSyncPolicy.pullFloor(state.cursor, AttendanceSyncPolicy.PullScope.INCREMENTAL)
        assertTrue("the cursor really is above the row", incrementalFloor > 3L)

        // What Sync now does.
        val reconcileFloor = AttendanceSyncPolicy.pullFloor(state.cursor, AttendanceSyncPolicy.PullScope.RECONCILE)
        assertEquals(0L, reconcileFloor)
        val remote = listOf(courseRow(id = COURSE_ID, targetBp = 9_000, clientUpdatedAt = "2026-09-20T00:00:00Z", revision = 3L))
            .filter { it.revision > reconcileFloor }
        assertTrue("a read from 0 still sees it", remote.isNotEmpty())

        store.applyCourses(remote)
        assertEquals(9_000, db.courseDao().byId(courseId)!!.targetBasisPoints)
    }

    @Test
    fun `a reconcile read of rows this device already holds writes nothing`() = runBlocking {
        // The other half of the property, and what makes the full re-read affordable: every row
        // a manual sync re-reads was already applied, so it is not strictly newer than the copy
        // held and is rejected without a write. Without this, RECONCILE would rewrite a term's
        // history on every press.
        store.applySemesters(listOf(semesterRow(CLOUD_ID, revision = 1L)))
        store.applyCourses(listOf(courseRow(id = COURSE_ID, semesterId = CLOUD_ID, targetBp = 9_000, revision = 2L)))
        val before = db.courseDao().byCloudId(COURSE_ID)!!

        val applied = store.applyCourses(listOf(courseRow(id = COURSE_ID, semesterId = CLOUD_ID, targetBp = 9_000, revision = 2L)))

        assertEquals("the revision is reported as dealt with, so the cursor may pass it", listOf(2L), applied.revisions)
        assertNull("and nothing was deferred", applied.firstDeferred)
        assertEquals(before, db.courseDao().byCloudId(COURSE_ID))
        assertFalse("an unchanged row is not marked as owing a push", db.courseDao().byCloudId(COURSE_ID)!!.dirty)
    }

    @Test
    fun `a row whose parent has not arrived is deferred rather than dropped`() = runBlocking {
        // Read order is semesters, courses, patterns, sessions, and the cloud enforces it with
        // composite foreign keys — but a page can still arrive before its parent if the parent
        // is what the previous page deferred. The row is held back and its revision reported so
        // the cursor stops below it, which is the difference between "not yet" and "lost".
        val applied = store.applySessions(
            listOf(sessionRow(id = SESSION_ID, courseId = "course-not-here", date = "2026-09-15", status = SessionStatus.HELD, revision = 4L)),
        )

        assertEquals(0, db.sessionDao().all().size)
        assertTrue("nothing was applied", applied.revisions.isEmpty())
        assertEquals("and the cursor is told to stop below the row", 4L, applied.firstDeferred)
    }

    // ---- helpers -------------------------------------------------------------

    private fun course(
        localSemesterId: Long? = null,
        cloudId: String?,
        clientUpdatedAt: Long?,
        targetBp: Int = 7_500,
        archived: Boolean = false,
    ) = CourseEntity(
        name = "Data Structures",
        code = "DSC",
        targetBasisPoints = targetBp,
        colorArgb = 0xFF3366,
        archived = archived,
        semesterId = localSemesterId,
        cloudId = cloudId,
        clientUpdatedAt = clientUpdatedAt,
        dirty = cloudId == null,
    )

    private fun pattern(courseId: Long, cloudId: String?, clientUpdatedAt: Long?) = PatternEntity(
        courseId = courseId,
        dayOfWeek = DayOfWeek.TUESDAY,
        startHour = 11,
        units = 2,
        kind = SessionKind.PRACTICAL,
        room = "Lab 3",
        effectiveFrom = LocalDate.of(2026, 7, 1),
        effectiveTo = null,
        cloudId = cloudId,
        clientUpdatedAt = clientUpdatedAt,
        dirty = cloudId == null,
    )

    private fun session(courseId: Long, patternId: Long?, cloudId: String?, clientUpdatedAt: Long?) = SessionEntity(
        courseId = courseId,
        patternId = patternId,
        date = LocalDate.of(2026, 9, 15),
        startHour = 11,
        unitsPlanned = 2,
        unitsMaskBits = 0b11,
        status = SessionStatus.HELD,
        cancellationReason = null,
        kind = SessionKind.PRACTICAL,
        room = "Lab 3",
        note = null,
        approvedAt = null,
        lastEditedAt = null,
        movedToSessionId = null,
        movedFromSessionId = null,
        cloudId = cloudId,
        clientUpdatedAt = clientUpdatedAt,
        dirty = cloudId == null,
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
        id = id, userId = USER, year = year, kind = kind.name,
        startDate = start, endDate = end, archived = archived,
        clientUpdatedAt = clientUpdatedAt, revision = revision, deletedAt = deletedAt,
    )

    private fun courseRow(
        id: String,
        semesterId: String? = null,
        name: String = "Data Structures",
        code: String = "DSC",
        targetBp: Int = 7_500,
        archived: Boolean = false,
        clientUpdatedAt: String = "2026-09-01T00:00:00Z",
        revision: Long = 1L,
        deletedAt: String? = null,
    ) = CourseRow(
        id = id, userId = USER, semesterId = semesterId, name = name, code = code,
        targetBp = targetBp, colorArgb = 0xFF3366, archived = archived,
        clientUpdatedAt = clientUpdatedAt, revision = revision, deletedAt = deletedAt,
    )

    private fun patternRow(
        id: String,
        courseId: String,
        effectiveFrom: String = "2026-07-01",
        effectiveTo: String? = null,
        kind: SessionKind = SessionKind.PRACTICAL,
        room: String? = "Lab 3",
        clientUpdatedAt: String = "2026-09-01T00:00:00Z",
        revision: Long = 1L,
        deletedAt: String? = null,
    ) = PatternRow(
        id = id, userId = USER, courseId = courseId, dayOfWeek = DayOfWeek.TUESDAY.value,
        startHour = 11, units = 2, kind = kind.name, room = room,
        effectiveFrom = effectiveFrom, effectiveTo = effectiveTo,
        clientUpdatedAt = clientUpdatedAt, revision = revision, deletedAt = deletedAt,
    )

    private fun sessionRow(
        id: String,
        courseId: String,
        patternId: String? = null,
        date: String = "2026-09-15",
        startHour: Int = 11,
        unitsPlanned: Int = 2,
        maskBits: Int = 0,
        status: SessionStatus = SessionStatus.HELD,
        reason: CancellationReason? = null,
        kind: SessionKind = SessionKind.PRACTICAL,
        room: String? = "Lab 3",
        note: String? = null,
        approvedAt: String? = null,
        lastEditedAt: String? = null,
        movedTo: String? = null,
        movedFrom: String? = null,
        clientUpdatedAt: String = "2026-09-01T00:00:00Z",
        revision: Long = 1L,
        deletedAt: String? = null,
    ) = SessionRow(
        id = id, userId = USER, courseId = courseId, patternId = patternId, date = date,
        startHour = startHour, unitsPlanned = unitsPlanned, unitsMaskBits = maskBits,
        status = status.name, cancellationReason = reason?.name, kind = kind.name, room = room,
        note = note, approvedAt = approvedAt, lastEditedAt = lastEditedAt,
        movedToSessionId = movedTo, movedFromSessionId = movedFrom,
        clientUpdatedAt = clientUpdatedAt, revision = revision, deletedAt = deletedAt,
    )

    private fun accountRow(
        clientUpdatedAt: String,
        termStart: String = "2026-07-01",
        termEnd: String = "2026-12-15",
        holidays: List<String> = emptyList(),
        workingSaturdays: List<String> = emptyList(),
        overallTargetBp: Int = 7_500,
        defaultTargetBp: Int = 7_500,
        section: String? = null,
        batch: String? = null,
        displayName: String? = null,
        basis: String = "UNIVERSITY",
        joinedOn: String? = null,
        deletedAt: String? = null,
    ) = AccountRow(
        userId = USER, termStart = termStart, termEnd = termEnd,
        holidays = holidays, workingSaturdays = workingSaturdays,
        overallTargetBp = overallTargetBp, defaultTargetBp = defaultTargetBp,
        section = section, batch = batch, displayName = displayName,
        attendanceBasis = basis, joinedOn = joinedOn, clientUpdatedAt = clientUpdatedAt,
        deletedAt = deletedAt,
    )

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.array(key: String): List<String> =
        (getValue(key) as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }

    private companion object {
        const val USER = "11111111-1111-4111-8111-111111111111"
        const val CLOUD_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val PAST_SEMESTER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val COURSE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val ARCHIVED_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val PATTERN_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val SESSION_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        const val CANCELLED_ID = "11111111-2222-4333-8444-555555555555"
        const val ORIGINAL_ID = "22222222-3333-4444-8555-666666666666"
        const val REPLACEMENT_ID = "33333333-4444-4555-8666-777777777777"
    }
}
