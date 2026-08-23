package com.attendo.core.engine

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
