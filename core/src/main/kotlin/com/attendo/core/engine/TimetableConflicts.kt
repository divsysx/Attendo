package com.attendo.core.engine

import com.attendo.core.model.RoomBooking
import java.time.DayOfWeek

/**
 * What a room — or a teacher — holding more than one booking at one hour turns out to be.
 */
enum class ConflictKind {
    /**
     * One class, printed once on each cohort's page.
     *
     * The grid has no other way to say "these two cohorts sit together": a joint elective, a
     * workshop two branches attend, a practical two sections share. Read as rows it looks like
     * a double booking and is not one — the room holds them all, which is the point. Names
     * differ across the pages only in the cohort they were printed under, so the rows fold
     * back into the single class they describe and nothing needs looking at.
     */
    CLUBBED,

    /**
     * More than one booking for at least one cohort, or more than one class in the room.
     *
     * Not foldable, and so not decided here. Whether it is a transcription slip or something
     * the faculty really printed, the app cannot schedule around it and a student cannot
     * attend both, so it is reported for a person to settle rather than quietly resolved.
     */
    GENUINE,
}

/**
 * One hour, or run of hours, where the timetable is over-subscribed.
 *
 * [endHour] is inclusive. A run of hours is one conflict rather than one per hour, so a
 * two-hour block reads as a single line — the way it is printed.
 */
data class TimetableConflict(
    val dayOfWeek: DayOfWeek,
    val startHour: Int,
    val endHour: Int,
    val kind: ConflictKind,
    /** The room the rows are in, or null when this is a faculty conflict. */
    val room: String?,
    /** The teacher the rows want, or null when this is a room conflict. */
    val faculty: String?,
    val bookings: List<RoomBooking>,
) {
    val sections: List<String> get() = bookings.map { it.section }.distinct().sorted()

    val subjects: List<String> get() = bookings.map { it.subject }.distinct().sorted()

    /**
     * The cohorts this cell has two bookings for — empty for a clubbed class.
     *
     * What makes a [ConflictKind.GENUINE] finding genuine, and the only thing about it a
     * reader cannot see from the list of the room's occupants.
     */
    val repeated: List<String>
        get() = bookings
            .groupingBy { it.section }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

    /** "Tue" — the day, abbreviated the way the day rows of the grid are. */
    val dayLabel: String
        get() = dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

    /** "11 AM–1 PM", or "3 PM" for a single hour. */
    val slotLabel: String
        get() = if (endHour == startHour) clock(startHour) else "${clock(startHour)}–${clock(endHour + 1)}"

    /**
     * One line naming the place, the class and everyone in it.
     *
     * "Tue 3 PM, Room 311: ECFC — 4th Yr CSE-A twice (4th Yr CSE-A, 4th Yr CSE-B, …)", or for
     * a clubbed class "Tue 2 PM, Room 311: ECFC (4th Yr CSE-A, …)".
     */
    val label: String
        get() = buildString {
            val what = room?.let { "Room $it" } ?: "Faculty ${faculty.orEmpty()}"
            append(dayLabel).append(' ').append(slotLabel).append(", ").append(what).append(": ")
            append(subjects.joinToString(" vs "))
            repeated.takeIf { it.isNotEmpty() }?.let { twice ->
                append(" — ").append(twice.joinToString(", ")).append(" twice")
            }
            append(" (").append(sections.joinToString(", ")).append(')')
        }

    private fun clock(hour24: Int): String {
        val hour = (hour24 % 12).let { if (it == 0) 12 else it }
        return "$hour ${if (hour24 < 12) "AM" else "PM"}"
    }
}

/**
 * Reads the room timetable back for the ways it can name more than one booking at an hour.
 *
 * This exists because the timetable is transcribed from a printed grid, and a printed grid
 * says "one class" in a way that survives transcription only as several rows: each cohort's
 * page carries the class, so a lecture both ECE-A and ECE-B attend arrives as two rows naming
 * one room and one hour. Counting rows therefore reports the timetable's normal way of
 * expressing a shared class as an error, which is worse than not checking at all — a check
 * that cries wolf on every elective is a check nobody reads.
 *
 * So the two cases are told apart by what the rows are, not how many: if the cell reduces to
 * one class with each cohort listed once, it is that class, printed once per page — see
 * [ConflictKind] for why folding those is safe. Anything else is handed back for a person.
 *
 * ### What makes two rows the same class
 *
 * Everything the class *is* — day, room, subject, teacher, kind — and nothing about the page
 * it was printed on.
 *
 * The **section** is absent because two pages printing one class is the whole question. The
 * **batch** is absent because a shared class has no single batch to be in: 4th Yr ECE-A's
 * DASIC practical and 4th Yr ECE-B's are one class in Room 314, and each section numbers its
 * own half differently — A1 on one page, B1 on the other. `A1 == B1` is false and the two rows
 * are still one class, so comparing batch would report every cross-section clubbed cohort as a
 * conflict, which is the mistake this file exists to correct. Within one section the batch
 * really is part of the identity, but two batches of one subject in one room at one hour are
 * two different hours apart in the printed grid, and the hour catches them.
 *
 * The **length** is absent because the pages do not always agree on it, and disagreement about
 * how long a class runs is not a second class. It is, however, not nothing: a row that repeats
 * an hour another row already covers leaves the cohort with two bookings for one class, which
 * is exactly the case [ConflictKind.GENUINE] is for. Leaving the length out of the key is what
 * lets that show up as one class listed twice for one section, rather than as two classes.
 */
object TimetableConflicts {

    /** Every room and faculty booking clash in [bookings], clubbed classes marked as such. */
    fun inUse(bookings: Iterable<RoomBooking>): List<TimetableConflict> {
        val materialised = bookings.toList()
        return (roomConflicts(materialised) + facultyConflicts(materialised))
            .sortedWith(
                compareBy(
                    { it.dayOfWeek.value },
                    { it.startHour },
                    { it.room.orEmpty() },
                    { it.faculty.orEmpty() },
                ),
            )
    }

    /** Just the ones that need a person — what a validation run is asking about. */
    fun genuine(bookings: Iterable<RoomBooking>): List<TimetableConflict> =
        inUse(bookings).filter { it.kind == ConflictKind.GENUINE }

    /**
     * Every code in a faculty cell, which may name two teachers: `[AKT / SG]`.
     *
     * A clubbed class taught by a pair is one class for both codes, and asks the same question
     * of each — so each is checked on its own.
     */
    fun facultyOf(booking: RoomBooking): List<String> =
        booking.faculty
            ?.split('/', '&')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun roomConflicts(bookings: List<RoomBooking>): List<TimetableConflict> =
        conflictsIn(
            bookings = bookings,
            isRoom = true,
            key = { row -> row.room?.let { listOf(it) }.orEmpty() },
        )

    /**
     * The rooms [bookings] span, with "no room" counting as a place of its own.
     *
     * Only asked of faculty conflicts: a teacher with two overlapping bookings in *one* room
     * is a room problem already reported as one, and saying it twice would double every line
     * of the report. A teacher in two rooms at once is the thing only this view can see.
     */
    private fun facultyConflicts(bookings: List<RoomBooking>): List<TimetableConflict> =
        conflictsIn(bookings = bookings, isRoom = false, key = ::facultyOf)
            .filter { it.bookings.map { booking -> booking.room }.distinct().size > 1 }

    /**
     * Conflicts at one kind of key — a room, or one teacher — over [bookings].
     *
     * [key] returns nothing for a row that does not take part: a class with no room occupies
     * no room, so the sports hour is never reported as a clash in a room it is not in.
     */
    private fun conflictsIn(
        bookings: List<RoomBooking>,
        isRoom: Boolean,
        key: (RoomBooking) -> List<String>,
    ): List<TimetableConflict> = buildList {
        bookings
            .flatMap { row -> key(row).map { it to row } }
            .groupBy({ it.first }, { it.second })
            .forEach { (value, rows) ->
                // A cell is one day's hour, not the hour across the week: room 203 at nine
                // is five different days' classes, and comparing them would report the
                // timetable's own weekly rhythm as a permanent clash.
                rows.flatMap { row -> row.occupiedHours.map { hour -> Cell(row.dayOfWeek, hour) to row } }
                    .groupBy({ it.first }, { it.second })
                    .filterValues { covering -> covering.size > 1 }
                    .entries
                    .sortedBy { it.key }
                    .forEach { (cell, covering) ->
                        val conflict = classify(cell.hour, value, isRoom, covering)
                        // A two-hour block is one conflict, not two: extend the run already
                        // open when this is the same problem, with the same rows.
                        val previous = lastOrNull()
                        if (previous != null &&
                            previous.dayOfWeek == conflict.dayOfWeek &&
                            previous.endHour == conflict.startHour - 1 &&
                            previous.kind == conflict.kind &&
                            previous.room == conflict.room &&
                            previous.faculty == conflict.faculty &&
                            previous.bookings == conflict.bookings
                        ) {
                            set(lastIndex, previous.copy(endHour = conflict.endHour))
                        } else {
                            add(conflict)
                        }
                    }
            }
    }

    private fun classify(
        hour: Int,
        value: String,
        isRoom: Boolean,
        covering: List<RoomBooking>,
    ): TimetableConflict {        val oneClass = covering.distinctBy { it.sameClassKey() }.size == 1
        val onceEach = covering.map { it.section }.distinct().size == covering.size
        return TimetableConflict(
            dayOfWeek = covering.first().dayOfWeek,
            startHour = hour,
            endHour = hour,
            // Foldable only when the cell is one class and no cohort is in it twice.
            kind = if (oneClass && onceEach) ConflictKind.CLUBBED else ConflictKind.GENUINE,
            room = value.takeIf { isRoom },
            faculty = value.takeUnless { isRoom },
            bookings = covering.sortedBy { it.section },
        )
    }

    /**
     * Everything that makes a row *that class* rather than this cohort's copy of it.
     *
     * See this object's header for why the section, the batch and the length are all absent.
     */
    private fun RoomBooking.sameClassKey(): List<Any?> =
        listOf(dayOfWeek, room, subject, faculty, kind)

    /** One square of the grid: a day and an hour, in reading order. */
    private data class Cell(val day: DayOfWeek, val hour: Int) : Comparable<Cell> {
        override fun compareTo(other: Cell): Int =
            compareValuesBy(this, other, { it.day.value }, { it.hour })
    }
}
