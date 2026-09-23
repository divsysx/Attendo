package com.attendo.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The teaching calendar for one semester: when it runs, and which days are
 * actually working days.
 *
 * The college runs Monday–Friday with occasional working Saturdays, so Saturday is
 * treated as a holiday *unless* explicitly listed in [workingSaturdays]. That
 * inversion matters: a Saturday pattern must not quietly generate a class on every
 * Saturday of the term.
 *
 * Defaults match the 2026-27 session in the supplied timetable (w.e.f. 28 July 2026).
 */
data class AcademicCalendar(
    val termStart: LocalDate,
    val termEnd: LocalDate,
    /** Dates with no classes at all, whatever the patterns say. */
    val holidays: Set<LocalDate> = emptySet(),
    /** The specific Saturdays that *are* working days. */
    val workingSaturdays: Set<LocalDate> = emptySet(),
) {
    init {
        require(!termEnd.isBefore(termStart)) { "termEnd $termEnd precedes termStart $termStart" }
    }

    fun isWithinTerm(date: LocalDate): Boolean =
        !date.isBefore(termStart) && !date.isAfter(termEnd)

    fun isWorkingDay(date: LocalDate): Boolean = when {
        date in holidays -> false
        date.dayOfWeek == DayOfWeek.SUNDAY -> false
        date.dayOfWeek == DayOfWeek.SATURDAY -> date in workingSaturdays
        else -> true
    }

    /** A day that classes can be generated for: inside the term and working. */
    fun isTeachingDay(date: LocalDate): Boolean = isWithinTerm(date) && isWorkingDay(date)

    fun teachingDaysBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filter(::isTeachingDay)
            .toList()
    }

    /** Every Saturday the term contains, in order — the working-Saturday picker's options. */
    fun saturdaysInTerm(): List<LocalDate> {
        val first = termStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        return generateSequence(first) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(termEnd) }
            .toList()
    }

    /**
     * The same calendar with every Saturday in the term promoted to a working day.
     *
     * This is how a section that is timetabled on Saturday every week is expressed without
     * a second calendar, a new stored field, or a change to what the account syncs: the
     * dates are derived from [termStart] and [termEnd] instead of being written down, so
     * moving the term moves them and nothing can go stale.
     *
     * It is the *derived* calendar, never the stored one — see [RecurringSaturdays] for
     * which sections get it, and note that a holiday still wins over it, because
     * [isWorkingDay] checks [holidays] before it looks at the day of the week.
     */
    fun withEverySaturdayWorking(): AcademicCalendar =
        copy(workingSaturdays = workingSaturdays + saturdaysInTerm())

    companion object {
        /**
         * Session 2026-27, w.e.f. 28 July 2026 per the Faculty of Technology timetable.
         *
         * `termEnd` is the dispersal / prep-leave boundary — 20 November 2026 — rather than the
         * start of theory exams (4 December). Attendance is counted over teaching days, and once
         * dispersal begins there are no more classes to attend, so the figure the student is shown
         * should stop there too. This is only the default for an install that has not yet been
         * configured; a student who sets their own term dates keeps them.
         */
        val DEFAULT_2026_27: AcademicCalendar = AcademicCalendar(
            termStart = LocalDate.of(2026, 7, 28),
            termEnd = LocalDate.of(2026, 11, 20),
        )
    }
}
