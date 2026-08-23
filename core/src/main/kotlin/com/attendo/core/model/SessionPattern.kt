package com.attendo.core.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One recurring weekly slot for a course — the *template*, never a thing that
 * happened. "ADEC, Monday, 9 AM, 2 units, room 204" is one pattern; a course with
 * a 2-hour block on Monday and a 1-hour class on Wednesday has two.
 *
 * ### Why patterns carry a date range
 *
 * Timetables get revised mid-semester. If a pattern were simply edited in place,
 * every session already generated from it would retroactively claim it had always
 * been at the new time, and history would lie. Instead a pattern is *retired* by
 * setting [effectiveTo] to the last date it applied, and a replacement pattern
 * starts the next day. Past sessions keep pointing at the pattern that actually
 * produced them, so the history view stays truthful.
 *
 * A pattern is therefore append-mostly: the only routine mutation is closing it.
 */
data class SessionPattern(
    val id: Long = 0L,
    val courseId: Long,
    val dayOfWeek: DayOfWeek,
    /** Slot start on a 24-hour clock, 9..17. */
    val startHour: Int,
    /** Length in one-hour attendance units. A 2-hour block is 2. */
    val units: Int,
    val kind: SessionKind = SessionKind.LECTURE,
    val room: String? = null,
    /** First date this pattern applies, inclusive. */
    val effectiveFrom: LocalDate,
    /** Last date this pattern applies, inclusive; null means open-ended. */
    val effectiveTo: LocalDate? = null,
) {
    init {
        require(TimeGrid.fits(startHour, units)) {
            "session $startHour +${units}h does not fit the ${TimeGrid.FIRST_START_HOUR}-" +
                "${TimeGrid.LAST_END_HOUR} teaching day"
        }
        require(units <= UnitMask.MAX_UNITS) { "units must be <= ${UnitMask.MAX_UNITS}" }
        require(effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) {
            "effectiveTo $effectiveTo precedes effectiveFrom $effectiveFrom"
        }
    }

    val endHour: Int get() = startHour + units

    val slotLabel: String get() = TimeGrid.rangeLabel(startHour, units)

    val isOpenEnded: Boolean get() = effectiveTo == null

    fun isEffectiveOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))

    /** Closes this pattern so it stops generating sessions after [lastDate]. */
    fun retiredAfter(lastDate: LocalDate): SessionPattern = copy(effectiveTo = lastDate)

    /** True when this pattern would collide with [other] on the same weekday hours. */
    fun overlaps(other: SessionPattern): Boolean {
        if (dayOfWeek != other.dayOfWeek) return false
        val datesOverlap = (effectiveTo == null || !effectiveTo.isBefore(other.effectiveFrom)) &&
            (other.effectiveTo == null || !other.effectiveTo.isBefore(effectiveFrom))
        if (!datesOverlap) return false
        return startHour < other.endHour && other.startHour < endHour
    }
}
