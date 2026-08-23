package com.attendo.core.model

/**
 * The college's fixed hourly slot grid: 9-10, 10-11, 11-12, 12-1, 1-2, 2-3, 3-4,
 * 4-5, 5-6. Slots are identified by their start hour on a 24-hour clock, so
 * 9 means 9 AM and 17 means 5 PM.
 *
 * Every attendance unit is exactly one of these hours. A 2-hour class starting at
 * 9 occupies units [9-10, 10-11] and is worth 2 units.
 */
object TimeGrid {

    /** 9 AM — first teaching hour. */
    const val FIRST_START_HOUR: Int = 9

    /** 17 = 5 PM — the last slot a class may *start* in. */
    const val LAST_START_HOUR: Int = 17

    /** 18 = 6 PM — the earliest a class may *end* after the final slot. */
    const val LAST_END_HOUR: Int = 18

    /** Longest possible single session: 9 AM straight through to 6 PM. */
    const val MAX_UNITS_PER_SESSION: Int = LAST_END_HOUR - FIRST_START_HOUR

    /** All nine slot start hours, in timetable order. */
    val startHours: List<Int> = (FIRST_START_HOUR..LAST_START_HOUR).toList()

    fun isValidStartHour(hour: Int): Boolean = hour in FIRST_START_HOUR..LAST_START_HOUR

    fun fits(startHour: Int, units: Int): Boolean =
        isValidStartHour(startHour) && units >= 1 && startHour + units <= LAST_END_HOUR

    /** Single-slot label as printed in the PDF header, e.g. 11 -> "11 AM–12 PM". */
    fun slotLabel(startHour: Int): String = rangeLabel(startHour, 1)

    /**
     * Label spanning [units] hours from [startHour]: (9, 2) -> "9–11 AM",
     * (11, 2) -> "11 AM–1 PM", (13, 1) -> "1–2 PM".
     *
     * The meridiem is printed once when start and end share it, which is how the
     * college timetable reads.
     */
    fun rangeLabel(startHour: Int, units: Int): String {
        require(units >= 1) { "units must be >= 1, was $units" }
        val endHour = startHour + units
        val startMeridiem = meridiem(startHour)
        val endMeridiem = meridiem(endHour)
        return if (startMeridiem == endMeridiem) {
            "${clock12(startHour)}–${clock12(endHour)} $endMeridiem"
        } else {
            "${clock12(startHour)} $startMeridiem–${clock12(endHour)} $endMeridiem"
        }
    }

    /** Label for a single unit *within* a session, used by the per-hour toggles. */
    fun unitLabel(sessionStartHour: Int, unitIndex: Int): String =
        slotLabel(sessionStartHour + unitIndex)

    private fun clock12(hour24: Int): Int = (hour24 % 12).let { if (it == 0) 12 else it }

    private fun meridiem(hour24: Int): String = if (hour24 < 12) "AM" else "PM"
}
