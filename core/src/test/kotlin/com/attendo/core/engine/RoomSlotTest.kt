package com.attendo.core.engine

import com.attendo.core.model.AcademicCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Rooms tab must stay anchored to the actual current day and never auto-advance to Monday or
 * the next teaching day because today is a Sunday, a holiday, after hours, or a day the timetable
 * does not use. The fix lives in [currentSlot]: with no manual pick, the slot it returns always
 * carries the clock's own [DayOfWeek]. These tests pin that invariant directly against the pure
 * helper, plus the [AcademicCalendar.isTeachingDay] flag the ViewModel crosses with it to decide
 * the non-teaching empty state.
 *
 * The old fallback was [RoomAvailability.nextSlot], which rolls forward — its own tests still
 * assert that rolling and stay green; it simply is no longer the default here.
 */
class RoomSlotTest {

    private val termStart: LocalDate = LocalDate.of(2026, 7, 28) // a Tuesday
    private val calendar = AcademicCalendar(
        termStart = termStart,
        termEnd = LocalDate.of(2026, 11, 20),
    )

    /** Monday–Friday: a timetable whose bookings never fall on a Saturday. */
    private val weekdays = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    /** Monday–Saturday: a timetable that does teach on Saturdays. */
    private val weekdaysPlusSaturday = weekdays + DayOfWeek.SATURDAY

    @Test
    fun `a Sunday stays on Sunday, never Monday`() {
        val now = LocalDateTime.of(2026, 8, 9, 11, 0) // a Sunday inside the term

        val slot = currentSlot(now, weekdays, chosen = null)

        assertEquals(DayOfWeek.SUNDAY, slot.dayOfWeek)
        // The whole point: it does not roll to Monday the way nextSlot would.
        assertNotEquals(DayOfWeek.MONDAY, slot.dayOfWeek)
    }

    @Test
    fun `a Saturday with no Saturday bookings stays on Saturday`() {
        // After hours on a Saturday the timetable does not use.
        val evening = LocalDateTime.of(2026, 8, 8, 19, 0)

        val slot = currentSlot(evening, weekdays, chosen = null)

        assertEquals(DayOfWeek.SATURDAY, slot.dayOfWeek)
        // No live slot (19:00 is outside the grid), so the hour falls back to the first slot.
        assertEquals(SlotQuery(DayOfWeek.SATURDAY, 9), slot)

        // And the live-slot branch is bypassed even inside hours when Saturday is not in `days`:
        // a Saturday at 10 AM still returns Saturday, not Monday.
        val morning = LocalDateTime.of(2026, 8, 8, 10, 0)
        assertEquals(DayOfWeek.SATURDAY, currentSlot(morning, weekdays, chosen = null).dayOfWeek)
    }

    @Test
    fun `a working Saturday uses its live slot`() {
        val now = LocalDateTime.of(2026, 8, 15, 10, 0) // a Saturday inside the term

        val slot = currentSlot(now, weekdaysPlusSaturday, chosen = null)

        // The clock is inside teaching hours on a day the timetable uses, so the live slot wins.
        assertEquals(SlotQuery(DayOfWeek.SATURDAY, 10), slot)
    }

    @Test
    fun `a holiday stays on the holiday and is not a teaching day`() {
        val holiday = LocalDate.of(2026, 8, 3) // a Monday declared a holiday
        val withHoliday = calendar.copy(holidays = setOf(holiday))
        val now = holiday.atTime(10, 0)

        val slot = currentSlot(now, weekdaysPlusSaturday, chosen = null)

        // The slot stays on the holiday's own day — it does not jump to the next teaching day.
        assertEquals(DayOfWeek.MONDAY, slot.dayOfWeek)
        assertEquals(holiday.dayOfWeek, slot.dayOfWeek)
        // ...and the calendar flag the ViewModel crosses with this is false, so the empty state shows.
        assertFalse(withHoliday.isTeachingDay(holiday))
    }

    @Test
    fun `a working day with no bookings stays on that day`() {
        // A Tuesday inside the term. The timetable teaches Monday–Friday, but Tuesday itself
        // has no booking — the free/busy lists would be empty, yet the day shown is still today.
        val now = LocalDateTime.of(2026, 8, 4, 10, 0)

        val slot = currentSlot(now, weekdays, chosen = null)

        assertEquals(DayOfWeek.TUESDAY, slot.dayOfWeek)
        assertEquals(now.dayOfWeek, slot.dayOfWeek)
    }

    @Test
    fun `a normal working day uses the live slot unchanged`() {
        val now = LocalDateTime.of(2026, 8, 3, 10, 5) // a Monday, mid-slot

        val slot = currentSlot(now, weekdaysPlusSaturday, chosen = null)

        // Indistinguishable from the old behaviour on a live teaching day: the slot the clock
        // is in is returned directly.
        assertEquals(SlotQuery(DayOfWeek.MONDAY, 10), slot)
    }

    @Test
    fun `currentSlot honours a manually chosen slot`() {
        // Manual navigation is preserved at the logic level: a slot the student picked wins
        // outright, regardless of where the clock is.
        val now = LocalDateTime.of(2026, 8, 3, 10, 5) // Monday morning
        val chosen = SlotQuery(DayOfWeek.WEDNESDAY, 14)

        val slot = currentSlot(now, weekdaysPlusSaturday, chosen = chosen)

        assertEquals(chosen, slot)
    }
}
