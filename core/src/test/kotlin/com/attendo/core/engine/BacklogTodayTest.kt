package com.attendo.core.engine

import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * When today's classes become backlog.
 *
 * The backlog is catch-up work — "classes you have not marked". A class from a past date
 * is backlog the moment it exists. A class scheduled *today* is different: at ten past
 * nine the nine o'clock lecture is happening or about to, and "I missed all of them" has
 * no honest answer for it yet. The line this file pins down is the scheduled end time —
 * the moment a class's last hour ends, it becomes backlog material:
 *
 * - past dates: eligible, always;
 * - today: eligible only after the scheduled end time (start hour + planned units);
 * - in-progress and still-to-come classes today: not eligible;
 * - future dates: never eligible.
 *
 * The same predicate, [ClassSession.isBacklogEligibleOn], drives the backlog *date range*
 * shown on the dashboard and the session set the bulk actions ("I missed all of them",
 * bulk cancel) touch — one rule, so the nudge and the sweep can never disagree.
 *
 * Deliberately unchanged by all this: today is *reviewable* the moment the app is opened
 * ([ClassSession.isReviewableOn] and FutureReviewTest) — a student marking the morning's
 * lecture at lunchtime is the ordinary case. Only the backlog waits for the clock.
 */
class BacklogTodayTest {

    /** A Monday, treated as today. */
    private val today: LocalDate = LocalDate.of(2026, 9, 1)

    private val yesterday: LocalDate = today.minusDays(1)
    private val tomorrow: LocalDate = today.plusDays(1)

    private val course = Course(id = 1L, name = "Power System Analysis", code = "PSA")

    private fun session(
        date: LocalDate,
        startHour: Int = 9,
        units: Int = 1,
        status: SessionStatus = SessionStatus.SCHEDULED,
    ) = ClassSession(
        id = 0L,
        courseId = course.id,
        patternId = 1L,
        date = date,
        startHour = startHour,
        unitsPlanned = units,
        status = status,
    )

    // ---- the eligibility predicate ------------------------------------------

    @Test
    fun `a past class is backlog material whatever the hour`() {
        // Yesterday finished being today long ago; the clock of the current day no
        // longer has an opinion about it.
        assertTrue(session(yesterday, startHour = 9, units = 1).isBacklogEligibleOn(today.atTime(9, 0)))
        assertTrue(session(yesterday, startHour = 17, units = 1).isBacklogEligibleOn(today.atTime(23, 0)))
    }

    @Test
    fun `a class today becomes eligible the hour it ends`() {
        // 9–11: at ten it is in progress; at eleven — the hour its last slot ends —
        // it is finished and therefore backlog.
        val lecture = session(today, startHour = 9, units = 2)

        assertFalse(lecture.isBacklogEligibleOn(today.atTime(10, 0)))
        assertTrue(lecture.isBacklogEligibleOn(today.atTime(11, 0)))
        assertTrue(lecture.isBacklogEligibleOn(today.atTime(11, 30)))
    }

    @Test
    fun `a class later today is not backlog yet`() {
        // A 2 PM class asked about at noon: still to come, so not eligible — the
        // boundary is the class's own end time, not the day's.
        val afternoon = session(today, startHour = 14, units = 1)

        assertFalse(afternoon.isBacklogEligibleOn(today.atTime(12, 0)))
        assertTrue(afternoon.isBacklogEligibleOn(today.atTime(15, 0)))
    }

    @Test
    fun `a class that has not started today is not eligible at any earlier hour`() {
        val firstSlot = session(today, startHour = 9, units = 1)

        // The teaching day starts at nine, so at nine sharp nothing has finished yet.
        assertFalse(firstSlot.isBacklogEligibleOn(today.atTime(9, 0)))
    }

    @Test
    fun `a future date is never eligible however late the hour`() {
        assertFalse(session(tomorrow, startHour = 9, units = 1).isBacklogEligibleOn(today.atTime(23, 59)))
    }

    @Test
    fun `an already-decided class is never backlog whatever its date`() {
        // The bulk actions leave Present, Missed and Cancelled alone; eligibility is
        // for the unmarked alone. (This is the "I missed all of them" contract.)
        val past = session(yesterday, startHour = 9, units = 1)
        assertFalse(past.copy(status = SessionStatus.HELD).isBacklogEligibleOn(today.atTime(12, 0)))
        assertFalse(past.copy(status = SessionStatus.CANCELLED).isBacklogEligibleOn(today.atTime(12, 0)))
        assertFalse(session(today, startHour = 9, units = 1, status = SessionStatus.HELD)
            .isBacklogEligibleOn(today.atTime(12, 0)))
    }

    // ---- the backlog date range ---------------------------------------------

    @Test
    fun `today joins the backlog range once its first class has finished`() {
        val sessions = listOf(
            session(yesterday),                     // past, eligible
            session(today, startHour = 9, units = 1), // finished by 10
            session(today, startHour = 14, units = 1), // still to come at 10:30
        )

        // Half past ten: yesterday plus the finished morning class. The afternoon
        // class keeps today off the list at 9:30 — one eligible class is enough at 10:30.
        assertEquals(listOf(yesterday), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(9, 30)))
        assertEquals(listOf(yesterday, today), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(10, 30)))
    }

    @Test
    fun `today appears once any of its classes has finished, even mid-day`() {
        val sessions = listOf(session(today, startHour = 9, units = 2))

        assertEquals(listOf(today), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(11, 0)))
        assertEquals(emptyList<LocalDate>(), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(10, 0)))
    }

    @Test
    fun `a fully unfinished today stays off the range while the day runs`() {
        val sessions = listOf(
            session(today, startHour = 9, units = 1),
            session(today, startHour = 10, units = 1),
        )

        assertEquals(emptyList<LocalDate>(), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(9, 59)))
        assertEquals(listOf(today), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(10, 0)))
    }

    @Test
    fun `the window still bounds the backlog with today in play`() {
        // A student who joined today does not owe yesterday's classes, finished or not.
        val sessions = listOf(session(yesterday), session(today, startHour = 9, units = 1))
        val window = AttendanceWindow.since(today)

        assertEquals(listOf(today), AttendanceEngine.daysAwaitingReview(sessions, today.atTime(10, 0), window))
    }

    @Test
    fun `the review queue itself still holds today's classes from the start of the day`() {
        // The day's own review — the queue Day Review walks — is not made to wait for
        // the clock; only the backlog is. Marking the morning's lecture at ten past
        // nine stays possible.
        val morning = session(today, startHour = 9, units = 1)

        assertTrue(morning.isReviewableOn(today))
        assertEquals(
            listOf(today to 9),
            AttendanceEngine.sessionsAwaitingReview(listOf(morning), today).map { it.date to it.startHour },
        )
    }
}
