package com.attendo.core.engine

import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The calendar half of the "no future To mark" rule.
 *
 * [CourseHistoryFutureFilterTest] pinned the *audit list*: a future SCHEDULED row must not
 * survive into the scrollable history. This pins the other surface the screenshot showed the
 * bug on — the colour-coded month grid in Course Detail, whose cells come from
 * [AttendanceEngine.dayAttendance] via [CalendarBuilder.monthGrid].
 *
 * The grid is a *derived* structure: its per-day mark is decided before any list filter runs,
 * so a date filter on the history alone never touched it. A future teaching day whose sessions
 * are still SCHEDULED used to fall straight into the `AWAITING_REVIEW` band and paint the cell
 * amber — read by a student as "review this class now" for a class that has not happened.
 *
 * The fix draws the same line [ClassSession.isReviewableOn] does (`!date.isAfter(today)`) at the
 * day-attendance boundary, *only* for the band: when a `today` is supplied, a future
 * pure-SCHEDULED day is marked [DayMark.NO_CLASS] (the same neutral empty state a no-class future
 * day takes) rather than [DayMark.AWAITING_REVIEW]. That means no status background, no "Not
 * counted" label and no legend entry — it reads as a plain future date. The count, the tally, and
 * the held/cancelled math are untouched — a future HELD what-if still resolves through the held
 * branch and keeps its real colour, so the projections that ride on the tally are unchanged.
 * Default `null` keeps the unbounded grid the existing tests (and any caller that never supplied a
 * date) relied on.
 *
 * The scenario is the screenshot verbatim: today is Sunday 2026-08-23, and the EDC calendar
 * showed Monday 2026-08-24 and Thursday 2026-08-27 as amber "To review". Both must read as a
 * neutral future day instead.
 */
class CalendarFutureReviewTest {

    /** Sunday 23 Aug 2026 — the day the screenshot was taken. */
    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    private val saturday22: LocalDate = LocalDate.of(2026, 8, 22) // past — yesterday
    private val sunday23: LocalDate = LocalDate.of(2026, 8, 23)   // today
    private val monday24: LocalDate = LocalDate.of(2026, 8, 24)   // future
    private val thursday27: LocalDate = LocalDate.of(2026, 8, 27) // future

    private val course = Course(id = EDC_ID, name = "Electronic Devices and Circuits", code = "EDC")

    private val august: YearMonth = YearMonth.of(2026, 8)

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

    // ---- the screenshot: a future SCHEDULED day is not a review cell -------------

    @Test
    fun `a future scheduled day is not marked awaiting review when today is supplied`() {
        val day = AttendanceEngine.dayAttendance(monday24, listOf(session(monday24)), today = today)

        // The existing neutral calendar state — not the review band, not the out-of-window grey.
        assertEquals(DayMark.NO_CLASS, day.mark)
        assertNotEquals(DayMark.AWAITING_REVIEW, day.mark)
        assertNotEquals(DayMark.NOT_COUNTED, day.mark)
        // The class still exists on the cell — only its band changed.
        assertEquals(1, day.sessions.size)
        assertEquals(monday24, day.sessions.single().date)
    }

    @Test
    fun `the calendar grid never paints a future scheduled day amber`() {
        val sessions = listOf(
            session(saturday22),
            session(sunday23),
            session(monday24),
            session(thursday27),
        )

        val grid = CalendarBuilder.monthGrid(august, sessions, courseId = EDC_ID, today = today)

        // Past and today still read as review work — they have happened and are unmarked.
        assertEquals(DayMark.AWAITING_REVIEW, grid.cellFor(saturday22)!!.mark)
        assertEquals(DayMark.AWAITING_REVIEW, grid.cellFor(sunday23)!!.mark)
        // The two future dates the screenshot named must read as the neutral empty state —
        // not the review band and not the out-of-window "Not counted" grey.
        assertEquals(DayMark.NO_CLASS, grid.cellFor(monday24)!!.mark)
        assertEquals(DayMark.NO_CLASS, grid.cellFor(thursday27)!!.mark)
        assertNotEquals(DayMark.AWAITING_REVIEW, grid.cellFor(monday24)!!.mark)
        assertNotEquals(DayMark.NOT_COUNTED, grid.cellFor(monday24)!!.mark)
        assertNotEquals(DayMark.AWAITING_REVIEW, grid.cellFor(thursday27)!!.mark)
        assertNotEquals(DayMark.NOT_COUNTED, grid.cellFor(thursday27)!!.mark)
        // And the "Not counted" legend category is absent entirely — a future scheduled date
        // introduces no legend entry.
        assertEquals(0, grid.countOf(DayMark.NOT_COUNTED))
        // Stated as the invariant the rest of the app already holds: nothing the grid presents as
        // awaiting review is dated after today.
        val reviewDates = grid.days
            .filter { it.mark == DayMark.AWAITING_REVIEW }
            .mapNotNull { it.date }
        assertTrue(reviewDates.all { !it.isAfter(today) })
        assertFalse(monday24 in reviewDates)
        assertFalse(thursday27 in reviewDates)
    }

    @Test
    fun `the exact screenshot scenario - only Aug 22 and Aug 23 are review cells`() {
        val sessions = listOf(
            session(saturday22),
            session(sunday23),
            session(monday24),
            session(thursday27),
        )

        val grid = CalendarBuilder.monthGrid(august, sessions, courseId = EDC_ID, today = today)

        // Only the two past/today dates carry the review band; the future dates do not.
        assertEquals(setOf(saturday22, sunday23), grid.days
            .filter { it.mark == DayMark.AWAITING_REVIEW }
            .mapNotNull { it.date }
            .toSet())
        // The two future SCHEDULED dates are rendered as the neutral empty state (NO_CLASS),
        // never as "Not counted" — no out-of-window grey, no legend category.
        assertEquals(setOf(monday24, thursday27), grid.days
            .filter { it.mark == DayMark.NO_CLASS && it.day != null }
            .mapNotNull { it.date }
            .toSet())
        assertEquals(emptySet<LocalDate>(), grid.days
            .filter { it.mark == DayMark.NOT_COUNTED }
            .mapNotNull { it.date }
            .toSet())
        // (c) They are visually represented by the existing neutral calendar state — the same
        // mark a future date with no classes takes.
        assertEquals(DayMark.NO_CLASS, grid.cellFor(monday24)!!.mark)
        assertEquals(DayMark.NO_CLASS, grid.cellFor(thursday27)!!.mark)
        // (d) They do not affect attendance totals: nothing on them was HELD, so the month's
        // tally is empty — a future SCHEDULED day never moves the figure.
        assertEquals(Tally.EMPTY, grid.tally)
    }

    // ---- the boundary is today itself -------------------------------------------

    @Test
    fun `the boundary is today - today stays reviewable, tomorrow does not`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(session(sunday23), session(monday24)),
            courseId = EDC_ID,
            today = today,
        )

        assertEquals(DayMark.AWAITING_REVIEW, grid.cellFor(sunday23)!!.mark)
        assertEquals(DayMark.NO_CLASS, grid.cellFor(monday24)!!.mark)
        assertNotEquals(DayMark.NOT_COUNTED, grid.cellFor(monday24)!!.mark)
    }

    @Test
    fun `a past scheduled day stays amber and the cell count is unchanged`() {
        // The count that drives the pending dot is deliberately date-blind, so the fix must not
        // zero it for a past day. A past unmarked day is both amber *and* pending.
        val past = session(saturday22)
        val day = AttendanceEngine.dayAttendance(saturday22, listOf(past), today = today)

        assertEquals(DayMark.AWAITING_REVIEW, day.mark)
        assertEquals(1, day.unitsAwaitingReview)
        assertTrue(past.isReviewableOn(today))
    }

    @Test
    fun `a future scheduled day still reports its pending units count`() {
        // The band is redirected to the neutral empty state; the count is not. The dot's own
        // date gate (in MonthCalendar) keeps the dot off the future cell, but the number behind
        // it stays honest.
        val day = AttendanceEngine.dayAttendance(thursday27, listOf(session(thursday27, units = 2)), today = today)

        assertEquals(DayMark.NO_CLASS, day.mark)
        assertNotEquals(DayMark.AWAITING_REVIEW, day.mark)
        assertNotEquals(DayMark.NOT_COUNTED, day.mark)
        assertEquals(2, day.unitsAwaitingReview)
        assertFalse(session(thursday27).isReviewableOn(today))
    }

    // ---- what-if: a future HELD day keeps its real colour -----------------------

    @Test
    fun `a future held what-if day keeps its attendance colour and is not review work`() {
        val futureFull = session(thursday27, status = SessionStatus.HELD, attendedUnits = 2, units = 2)
        val day = AttendanceEngine.dayAttendance(thursday27, listOf(futureFull), today = today)

        assertEquals(DayMark.FULL, day.mark)
        assertEquals(Tally(2, 2), day.tally)
        assertEquals(0, day.unitsAwaitingReview)
        assertFalse(futureFull.isReviewableOn(today))
    }

    @Test
    fun `a future held what-if still participates in the figures`() {
        val heldPast = session(saturday22, status = SessionStatus.HELD, attendedUnits = 2, units = 2)
        val heldFuture = session(thursday27, status = SessionStatus.HELD, attendedUnits = 2, units = 2)
        val sessions = listOf(heldPast, heldFuture)

        val stats = AttendanceEngine.courseStats(course, sessions, today = today)

        assertEquals(2, stats.sessionsHeld)
        assertEquals(4, stats.tally.unitsHeld)
        assertEquals(4, stats.tally.unitsAttended)
        assertEquals(Percent.FULL, stats.percent)
        assertEquals(0, stats.sessionsAwaitingReview)
    }

    @Test
    fun `a future day with one held and one scheduled session colours by the held half`() {
        // The held branch decides the band before the SCHEDULED-only future redirect can — a
        // day that is half decided should not be demoted to "not counted".
        val day = AttendanceEngine.dayAttendance(
            thursday27,
            listOf(
                session(thursday27, startHour = 9, status = SessionStatus.HELD, attendedUnits = 1, units = 2),
                session(thursday27, startHour = 14, status = SessionStatus.SCHEDULED, units = 2),
            ),
            today = today,
        )

        assertEquals(DayMark.PARTIAL, day.mark)
        assertEquals(Tally(1, 2), day.tally)
        assertEquals(2, day.unitsAwaitingReview)
    }

    // ---- default null keeps the old unbounded grid ------------------------------

    @Test
    fun `without a today the grid keeps its old unbounded review band`() {
        // Nobody asked to bound the calendar, so a SCHEDULED day is amber whatever its date —
        // the contract every caller that never supplied a today relied on. Pinning it keeps the
        // optional-today change from accidentally bounding them.
        val day = AttendanceEngine.dayAttendance(thursday27, listOf(session(thursday27)))

        assertEquals(DayMark.AWAITING_REVIEW, day.mark)
        assertEquals(1, day.unitsAwaitingReview)

        val grid = CalendarBuilder.monthGrid(august, listOf(session(thursday27)), courseId = EDC_ID)
        assertEquals(DayMark.AWAITING_REVIEW, grid.cellFor(thursday27)!!.mark)
    }

    // ---- a future SCHEDULED day is indistinguishable from a no-class future day ---------
    //
    // The acceptance criterion is the *rendering path*, not the enum. Every input the calendar
    // composable reads off a cell — its [MonthCell.mark] (drives background + text colour via
    // [com.attendo.ui.theme.BandColors] / [com.attendo.ui.components.DayCell]), its presence in
    // the legend (via [MonthGrid.countOf], which [com.attendo.ui.components.BandLegend] filters
    // on), and the month tally (the "This month" line) — must come out identical for a future
    // date that *has* a scheduled class and one that has none. The two cases are the two cells
    // built below: `scheduled` carries a real SCHEDULED session, `empty` has nothing on it at
    // all. If anything the UI switches on could tell them apart, the bug is back.

    @Test
    fun `a future scheduled calendar day reaches the same rendering state as a neutral future day`() {
        val emptyDay = LocalDate.of(2026, 8, 28)   // future, no session on it at all
        val sessions = listOf(session(monday24), session(thursday27))

        val grid = CalendarBuilder.monthGrid(august, sessions, courseId = EDC_ID, today = today)

        val scheduled24 = grid.cellFor(monday24)!!
        val scheduled27 = grid.cellFor(thursday27)!!
        val empty = grid.cellFor(emptyDay)!!

        // The no-class future day is the neutral baseline: NO_CLASS, no sessions attached.
        assertEquals(DayMark.NO_CLASS, empty.mark)
        assertNull(empty.day)

        // The future scheduled days carry their sessions (so the what-if math still has them)...
        assertNotNull(scheduled24.day)
        assertEquals(1, scheduled24.day!!.sessions.size)
        assertNotNull(scheduled27.day)
        // ...but present the *same* mark the neutral cell does — same background, same text colour.
        assertEquals(empty.mark, scheduled24.mark)
        assertEquals(empty.mark, scheduled27.mark)
        assertEquals(DayMark.NO_CLASS, scheduled24.mark)
        assertEquals(DayMark.NO_CLASS, scheduled27.mark)

        // The legend is driven by [MonthGrid.countOf]: NO_CLASS is not a legend band, so neither
        // the scheduled future days nor the empty one contribute an entry, and the "Not counted"
        // entry the rejected fix added is absent.
        assertEquals(0, grid.countOf(DayMark.NOT_COUNTED))
        assertEquals(0, grid.countOf(DayMark.AWAITING_REVIEW))
        // (Counting the future scheduled days as NO_CLASS would *not* create a legend row even if
        //  the legend listed NO_CLASS — it does not — but the point is they add no new category.)

        // The month tally (the "This month" line) is driven only by HELD sessions, so the future
        // scheduled days affect it exactly as much as the empty day: not at all.
        assertEquals(Tally.EMPTY, grid.tally)

        // The pending dot is gated in the composable by `!date.isAfter(today)`, so a future cell
        // gets no dot whether or not it has a scheduled class behind it. The count stays honest
        // internally (the what-if uses it) but never reaches the future cell's paint.
        assertTrue(monday24.isAfter(today))
        assertTrue(thursday27.isAfter(today))
        assertFalse(session(monday24).isReviewableOn(today))
    }

    private companion object {
        const val EDC_ID = 42L
    }
}
