package com.attendo.core.model

import java.time.LocalDate
import java.time.Month

/**
 * Which half of the academic year a semester is.
 *
 * The university runs the odd semester from July to December and the even one from January to
 * May. June is neither: it is the gap between them, which is why [forMonth] returns null for
 * it rather than picking a side.
 */
enum class SemesterType(val label: String) {
    ODD("Odd"),
    EVEN("Even"),
    ;

    /** The one that comes after this: odd is followed by even, and even by next year's odd. */
    val next: SemesterType get() = if (this == ODD) EVEN else ODD

    companion object {
        /** ODD for July–December, EVEN for January–May, null for June. */
        fun forMonth(month: Month): SemesterType? = when (month.value) {
            in 7..12 -> ODD
            in 1..5 -> EVEN
            else -> null
        }

        /**
         * The type of a semester that starts on [date].
         *
         * June resolves to ODD, because a term *starting* in June is the odd semester
         * starting early — a term that merely runs into June is the even one running late,
         * and it is the start date that names a semester. This is the one place the app puts
         * a default on the boundary month, and it is never the last word: the import screen
         * shows the detected type as a choice the student can change before anything is
         * written, which is the same rule the timetable itself follows.
         */
        fun startingOn(date: LocalDate): SemesterType = forMonth(date.month) ?: ODD
    }
}

/**
 * One semester: which one it is, when it ran, and whether it is still live.
 *
 * ### Why this exists
 *
 * Attendance is a per-semester number. Nothing in a percentage means anything if it spans two
 * of them — six subjects that finished in December averaged with five that started in January
 * is not a figure anybody has a use for. So a semester is a first-class row, every course
 * belongs to exactly one, and the tally for a semester is bounded by [window]. That bound is
 * the whole mechanism behind "never mix semesters in one attendance calculation".
 *
 * ### What it deliberately does not hold
 *
 * Holidays and working Saturdays. Those live in the app's settings and are applied to whatever
 * semester is running, because a holiday outside a semester's dates is inert anyway — the
 * generator never asks about a day outside the term. Keeping them out means this type is
 * exactly the span and the identity, and that the settings calendar a dozen screens already
 * read stays the single authority for the current term's shape. Use [calendarWith] to get the
 * full [AcademicCalendar] for a semester.
 *
 * ### Identity
 *
 * A semester *is* its [year] and [type]: "the odd semester of 2026" names one thing, and two
 * imports that both describe it are two readings of the same semester rather than two
 * semesters. [isSameTermAs] is what the import flow uses to tell a mid-semester correction
 * apart from a new semester, so identity is not derived from the dates — those get corrected.
 */
data class Semester(
    val id: Long = 0L,
    /** The calendar year the semester starts in: 2026 for Jul–Dec 2026, 2027 for Jan–May 2027. */
    val year: Int,
    val type: SemesterType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /**
     * True once the semester is over and has been put away. Its courses and sessions are all
     * still there — archiving is what makes a semester stop counting, never what deletes it.
     */
    val archived: Boolean = false,
) {
    init {
        require(year in PLAUSIBLE_YEARS) { "implausible academic year: $year" }
        require(!endDate.isBefore(startDate)) { "a semester cannot end ($endDate) before it starts ($startDate)" }
    }

    /** The calendar year the academic year opens in: 2026 for both halves of 2026–27. */
    val academicYearStart: Int
        get() = if (type == SemesterType.ODD) year else year - 1

    /** "2026–27" */
    val academicYear: String
        get() = "$academicYearStart–${((academicYearStart + 1) % 100).toString().padStart(2, '0')}"

    /** "Odd semester 2026–27" */
    val label: String get() = "${type.label} semester $academicYear"

    /** "28 Jul 2026 – 15 Dec 2026" */
    val rangeLabel: String get() = "${dayLabel(startDate)} – ${dayLabel(endDate)}"

    /** The dates whose sessions belong to this semester, and to no other. */
    val window: AttendanceWindow get() = AttendanceWindow(startDate, endDate)

    val weeks: Int get() = (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt() / 7) + 1

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDate)

    /** True when [date] falls after this semester ended — the signal that a new one is due. */
    fun hasEndedBy(date: LocalDate): Boolean = date.isAfter(endDate)

    /**
     * True when both name the same semester of the same academic year.
     *
     * Compared on identity and not on dates, because a semester whose end date gets corrected
     * in March is still the semester it was in July.
     */
    fun isSameTermAs(other: Semester): Boolean = year == other.year && type == other.type

    /** The full calendar for this semester, with the app's holiday sets applied to its span. */
    fun calendarWith(
        holidays: Set<LocalDate> = emptySet(),
        workingSaturdays: Set<LocalDate> = emptySet(),
    ): AcademicCalendar = AcademicCalendar(
        termStart = startDate,
        termEnd = endDate,
        holidays = holidays.filter { it in this }.toSet(),
        workingSaturdays = workingSaturdays.filter { it in this }.toSet(),
    )

    /**
     * The semester that follows this one, over [span] days.
     *
     * Only a suggestion: the dates cannot be known from this side of the break, so the import
     * screen offers them pre-filled and the student corrects them. The *identity* it derives —
     * odd is followed by even, and even by next year's odd — is not a guess.
     */
    fun following(startsOn: LocalDate, endsOn: LocalDate): Semester = Semester(
        year = startsOn.year,
        type = type.next,
        startDate = startsOn,
        endDate = endsOn,
    )

    companion object {
        /**
         * Years a semester may plausibly fall in.
         *
         * Wide on purpose — this is a typo filter, not a policy. It exists so a backup carrying
         * `"year": 0` is reported as a bad field rather than throwing out of a constructor, which
         * is why [com.attendo.core.backup.BackupCodec] checks against this same range instead of
         * repeating the bounds.
         */
        val PLAUSIBLE_YEARS: IntRange = 1900..2999

        /**
         * The semester a term running [startDate]..[endDate] describes, with its year and type
         * derived from the start date.
         */
        fun spanning(startDate: LocalDate, endDate: LocalDate, archived: Boolean = false): Semester =
            Semester(
                year = startDate.year,
                type = SemesterType.startingOn(startDate),
                startDate = startDate,
                endDate = endDate,
                archived = archived,
            )

        /** The semester a stored [AcademicCalendar] describes — how a pre-semester install is adopted. */
        fun of(calendar: AcademicCalendar, archived: Boolean = false): Semester =
            spanning(calendar.termStart, calendar.termEnd, archived)

        /** "28 Jul 2026" */
        internal fun dayLabel(date: LocalDate): String = buildString {
            append(date.dayOfMonth).append(' ')
            append(date.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3))
            append(' ').append(date.year)
        }
    }
}
