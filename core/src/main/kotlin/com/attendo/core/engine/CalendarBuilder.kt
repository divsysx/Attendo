package com.attendo.core.engine

import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Percent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * One cell of the month grid.
 *
 * [date] is null for the padding cells that align the first of the month under the
 * right weekday column. A real date with a null [day] means nothing was scheduled —
 * the grey/blank band.
 */
data class MonthCell(
    val date: LocalDate?,
    val day: DayAttendance?,
) {
    val isPadding: Boolean get() = date == null

    /**
     * The colour band for this cell. [DayMark.NO_CLASS] covers both a day with no
     * classes and a padding cell, so the UI has a single value to switch on.
     */
    val mark: DayMark get() = day?.mark ?: DayMark.NO_CLASS

    val dayOfMonth: Int? get() = date?.dayOfMonth
}

/**
 * A month laid out as calendar weeks, for the colour-coded attendance calendar.
 *
 * Green [DayMark.FULL], red [DayMark.ABSENT] and amber [DayMark.PARTIAL] come
 * straight from [AttendanceEngine.dayAttendance], so the split-class case — one hour
 * of a 2-hour block — colours the day amber rather than rounding it to present or
 * absent. Cancelled-only and unreviewed days get their own bands instead of being
 * mistaken for absences.
 */
data class MonthGrid(
    val month: YearMonth,
    /** Rows of exactly 7 cells, padded at both ends. */
    val weeks: List<List<MonthCell>>,
    /** The weekday each row starts on, for the column headers. */
    val weekStart: DayOfWeek,
    /** Tally across the whole month, for the header line. */
    val tally: Tally,
    /** How many days fall in each band, for the legend. */
    val markCounts: Map<DayMark, Int>,
) {
    val percent: Percent? get() = tally.percent

    /** Weekday order of the columns, e.g. Mon..Sun. */
    val weekdayOrder: List<DayOfWeek>
        get() = (0L until 7L).map { weekStart.plus(it) }

    /** Real (non-padding) cells in calendar order. */
    val days: List<MonthCell> get() = weeks.flatten().filterNot { it.isPadding }

    fun cellFor(date: LocalDate): MonthCell? = days.firstOrNull { it.date == date }

    fun countOf(mark: DayMark): Int = markCounts[mark] ?: 0
}

/**
 * Lays sessions out into month grids.
 *
 * Pure and calendar-agnostic: it colours the days that have sessions and leaves
 * everything else blank, so it needs no term dates or holiday list to be correct.
 */
object CalendarBuilder {

    /**
     * Builds the grid for [month].
     *
     * Pass [courseId] to colour a single subject's calendar, or leave it null for the
     * combined view. [sessions] may be the entire table; anything outside the month
     * is ignored.
     *
     * [window] is the dates that count. Days outside it are still drawn — they are days the
     * student really had classes on — but they are marked [DayMark.NOT_COUNTED] and left out of
     * the grid's tally, which is how a term the student joined halfway through shows its first
     * fortnight greyed rather than missing.
     *
     * [today], when supplied, bounds the review band exactly as [AttendanceEngine.dayAttendance]
     * does: a future SCHEDULED day is an upcoming class, not one to mark now, so it does not get
     * the AWAITING_REVIEW band. The course-detail screen passes `today` so the calendar can no
     * longer present a future class as "To review"; a null default keeps the older unbounded grids.
     */
    fun monthGrid(
        month: YearMonth,
        sessions: Iterable<ClassSession>,
        courseId: Long? = null,
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
        window: AttendanceWindow = AttendanceWindow.OPEN,
        today: LocalDate? = null,
    ): MonthGrid {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val inMonth = sessions.filter { session ->
            (courseId == null || session.courseId == courseId) &&
                !session.date.isBefore(first) && !session.date.isAfter(last)
        }
        val marks = AttendanceEngine.calendarMarks(inMonth, first, last, window, today)

        // Blank cells before the 1st so it lands under its own weekday column.
        val leadingPad = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
        val cells = buildList {
            repeat(leadingPad) { add(MonthCell(date = null, day = null)) }
            for (dayOfMonth in 1..month.lengthOfMonth()) {
                val date = month.atDay(dayOfMonth)
                add(MonthCell(date = date, day = marks[date]))
            }
            while (size % 7 != 0) add(MonthCell(date = null, day = null))
        }

        val realCells = cells.filterNot { it.isPadding }
        return MonthGrid(
            month = month,
            weeks = cells.chunked(7),
            weekStart = weekStart,
            tally = AttendanceEngine.tallyOf(inMonth, window),
            markCounts = realCells.groupingBy { it.mark }.eachCount(),
        )
    }

    /**
     * Grids for every month from [from] to [to] inclusive, oldest first — enough to
     * back a pager without rebuilding on every swipe.
     *
     * [today] is forwarded to each [monthGrid] so a pager of grids draws the same review
     * boundary as a single grid.
     */
    fun monthGrids(
        from: YearMonth,
        to: YearMonth,
        sessions: Iterable<ClassSession>,
        courseId: Long? = null,
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
        window: AttendanceWindow = AttendanceWindow.OPEN,
        today: LocalDate? = null,
    ): List<MonthGrid> {
        if (to.isBefore(from)) return emptyList()
        val materialised = sessions.toList()
        return generateSequence(from) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(to) }
            .map { monthGrid(it, materialised, courseId, weekStart, window, today) }
            .toList()
    }

    /**
     * The sessions behind one calendar cell, in slot order — what the day-detail
     * sheet shows when a date is tapped.
     */
    fun sessionsOn(
        date: LocalDate,
        sessions: Iterable<ClassSession>,
        courseId: Long? = null,
    ): List<ClassSession> = sessions
        .filter { it.date == date && (courseId == null || it.courseId == courseId) }
        .sortedBy { it.startHour }
}
