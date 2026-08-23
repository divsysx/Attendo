package com.attendo.core.model

import java.time.DayOfWeek

/**
 * One cell of the printed timetable: a class, its cohort, and the room it sits in.
 *
 * This is the Rooms feature's whole data model. It is deliberately *not* a
 * [SessionPattern]: patterns belong to the student's own courses and drive
 * attendance, while bookings describe the whole faculty's use of rooms and drive
 * availability. The two are joined only when the student asks to seed their courses
 * from a section's timetable.
 *
 * The PDF splits a 2-hour block across two adjacent 1-hour columns. Those are merged
 * back into a single booking with `units = 2` at import time, so "203 is busy until
 * 11" is answerable without stitching rows together at query time.
 *
 * A few cells print no room at all — `SFL EE [RHS]`, the sports hour. That is still a
 * class the section attends and has to be offered when seeding courses, so it is
 * transcribed like any other cell with a null [room]. What it is *not* is an occupied
 * room, which is why every room-facing query in [com.attendo.core.engine.RoomAvailability]
 * drops it: reporting the sports hour against a room would make a free room look busy,
 * and inventing a room name to hang it on would be worse.
 */
data class RoomBooking(
    val dayOfWeek: DayOfWeek,
    /** Slot start on a 24-hour clock, 9..17. */
    val startHour: Int,
    /** Length in whole hours; 1 or 2 in practice. */
    val units: Int,
    /** Room as printed: "203", "R1", "Basement Lab"; null when the cell names none. */
    val room: String?,
    /** Subject acronym as printed, e.g. "ADEC". */
    val subject: String,
    /** Section as printed, e.g. "2nd Yr CSE-A". */
    val section: String,
    /** Faculty initials from the brackets, e.g. "RHS"; null when the cell omits them. */
    val faculty: String? = null,
    val kind: SessionKind = SessionKind.LECTURE,
    /** Batch tag for split practicals, e.g. "A1"; null for whole-section classes. */
    val batch: String? = null,
) {
    init {
        require(TimeGrid.fits(startHour, units)) {
            "booking $startHour +${units}h does not fit the teaching day"
        }
        // Null is "no room"; blank is a transcription slip that would show up as a room
        // called "" in the room list.
        require(room == null || room.isNotBlank()) { "a room must be named or absent, not blank" }
        require(subject.isNotBlank()) { "a booking must name a subject" }
    }

    /** False for the cells that print no room — the sports hour. */
    val occupiesARoom: Boolean get() = room != null

    /** Exclusive end hour: a 9 AM 2-hour block ends at 11. */
    val endHour: Int get() = startHour + units

    val slotLabel: String get() = TimeGrid.rangeLabel(startHour, units)

    /** The hours this booking occupies, e.g. [9, 10] for a 9-11 block. */
    val occupiedHours: IntRange get() = startHour until endHour

    fun occupiesHour(hour: Int): Boolean = hour in startHour until endHour

    /** True when this booking overlaps [startHour]..[startHour]+[units] at all. */
    fun overlaps(startHour: Int, units: Int): Boolean =
        this.startHour < startHour + units && startHour < endHour

    /** "ADEC (P) — A1" style description for the room detail screen. */
    val label: String get() = buildString {
        append(subject)
        append(kind.timetableTag)
        batch?.let { append(" — ").append(it) }
    }

    /** "2nd Yr CSE-A · RHS" for the second line of a list row. */
    val subtitle: String get() = listOfNotNull(section, faculty).joinToString(" · ")
}
