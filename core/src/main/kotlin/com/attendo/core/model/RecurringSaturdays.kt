package com.attendo.core.model

/**
 * Which sections are taught on Saturday as a matter of routine.
 *
 * The college runs a five-day week with occasional working Saturdays, and [AcademicCalendar]
 * models exactly that: a Saturday is a holiday unless its date is listed. Nine sections are
 * the exception. From the 17 September 2026 timetable they are timetabled on Saturday every
 * week, so for them a Saturday class is not an occasional arrangement to be switched on a
 * date at a time — it is simply part of the week.
 *
 * This is deliberately a fixed list rather than a reading of the timetable. "The grid prints
 * a Saturday cell" is a weaker claim than "this section is taught every Saturday", and
 * deriving the second from the first would silently promote a section to a six-day week the
 * first time a stray Saturday cell appeared in the source. The list changes when the
 * timetable is revised and somebody says so.
 *
 * The two consequences elsewhere in the app both follow from this predicate:
 *
 * - **Sessions.** A section on this list has Saturday treated as a working day, via
 *   [AcademicCalendar.withEverySaturdayWorking], so its Saturday slots generate classes on
 *   every Saturday of the term without anyone ticking them off one by one.
 * - **Settings.** The Working Saturdays screen is not offered to these sections at all —
 *   there is nothing for it to configure, and a list of Saturdays that the app ignores
 *   would be worse than no list.
 */
object RecurringSaturdays {

    /** Every section of this year is taught on Saturday. */
    private const val FIRST_YEAR_PREFIX: String = "1st Yr "

    /**
     * The upper-year sections that are taught on Saturday, named in full.
     *
     * Written out rather than derived from a pattern: these three are individually
     * decided, and they are the whole of the list.
     */
    private val NAMED_SECTIONS: Set<String> = setOf(
        "4th Yr EE",
        "4th Yr ECE-A",
        "4th Yr ECE-B",
    )

    /**
     * Whether [section] is one the timetable teaches on Saturday every week.
     *
     * Null and blank are false: an install that has not seeded yet has no section, and
     * "no section" is not a reason to give it a six-day week.
     */
    fun appliesTo(section: String?): Boolean {
        val name = section?.trim().orEmpty()
        return name.startsWith(FIRST_YEAR_PREFIX) || name in NAMED_SECTIONS
    }

    /** The list as data, for tests and for anything that needs to enumerate it. */
    val namedSections: Set<String> get() = NAMED_SECTIONS
}
