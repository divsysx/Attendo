package com.attendo.core.engine

import com.attendo.core.model.RoomBooking
import com.attendo.core.model.SessionKind
import com.attendo.core.model.TimeGrid
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** A day-and-hour the availability screens are asking about. */
data class SlotQuery(val dayOfWeek: DayOfWeek, val startHour: Int) {
    init {
        require(TimeGrid.isValidStartHour(startHour)) {
            "$startHour is outside the ${TimeGrid.FIRST_START_HOUR}-${TimeGrid.LAST_END_HOUR} grid"
        }
    }

    val slotLabel: String get() = TimeGrid.slotLabel(startHour)
}

/**
 * A room's state in one slot.
 *
 * [freeUntilHour] and [busyUntilHour] are what turn a yes/no answer into a useful
 * one — "204 is free until 2 PM" is what a student actually wants when looking for
 * somewhere to sit, and it is why bookings are merged into runs rather than queried
 * hour by hour.
 */
data class RoomStatus(
    val room: String,
    /** The hour this status was computed for. */
    val queriedFrom: Int,
    val isFree: Boolean,
    /** Bookings covering [queriedFrom]; more than one means a timetable clash. */
    val bookings: List<RoomBooking>,
    /** Exclusive hour the current free run ends, when free. 18 means "rest of the day". */
    val freeUntilHour: Int?,
    /** Exclusive hour the room frees up, when busy. */
    val busyUntilHour: Int?,
) {
    /** Whole hours the room stays free from [queriedFrom] onwards; 0 when busy. */
    val freeHours: Int get() = if (isFree && freeUntilHour != null) freeUntilHour - queriedFrom else 0

    /**
     * [bookings] with the cohorts of a joint class folded into one line.
     *
     * Computed once and kept: this is read from inside a list item's composition, so a
     * plain getter re-grouped every visible room's bookings on every frame of a scroll.
     */
    val groups: List<BookingGroup> by lazy { RoomAvailability.groupBookings(bookings) }

    /** Hours until the room frees up; 0 when already free. */
    val busyHours: Int get() = if (!isFree && busyUntilHour != null) busyUntilHour - queriedFrom else 0

    /** "Free all day", "Free until 2 PM", "Busy until 11 AM". */
    val summary: String
        get() = when {
            isFree && freeUntilHour == TimeGrid.LAST_END_HOUR &&
                queriedFrom == TimeGrid.FIRST_START_HOUR -> "Free all day"
            isFree && freeUntilHour == TimeGrid.LAST_END_HOUR -> "Free for the rest of the day"
            isFree -> "Free until ${clockLabel(freeUntilHour!!)}"
            busyUntilHour == TimeGrid.LAST_END_HOUR -> "Busy for the rest of the day"
            else -> "Busy until ${clockLabel(busyUntilHour!!)}"
        }

    private fun clockLabel(hour24: Int): String {
        val clock = (hour24 % 12).let { if (it == 0) 12 else it }
        return "$clock ${if (hour24 < 12) "AM" else "PM"}"
    }
}

/**
 * One class, and every cohort that sits in it.
 *
 * A joint class — a 3rd-year elective, an EE workshop both EE-A and EE-B attend — is
 * printed on each cohort's page of the timetable, so the imported data holds one row
 * per cohort. In the room it is one class, not a clash, so the screens show a single
 * line naming all the sections. Genuine clashes (different subjects, same room and
 * hour) survive grouping and stay visible as separate lines.
 */
data class BookingGroup(
    /** Any one of the grouped rows; they differ only by section. */
    val booking: RoomBooking,
    val sections: List<String>,
) {
    val isShared: Boolean get() = sections.size > 1

    /** "3rd Yr ECE-A, 3rd Yr ECE-B" */
    val sectionsLabel: String get() = sections.filter { it.isNotBlank() }.joinToString(", ")

    /** "3rd Yr ECE-A, 3rd Yr ECE-B · GS" */
    val subtitle: String
        get() = listOfNotNull(sectionsLabel.ifBlank { null }, booking.faculty).joinToString(" · ")
}

/** A contiguous stretch of free hours in one room on one day. */
data class FreeRun(
    /** Inclusive first free hour. */
    val startHour: Int,
    /** Exclusive end hour. */
    val endHour: Int,
) {
    init {
        require(endHour > startHour) { "empty free run $startHour..$endHour" }
    }

    val hours: Int get() = endHour - startHour

    val label: String get() = TimeGrid.rangeLabel(startHour, hours)

    fun contains(hour: Int): Boolean = hour in startHour until endHour
}

/** One room's free time across the week, backing the room-first browse screen. */
data class RoomWeek(
    val room: String,
    /** Free runs per weekday, in slot order. A day with no free time maps to an empty list. */
    val freeByDay: Map<DayOfWeek, List<FreeRun>>,
    val bookingsByDay: Map<DayOfWeek, List<RoomBooking>>,
) {
    /** Total free hours across [freeByDay]; read from composition, so computed once. */
    val totalFreeHours: Int by lazy { freeByDay.values.sumOf { runs -> runs.sumOf { it.hours } } }

    val isNeverBooked: Boolean get() = bookingsByDay.values.all { it.isEmpty() }

    fun freeOn(day: DayOfWeek): List<FreeRun> = freeByDay[day].orEmpty()

    fun bookingsOn(day: DayOfWeek): List<RoomBooking> = bookingsByDay[day].orEmpty()
}

/**
 * Answers the three room questions from a flat list of [RoomBooking]s.
 *
 * 1. **"Right now"** — [slotAt] the device clock, then [freeRoomsAt].
 * 2. **Room-first** — [weekFor] gives one room's free time day by day.
 * 3. **Day + time grid** — [statusesAt] with a chosen slot.
 *
 * The room list is derived from the bookings themselves rather than hardcoded, so
 * re-importing next semester's timetable picks up new rooms with no code change.
 * Everything here is pure; nothing reads the clock — [slotAt] takes the time as an
 * argument so "what's free at 3 PM on Thursday?" runs through the same path as
 * "what's free right now?".
 *
 * A class printed without a room — the sports hour — is not an occupied room, so
 * [rooms], [bookingsAt] and [statusesAt] leave it out and every room-keyed query
 * misses it for free. It is deliberately still visible to [sections] and [forSection],
 * which is where the seeder looks: the student attends it, whatever room it isn't in.
 */
object RoomAvailability {

    /** Weekdays the timetable actually uses, Monday first. */
    fun daysInUse(bookings: Iterable<RoomBooking>): List<DayOfWeek> =
        bookings.map { it.dayOfWeek }.distinct().sortedBy { it.value }

    /**
     * Every room mentioned, ordered the way a person reads a room list: numbered
     * rooms ascending (203, 204, 211, 312), then named ones alphabetically
     * (Basement Lab, R1, R2). Plain lexicographic order would put "312" before "9".
     */
    fun rooms(bookings: Iterable<RoomBooking>): List<String> =
        bookings.mapNotNull { it.room }.distinct().sortedWith(ROOM_ORDER)

    /** Bookings covering [query]'s hour, by room then section. */
    fun bookingsAt(bookings: Iterable<RoomBooking>, query: SlotQuery): List<RoomBooking> =
        bookings
            .filter { it.occupiesARoom && it.dayOfWeek == query.dayOfWeek && it.occupiesHour(query.startHour) }
            .sortedWith(BOOKING_ORDER)

    /**
     * Folds rows that describe the same class into one [BookingGroup] each.
     *
     * Two rows are the same class when everything but the section matches — same room,
     * hour, length, subject, faculty, kind and batch. That is exactly the shape a joint
     * class takes once every cohort's page has been transcribed.
     */
    fun groupBookings(bookings: Iterable<RoomBooking>): List<BookingGroup> =
        bookings
            .groupBy { keyOf(it) }
            .map { (_, rows) -> BookingGroup(rows.first(), rows.map { it.section }.distinct().sorted()) }
            .sortedWith(GROUP_ORDER)

    /** [bookingsAt], with joint classes folded together — what a slot's detail list shows. */
    fun groupsAt(bookings: Iterable<RoomBooking>, query: SlotQuery): List<BookingGroup> =
        groupBookings(bookingsAt(bookings, query))

    /**
     * The state of every room in [query]'s slot: free rooms first, each group in
     * reading order.
     *
     * [allRooms] defaults to the rooms present in [bookings]; pass it explicitly to
     * include a room that happens to have no bookings at all this semester.
     */
    fun statusesAt(
        bookings: Iterable<RoomBooking>,
        query: SlotQuery,
        allRooms: List<String> = rooms(bookings),
    ): List<RoomStatus> {
        val byRoom = bookings
            .filter { it.occupiesARoom && it.dayOfWeek == query.dayOfWeek }
            .groupBy { it.room }
        return allRooms
            .map { room -> statusOf(room, byRoom[room].orEmpty(), query.startHour) }
            .sortedWith(FREE_FIRST)
    }

    /** Just the free rooms in [query]'s slot, longest free stretch first. */
    fun freeRoomsAt(
        bookings: Iterable<RoomBooking>,
        query: SlotQuery,
        allRooms: List<String> = rooms(bookings),
    ): List<RoomStatus> =
        statusesAt(bookings, query, allRooms)
            .filter { it.isFree }
            .sortedWith(LONGEST_FREE_FIRST)

    /** The rooms in use in [query]'s slot, for the "busy" half of the grid. */
    fun busyRoomsAt(
        bookings: Iterable<RoomBooking>,
        query: SlotQuery,
        allRooms: List<String> = rooms(bookings),
    ): List<RoomStatus> = statusesAt(bookings, query, allRooms).filterNot { it.isFree }

    /** One room's state in one slot. */
    fun statusAt(bookings: Iterable<RoomBooking>, room: String, query: SlotQuery): RoomStatus =
        statusOf(
            room = room,
            dayBookings = bookings.filter { it.dayOfWeek == query.dayOfWeek && it.room == room },
            hour = query.startHour,
        )

    /** Contiguous free stretches for one room on one day. */
    fun freeRuns(bookings: Iterable<RoomBooking>, room: String, day: DayOfWeek): List<FreeRun> =
        freeRunsFrom(occupiedHours(bookings.filter { it.room == room && it.dayOfWeek == day }))

    /**
     * A whole week of free time for one room — the room-first browse screen.
     *
     * [days] defaults to Monday–Friday plus Saturday when the timetable uses it, so a
     * room is never reported "free all Saturday" for a faculty that has no Saturday
     * classes at all.
     */
    fun weekFor(
        bookings: Iterable<RoomBooking>,
        room: String,
        days: List<DayOfWeek> = weekdaysFor(bookings),
    ): RoomWeek {
        val byDay = bookings.filter { it.room == room }.groupBy { it.dayOfWeek }
        return RoomWeek(
            room = room,
            freeByDay = days.associateWith { day -> freeRunsFrom(occupiedHours(byDay[day].orEmpty())) },
            bookingsByDay = days.associateWith { day ->
                byDay[day].orEmpty().sortedBy { it.startHour }
            },
        )
    }

    /** Weeks for every room, in reading order — the room list screen. */
    fun allWeeks(
        bookings: Iterable<RoomBooking>,
        days: List<DayOfWeek> = weekdaysFor(bookings),
    ): List<RoomWeek> {
        val materialised = bookings.toList()
        return rooms(materialised).map { weekFor(materialised, it, days) }
    }

    /**
     * Which slot a moment falls in, or null when it is outside teaching hours —
     * before 9 AM, from 6 PM, or on a Sunday. The "right now" view uses the null to
     * offer [nextSlot] instead of an empty grid.
     */
    fun slotAt(dateTime: LocalDateTime): SlotQuery? = slotAt(dateTime.dayOfWeek, dateTime.toLocalTime())

    fun slotAt(day: DayOfWeek, time: LocalTime): SlotQuery? = when {
        day == DayOfWeek.SUNDAY -> null
        !TimeGrid.isValidStartHour(time.hour) -> null
        else -> SlotQuery(day, time.hour)
    }

    /**
     * The next slot at or after [dateTime], rolling to the next teaching day's 9 AM
     * when the day is over. [days] bounds which weekdays count as teaching days.
     */
    fun nextSlot(
        dateTime: LocalDateTime,
        days: List<DayOfWeek> = MONDAY_TO_SATURDAY,
    ): SlotQuery {
        slotAt(dateTime)?.takeIf { it.dayOfWeek in days }?.let { return it }
        if (dateTime.dayOfWeek in days && dateTime.hour < TimeGrid.FIRST_START_HOUR) {
            return SlotQuery(dateTime.dayOfWeek, TimeGrid.FIRST_START_HOUR)
        }
        var day = dateTime.dayOfWeek
        repeat(7) {
            day = day.plus(1L)
            if (day in days) return SlotQuery(day, TimeGrid.FIRST_START_HOUR)
        }
        return SlotQuery(DayOfWeek.MONDAY, TimeGrid.FIRST_START_HOUR)
    }

    /** Monday–Friday, plus Saturday when the timetable uses it. */
    fun weekdaysFor(bookings: Iterable<RoomBooking>): List<DayOfWeek> =
        if (bookings.any { it.dayOfWeek == DayOfWeek.SATURDAY }) MONDAY_TO_SATURDAY
        else MONDAY_TO_FRIDAY

    /** Sections the timetable covers, for the section picker. */
    fun sections(bookings: Iterable<RoomBooking>): List<String> =
        bookings.map { it.section }.distinct().sorted()

    /** One section's bookings in weekly order — the basis for seeding courses. */
    fun forSection(bookings: Iterable<RoomBooking>, section: String): List<RoomBooking> =
        bookings
            .filter { it.section == section }
            .sortedWith(compareBy({ it.dayOfWeek.value }, { it.startHour }))

    // ---- internals ----------------------------------------------------------

    /** Everything that makes a class *this* class — section deliberately excluded. */
    private data class ClassKey(
        val dayOfWeek: DayOfWeek,
        val startHour: Int,
        val units: Int,
        val room: String?,
        val subject: String,
        val faculty: String?,
        val kind: SessionKind,
        val batch: String?,
    )

    private fun keyOf(booking: RoomBooking) = ClassKey(
        dayOfWeek = booking.dayOfWeek,
        startHour = booking.startHour,
        units = booking.units,
        room = booking.room,
        subject = booking.subject,
        faculty = booking.faculty,
        kind = booking.kind,
        batch = booking.batch,
    )

    private fun statusOf(room: String, dayBookings: List<RoomBooking>, hour: Int): RoomStatus {
        val covering = dayBookings.filter { it.occupiesHour(hour) }.sortedBy { it.section }
        val occupied = occupiedHours(dayBookings)
        val free = covering.isEmpty()
        // Walk forward while the state holds, so the answer is "until 2 PM" rather
        // than "for this hour".
        var end = hour
        while (end < TimeGrid.LAST_END_HOUR && (end in occupied) != free) end++
        return RoomStatus(
            room = room,
            queriedFrom = hour,
            isFree = free,
            bookings = covering,
            freeUntilHour = end.takeIf { free },
            busyUntilHour = end.takeUnless { free },
        )
    }

    private fun occupiedHours(bookings: List<RoomBooking>): Set<Int> =
        bookings.flatMapTo(mutableSetOf()) { it.occupiedHours }

    private fun freeRunsFrom(occupied: Set<Int>): List<FreeRun> {
        val runs = mutableListOf<FreeRun>()
        var runStart: Int? = null
        for (hour in TimeGrid.FIRST_START_HOUR..TimeGrid.LAST_START_HOUR) {
            if (hour in occupied) {
                runStart?.let { runs += FreeRun(it, hour) }
                runStart = null
            } else if (runStart == null) {
                runStart = hour
            }
        }
        runStart?.let { runs += FreeRun(it, TimeGrid.LAST_END_HOUR) }
        return runs
    }

    private val MONDAY_TO_FRIDAY: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    private val MONDAY_TO_SATURDAY: List<DayOfWeek> = MONDAY_TO_FRIDAY + DayOfWeek.SATURDAY

    /**
     * Numbered rooms first, ascending; then everything else alphabetically. Keeps
     * "204" before "312" and both before "Basement Lab". A class with no room sorts
     * last; the queries that use this have already dropped those, so it never comes up.
     */
    internal val ROOM_ORDER: Comparator<String?> = Comparator { a, b ->
        val na = a?.toIntOrNull()
        val nb = b?.toIntOrNull()
        when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            a == null -> if (b == null) 0 else 1
            b == null -> -1
            else -> a.compareTo(b)
        }
    }

    private val FREE_FIRST: Comparator<RoomStatus> =
        compareByDescending<RoomStatus> { it.isFree }
            .thenComparator { a, b -> ROOM_ORDER.compare(a.room, b.room) }

    private val LONGEST_FREE_FIRST: Comparator<RoomStatus> =
        compareByDescending<RoomStatus> { it.freeHours }
            .thenComparator { a, b -> ROOM_ORDER.compare(a.room, b.room) }

    private val BOOKING_ORDER: Comparator<RoomBooking> =
        Comparator<RoomBooking> { a, b -> ROOM_ORDER.compare(a.room, b.room) }
            .thenBy { it.section }
            .thenBy { it.startHour }

    private val GROUP_ORDER: Comparator<BookingGroup> =
        Comparator<BookingGroup> { a, b -> ROOM_ORDER.compare(a.booking.room, b.booking.room) }
            .thenBy { it.booking.startHour }
            .thenBy { it.booking.subject }
            .thenBy { it.sectionsLabel }
}
