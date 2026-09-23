package com.attendo.data.sync

import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.Percent
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionStatus
import com.attendo.data.AppSettings
import com.attendo.data.AppBackupFixtures
import com.attendo.data.db.CourseEntity
import com.attendo.data.db.PatternEntity
import com.attendo.data.db.SemesterEntity
import com.attendo.data.db.SessionEntity
import com.attendo.data.db.toEntity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The two conversions a sync lives or dies by, pinned as pure functions.
 *
 * Neither direction can be exercised end to end here: the store needs Room and the API needs a
 * server, and this module has neither in its test source set. What *can* be tested is the part
 * that actually decides things — what a local row becomes on the wire, and what an incoming row
 * is allowed to become locally. Both are pure, both are where a wrong value would enter the
 * database, and both are therefore tested here rather than being taken on trust.
 *
 * Three properties run through the whole file:
 *
 *  * **A row this client cannot read is refused, never repaired.** An unknown enum, an
 *    unparseable date, a term ending before it starts — each returns null, and the caller
 *    leaves the cloud's row alone. A default here would be a wrong class in a percentage.
 *  * **A value the schema forbids is clamped, not written.** Lengths, targets and the unit
 *    mask are the fields where an out-of-range value would corrupt a figure rather than
 *    merely look wrong.
 *  * **The server's columns are never sent.** `revision` and `updated_at` are the server's to
 *    assign, and a client that appeared to choose them would look like it was choosing its
 *    own place in the change stream.
 */
class AttendanceSyncRowsTest {

    // ---- the shape of a write ---------------------------------------------------

    @Test
    fun `a written row carries no server-owned column`() {
        // Migration 0020: revision and updated_at are "ignored/overwritten" on the way in.
        // Sending them would not corrupt anything — the triggers overwrite unconditionally —
        // but it would be this client stating a cursor position, which it never has.
        val body = wireBodyOf(sessionEntity().copy(cloudId = CLOUD_SESSION, clientUpdatedAt = 1L).toRow(
            userId = USER,
            courseCloudId = CLOUD_COURSE,
            patternCloudId = null,
            movedToCloudId = null,
            movedFromCloudId = null,
        ))

        assertFalse("revision must not be sent", body.containsKey("revision"))
        assertFalse("updated_at must not be sent", body.containsKey("updated_at"))
    }

    @Test
    fun `a written row uses the column names migration 0020 declares`() {
        // PostgREST is reached with PropertyConversionMethod.NONE, so a Kotlin property name
        // that leaked onto the wire would be a column PostgREST would reject by name. Pinned
        // on the fields most likely to drift, not on all of them.
        val body = wireBodyOf(
            sessionEntity().copy(cloudId = CLOUD_SESSION, clientUpdatedAt = 1L).toRow(
                userId = USER,
                courseCloudId = CLOUD_COURSE,
                patternCloudId = null,
                movedToCloudId = null,
                movedFromCloudId = null,
            ),
        )

        assertEquals(USER, body.getValue("user_id").jsonPrimitive.content)
        assertEquals(CLOUD_SESSION, body.getValue("id").jsonPrimitive.content)
        assertEquals(CLOUD_COURSE, body.getValue("course_id").jsonPrimitive.content)
        assertNotNull(body["units_mask_bits"])
        assertNotNull(body["units_planned"])
        assertNotNull(body["client_updated_at"])
        assertNotNull(body["start_hour"])
    }

    @Test
    fun `a cleared field is sent as an explicit null, not omitted`() {
        // The load-bearing half of `explicitNulls`. PostgREST sets exactly the columns
        // present in the body, so a room the student cleared must arrive as null; omitting
        // the key would leave the cloud's old room standing, and the next pull would put it
        // back on the phone.
        val cleared = sessionEntity()
            .copy(cloudId = CLOUD_SESSION, clientUpdatedAt = 1L, room = null, note = null)
            .toRow(USER, CLOUD_COURSE, null, null, null)

        val body = wireBodyOf(cleared)

        assertTrue("room must be present, as null", body.containsKey("room"))
        assertNull(body.getValue("room").jsonPrimitive.contentOrNull)
        assertTrue("note must be present, as null", body.containsKey("note"))
        assertNull(body.getValue("note").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `a boolean is sent as a boolean, not as a string`() {
        // `archived` is a real column, and a client that sent "false" would have PostgREST
        // reject the batch — every row in it, for one field.
        val course = CourseEntity(
            id = 1L,
            name = "Signals",
            code = "SS",
            targetBasisPoints = 7_500,
            colorArgb = 0,
            archived = false,
            cloudId = CLOUD_COURSE,
            clientUpdatedAt = 1L,
        )

        val body = wireBodyOf(course.toRow(USER, semesterCloudId = null))

        assertFalse(body.getValue("archived").jsonPrimitive.boolean)
    }

    @Test
    fun `the device clock is sent as an instant, not as a local date`() {
        // client_updated_at decides which device wins, and a bare date would make two edits
        // on the same day tie. A tie loses, so the day's first edit would stand.
        val row = sessionEntity()
            .copy(cloudId = CLOUD_SESSION, clientUpdatedAt = 1_765_812_345_678L)
            .toRow(USER, CLOUD_COURSE, null, null, null)

        assertEquals("2025-12-15T15:25:45.678Z", row.clientUpdatedAt)
    }

    // ---- reading a row off the wire ---------------------------------------------

    @Test
    fun `a pulled row is stored under the local id it already has`() {
        // The local primary key is not the cloud id — a restore renumbers it — so a row that
        // already exists locally must keep its own id, or every local reference to it (a
        // pattern's course, a session's pattern) would be left pointing at nothing.
        val existing = SemesterEntity(
            id = 77L,
            year = 2026,
            type = SemesterType.ODD,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 11, 30),
            archived = false,
        )

        val applied = semesterRow().toEntity(existing = existing, clientUpdatedAtMillis = 1_000L)

        assertEquals(77L, applied?.id)
    }

    @Test
    fun `a pulled row this device has never seen is inserted with a fresh id`() {
        // 0 is Room's "assign one", the same convention `toEntity()` on a `:core` model uses.
        val applied = semesterRow().toEntity(existing = null, clientUpdatedAtMillis = 1_000L)

        assertEquals(0L, applied?.id)
    }

    @Test
    fun `a pulled row lands clean, owning nothing back`() {
        // `dirty = false` is what makes this a row the cloud already knows: an applied row
        // that kept a dirty flag would be pushed straight back, which is a loop, not a sync.
        val applied = semesterRow().toEntity(existing = null, clientUpdatedAtMillis = 1_000L)!!

        assertEquals(CLOUD_SEMESTER, applied.cloudId)
        assertEquals(1_000L, applied.clientUpdatedAt)
        assertNull(applied.deletedAt)
        assertFalse(applied.dirty)
    }

    @Test
    fun `an unknown enum is refused rather than guessed`() {
        // A row from a version of the app that knows a term type this one does not. Storing
        // it as anything — the nearest match, the first entry, a default — would be this
        // client inventing a value the student never chose.
        assertNull(
            semesterRow(kind = "TRIMESTER").toEntity(existing = null, clientUpdatedAtMillis = 1L),
        )
        assertNull(patternRow(kind = "SEMINAR").toEntity(null, 1L, courseId = 5L))
        assertNull(sessionRow(status = "PENDING").toEntity(null, 1L, 5L, null, null, null))
        assertNull(sessionRow(kind = "SEMINAR").toEntity(null, 1L, 5L, null, null, null))
    }

    @Test
    fun `a date that cannot be parsed is refused`() {
        // `LocalDate.parse` on a corrupted or half-written value throws; catching it into
        // null is the difference between one skipped row and a sync that never completes.
        assertNull(semesterRow(startDate = "19-09-2026").toEntity(null, 1L))
        assertNull(sessionRow(date = "not a date").toEntity(null, 1L, 5L, null, null, null))
        assertNull(patternRow(effectiveFrom = "").toEntity(null, 1L, 5L))
    }

    @Test
    fun `a term that ends before it starts is refused`() {
        // The server's own constraint forbids it, so meeting one means the row is not what
        // this client thinks it is. Storing it would put a semester in the app whose every
        // per-term figure has to guess about the window.
        val reversed = semesterRow(startDate = "2026-11-30", endDate = "2026-07-01")

        assertNull(reversed.toEntity(existing = null, clientUpdatedAtMillis = 1L))
    }

    @Test
    fun `an empty timestamp reads as no timestamp rather than as the epoch`() {
        // The one that would silently lose data: a zero here is "edited at 1970", which
        // loses to everything, so a malformed timestamp would make a row impossible to
        // apply *and* impossible to notice.
        assertNull(millisOrNull(null))
        assertNull(millisOrNull(""))
        assertNull(millisOrNull("yesterday"))
        assertEquals(0L, millisOrNull("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `both timestamp shapes PostgREST can produce are read the same way`() {
        // PostgREST sends `Z` for a `timestamptz` but an explicit offset is a shape it is
        // equally entitled to produce; the two must not disagree, or the same row would win
        // or lose depending on which one arrived.
        assertEquals(
            millisOrNull("2026-09-19T10:30:00Z"),
            millisOrNull("2026-09-19T10:30:00+00:00"),
        )
    }

    @Test
    fun `an over-long field is clamped to what the column holds`() {
        // The server's constraints are the real limit; clamping here means a row that would
        // have been refused outright — taking its whole chunk of the push with it — lands
        // instead, shortened, with the rest of the batch intact.
        // Longer than every limit under test, so each assertion is the clamp and not the input.
        val long = "x".repeat(600)

        assertEquals(120, courseRow(name = long).toEntity(null, 1L, null).name.length)
        assertEquals(40, courseRow(code = long).toEntity(null, 1L, null).code.length)
        assertEquals(500, sessionRow(note = long).toEntity(null, 1L, 5L, null, null, null)?.note?.length)
    }

    @Test
    fun `a target outside the allowed range is clamped, not stored`() {
        // `target_bp between 0 and 10000` in migration 0020. A stored 200% would be a figure
        // the dashboard cannot reach, which reads to a student as "I can never pass".
        assertEquals(10_000, courseRow(targetBp = 99_999).toEntity(null, 1L, null).targetBasisPoints)
        assertEquals(0, courseRow(targetBp = -5).toEntity(null, 1L, null).targetBasisPoints)
    }

    @Test
    fun `a session's mask is narrowed to the units it actually plans`() {
        // The one field where a stale bit inflates a percentage instead of merely looking
        // wrong: a row claiming four attended units in a two-unit class would count twice.
        val applied = sessionRow(unitsPlanned = 2, unitsMaskBits = 0b1111)
            .toEntity(null, 1L, 5L, null, null, null)!!

        assertEquals(2, applied.unitsPlanned)
        assertEquals(0b11, applied.unitsMaskBits)
    }

    @Test
    fun `a negative mask is refused rather than stored as a large positive one`() {
        // Two's complement: a negative int has every high bit set, and a mask that was not
        // coerced first would clamp to "all units attended" — the most inflating possible
        // reading of a corrupt value.
        val applied = sessionRow(unitsPlanned = 3, unitsMaskBits = -1)
            .toEntity(null, 1L, 5L, null, null, null)!!

        assertEquals(0, applied.unitsMaskBits)
    }

    @Test
    fun `a class keeps the reason it was cancelled`() {
        val applied = sessionRow(status = "CANCELLED", cancellationReason = "HOLIDAY")
            .toEntity(null, 1L, 5L, null, null, null)!!

        assertEquals(SessionStatus.CANCELLED, applied.status)
        assertEquals(CancellationReason.HOLIDAY, applied.cancellationReason)
    }

    @Test
    fun `an unknown cancellation reason is dropped while the rest of the row is kept`() {
        // Unlike an unknown *status*, a reason the client does not know is not a reason to
        // refuse the row: the class was still cancelled, and that is the part that counts.
        val applied = sessionRow(status = "CANCELLED", cancellationReason = "STRIKE")
            .toEntity(null, 1L, 5L, null, null, null)!!

        assertEquals(SessionStatus.CANCELLED, applied.status)
        assertNull(applied.cancellationReason)
    }

    @Test
    fun `a hard-linked row is reflected rather than followed`() {
        // A reschedule is two rows linked to each other. The link is carried as a local id,
        // so the caller resolves the cloud ids before calling — and a link this device
        // cannot resolve reads as no link, which is the same shape the app already models
        // for a class whose counterpart has not arrived yet.
        val applied = sessionRow().toEntity(
            existing = null,
            clientUpdatedAtMillis = 1L,
            courseId = 5L,
            patternId = null,
            movedToSessionId = null,
            movedFromSessionId = 900L,
        )!!

        assertNull(applied.movedToSessionId)
        assertEquals(900L, applied.movedFromSessionId)
    }

    // ---- the settings row -------------------------------------------------------

    @Test
    fun `the settings row is keyed by the account, not by the device`() {
        // One row per account: `attendance.accounts` has `user_id` as its primary key, so a
        // second device signing in to the same account writes the same row rather than
        // creating a second one.
        val body = wireBodyOf(settings().toRow(USER, clientUpdatedAtMillis = 1L))

        assertEquals(USER, body.getValue("user_id").jsonPrimitive.content)
        assertEquals("2026-07-01", body.getValue("term_start").jsonPrimitive.content)
    }

    @Test
    fun `the same settings always fingerprint the same, and different ones never do`() {
        // The fingerprint is only ever compared against another version of itself — nothing
        // parses it — so all that matters is that it is a function of the settings and
        // nothing else. Two devices that agree must produce the same string, or they would
        // push the same row at each other forever.
        val mine = settings().toRow(USER, 1L)
        val same = settings().toRow(USER, 999L)

        assertEquals(mine.fingerprint(), same.fingerprint())
        assertFalse(mine.fingerprint() == mine.copy(overallTargetBp = 8_000).fingerprint())
        assertFalse(mine.fingerprint() == mine.copy(displayName = "Divyansh").fingerprint())
        assertFalse(mine.fingerprint() == mine.copy(holidays = listOf("2026-08-15")).fingerprint())
    }

    @Test
    fun `a holiday and a working Saturday are each part of the fingerprint`() {
        // The two fields a student changes from Settings and would never think to check had
        // synced: the calendar is what every date the app counts is measured against.
        val bare = settings().toRow(USER, 1L)

        assertFalse(bare.fingerprint() == bare.copy(workingSaturdays = listOf("2026-09-26")).fingerprint())
    }

    @Test
    fun `a settings row this client cannot read leaves the local settings alone`() {
        // Each of these is a value the *server's* constraints already forbid, so meeting one
        // means the row is not what this client thinks an accounts row is. Returning null is
        // the only safe reading: it writes nothing, and the row is re-decided next pass.
        val current = settings()

        assertNull(settings().toRow(USER, 1L).copy(attendanceBasis = "SEMESTER").toSettings(current))
        assertNull(settings().toRow(USER, 1L).copy(termStart = "nonsense").toSettings(current))
        assertNull(
            settings().toRow(USER, 1L).copy(termStart = "2026-11-30", termEnd = "2026-07-01")
                .toSettings(current),
        )
    }

    @Test
    fun `a personal basis with no joining date is refused`() {
        // `acc_personal_needs_date`: the server will not hold this row, so a client that
        // applied one would be applying a state the app's own arithmetic has no answer for —
        // there is no date to count attendance from.
        val personal = settings().toRow(USER, 1L).copy(
            attendanceBasis = AttendanceBasis.PERSONAL.name,
            joinedOn = null,
        )

        assertNull(personal.toSettings(settings()))
        assertNotNull(personal.copy(joinedOn = "2026-08-01").toSettings(settings()))
    }

    @Test
    fun `a readable settings row replaces the calendar and keeps nothing stale`() {
        // The whole point of pulling this row: the term on this phone becomes the term on
        // the account. A field left behind would be a device quietly disagreeing about which
        // classes count.
        val applied = settings().toRow(USER, 1L)
            .copy(holidays = listOf("2026-08-15"), workingSaturdays = listOf("2026-09-26"))
            .toSettings(settings())!!

        assertEquals(LocalDate.of(2026, 7, 1), applied.calendar.termStart)
        assertEquals(LocalDate.of(2026, 11, 30), applied.calendar.termEnd)
        assertEquals(setOf(LocalDate.of(2026, 8, 15)), applied.calendar.holidays)
        assertEquals(setOf(LocalDate.of(2026, 9, 26)), applied.calendar.workingSaturdays)
    }

    @Test
    fun `a display name longer than the column allows is truncated, not refused`() {
        val applied = settings().toRow(USER, 1L).copy(displayName = "x".repeat(200)).toSettings(settings())!!

        assertEquals(60, applied.displayName?.length)
    }

    // ---- fixtures ---------------------------------------------------------------

    private fun settings() = AppSettings(
        overallTarget = Percent.ofPercent(75.0),
        courseTarget = Percent.ofPercent(75.0),
        calendar = AcademicCalendar(
            termStart = LocalDate.of(2026, 7, 1),
            termEnd = LocalDate.of(2026, 11, 30),
        ),
        attendanceStart = AttendanceStart(basis = AttendanceBasis.UNIVERSITY),
    )

    private fun semesterRow(
        kind: String = SemesterType.ODD.name,
        startDate: String = "2026-07-01",
        endDate: String = "2026-11-30",
    ) = SemesterRow(
        id = CLOUD_SEMESTER,
        userId = USER,
        year = 2026,
        kind = kind,
        startDate = startDate,
        endDate = endDate,
        archived = false,
        clientUpdatedAt = "2026-09-19T10:30:00Z",
    )

    private fun courseRow(
        name: String = "Signals and Systems",
        code: String = "SS",
        targetBp: Int = 7_500,
    ) = CourseRow(
        id = CLOUD_COURSE,
        userId = USER,
        name = name,
        code = code,
        targetBp = targetBp,
        colorArgb = 0,
        archived = false,
        clientUpdatedAt = "2026-09-19T10:30:00Z",
    )

    private fun patternRow(
        kind: String = SessionKind.LECTURE.name,
        effectiveFrom: String = "2026-07-01",
    ) = PatternRow(
        id = CLOUD_PATTERN,
        userId = USER,
        courseId = CLOUD_COURSE,
        dayOfWeek = DayOfWeek.MONDAY.value,
        startHour = 10,
        units = 2,
        kind = kind,
        effectiveFrom = effectiveFrom,
        clientUpdatedAt = "2026-09-19T10:30:00Z",
    )

    private fun sessionRow(
        date: String = "2026-09-19",
        status: String = SessionStatus.HELD.name,
        kind: String = SessionKind.LECTURE.name,
        unitsPlanned: Int = 2,
        unitsMaskBits: Int = 0b11,
        cancellationReason: String? = null,
        note: String? = null,
    ) = SessionRow(
        id = CLOUD_SESSION,
        userId = USER,
        courseId = CLOUD_COURSE,
        date = date,
        startHour = 10,
        unitsPlanned = unitsPlanned,
        unitsMaskBits = unitsMaskBits,
        status = status,
        cancellationReason = cancellationReason,
        kind = kind,
        note = note,
        clientUpdatedAt = "2026-09-19T10:30:00Z",
    )

    /** A real local row, built the way the app builds one. */
    private fun sessionEntity(): SessionEntity = AppBackupFixtures.attendedInFull.toEntity()

    private companion object {
        const val USER = "8f14e45f-ceea-467a-9575-1b0c3a5e9d21"
        const val CLOUD_SEMESTER = "11111111-1111-4111-8111-111111111111"
        const val CLOUD_COURSE = "22222222-2222-4222-8222-222222222222"
        const val CLOUD_PATTERN = "33333333-3333-4333-8333-333333333333"
        const val CLOUD_SESSION = "44444444-4444-4444-8444-444444444444"
    }
}

/** `JsonPrimitive.contentOrNull` reads a JSON null as a null string, which is what a test wants. */
private val JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
