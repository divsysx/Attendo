package com.attendo.core.engine

import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The colour-coded attendance calendar: green for a fully attended day, red for a
 * fully missed one, amber for the split-class case, blank where nothing was
 * scheduled.
 */
class CalendarBuilderTest {

    private fun session(
        courseId: Long = ADEC_ID,
        date: LocalDate,
        startHour: Int = 9,
        units: Int = 1,
        attended: Int = units,
        status: SessionStatus = SessionStatus.HELD,
        cancellationReason: CancellationReason? = null,
    ) = ClassSession(
        courseId = courseId,
        patternId = 1L,
        date = date,
        startHour = startHour,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(attended),
        status = status,
        cancellationReason = cancellationReason,
    )

    private val august: YearMonth = YearMonth.of(2026, 8)

    // August 2026 starts on a Saturday, so a Monday-first grid pads five cells.
    private val aug1: LocalDate = LocalDate.of(2026, 8, 1)
    private val aug3: LocalDate = LocalDate.of(2026, 8, 3)

    @Test
    fun `the grid is whole weeks with the first of the month under its own weekday`() {
        val grid = CalendarBuilder.monthGrid(august, emptyList())

        assertEquals(DayOfWeek.SATURDAY, aug1.dayOfWeek)
        assertTrue(grid.weeks.all { it.size == 7 })
        assertEquals(5, grid.weeks.first().count { it.isPadding })
        assertEquals(aug1, grid.weeks.first()[5].date)
        assertEquals(31, grid.days.size)
        assertEquals(august, grid.month)
    }

    @Test
    fun `weekday headers follow the chosen week start`() {
        val mondayFirst = CalendarBuilder.monthGrid(august, emptyList())
        val sundayFirst = CalendarBuilder.monthGrid(august, emptyList(), weekStart = DayOfWeek.SUNDAY)

        assertEquals(DayOfWeek.MONDAY, mondayFirst.weekdayOrder.first())
        assertEquals(DayOfWeek.SUNDAY, mondayFirst.weekdayOrder.last())
        assertEquals(DayOfWeek.SUNDAY, sundayFirst.weekdayOrder.first())
        assertEquals(DayOfWeek.SATURDAY, sundayFirst.weekdayOrder.last())
        // Saturday the 1st sits in the last column of a Monday-first grid and the
        // first column of a Sunday-first one.
        assertEquals(6, sundayFirst.weeks.first().count { it.isPadding })
        assertEquals(aug1, sundayFirst.weeks.first()[6].date)
    }

    @Test
    fun `a fully attended day is green and a fully missed one is red`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(date = aug3, units = 2, attended = 2),
                session(date = aug3.plusDays(1), units = 2, attended = 0),
            ),
        )

        assertEquals(DayMark.FULL, grid.cellFor(aug3)!!.mark)
        assertEquals(DayMark.ABSENT, grid.cellFor(aug3.plusDays(1))!!.mark)
    }

    @Test
    fun `a half attended two hour block colours the day amber`() {
        // The case the whole fractional model exists for: neither present nor absent.
        val grid = CalendarBuilder.monthGrid(august, listOf(session(date = aug3, units = 2, attended = 1)))

        val cell = grid.cellFor(aug3)!!
        assertEquals(DayMark.PARTIAL, cell.mark)
        assertEquals(Percent.ofPercent(50.0), cell.day!!.percent)
    }

    @Test
    fun `a day is amber when one of two classes was missed entirely`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(date = aug3, startHour = 9, units = 2, attended = 2),
                session(date = aug3, startHour = 14, units = 1, attended = 0),
            ),
        )

        val cell = grid.cellFor(aug3)!!
        assertEquals(DayMark.PARTIAL, cell.mark)
        assertEquals(Tally(2, 3), cell.day!!.tally)
    }

    @Test
    fun `a day with no classes is blank rather than absent`() {
        val grid = CalendarBuilder.monthGrid(august, listOf(session(date = aug3)))

        val sunday = grid.cellFor(LocalDate.of(2026, 8, 2))!!
        assertEquals(DayMark.NO_CLASS, sunday.mark)
        assertNull(sunday.day)
        assertFalse(sunday.isPadding)
        assertEquals(2, sunday.dayOfMonth)
    }

    @Test
    fun `cancelled and unreviewed days get their own bands`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(
                    date = aug3,
                    units = 2,
                    attended = 0,
                    status = SessionStatus.CANCELLED,
                    cancellationReason = CancellationReason.HOLIDAY,
                ),
                session(date = aug3.plusDays(1), units = 2, status = SessionStatus.SCHEDULED),
            ),
        )

        assertEquals(DayMark.ALL_CANCELLED, grid.cellFor(aug3)!!.mark)
        assertEquals(DayMark.AWAITING_REVIEW, grid.cellFor(aug3.plusDays(1))!!.mark)
    }

    @Test
    fun `padding cells report no class so the UI has one value to switch on`() {
        val grid = CalendarBuilder.monthGrid(august, listOf(session(date = aug3)))

        val pad = grid.weeks.first().first()
        assertTrue(pad.isPadding)
        assertEquals(DayMark.NO_CLASS, pad.mark)
        assertNull(pad.dayOfMonth)
    }

    @Test
    fun `the legend counts only real days`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(date = aug3, units = 2, attended = 2),
                session(date = aug3.plusDays(1), units = 2, attended = 1),
                session(date = aug3.plusDays(2), units = 2, attended = 0),
            ),
        )

        assertEquals(1, grid.countOf(DayMark.FULL))
        assertEquals(1, grid.countOf(DayMark.PARTIAL))
        assertEquals(1, grid.countOf(DayMark.ABSENT))
        assertEquals(28, grid.countOf(DayMark.NO_CLASS))
        assertEquals(31, grid.markCounts.values.sum())
    }

    @Test
    fun `the month header tally covers the month only`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(date = LocalDate.of(2026, 7, 31), units = 2, attended = 0),
                session(date = aug3, units = 2, attended = 1),
                session(date = LocalDate.of(2026, 8, 31), units = 2, attended = 2),
                session(date = LocalDate.of(2026, 9, 1), units = 2, attended = 0),
            ),
        )

        assertEquals(Tally(3, 4), grid.tally)
        assertEquals(Percent.ofPercent(75.0), grid.percent)
        assertNull(grid.cellFor(LocalDate.of(2026, 7, 31)))
        assertEquals(DayMark.FULL, grid.cellFor(LocalDate.of(2026, 8, 31))!!.mark)
    }

    @Test
    fun `a per-course calendar ignores other subjects`() {
        val sessions = listOf(
            session(courseId = ADEC_ID, date = aug3, units = 2, attended = 2),
            session(courseId = PSA_ID, date = aug3, startHour = 14, units = 2, attended = 0),
        )

        val combined = CalendarBuilder.monthGrid(august, sessions)
        val adecOnly = CalendarBuilder.monthGrid(august, sessions, courseId = ADEC_ID)
        val psaOnly = CalendarBuilder.monthGrid(august, sessions, courseId = PSA_ID)

        // Combined: 2 of 4 units, so amber. Per-course: green and red respectively.
        assertEquals(DayMark.PARTIAL, combined.cellFor(aug3)!!.mark)
        assertEquals(DayMark.FULL, adecOnly.cellFor(aug3)!!.mark)
        assertEquals(DayMark.ABSENT, psaOnly.cellFor(aug3)!!.mark)
    }

    @Test
    fun `tapping a day yields its sessions in slot order`() {
        val sessions = listOf(
            session(courseId = PSA_ID, date = aug3, startHour = 14, units = 2, attended = 1),
            session(courseId = ADEC_ID, date = aug3, startHour = 9, units = 2, attended = 2),
            session(courseId = ADEC_ID, date = aug3.plusDays(1), startHour = 9),
        )

        val onDay = CalendarBuilder.sessionsOn(aug3, sessions)

        assertEquals(listOf(9, 14), onDay.map { it.startHour })
        assertEquals(listOf(ADEC_ID, PSA_ID), onDay.map { it.courseId })
        assertEquals(1, CalendarBuilder.sessionsOn(aug3, sessions, courseId = PSA_ID).size)
        assertTrue(CalendarBuilder.sessionsOn(aug3.plusDays(5), sessions).isEmpty())
    }

    @Test
    fun `the day cell carries the sessions behind its colour`() {
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(date = aug3, startHour = 9, units = 2, attended = 1),
                session(date = aug3, startHour = 14, units = 1, attended = 1),
            ),
        )

        val day = grid.cellFor(aug3)!!.day!!
        assertEquals(listOf(9, 14), day.sessions.map { it.startHour })
        assertEquals(Tally(2, 3), day.tally)
    }

    @Test
    fun `a range of months comes back oldest first`() {
        val grids = CalendarBuilder.monthGrids(
            from = YearMonth.of(2026, 7),
            to = YearMonth.of(2026, 9),
            sessions = listOf(session(date = aug3, units = 2, attended = 1)),
        )

        assertEquals(
            listOf(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9)),
            grids.map { it.month },
        )
        assertEquals(Tally.EMPTY, grids[0].tally)
        assertEquals(Tally(1, 2), grids[1].tally)
        assertTrue(CalendarBuilder.monthGrids(YearMonth.of(2026, 9), YearMonth.of(2026, 7), emptyList()).isEmpty())
    }

    @Test
    fun `february alignment holds in a leap year`() {
        val feb2028 = YearMonth.of(2028, 2)

        val grid = CalendarBuilder.monthGrid(feb2028, emptyList())

        assertEquals(29, grid.days.size)
        assertEquals(DayOfWeek.TUESDAY, LocalDate.of(2028, 2, 1).dayOfWeek)
        assertEquals(1, grid.weeks.first().count { it.isPadding })
        assertTrue(grid.weeks.all { it.size == 7 })
    }

    @Test
    fun `days before a joining date are still drawn, greyed rather than missing`() {
        // The spot admission: the student arrived on the 10th. The classes on the 3rd were
        // really held and the calendar must not pretend otherwise — the specification's
        // "do NOT delete sessions before joining date" seen from the grid.
        val grid = CalendarBuilder.monthGrid(
            august,
            listOf(
                session(date = aug3, units = 2, attended = 0),
                session(date = LocalDate.of(2026, 8, 12), units = 2, attended = 1),
            ),
            window = AttendanceWindow.since(LocalDate.of(2026, 8, 10)),
        )

        val early = grid.cellFor(aug3)!!
        assertEquals(DayMark.NOT_COUNTED, early.mark)
        assertFalse(early.isPadding)
        assertEquals(3, early.dayOfMonth)
        // The sessions are still on the cell, so tapping the day still opens them.
        assertEquals(1, early.day!!.sessions.size)
        assertEquals(Tally.EMPTY, early.day.tally)
        // And the day inside the window is coloured normally.
        assertEquals(DayMark.PARTIAL, grid.cellFor(LocalDate.of(2026, 8, 12))!!.mark)
    }

    @Test
    fun `the month tally counts only the days inside the window`() {
        val sessions = listOf(
            session(date = aug3, units = 2, attended = 0),
            session(date = LocalDate.of(2026, 8, 12), units = 2, attended = 2),
        )

        val whole = CalendarBuilder.monthGrid(august, sessions)
        val joinedLate = CalendarBuilder.monthGrid(
            august,
            sessions,
            window = AttendanceWindow.since(LocalDate.of(2026, 8, 10)),
        )

        // Half the term missed, and then the same student's figure once the fortnight
        // they were not enrolled for stops counting against them.
        assertEquals(Tally(2, 4), whole.tally)
        assertEquals(Percent.ofPercent(50.0), whole.percent)
        assertEquals(Tally(2, 2), joinedLate.tally)
        assertEquals(Percent.ofPercent(100.0), joinedLate.percent)
        // Every day is still on the grid; one has simply moved bands.
        assertEquals(31, joinedLate.markCounts.values.sum())
        assertEquals(1, joinedLate.countOf(DayMark.NOT_COUNTED))
    }

    @Test
    fun `a range of months carries the window through`() {
        val grids = CalendarBuilder.monthGrids(
            from = YearMonth.of(2026, 8),
            to = YearMonth.of(2026, 9),
            sessions = listOf(
                session(date = aug3, units = 2, attended = 0),
                session(date = LocalDate.of(2026, 9, 7), units = 2, attended = 2),
            ),
            window = AttendanceWindow.since(LocalDate.of(2026, 8, 10)),
        )

        assertEquals(Tally.EMPTY, grids[0].tally)
        assertEquals(DayMark.NOT_COUNTED, grids[0].cellFor(aug3)!!.mark)
        assertEquals(Tally(2, 2), grids[1].tally)
    }

    private companion object {
        const val ADEC_ID = 1L
        const val PSA_ID = 2L
    }
}
