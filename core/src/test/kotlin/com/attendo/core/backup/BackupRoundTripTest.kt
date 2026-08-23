package com.attendo.core.backup

import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.Tally
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionOrigin
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Export → import, checked for *logical* equality rather than for the numbers on the dashboard
 * agreeing.
 *
 * Those are different claims. Two databases can show the same 78% while disagreeing about which
 * classes were cancelled, which pattern generated which session, and which of a rescheduled
 * pair carries the attendance — and every one of those differences becomes visible the moment
 * the timetable is regenerated or a class is edited. So the central assertion here compares the
 * whole restored snapshot against the original, field by field, via
 * [BackupSnapshot.normalised]: ids are reassigned canonically on both sides, so the comparison
 * ignores the one thing a restore is *allowed* to change and nothing else.
 *
 * The per-state tests that follow are not redundant with it. The equality test says the data is
 * identical; they say what the data was, so that a future change to `normalised` that quietly
 * dropped a field could not make the equality test pass by weakening both sides at once.
 */
class BackupRoundTripTest {

    private val original = BackupFixtures.snapshot()

    // ---- the central property -----------------------------------------------

    @Test
    fun `a semester of every kind of class survives the round trip`() {
        val restored = readOk(encoded(original)).snapshot

        assertEquals(original.normalised(), restored)
    }

    @Test
    fun `restoring is stable, so a file exported from a restore is the same file`() {
        val once = encoded(original)
        val twice = encoded(readOk(once).snapshot)

        assertEquals(once, twice)
    }

    @Test
    fun `normalising is idempotent`() {
        val once = original.normalised()

        assertEquals(once, once.normalised())
    }

    @Test
    fun `the same data exports to the same bytes whatever order it arrives in`() {
        val shuffled = original.copy(
            courses = original.courses.reversed(),
            patterns = original.patterns.reversed(),
            sessions = original.sessions.reversed(),
        )

        assertEquals(encoded(original), encoded(shuffled))
    }

    @Test
    fun `a compact backup and an indented one carry the same data`() {
        val pretty = readOk(encoded(original, pretty = true)).snapshot
        val compact = readOk(encoded(original, pretty = false)).snapshot

        assertEquals(pretty, compact)
        assertNotEquals(encoded(original, pretty = true), encoded(original, pretty = false))
    }

    // ---- every session state ------------------------------------------------

    @Test
    fun `a fully attended class comes back fully attended`() {
        val session = restored(BackupFixtures.fullyAttended)

        assertEquals(SessionStatus.HELD, session.status)
        assertEquals(2, session.unitsPlanned)
        assertEquals(2, session.unitsAttended)
        assertTrue(session.unitsMask.isFullyAttended(2))
        assertEquals(Percent.FULL, session.sessionPercent)
        assertNotNull(session.approvedAt)
        assertEquals(BackupFixtures.fullyAttended.approvedAt, session.approvedAt)
    }

    @Test
    fun `attendance for part of a class comes back to the hour`() {
        val session = restored(BackupFixtures.halfAttended)

        assertEquals(1, session.unitsAttended)
        assertEquals(2, session.unitsPlanned)
        assertEquals(Percent.ofPercent(50.0), session.sessionPercent)
        // Which hour, not just how many: the second was attended and the first was not.
        assertFalse(session.isUnitAttended(0))
        assertTrue(session.isUnitAttended(1))
        assertEquals(listOf(1), session.unitsMask.attendedIndices(2))
        assertEquals("Missed the first hour", session.note)
    }

    @Test
    fun `a class held and entirely missed stays held rather than becoming cancelled`() {
        val session = restored(BackupFixtures.fullyMissed)

        assertEquals(SessionStatus.HELD, session.status)
        assertEquals(0, session.unitsAttended)
        assertEquals(2, session.unitsPlanned)
        // The distinction that matters: this one is in the denominator, a cancelled one is not.
        assertTrue(session.countsTowardAttendance)
        assertFalse(session.isCancelled)
        assertEquals(Percent.ZERO, session.sessionPercent)
        assertNull(session.cancellationReason)
    }

    @Test
    fun `a shortened class keeps its reduced length and its clamped mask`() {
        val session = restored(BackupFixtures.shortened)

        // The pattern says two hours; this occurrence ran one, and the missed hour must not be
        // held against the student.
        assertEquals(2, BackupFixtures.adecMondayOld.units)
        assertEquals(1, session.unitsPlanned)
        assertEquals(1, session.unitsAttended)
        assertEquals(0, session.unitsMissed)
        assertEquals(Percent.FULL, session.sessionPercent)
        assertEquals(BackupFixtures.shortened.lastEditedAt, session.lastEditedAt)
    }

    @Test
    fun `both kinds of cancellation keep their reason`() {
        val byFaculty = restored(BackupFixtures.cancelledByFaculty)
        val forHoliday = restored(BackupFixtures.cancelledForHoliday)

        assertEquals(SessionStatus.CANCELLED, byFaculty.status)
        assertEquals(CancellationReason.FACULTY_CANCELLED, byFaculty.cancellationReason)
        assertFalse(byFaculty.countsTowardAttendance)
        assertNull(byFaculty.sessionPercent)

        assertEquals(CancellationReason.HOLIDAY, forHoliday.cancellationReason)
        assertFalse(forHoliday.countsTowardAttendance)
    }

    @Test
    fun `a class awaiting review comes back awaiting review and still counts for nothing`() {
        val session = restored(BackupFixtures.awaitingReview)

        assertEquals(SessionStatus.SCHEDULED, session.status)
        assertTrue(session.isAwaitingReview)
        assertFalse(session.countsTowardAttendance)
        // Pre-filled present in the UI, which must not turn into recorded attendance.
        assertEquals(UnitMask.allPresent(2), session.unitsMask)
        assertNull(session.approvedAt)
    }

    @Test
    fun `a reschedule comes back as two rows that point at each other`() {
        val snapshot = readOk(encoded(original)).snapshot
        val cancelled = snapshot.session(BackupFixtures.movedAway)
        val replacement = snapshot.session(BackupFixtures.movedTo)

        assertTrue(cancelled.wasRescheduledAway)
        assertEquals(CancellationReason.RESCHEDULED, cancelled.cancellationReason)
        assertFalse(cancelled.countsTowardAttendance)

        // The links are ids, and the ids changed. What has to survive is that they still find
        // each other — otherwise one moved class is counted as two.
        assertEquals(replacement.id, cancelled.movedToSessionId)
        assertEquals(cancelled.id, replacement.movedFromSessionId)
        assertNotEquals(BackupFixtures.movedTo.id, replacement.id)

        // The replacement is where the attendance lives, and it is ad-hoc.
        assertEquals(SessionOrigin.ADHOC, replacement.origin)
        assertNull(replacement.patternId)
        assertEquals(SessionStatus.HELD, replacement.status)
        assertEquals(2, replacement.unitsAttended)
        assertEquals(LocalDate.of(2026, 9, 2), replacement.date)
        assertEquals(16, replacement.startHour)
    }

    @Test
    fun `a cancellation whose replacement was deleted keeps the cancellation and loses the arrow`() {
        // Deleting the ad-hoc replacement on its own is something the app allows, and it leaves
        // the original pointing at a row that is gone. The cancellation is still true.
        val withoutReplacement = original.copy(
            sessions = original.sessions.filter { it.id != BackupFixtures.movedTo.id },
        )

        val snapshot = readOk(encoded(withoutReplacement)).snapshot
        val cancelled = snapshot.session(BackupFixtures.movedAway)

        assertEquals(SessionStatus.CANCELLED, cancelled.status)
        assertEquals(CancellationReason.RESCHEDULED, cancelled.cancellationReason)
        assertNull(cancelled.movedToSessionId)
        assertEquals(original.sessions.size - 1, snapshot.sessions.size)
    }

    @Test
    fun `an extra class comes back ad-hoc`() {
        val session = restored(BackupFixtures.extraClass)

        assertEquals(SessionOrigin.ADHOC, session.origin)
        assertNull(session.patternId)
        assertNull(session.movedFromSessionId)
        assertEquals(SessionKind.PRACTICAL, session.kind)
        assertEquals("Makeup viva", session.note)
        assertEquals(1, session.unitsAttended)
    }

    @Test
    fun `a class whose pattern was deleted stays a timetabled class`() {
        val snapshot = readOk(encoded(original)).snapshot
        val session = snapshot.session(BackupFixtures.labFromDeletedPattern)

        // The pattern is not in the file, so the id it gets cannot be one of the real ones —
        // but it must still be *an* id, or the class would come back as an extra class and the
        // generator would fill the slot it vacated all over again.
        assertEquals(SessionOrigin.GENERATED, session.origin)
        assertNotNull(session.patternId)
        assertFalse(snapshot.patterns.any { it.id == session.patternId })
        assertEquals(SessionStatus.HELD, session.status)
        assertEquals(2, session.unitsAttended)

        // And it is the only one: no real pattern was displaced to make room for it.
        assertEquals(original.patterns.size, snapshot.patterns.size)
    }

    @Test
    fun `a stray attendance bit beyond the planned hours is dropped rather than carried`() {
        // A mask bit left over from before a class was shortened. It is already invisible —
        // unitsAttended masks to unitsPlanned — so the export writes the hours that count and
        // the import comes back with exactly those.
        val stale = BackupFixtures.shortened.copy(unitsMask = UnitMask.of(0, 5))
        val snapshot = original.copy(
            sessions = original.sessions.map { if (it.id == stale.id) stale else it },
        )

        val session = readOk(encoded(snapshot)).snapshot.session(stale)

        assertEquals(UnitMask.of(0), session.unitsMask)
        assertEquals(stale.unitsAttended, session.unitsAttended)
        assertEquals(1, session.unitsAttended)
    }

    // ---- patterns -----------------------------------------------------------

    @Test
    fun `a retired pattern keeps its effective dates and its replacement keeps its open end`() {
        val snapshot = readOk(encoded(original)).snapshot
        val retired = snapshot.pattern(BackupFixtures.adecMondayOld)
        val replacement = snapshot.pattern(BackupFixtures.adecMondayNew)

        assertEquals(LocalDate.of(2026, 7, 28), retired.effectiveFrom)
        assertEquals(LocalDate.of(2026, 9, 30), retired.effectiveTo)
        assertFalse(retired.isOpenEnded)

        assertEquals(LocalDate.of(2026, 10, 1), replacement.effectiveFrom)
        assertNull(replacement.effectiveTo)
        assertTrue(replacement.isOpenEnded)

        // Two slots for one course on one weekday, distinguished only by their date ranges —
        // which is exactly what a mid-semester timetable revision leaves behind.
        assertEquals(retired.courseId, replacement.courseId)
        assertEquals(DayOfWeek.MONDAY, retired.dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, replacement.dayOfWeek)
        assertNotEquals(retired.startHour, replacement.startHour)
    }

    @Test
    fun `every pattern keeps its kind and its room`() {
        val snapshot = readOk(encoded(original)).snapshot

        assertEquals(SessionKind.PRACTICAL, snapshot.pattern(BackupFixtures.labWednesday).kind)
        assertEquals("Lab 3", snapshot.pattern(BackupFixtures.labWednesday).room)
        assertEquals(SessionKind.TUTORIAL, snapshot.pattern(BackupFixtures.evsTutorial).kind)
        assertNull(snapshot.pattern(BackupFixtures.evsTutorial).room)
    }

    @Test
    fun `each pattern still belongs to the course it belonged to`() {
        val snapshot = readOk(encoded(original)).snapshot

        assertEquals(
            snapshot.course(BackupFixtures.adec).id,
            snapshot.pattern(BackupFixtures.adecMondayOld).courseId,
        )
        assertEquals(
            snapshot.course(BackupFixtures.adecLab).id,
            snapshot.pattern(BackupFixtures.labWednesday).courseId,
        )
        assertEquals(
            snapshot.course(BackupFixtures.evs).id,
            snapshot.pattern(BackupFixtures.evsTutorial).courseId,
        )
    }

    // ---- courses ------------------------------------------------------------

    @Test
    fun `an archived course is still in the backup and still archived`() {
        val snapshot = readOk(encoded(original)).snapshot
        val archived = snapshot.course(BackupFixtures.evs)

        assertTrue(archived.archived)
        assertEquals(Percent.ofPercent(65.0), archived.targetPercent)
        assertEquals(3, snapshot.courses.size)
    }

    @Test
    fun `a course keeps its own target and its colour`() {
        val snapshot = readOk(encoded(original)).snapshot

        assertEquals(Percent.ofPercent(80.0), snapshot.course(BackupFixtures.adecLab).targetPercent)
        assertEquals(BackupFixtures.adec.colorArgb, snapshot.course(BackupFixtures.adec).colorArgb)
        assertEquals("ADEC (P)", snapshot.course(BackupFixtures.adecLab).code)
        assertEquals(BackupFixtures.adecLab.name, snapshot.course(BackupFixtures.adecLab).name)
    }

    // ---- settings and calendar ----------------------------------------------

    @Test
    fun `the calendar comes back with its holidays and its working Saturdays`() {
        val calendar = readOk(encoded(original)).snapshot.calendar

        assertEquals(LocalDate.of(2026, 7, 28), calendar.termStart)
        assertEquals(LocalDate.of(2026, 12, 15), calendar.termEnd)
        assertEquals(BackupFixtures.calendar.holidays, calendar.holidays)
        assertEquals(BackupFixtures.calendar.workingSaturdays, calendar.workingSaturdays)

        // Restored well enough to answer the question the calendar exists for.
        assertFalse(calendar.isTeachingDay(LocalDate.of(2026, 8, 7)))
        assertTrue(calendar.isTeachingDay(LocalDate.of(2026, 9, 12)))
        assertFalse(calendar.isTeachingDay(LocalDate.of(2026, 9, 19)))
    }

    @Test
    fun `the targets and the section come back`() {
        val preferences = readOk(encoded(original)).snapshot.preferences

        assertEquals(Percent.ofPercent(70.0), preferences.overallTarget)
        assertEquals(Percent.ofPercent(80.0), preferences.courseTarget)
        assertEquals("2nd Yr ECE-A1", preferences.section)
        assertEquals("A1", preferences.batch)
    }

    @Test
    fun `an install that never picked a section restores without one`() {
        val snapshot = original.copy(
            preferences = BackupPreferences(section = null, batch = null),
        )

        val preferences = readOk(encoded(snapshot)).snapshot.preferences

        assertNull(preferences.section)
        assertNull(preferences.batch)
        assertEquals(Percent.DEFAULT_TARGET, preferences.overallTarget)
    }

    // ---- whose data it is ---------------------------------------------------

    @Test
    fun `the display name comes back, so the new phone greets the same person`() {
        val preferences = readOk(encoded(original)).snapshot.preferences

        assertEquals("Divyansh", preferences.displayName)
    }

    @Test
    fun `an install with no name set restores without one`() {
        val anonymous = original.copy(
            preferences = original.preferences.copy(displayName = null),
        )

        val preferences = readOk(encoded(anonymous)).snapshot.preferences

        assertNull(preferences.displayName)
        // Everything else is untouched: the name is a label, and nothing hangs off it.
        assertEquals("2nd Yr ECE-A1", preferences.section)
        assertEquals(original.sessions.size, readOk(encoded(anonymous)).snapshot.sessions.size)
    }

    @Test
    fun `a blank or padded name is normalised on the way back in`() {
        // The app stores the name trimmed, and the restore verifies itself by reading the settings
        // back. A file with " Divyansh " in it has to land on the same string the app would store,
        // or that read-back would report a difference nobody can see.
        val padded = original.copy(
            preferences = original.preferences.copy(displayName = "  Divyansh  "),
        )
        val blank = original.copy(
            preferences = original.preferences.copy(displayName = "   "),
        )

        assertEquals("Divyansh", readOk(encoded(padded)).snapshot.preferences.displayName)
        assertNull(readOk(encoded(blank)).snapshot.preferences.displayName)
    }

    @Test
    fun `a file written before names existed still reads, at the same format version`() {
        // The field was added with a default rather than by bumping the format version, so an
        // export taken by an older build has to decode — not be refused as the wrong version.
        val older = repack(encoded(original)) { payload ->
            payload.withField(
                "preferences",
                payload.getValue("preferences").jsonObject.withoutField("displayName"),
            )
        }

        val backup = readOk(older)

        assertNull(backup.snapshot.preferences.displayName)
        assertEquals(BackupCodec.FORMAT_VERSION, backup.meta.formatVersion)
        assertEquals(original.sessions.size, backup.snapshot.sessions.size)
    }

    // ---- provenance ---------------------------------------------------------

    @Test
    fun `the file records the format version the app version and the export time`() {
        val meta = readOk(encoded(original)).meta

        assertEquals(BackupCodec.FORMAT_VERSION, meta.formatVersion)
        assertEquals(BackupCodec.FORMAT_VERSION, meta.originalFormatVersion)
        assertEquals(TEST_APP_VERSION_NAME, meta.appVersionName)
        assertEquals(TEST_APP_VERSION_CODE, meta.appVersionCode)
        assertEquals(TEST_EXPORTED_AT, meta.exportedAt)
    }

    @Test
    fun `the suggested file name says what the file is and when it was made`() {
        assertEquals(
            "attendo-backup-2026-12-20.json",
            BackupCodec.suggestedFileName(LocalDate.of(2026, 12, 20)),
        )
    }

    // ---- nothing at all -----------------------------------------------------

    @Test
    fun `an empty backup round trips`() {
        val empty = BackupSnapshot()

        val backup = readOk(encoded(empty))

        assertTrue(backup.snapshot.isEmpty)
        assertEquals(empty.normalised(), backup.snapshot)
        assertTrue(backup.summary.isEmpty)
        assertNull(backup.summary.overallPercent)
    }

    @Test
    fun `a first-day install with courses but no classes yet round trips`() {
        val fresh = BackupSnapshot(
            courses = listOf(BackupFixtures.adec, BackupFixtures.adecLab),
            patterns = listOf(BackupFixtures.adecMondayOld, BackupFixtures.labWednesday),
            calendar = BackupFixtures.calendar,
        )

        val restored = readOk(encoded(fresh)).snapshot

        assertEquals(fresh.normalised(), restored)
        assertTrue(restored.sessions.isEmpty())
        assertFalse(restored.isEmpty)
    }

    // ---- a whole semester ---------------------------------------------------

    @Test
    fun `a whole semester of history survives the round trip`() {
        val semester = BackupFixtures.fullSemester()
        assertTrue(
            "the fixture should be a real term's worth of classes",
            semester.sessions.size > 100,
        )

        val restored = readOk(encoded(semester)).snapshot

        assertEquals(semester.normalised(), restored)
        assertEquals(semester.sessions.size, restored.sessions.size)
        assertEquals(
            semester.sessions.minOfOrNull { it.date },
            restored.sessions.minOfOrNull { it.date },
        )
        assertEquals(
            semester.sessions.maxOfOrNull { it.date },
            restored.sessions.maxOfOrNull { it.date },
        )
    }

    @Test
    fun `the restored data computes the same attendance as the original`() {
        val semester = BackupFixtures.fullSemester()

        assertEquals(attendanceOf(semester), attendanceOf(readOk(encoded(semester)).snapshot))
        assertEquals(attendanceOf(original), attendanceOf(readOk(encoded(original)).snapshot))
    }

    @Test
    fun `the attendance in the fixture is not trivially equal for want of any classes`() {
        // Guards the test above: comparing two empty rollups would pass and prove nothing.
        val figures = attendanceOf(original)

        assertEquals(3, figures.size)
        assertTrue(figures.values.any { it.tally.unitsHeld > 0 })
        assertTrue(figures.values.any { it.partial > 0 })
        assertTrue(figures.values.any { it.cancelled > 0 })
        assertTrue(figures.values.any { it.awaitingReview > 0 })
    }

    // ---- helpers ------------------------------------------------------------

    private fun restored(session: ClassSession): ClassSession =
        readOk(encoded(original)).snapshot.session(session)

    private fun BackupSnapshot.session(like: ClassSession): ClassSession =
        sessions.singleOrNull { it.date == like.date && it.startHour == like.startHour }
            ?: throw AssertionError(
                "no single session on ${like.date} at ${like.startHour} in $sessions",
            )

    private fun BackupSnapshot.pattern(like: SessionPattern): SessionPattern =
        patterns.single {
            it.dayOfWeek == like.dayOfWeek &&
                it.startHour == like.startHour &&
                it.effectiveFrom == like.effectiveFrom
        }

    private fun BackupSnapshot.course(like: Course): Course =
        courses.single { it.code == like.code }

    /**
     * The attendance rollup keyed by course *code* rather than by row id.
     *
     * Ids are the one thing a restore is allowed to change, so an id-keyed comparison would
     * fail on a perfect restore. A code-keyed one asks the question that matters: does the new
     * install work out the same attendance, class by class, as the old one did?
     */
    private fun attendanceOf(snapshot: BackupSnapshot): Map<String, Figures> =
        AttendanceEngine
            .overallStats(snapshot.courses, snapshot.sessions, snapshot.preferences.overallTarget)
            .perCourse
            .associate { stats ->
                stats.course.code to Figures(
                    tally = stats.tally,
                    percent = stats.percent,
                    held = stats.sessionsHeld,
                    cancelled = stats.sessionsCancelled,
                    awaitingReview = stats.sessionsAwaitingReview,
                    partial = stats.sessionsPartial,
                    meetsTarget = stats.target.meetsTarget,
                )
            }

    private data class Figures(
        val tally: Tally,
        val percent: Percent?,
        val held: Int,
        val cancelled: Int,
        val awaitingReview: Int,
        val partial: Int,
        val meetsTarget: Boolean,
    )
}
