package com.attendo.core.engine

import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Which classes the review workflow may ask about.
 *
 * The rule, and this file is its specification: **past is reviewable, today is reviewable,
 * the future never is.** Nothing else about a session decides it — not how it was created,
 * not whether it was once marked and unmarked again.
 *
 * The reason the future case needs a test at all is that the app deliberately lets a student
 * mark a class that has not happened yet. It is how a what-if is asked — "where do I land if
 * I skip Thursday's lab?" — and taking the mark off again leaves a SCHEDULED row on a future
 * date, indistinguishable by status from a lecture last week that was never marked. Only the
 * date separates them, so the date is what these tests pin down.
 *
 * The what-if arithmetic itself is not being changed by any of this, and the last two tests
 * are here to say so: a future session marked present still counts exactly as it did.
 */
class FutureReviewTest {

    /** A Monday, and the day every test treats as "now". */
    private val today: LocalDate = LocalDate.of(2026, 8, 3)

    private val lastWeek: LocalDate = today.minusDays(7)
    private val thursday: LocalDate = today.plusDays(3)

    private val course = Course(id = COURSE_ID, name = "Power System Analysis", code = "PSA")

    private val stamp: Instant = Instant.parse("2026-08-03T10:15:30Z")

    private fun session(
        date: LocalDate,
        startHour: Int = 9,
        units: Int = 2,
        status: SessionStatus = SessionStatus.SCHEDULED,
        attendedUnits: Int = 0,
    ) = ClassSession(
        id = 0L,
        courseId = COURSE_ID,
        patternId = 1L,
        date = date,
        startHour = startHour,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(attendedUnits),
        status = status,
    )

    // ---- the rule, session by session ---------------------------------------

    @Test
    fun `a past unreviewed session is reviewable`() {
        val past = session(lastWeek)

        assertTrue(past.isReviewableOn(today))
        assertEquals(listOf(past), AttendanceEngine.sessionsAwaitingReview(listOf(past), today))
    }

    @Test
    fun `todays unreviewed session is reviewable`() {
        val now = session(today)

        assertTrue(now.isReviewableOn(today))
        assertEquals(listOf(now), AttendanceEngine.sessionsAwaitingReview(listOf(now), today))
    }

    @Test
    fun `a future unreviewed session is never reviewable`() {
        val future = session(thursday)

        assertFalse(future.isReviewableOn(today))
        assertEquals(emptyList<ClassSession>(), AttendanceEngine.sessionsAwaitingReview(listOf(future), today))
    }

    @Test
    fun `tomorrow is future and yesterday is not — the boundary is today itself`() {
        assertTrue(session(today.minusDays(1)).isReviewableOn(today))
        assertTrue(session(today).isReviewableOn(today))
        assertFalse(session(today.plusDays(1)).isReviewableOn(today))
    }

    @Test
    fun `a reviewed session is not review work whatever its date`() {
        assertFalse(session(lastWeek, status = SessionStatus.HELD).isReviewableOn(today))
        assertFalse(session(lastWeek, status = SessionStatus.CANCELLED).isReviewableOn(today))
        assertFalse(session(thursday, status = SessionStatus.HELD).isReviewableOn(today))
    }

    // ---- the queue the student is shown -------------------------------------

    @Test
    fun `the review queue holds past and today, oldest first, and nothing later`() {
        val sessions = listOf(
            session(thursday),
            session(today, startHour = 14),
            session(today, startHour = 9),
            session(lastWeek),
            session(today.plusDays(30)),
        )

        val queue = AttendanceEngine.sessionsAwaitingReview(sessions, today)

        assertEquals(
            listOf(lastWeek to 9, today to 9, today to 14),
            queue.map { it.date to it.startHour },
        )
    }

    @Test
    fun `the backlog of missed days excludes today and every future date`() {
        val sessions = listOf(
            session(lastWeek),
            session(today),
            session(thursday),
        )

        // Today is reviewable but is not *backlog*: the day has its own card and its own
        // one-tap approve on the screen this list appears under.
        assertEquals(listOf(lastWeek), AttendanceEngine.daysAwaitingReview(sessions, today))
    }

    @Test
    fun `a future session outside the counting window stays out of the queue too`() {
        val joinedOn = lastWeek.plusDays(2)
        val sessions = listOf(session(lastWeek), session(today), session(thursday))

        val queue = AttendanceEngine.sessionsAwaitingReview(
            sessions = sessions,
            today = today,
            window = AttendanceWindow.since(joinedOn),
        )

        assertEquals(listOf(today), queue.map { it.date })
    }

    // ---- the counter under each course --------------------------------------

    @Test
    fun `the unmarked count a student is shown ignores future sessions`() {
        val sessions = listOf(session(lastWeek), session(today), session(thursday))

        val shown = AttendanceEngine.courseStats(course, sessions, today = today)

        assertEquals(2, shown.sessionsAwaitingReview)
    }

    @Test
    fun `without a date the unmarked count keeps its old unbounded meaning`() {
        val sessions = listOf(session(lastWeek), session(today), session(thursday))

        assertEquals(3, AttendanceEngine.courseStats(course, sessions).sessionsAwaitingReview)
    }

    @Test
    fun `a future day reports pending units but is not itself review work`() {
        // The calendar cell exposes DayAttendance.unitsAwaitingReview — a count of SCHEDULED
        // units on the day with no date guard. A future teaching day genuinely has classes to
        // mark, just not yet, so the count is right; what would be wrong is treating a positive
        // count as "review this day now". The review queue is bounded by date alone
        // (isReviewableOn), so the calendar's pending dot must gate on the date, never on the
        // count. This pins both halves so neither can be "fixed" by silently changing the
        // other: the count stays unbounded, the queue stays date-bounded, and a future day is
        // pending-but-not-reviewable however many unreviewed units it holds.
        val future = session(thursday)
        val day = AttendanceEngine.dayAttendance(thursday, listOf(future))

        assertEquals(2, day.unitsAwaitingReview)
        assertEquals(DayMark.AWAITING_REVIEW, day.mark)
        // ...and yet none of the surfaces that ask a student to review anything sees the day.
        assertFalse(future.isReviewableOn(today))
        assertEquals(
            emptyList<ClassSession>(),
            AttendanceEngine.sessionsAwaitingReview(listOf(future), today),
        )
        assertEquals(emptyList<LocalDate>(), AttendanceEngine.daysAwaitingReview(listOf(future), today))
    }

    @Test
    fun `the overall rollup bounds each courses unmarked count as well`() {
        val sessions = listOf(session(lastWeek), session(thursday), session(today.plusDays(9)))

        val overall = AttendanceEngine.overallStats(
            courses = listOf(course),
            sessions = sessions,
            today = today,
        )

        assertEquals(1, overall.perCourse.single().sessionsAwaitingReview)
    }

    // ---- the case that prompted the rule ------------------------------------

    @Test
    fun `marking a future session and then unmarking it creates no review work`() {
        val future = session(thursday)

        // What a student does to try a what-if: mark Thursday present, look at the number,
        // then take the mark off again. `reopen` is the same call the review screen makes.
        val marked = SessionOps.approve(SessionOps.setFullyPresent(future, stamp), stamp)
        val unmarked = SessionOps.reopen(marked, stamp)

        // Back to SCHEDULED — the status is untouched by this rule, and must be, because the
        // row genuinely has not been decided.
        assertEquals(SessionStatus.SCHEDULED, unmarked.status)
        assertTrue(unmarked.isAwaitingReview)

        // ...and yet none of the three things that ask a student to review anything sees it.
        assertFalse(unmarked.isReviewableOn(today))
        assertEquals(
            emptyList<ClassSession>(),
            AttendanceEngine.sessionsAwaitingReview(listOf(unmarked), today),
        )
        assertEquals(emptyList<LocalDate>(), AttendanceEngine.daysAwaitingReview(listOf(unmarked), today))
        assertEquals(
            0,
            AttendanceEngine.courseStats(course, listOf(unmarked), today = today).sessionsAwaitingReview,
        )
    }

    @Test
    fun `unmarking a past session does put it back into review`() {
        // The mirror of the test above, and the reason the bound has to be a date rather than
        // a flag: the same two calls on a class that has happened must produce review work.
        val past = session(lastWeek)
        val marked = SessionOps.approve(SessionOps.setFullyPresent(past, stamp), stamp)
        val unmarked = SessionOps.reopen(marked, stamp)

        assertTrue(unmarked.isReviewableOn(today))
        assertEquals(listOf(lastWeek), AttendanceEngine.daysAwaitingReview(listOf(unmarked), today))
        assertEquals(
            1,
            AttendanceEngine.courseStats(course, listOf(unmarked), today = today).sessionsAwaitingReview,
        )
    }

    // ---- what-if planning, unchanged ---------------------------------------

    @Test
    fun `a future session marked present still counts toward the percentage`() {
        // The whole point of being allowed to mark ahead. Two hours held last week, one of
        // them attended, plus two future hours marked present: 3 of 4.
        val sessions = listOf(
            session(lastWeek, status = SessionStatus.HELD, attendedUnits = 1),
            session(thursday, status = SessionStatus.HELD, attendedUnits = 2),
        )

        val stats = AttendanceEngine.courseStats(course, sessions, today = today)

        assertEquals(2, stats.sessionsHeld)
        assertEquals(4, stats.tally.unitsHeld)
        assertEquals(3, stats.tally.unitsAttended)
        assertEquals(Percent.ofPercent(75.0), stats.percent)
        // Bounding the review count did not bound the arithmetic.
        assertEquals(0, stats.sessionsAwaitingReview)
    }

    @Test
    fun `a future session marked absent still drags the percentage down`() {
        val sessions = listOf(
            session(lastWeek, status = SessionStatus.HELD, attendedUnits = 2),
            session(thursday, status = SessionStatus.HELD, attendedUnits = 0),
        )

        val stats = AttendanceEngine.courseStats(course, sessions, today = today)

        assertEquals(Percent.ofPercent(50.0), stats.percent)
        assertFalse(stats.target.meetsTarget)
    }

    @Test
    fun `an unmarked future session is invisible to the percentage, as SCHEDULED always was`() {
        val sessions = listOf(
            session(lastWeek, status = SessionStatus.HELD, attendedUnits = 2),
            session(thursday),
        )

        val stats = AttendanceEngine.courseStats(course, sessions, today = today)

        assertEquals(2, stats.tally.unitsHeld)
        assertEquals(Percent.FULL, stats.percent)
    }

    private companion object {
        const val COURSE_ID = 7L
    }
}
