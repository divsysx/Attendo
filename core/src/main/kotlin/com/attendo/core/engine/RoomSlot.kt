package com.attendo.core.engine

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.TimeGrid
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Which slot the Rooms tab is asking about, anchored to the actual current day.
 *
 * The right-now view wants the slot the clock is in. [RoomAvailability.slotAt] returns null
 * outside teaching hours and on a Sunday, and the old answer to that was [RoomAvailability.nextSlot],
 * which rolls forward to the next teaching day's 9 AM — opening the tab on a Sunday showed
 * Monday instead of the day it actually is. That is the bug this exists to fix.
 *
 * When there is no live slot, this stays on **today's** day-of-week at a valid grid hour rather
 * than advancing, so the displayed day always matches the clock's day. A student who wants
 * Monday can still tap its chip — [chosen] is honoured first — but nothing is decided for them.
 *
 * Pure: it calls [RoomAvailability.slotAt] (read-only on the clock) and never touches the
 * timetable, so it does not duplicate or alter any availability calculation.
 */
fun currentSlot(
    now: LocalDateTime,
    days: List<DayOfWeek>,
    chosen: SlotQuery?,
): SlotQuery {
    // A slot the student picked by hand wins outright — manual navigation is preserved.
    chosen?.let { return it }

    // The live slot, when the clock is inside teaching hours on a day the timetable uses.
    RoomAvailability.slotAt(now)?.takeIf { it.dayOfWeek in days }?.let { return it }

    // No live slot: stay on today's day-of-week at a valid grid hour. `now.hour` is used when
    // it lands inside the grid (e.g. 6:30 PM reads as the 5 PM slot's day), and otherwise the
    // first slot, so the day shown is always the clock's own day — never the next teaching one.
    val hour = now.hour.takeIf(TimeGrid::isValidStartHour) ?: TimeGrid.FIRST_START_HOUR
    return SlotQuery(now.dayOfWeek, hour)
}

/**
 * The calendar date a Rooms-tab query is *asking about* — the thing the chips say, as a date.
 *
 * A [SlotQuery] carries a day-of-week, not a date, because the timetable is a weekly grid.
 * But "is this a teaching day?" is a question about dates: holidays, term boundaries and
 * working Saturdays are all keyed to the calendar. On the right-now view the answer is
 * simply today. When the student picked a slot, the day they mean is the **next occurrence**
 * of that day-of-week from today — today itself when the chips still say today, which is the
 * case the holiday bug lived in: picking an hour on a holiday Sunday kept the query on
 * Sunday, and the weekly grid happily reported every room free because no Sunday class
 * exists in it.
 *
 * Pure: reads the clock, touches neither the timetable nor any state.
 */
fun queriedDate(
    now: LocalDateTime,
    query: SlotQuery,
    isRightNow: Boolean,
): java.time.LocalDate =
    if (isRightNow) {
        now.toLocalDate()
    } else {
        val today = now.toLocalDate()
        if (today.dayOfWeek == query.dayOfWeek) {
            today
        } else {
            val daysAhead = (query.dayOfWeek.value - today.dayOfWeek.value + 7) % 7
            today.plusDays(daysAhead.toLong())
        }
    }

/**
 * Whether the day a Rooms-tab query is asking about is a teaching day — for *every* query,
 * picked or live.
 *
 * The right-now view is not special: a student who taps an hour chip on a holiday is still
 * asking about that holiday, and the honest answer is the same empty state the live view
 * shows, not a free/busy list computed from a weekly grid that simply has no classes on
 * Sundays. Teaching-day queries are unaffected: [AcademicCalendar.isTeachingDay] answers
 * for the date, whatever way the date was reached.
 */
fun isNonTeachingDay(
    now: LocalDateTime,
    calendar: AcademicCalendar,
    query: SlotQuery,
    isRightNow: Boolean,
): Boolean = !calendar.isTeachingDay(queriedDate(now, query, isRightNow))

/**
 * What a room's detail screen may claim about "now" — and, just as deliberately, what it
 * may not.
 *
 * The screen's week list is a *pattern*: "when is 204 ever free", answered by the weekly
 * grid, and a pattern makes no claim about any particular date. But the "Now:" line and
 * the "Nothing until …" line *do* — they are claims about today, and on a holiday
 * Wednesday the grid has nothing true to say about today either way: a booking it shows
 * is a class that is not running, and an empty hour is not "free" so much as "moot". So
 * the calendar gets a veto before either claim is made, exactly as the Rooms tab's list
 * already gives it one (see [isNonTeachingDay]).
 */
sealed interface NowClaim {

    /** Today teaches and the clock is inside a slot the timetable uses: the honest "Now:". */
    data class InSlot(val slot: SlotQuery) : NowClaim

    /**
     * Today teaches but the clock is outside its slots (early, late, or a weekday the
     * timetable does not use). The next slot is named by the date it means — the next
     * *teaching* day the grid covers, never a holiday the grid cannot see.
     */
    data class Later(val next: SlotQuery) : NowClaim

    /** Not a teaching day: no now-claim at all. The week pattern below is all there is. */
    data object NonTeachingDay : NowClaim

    /**
     * A teaching day, but the last one the calendar knows — the term ends before the grid
     * resumes. Naming a "next" slot would point at a date outside the term.
     */
    data object NoMoreSlots : NowClaim
}

/**
 * The "now" claim a room detail screen may make, or [NowClaim.NonTeachingDay] when the
 * calendar forbids one. Pure: reads the clock and the calendar, touches no timetable.
 */
fun roomNowClaim(
    now: LocalDateTime,
    calendar: AcademicCalendar,
    days: List<DayOfWeek>,
): NowClaim {
    val today = now.toLocalDate()
    if (!calendar.isTeachingDay(today)) return NowClaim.NonTeachingDay

    RoomAvailability.slotAt(now)?.takeIf { it.dayOfWeek in days }?.let { return NowClaim.InSlot(it) }

    // Today still counts: before the first slot, or a weekday the timetable does not use.
    if (now.dayOfWeek in days && now.hour < TimeGrid.FIRST_START_HOUR) {
        return NowClaim.Later(SlotQuery(now.dayOfWeek, TimeGrid.FIRST_START_HOUR))
    }

    // Otherwise the grid resumes on the next date that is both a teaching day and one the
    // timetable uses — walking dates, not weekdays, is what keeps a holiday Monday from
    // being named "Nothing until Monday, 9–10 AM". Bounded by the term's end: a date past
    // it is not "next", it is another semester.
    val nextDate = generateSequence(today.plusDays(1)) { it.plusDays(1) }
        .takeWhile { calendar.isWithinTerm(it) }
        .firstOrNull { calendar.isTeachingDay(it) && it.dayOfWeek in days }
        ?: return NowClaim.NoMoreSlots
    return NowClaim.Later(SlotQuery(nextDate.dayOfWeek, TimeGrid.FIRST_START_HOUR))
}
