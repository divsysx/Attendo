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

    // ------------------------------------------- the queried date, and the calendar
    //
    // A SlotQuery carries a day-of-week, not a date — but "is this a teaching day?" is a
    // question about dates. These pin [queriedDate] (which day a query is about) and
    // [isNonTeachingDay] (the calendar's verdict on it) together: the pair that decides the
    // Rooms tab's empty state for *every* query, live or picked. The bug these guard
    // against read `slot.isRightNow && !calendar.isTeachingDay(now)` — an hour picked on a
    // holiday cleared the gate, the weekly grid answered with "no bookings, all free", and
    // a Sunday showed forty-one free rooms.

    /** The session calendar plus one mid-week holiday and one working Saturday. */
    private val holidayCalendar = AcademicCalendar(
        termStart = termStart,
        termEnd = LocalDate.of(2026, 11, 20),
        holidays = setOf(LocalDate.of(2026, 8, 5)), // a Wednesday
        workingSaturdays = setOf(LocalDate.of(2026, 8, 8)),
    )

    @Test
    fun `an hour picked on a holiday is still about that holiday`() {
        // The regression, straight up: Sunday 2026-08-09, an hour chip tapped. The query is
        // still about that Sunday whether it was reached by the clock or by the tap.
        val sunday = LocalDate.of(2026, 8, 9).atTime(11, 0)
        val picked = SlotQuery(DayOfWeek.SUNDAY, 14)

        assertEquals(LocalDate.of(2026, 8, 9), queriedDate(sunday, picked, isRightNow = false))
        assertTrue(
            "a picked hour on a non-teaching day must keep the empty state",
            isNonTeachingDay(sunday, holidayCalendar, picked, isRightNow = false),
        )
        // The live view said so before the bug; it still must.
        assertTrue(isNonTeachingDay(sunday, holidayCalendar, picked, isRightNow = true))
    }

    @Test
    fun `every hour of a mid-week holiday stays non-teaching`() {
        // A holiday Wednesday: whichever hour chip is tapped, the day being asked about is
        // the holiday — the grid's Wednesday bookings must never render as if classes ran.
        val holiday = LocalDate.of(2026, 8, 5).atTime(10, 0)

        for (hour in 9..17) {
            val picked = SlotQuery(DayOfWeek.WEDNESDAY, hour)
            assertEquals(LocalDate.of(2026, 8, 5), queriedDate(holiday, picked, isRightNow = false))
            assertTrue(
                "hour $hour picked on the holiday must stay non-teaching",
                isNonTeachingDay(holiday, holidayCalendar, picked, isRightNow = false),
            )
        }
    }

    @Test
    fun `a teaching day is unaffected by picking an hour`() {
        val monday = LocalDate.of(2026, 8, 3).atTime(10, 0) // an ordinary teaching Monday

        for (hour in listOf(9, 13, 17)) {
            val picked = SlotQuery(DayOfWeek.MONDAY, hour)
            assertFalse(isNonTeachingDay(monday, holidayCalendar, picked, isRightNow = true))
            assertFalse(isNonTeachingDay(monday, holidayCalendar, picked, isRightNow = false))
        }
    }

    @Test
    fun `a picked day is its next occurrence, never the past one`() {
        val saturday = LocalDate.of(2026, 8, 8).atTime(10, 0)

        // Friday picked on a Saturday means *next* Friday — Aug 14, not yesterday's Aug 7.
        assertEquals(
            LocalDate.of(2026, 8, 14),
            queriedDate(saturday, SlotQuery(DayOfWeek.FRIDAY, 9), isRightNow = false),
        )
    }

    @Test
    fun `a picked day is judged on the date it means, not the day-of-week in general`() {
        val picked = SlotQuery(DayOfWeek.WEDNESDAY, 9)

        // From the Monday before the holiday, Wednesday means the holiday itself…
        val mondayBefore = LocalDate.of(2026, 8, 3).atTime(9, 0)
        assertTrue(isNonTeachingDay(mondayBefore, holidayCalendar, picked, isRightNow = false))
        // …from the Monday after it, Wednesday means an ordinary teaching day.
        val mondayAfter = LocalDate.of(2026, 8, 10).atTime(9, 0)
        assertFalse(isNonTeachingDay(mondayAfter, holidayCalendar, picked, isRightNow = false))
    }

    @Test
    fun `a working Saturday teaches when picked and a plain Saturday does not`() {
        val picked = SlotQuery(DayOfWeek.SATURDAY, 10)

        // Friday Aug 7 picking Saturday means Aug 8 — the working Saturday.
        val friday = LocalDate.of(2026, 8, 7).atTime(10, 0)
        assertFalse(isNonTeachingDay(friday, holidayCalendar, picked, isRightNow = false))
        // A week later, the same pick means Aug 15 — a plain Saturday.
        val nextFriday = LocalDate.of(2026, 8, 14).atTime(10, 0)
        assertTrue(isNonTeachingDay(nextFriday, holidayCalendar, picked, isRightNow = false))
    }

    @Test
    fun `vacation dates are non-teaching for every pick`() {
        val july = LocalDate.of(2026, 7, 20).atTime(10, 0) // a Monday before the term starts

        assertTrue(isNonTeachingDay(july, holidayCalendar, SlotQuery(DayOfWeek.MONDAY, 10), isRightNow = true))
        assertTrue(isNonTeachingDay(july, holidayCalendar, SlotQuery(DayOfWeek.WEDNESDAY, 10), isRightNow = false))
        assertTrue(isNonTeachingDay(july, holidayCalendar, SlotQuery(DayOfWeek.FRIDAY, 10), isRightNow = false))
    }

    @Test
    fun `midnight moves the queried date with the clock`() {
        val beforeMidnight = LocalDate.of(2026, 8, 8).atTime(23, 59) // Saturday night
        val afterMidnight = LocalDate.of(2026, 8, 9).atTime(0, 1) // Sunday small hours

        // The live view: 23:59 is still Saturday; 00:01 is already Sunday.
        assertEquals(
            LocalDate.of(2026, 8, 8),
            queriedDate(beforeMidnight, SlotQuery(DayOfWeek.SATURDAY, 17), isRightNow = true),
        )
        assertEquals(
            LocalDate.of(2026, 8, 9),
            queriedDate(afterMidnight, SlotQuery(DayOfWeek.SUNDAY, 9), isRightNow = true),
        )
        // A Sunday picked on Saturday evening is tomorrow's Sunday — the same date the
        // clock crosses into a minute later.
        assertEquals(
            LocalDate.of(2026, 8, 9),
            queriedDate(beforeMidnight, SlotQuery(DayOfWeek.SUNDAY, 9), isRightNow = false),
        )
    }

    // ------------------------------------------------- the room detail "now" claim
    //
    // A room's detail screen may say "Now: free/in use" and "Nothing until …" — both are
    // claims about *today*, and the weekly grid has no idea what today is. Before the fix,
    // a holiday Wednesday at 10 AM got the grid's answer: a phantom "in use" for a class
    // that was not running, or a "free" read out of pure absence. [roomNowClaim] gives the
    // calendar the veto, and these pin every branch of it.

    @Test
    fun `a mid-week holiday makes no now-claim at all`() {
        // Wednesday 5 Aug 2026, 10 AM — the holiday in the fixture, mid-slot.
        val holidayMorning = LocalDateTime.of(2026, 8, 5, 10, 0)

        assertEquals(
            NowClaim.NonTeachingDay,
            roomNowClaim(holidayMorning, holidayCalendar, weekdays),
        )
    }

    @Test
    fun `a vacation weekday makes no now-claim either`() {
        // A Monday before the term starts — in-term logic is not enough on its own.
        val beforeTerm = LocalDateTime.of(2026, 7, 20, 10, 0)

        assertEquals(
            NowClaim.NonTeachingDay,
            roomNowClaim(beforeTerm, holidayCalendar, weekdays),
        )
    }

    @Test
    fun `a Sunday makes no now-claim`() {
        assertEquals(
            NowClaim.NonTeachingDay,
            roomNowClaim(LocalDateTime.of(2026, 8, 9, 11, 0), holidayCalendar, weekdays),
        )
    }

    @Test
    fun `a normal teaching day mid-slot claims that slot`() {
        val mondayMorning = LocalDateTime.of(2026, 8, 3, 10, 5) // an ordinary teaching Monday

        assertEquals(
            NowClaim.InSlot(SlotQuery(DayOfWeek.MONDAY, 10)),
            roomNowClaim(mondayMorning, holidayCalendar, weekdays),
        )
    }

    @Test
    fun `a teaching day the timetable does not use claims the next day it does`() {
        // Friday teaches, but this timetable is Monday–Thursday: the "Nothing until" line
        // must point at Monday, not at a Friday the grid has nothing on.
        val friday = LocalDateTime.of(2026, 8, 7, 10, 0)

        assertEquals(
            NowClaim.Later(SlotQuery(DayOfWeek.MONDAY, 9)),
            roomNowClaim(friday, holidayCalendar, weekdays.dropLast(1)),
        )
    }

    @Test
    fun `before the first slot on a teaching day, the claim is today's first slot`() {
        val earlyMonday = LocalDateTime.of(2026, 8, 3, 8, 0)

        assertEquals(
            NowClaim.Later(SlotQuery(DayOfWeek.MONDAY, 9)),
            roomNowClaim(earlyMonday, holidayCalendar, weekdays),
        )
    }

    @Test
    fun `after hours on a teaching day, the claim is the next teaching day`() {
        val mondayNight = LocalDateTime.of(2026, 8, 3, 19, 0)

        assertEquals(
            NowClaim.Later(SlotQuery(DayOfWeek.TUESDAY, 9)),
            roomNowClaim(mondayNight, holidayCalendar, weekdays),
        )
    }

    @Test
    fun `the next-slot claim skips a holiday the grid cannot see`() {
        // Tuesday 4 Aug at 7 PM: the grid would say "until Wednesday" — but Wednesday is
        // the holiday, so the honest next slot is Thursday.
        val tuesdayNight = LocalDateTime.of(2026, 8, 4, 19, 0)

        assertEquals(
            NowClaim.Later(SlotQuery(DayOfWeek.THURSDAY, 9)),
            roomNowClaim(tuesdayNight, holidayCalendar, weekdays),
        )
    }

    @Test
    fun `a working Saturday claims a slot, a plain Saturday does not`() {
        // 8 Aug 2026 is the fixture's working Saturday; 15 Aug is an ordinary one.
        assertEquals(
            NowClaim.InSlot(SlotQuery(DayOfWeek.SATURDAY, 10)),
            roomNowClaim(LocalDateTime.of(2026, 8, 8, 10, 0), holidayCalendar, weekdaysPlusSaturday),
        )
        assertEquals(
            NowClaim.NonTeachingDay,
            roomNowClaim(LocalDateTime.of(2026, 8, 15, 10, 0), holidayCalendar, weekdaysPlusSaturday),
        )
    }

    @Test
    fun `the last teaching evening of the term has no next slot to name`() {
        // 20 Nov 2026 is the term's end — a Friday. After hours there is no teaching day
        // left inside the term, and pointing past it would point at another semester.
        val lastEvening = LocalDateTime.of(2026, 11, 20, 19, 0)

        assertEquals(NowClaim.NoMoreSlots, roomNowClaim(lastEvening, holidayCalendar, weekdays))
    }

    @Test
    fun `the evening before the term's last day still names it`() {
        val penultimateEvening = LocalDateTime.of(2026, 11, 19, 19, 0) // a Thursday

        assertEquals(
            NowClaim.Later(SlotQuery(DayOfWeek.FRIDAY, 9)),
            roomNowClaim(penultimateEvening, holidayCalendar, weekdays),
        )
    }
}
