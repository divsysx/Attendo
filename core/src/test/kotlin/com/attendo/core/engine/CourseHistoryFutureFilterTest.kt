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
 * The per-course detail screen must not show a future class as "To mark".
 *
 * The screen has two halves: the *figures* (count, percentage, projections) and the *audit list*
 * of every logged session. The figures were already bounded — [AttendanceEngine.courseStats]
 * and [AttendanceEngine.sessionsAwaitingReview] both gate on [ClassSession.isReviewableOn], so a
 * future SCHEDULED row never inflated the "N unmarked" count. The audit list was not: it was built
 * by [CourseHistory.monthsFor] with no date, so a future SCHEDULED row landed in the list and was
 * rendered "To mark" — exactly the screenshot this test reproduces.
 *
 * The fix reuses the date half of [ClassSession.isReviewableOn] (`!date.isAfter(today)`) inside
 * [CourseHistory.rows]: when a `today` is supplied, future sessions are dropped from the list
 * alone. The status, the database row, and every figure computed from the full session set are
 * untouched — a future class a what-if turned HELD still counts exactly as it did.
 *
 * The scenario is the screenshot verbatim: today is Sunday 2026-08-23, and the EDC detail screen
 * showed Monday 2026-08-24 and Thursday 2026-08-27 as "To mark". Both must be gone.
 */
class CourseHistoryFutureFilterTest {

    /** Sunday 23 Aug 2026 — the day the screenshot was taken. */
    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    /** Monday 24 Aug 2026 — a future session the screen wrongly showed as "To mark". */
    private val monday24: LocalDate = LocalDate.of(2026, 8, 24)

    /** Thursday 27 Aug 2026 — the other future session the screen wrongly showed. */
    private val thursday27: LocalDate = LocalDate.of(2026, 8, 27)

    /** A past date the term is open on, used for sessions that legitimately belong to the list. */
    private val lastWeek: LocalDate = today.minusDays(7) // Sunday 16 Aug

    private val course = Course(id = EDC_ID, name = "Electronic Devices and Circuits", code = "EDC")

    private val stamp: Instant = Instant.parse("2026-08-23T08:00:00Z")

    private fun session(
        date: LocalDate,
        startHour: Int = 9,
        units: Int = 1,
        status: SessionStatus = SessionStatus.SCHEDULED,
        attendedUnits: Int = 0,
        courseId: Long = EDC_ID,
    ) = ClassSession(
        id = 0L,
        courseId = courseId,
        patternId = 1L,
        date = date,
        startHour = startHour,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(attendedUnits),
        status = status,
    )

    /** The dates a student sees when they scroll the audit list — newest first, flattened. */
    private fun historyDates(sessions: List<ClassSession>): List<LocalDate> =
        CourseHistory.monthsFor(sessions, EDC_ID, today = today)
            .flatMap { it.rows }
            .map { it.session.date }

    // ---- the screenshot: future sessions are gone from the list ----------------

    @Test
    fun `future sessions do not appear in the course history`() {
        val sessions = listOf(
            session(lastWeek),                       // past — reviewable, belongs
            session(today),                          // today — reviewable, belongs
            session(monday24),                       // future — must not show as "To mark"
            session(thursday27),                    // future — must not show as "To mark"
        )

        val shown = historyDates(sessions)

        assertEquals(listOf(today, lastWeek), shown) // newest first
        assertFalse(monday24 in shown)
        assertFalse(thursday27 in shown)
    }

    @Test
    fun `a past unreviewed session stays in the history and is reviewable`() {
        val past = session(lastWeek)

        val shown = historyDates(listOf(past))

        assertEquals(listOf(lastWeek), shown)
        assertTrue(past.isReviewableOn(today))
    }

    @Test
    fun `todays unreviewed session stays in the history and is reviewable`() {
        val todays = session(today)

        val shown = historyDates(listOf(todays))

        assertEquals(listOf(today), shown)
        assertTrue(todays.isReviewableOn(today))
    }

    @Test
    fun `the boundary is today itself - yesterday stays, tomorrow does not`() {
        val sessions = listOf(
            session(today.minusDays(1)), // Saturday 22 Aug — kept
            session(today.plusDays(1)),   // Monday 24 Aug — dropped
        )

        val shown = historyDates(sessions)

        assertEquals(listOf(today.minusDays(1)), shown)
    }

    @Test
    fun `the exact screenshot scenario - only Aug 22 and Aug 23 survive, Aug 24 and Aug 27 never`() {
        // The four dates the bug report names, verbatim: today is Sunday 23 Aug 2026, EDC has
        // a class every one of these days, all still SCHEDULED. The audit list must keep the
        // two that have happened (22nd, 23rd) and drop the two that have not (24th, 27th).
        val saturday22: LocalDate = LocalDate.of(2026, 8, 22) // past — yesterday
        val sunday23: LocalDate = LocalDate.of(2026, 8, 23)   // today
        val monday24: LocalDate = LocalDate.of(2026, 8, 24)   // future
        val thursday27: LocalDate = LocalDate.of(2026, 8, 27) // future

        val sessions = listOf(
            session(saturday22),
            session(sunday23),
            session(monday24),
            session(thursday27),
        )

        val shown = historyDates(sessions)

        // Newest first: today, then yesterday. The two future dates are absent.
        assertEquals(listOf(sunday23, saturday22), shown)
        assertFalse(monday24 in shown)
        assertFalse(thursday27 in shown)

        // The same invariant expressed directly: every row the screen would show is reviewable
        // on today, and none of the dropped dates is.
        assertTrue(sessions.filter { it.date in shown }.all { it.isReviewableOn(sunday23) })
        assertTrue(sessions.filter { it.date !in shown }.none { it.isReviewableOn(sunday23) })
    }

    @Test
    fun `without a today the history keeps its old unbounded behaviour`() {
        // The default-null path is the contract every other caller and test relied on; a future
        // session stays in the list because nobody asked to bound it. Pinning this keeps the
        // change from accidentally bounding callers that never supplied a date.
        val sessions = listOf(session(lastWeek), session(thursday27))

        val unbounded = CourseHistory.monthsFor(sessions, EDC_ID)
            .flatMap { it.rows }
            .map { it.session.date }

        assertEquals(listOf(thursday27, lastWeek), unbounded) // newest first, both present
    }

    // ---- the figures the list never touched ----------------------------------

    @Test
    fun `a future session does not contribute to the unmarked count`() {
        // The count card above the list was already correct; this pins that the history fix did
        // not regress it. Two real reviewable sessions, two future ones that must not count.
        val sessions = listOf(
            session(lastWeek),
            session(today),
            session(monday24),
            session(thursday27),
        )

        val stats = AttendanceEngine.courseStats(course, sessions, today = today)

        assertEquals(2, stats.sessionsAwaitingReview)
        assertEquals(
            listOf(lastWeek, today),
            AttendanceEngine.sessionsAwaitingReview(sessions, today).map { it.date },
        )
    }

    @Test
    fun `a future session marked present still counts toward the percentage and projections`() {
        // The what-if: a student marks Thursday 27 ahead to see where the number lands. Its
        // attendance state must still participate in the tally and the projections — the history
        // filter narrows the *list*, not the *arithmetic*. A future HELD row stays in the
        // figures even though it never appears in the audit list.
        val heldPast = session(lastWeek, status = SessionStatus.HELD, attendedUnits = 2, units = 2)
        val heldFuture = session(thursday27, status = SessionStatus.HELD, attendedUnits = 2, units = 2)
        val sessions = listOf(heldPast, heldFuture)

        val stats = AttendanceEngine.courseStats(course, sessions, today = today)

        // 4 held, 4 attended — the future HELD row is in the tally, exactly as before.
        assertEquals(2, stats.sessionsHeld)
        assertEquals(4, stats.tally.unitsHeld)
        assertEquals(4, stats.tally.unitsAttended)
        assertEquals(Percent.FULL, stats.percent)
        assertEquals(0, stats.sessionsAwaitingReview) // future HELD is not review work

        // The projections ride on the same tally, so they see the future row too. 6 of 6 if
        // the rest is attended, 4 of 6 if it is all missed — both pinned as exact ratios, since
        // [Percent] is held in basis points and 4/6 rounds to 66.67%, not a whole 67%.
        val up = 2
        assertEquals(
            Percent.FULL,
            AttendanceEngine.projectIfAllAttended(stats.tally, up),
        )
        assertEquals(
            Percent.ofRatio(4, 6),
            AttendanceEngine.projectIfAllMissed(stats.tally, up),
        )

        // ...and yet the audit list drops it, because its date is still in the future.
        assertEquals(listOf(lastWeek), historyDates(sessions))
    }

    @Test
    fun `marking a future session and unmarking it keeps it out of the history and out of the count`() {
        // The round-trip a what-if does: mark the future class, look at the number, take the mark
        // off again. The row returns to SCHEDULED on a future date — indistinguishable by status
        // from a real unmarked class — and the history list must still hide it.
        val future = session(monday24)
        val marked = SessionOps.approve(SessionOps.setFullyPresent(future, stamp), stamp)
        val unmarked = SessionOps.reopen(marked, stamp)

        assertEquals(SessionStatus.SCHEDULED, unmarked.status)
        assertTrue(unmarked.isAwaitingReview) // status untouched, as it must be

        // The date is what hides it from both surfaces.
        assertFalse(unmarked.isReviewableOn(today))
        assertEquals(emptyList<ClassSession>(), AttendanceEngine.sessionsAwaitingReview(listOf(unmarked), today))
        assertEquals(
            0,
            AttendanceEngine.courseStats(course, listOf(unmarked), today = today).sessionsAwaitingReview,
        )
        assertEquals(emptyList<LocalDate>(), historyDates(listOf(unmarked)))
    }

    // ---- what-if across the real counting window, end to end -------------------

    @Test
    fun `the audit list is bounded but the window still counts a late future what-if`() {
        // A joined-late student: their window opens partway through. A future class they try a
        // what-if on inside the window still tallies (the window includes it), while the history
        // list still hides it (its date is after today). Both bounds are independent and both hold.
        val joinedOn = lastWeek.plusDays(2) // Tuesday 18 Aug
        val sessions = listOf(
            session(joinedOn, status = SessionStatus.HELD, attendedUnits = 2, units = 2),
            session(monday24, status = SessionStatus.HELD, attendedUnits = 1, units = 2), // future what-if
        )

        val stats = AttendanceEngine.courseStats(
            course,
            sessions,
            window = AttendanceWindow.since(joinedOn),
            today = today,
        )

        // 4 held, 3 attended — the future HELD row inside the window is in the tally.
        assertEquals(4, stats.tally.unitsHeld)
        assertEquals(3, stats.tally.unitsAttended)
        assertEquals(Percent.ofPercent(75.0), stats.percent)
        assertEquals(0, stats.sessionsAwaitingReview)

        // ...and the audit list still shows only the past row.
        assertEquals(listOf(joinedOn), historyDates(sessions))
    }

    private companion object {
        const val EDC_ID = 42L
    }
}
