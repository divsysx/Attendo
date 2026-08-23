package com.attendo.core.model

import java.time.DayOfWeek
import java.time.LocalDate

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
