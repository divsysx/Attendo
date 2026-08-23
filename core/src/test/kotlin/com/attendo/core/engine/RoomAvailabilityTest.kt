package com.attendo.core.engine

import com.attendo.core.model.RoomBooking
import com.attendo.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Room availability: "what's free right now", one room's week, and the day+time grid.
 *
 * The fixture is a miniature of the real timetable — a 2-hour block, back-to-back
 * single hours, two batches in different rooms, and a Saturday class.
 */
class RoomAvailabilityTest {

    private fun booking(
        day: DayOfWeek = DayOfWeek.MONDAY,
        startHour: Int,
        units: Int = 1,
        room: String?,
        subject: String = "ADEC",
        section: String = "2nd Yr CSE-A",
        faculty: String? = "AP",
        kind: SessionKind = SessionKind.LECTURE,
        batch: String? = null,
    ) = RoomBooking(
        dayOfWeek = day,
        startHour = startHour,
        units = units,
        room = room,
        subject = subject,
        section = section,
        faculty = faculty,
        kind = kind,
        batch = batch,
    )

    /**
     * Monday: 204 busy 9-11 (a 2-hour block) and 2-3; 211 busy 9-10 and 10-11;
     * 312 free all day. Thursday: 204 busy 11-12. Saturday: 211 busy 9-10.
     */
    private val timetable = listOf(
        booking(startHour = 9, units = 2, room = "204"),
        booking(startHour = 14, room = "204", subject = "CN"),
        booking(startHour = 9, room = "211", subject = "TOC", section = "3rd Yr CSE-A"),
        booking(startHour = 10, room = "211", subject = "DMW", section = "3rd Yr CSE-A"),
        booking(day = DayOfWeek.THURSDAY, startHour = 11, room = "204", subject = "AIML"),
        booking(day = DayOfWeek.SATURDAY, startHour = 9, room = "211", subject = "EVS-2"),
        // 312 is only ever mentioned on Friday, so it must still appear in the list.
        booking(day = DayOfWeek.FRIDAY, startHour = 16, room = "312", subject = "EH"),
    )

    // ---- the room list ------------------------------------------------------

    @Test
    fun `rooms are listed numerically before names`() {
        val bookings = timetable + listOf(
            booking(startHour = 12, room = "R1", subject = "PSA"),
            booking(startHour = 12, room = "Basement Lab", subject = "ADEC", kind = SessionKind.PRACTICAL),
            booking(startHour = 15, room = "203", subject = "NN"),
        )

        // Lexicographic order would give 203, 204, 211, 312 mixed among R1 and
        // "Basement Lab" — and would put "312" before "9" if a single-digit room
        // ever appeared.
        assertEquals(
            listOf("203", "204", "211", "312", "Basement Lab", "R1"),
            RoomAvailability.rooms(bookings),
        )
    }

    @Test
    fun `rooms with no bookings can still be listed explicitly`() {
        val statuses = RoomAvailability.statusesAt(
            timetable,
            SlotQuery(DayOfWeek.MONDAY, 9),
            allRooms = listOf("204", "211", "999"),
        )

        val neverBooked = statuses.single { it.room == "999" }
        assertTrue(neverBooked.isFree)
        assertEquals("Free all day", neverBooked.summary)
    }

    @Test
    fun `sections and days come from the data`() {
        assertEquals(
            listOf("2nd Yr CSE-A", "3rd Yr CSE-A"),
            RoomAvailability.sections(timetable),
        )
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            RoomAvailability.daysInUse(timetable),
        )
    }

    // ---- a slot's occupancy -------------------------------------------------

    @Test
    fun `a two hour block occupies both of its hours`() {
        val block = booking(startHour = 9, units = 2, room = "204")

        assertTrue(block.occupiesHour(9))
        assertTrue(block.occupiesHour(10))
        assertFalse(block.occupiesHour(11))
        assertEquals(11, block.endHour)
        assertEquals("9–11 AM", block.slotLabel)
        assertEquals(listOf(9, 10), block.occupiedHours.toList())
    }

    @Test
    fun `the second hour of a two hour block still reads as busy`() {
        // The PDF splits the block across two columns; merging them at import must not
        // lose the second hour.
        val at10 = RoomAvailability.statusAt(timetable, "204", SlotQuery(DayOfWeek.MONDAY, 10))

        assertFalse(at10.isFree)
        assertEquals("ADEC", at10.bookings.single().subject)
        assertEquals(11, at10.busyUntilHour)
    }

    @Test
    fun `free rooms at a slot exclude the busy ones`() {
        val free = RoomAvailability.freeRoomsAt(timetable, SlotQuery(DayOfWeek.MONDAY, 9))

        assertEquals(listOf("312"), free.map { it.room })
        assertEquals("Free all day", free.single().summary)
    }

    @Test
    fun `by eleven the block has ended and the room is free again`() {
        val free = RoomAvailability.freeRoomsAt(timetable, SlotQuery(DayOfWeek.MONDAY, 11))

        assertEquals(listOf("204", "211", "312"), free.map { it.room }.sorted())
        assertEquals(
            "Free until 2 PM",
            free.single { it.room == "204" }.summary,
        )
        assertEquals("Free for the rest of the day", free.single { it.room == "211" }.summary)
    }

    @Test
    fun `statuses list free rooms first, each group in reading order`() {
        val statuses = RoomAvailability.statusesAt(timetable, SlotQuery(DayOfWeek.MONDAY, 10))

        assertEquals(listOf("312", "204", "211"), statuses.map { it.room })
        assertTrue(statuses.first().isFree)
        assertFalse(statuses.last().isFree)
    }

    @Test
    fun `free rooms are ordered by how long they stay free`() {
        val bookings = listOf(
            booking(startHour = 10, room = "204"),   // free 9-10 only
            booking(startHour = 12, room = "211"),   // free 9-12
            booking(startHour = 15, room = "312"),   // free 9-15
        )

        val free = RoomAvailability.freeRoomsAt(bookings, SlotQuery(DayOfWeek.MONDAY, 9))

        assertEquals(listOf("312", "211", "204"), free.map { it.room })
        assertEquals(listOf(6, 3, 1), free.map { it.freeHours })
    }

    @Test
    fun `busy rooms report who is in them`() {
        val busy = RoomAvailability.busyRoomsAt(timetable, SlotQuery(DayOfWeek.MONDAY, 9))

        assertEquals(listOf("204", "211"), busy.map { it.room })
        assertEquals("Busy until 11 AM", busy.first().summary)
        assertEquals("ADEC", busy.first().bookings.single().subject)
        assertEquals("2nd Yr CSE-A · AP", busy.first().bookings.single().subtitle)
    }

    @Test
    fun `back to back single hours read as one busy stretch`() {
        // 211 has separate 9-10 and 10-11 bookings; the answer should be "busy until
        // 11", not "busy until 10".
        val status = RoomAvailability.statusAt(timetable, "211", SlotQuery(DayOfWeek.MONDAY, 9))

        assertFalse(status.isFree)
        assertEquals(11, status.busyUntilHour)
        assertEquals(2, status.busyHours)
    }

    @Test
    fun `a clash surfaces both bookings rather than hiding one`() {
        val bookings = listOf(
            booking(startHour = 9, room = "204", subject = "ADEC", section = "2nd Yr CSE-A"),
            booking(startHour = 9, room = "204", subject = "TOC", section = "3rd Yr CSE-A"),
        )

        val status = RoomAvailability.statusAt(bookings, "204", SlotQuery(DayOfWeek.MONDAY, 9))

        assertEquals(listOf("ADEC", "TOC"), status.bookings.map { it.subject })
    }

    @Test
    fun `bookings at a slot are listed by room then section`() {
        val bookings = listOf(
            booking(startHour = 9, room = "211", subject = "TOC", section = "3rd Yr CSE-B"),
            booking(startHour = 9, room = "204", subject = "CN", section = "2nd Yr ECE-A"),
            booking(startHour = 9, room = "211", subject = "DMW", section = "3rd Yr CSE-A"),
        )

        val at9 = RoomAvailability.bookingsAt(bookings, SlotQuery(DayOfWeek.MONDAY, 9))

        assertEquals(listOf("204", "211", "211"), at9.map { it.room })
        assertEquals(listOf("CN", "DMW", "TOC"), at9.map { it.subject })
    }

    @Test
    fun `a different day has a different answer`() {
        assertTrue(RoomAvailability.statusAt(timetable, "204", SlotQuery(DayOfWeek.THURSDAY, 9)).isFree)
        assertFalse(RoomAvailability.statusAt(timetable, "204", SlotQuery(DayOfWeek.THURSDAY, 11)).isFree)
    }

    // ---- one room's week ----------------------------------------------------

    @Test
    fun `a room's free time is merged into contiguous runs`() {
        val week = RoomAvailability.weekFor(timetable, "204")

        // Busy 9-11 and 2-3, so free 11-2 and 3-6.
        assertEquals(
            listOf("11 AM–2 PM", "3–6 PM"),
            week.freeOn(DayOfWeek.MONDAY).map { it.label },
        )
        assertEquals(listOf(11 to 14, 15 to 18), week.freeOn(DayOfWeek.MONDAY).map { it.startHour to it.endHour })
    }

    @Test
    fun `a completely free day is one run covering the grid`() {
        val week = RoomAvailability.weekFor(timetable, "312")

        val tuesday = week.freeOn(DayOfWeek.TUESDAY).single()
        assertEquals(9, tuesday.startHour)
        assertEquals(18, tuesday.endHour)
        assertEquals(9, tuesday.hours)
        assertEquals("9 AM–6 PM", tuesday.label)
    }

    @Test
    fun `a day booked from the first hour starts its free run after the class`() {
        val week = RoomAvailability.weekFor(timetable, "211")

        assertEquals(listOf("11 AM–6 PM"), week.freeOn(DayOfWeek.MONDAY).map { it.label })
    }

    @Test
    fun `saturday appears only when the timetable uses it`() {
        val withSaturday = RoomAvailability.weekFor(timetable, "211")
        val weekdaysOnly = RoomAvailability.weekFor(
            timetable.filterNot { it.dayOfWeek == DayOfWeek.SATURDAY },
            "211",
        )

        assertTrue(DayOfWeek.SATURDAY in withSaturday.freeByDay)
        assertEquals(listOf("10 AM–6 PM"), withSaturday.freeOn(DayOfWeek.SATURDAY).map { it.label })
        assertFalse(DayOfWeek.SATURDAY in weekdaysOnly.freeByDay)
        assertEquals(5, weekdaysOnly.freeByDay.size)
    }

    @Test
    fun `a week reports its bookings in slot order alongside the free runs`() {
        val week = RoomAvailability.weekFor(timetable, "204")

        assertEquals(listOf(9, 14), week.bookingsOn(DayOfWeek.MONDAY).map { it.startHour })
        assertFalse(week.isNeverBooked)
        // Mon 6 free (11-2, 3-6) + Tue 9 + Wed 9 + Thu 8 + Fri 9 + Sat 9 = 50 hours.
        assertEquals(50, week.totalFreeHours)
    }

    @Test
    fun `every room gets a week in reading order`() {
        val weeks = RoomAvailability.allWeeks(timetable)

        assertEquals(listOf("204", "211", "312"), weeks.map { it.room })
    }

    @Test
    fun `free runs for a single room and day can be asked for directly`() {
        assertEquals(
            listOf(FreeRun(11, 14), FreeRun(15, 18)),
            RoomAvailability.freeRuns(timetable, "204", DayOfWeek.MONDAY),
        )
        assertTrue(RoomAvailability.freeRuns(timetable, "204", DayOfWeek.MONDAY).first().contains(12))
        assertFalse(RoomAvailability.freeRuns(timetable, "204", DayOfWeek.MONDAY).first().contains(14))
    }

    // ---- reading the clock --------------------------------------------------

    @Test
    fun `the current slot is the hour the clock is in`() {
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 9),
            RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 3, 9, 0)),
        )
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 9),
            RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 3, 9, 59)),
        )
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 17),
            RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 3, 17, 30)),
        )
    }

    @Test
    fun `outside teaching hours there is no current slot`() {
        assertNull(RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 3, 8, 59)))
        assertNull(RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 3, 18, 0)))
        assertNull(RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 3, 23, 30)))
        // Sunday the 9th.
        assertNull(RoomAvailability.slotAt(LocalDateTime.of(2026, 8, 9, 11, 0)))
        assertNull(RoomAvailability.slotAt(DayOfWeek.SUNDAY, LocalTime.of(11, 0)))
    }

    @Test
    fun `the next slot rolls forward past the end of the day`() {
        // Monday evening -> Tuesday morning.
        assertEquals(
            SlotQuery(DayOfWeek.TUESDAY, 9),
            RoomAvailability.nextSlot(LocalDateTime.of(2026, 8, 3, 19, 0)),
        )
        // Early Monday -> the same morning.
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 9),
            RoomAvailability.nextSlot(LocalDateTime.of(2026, 8, 3, 6, 30)),
        )
        // Mid-morning Monday -> right now.
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 10),
            RoomAvailability.nextSlot(LocalDateTime.of(2026, 8, 3, 10, 5)),
        )
        // Sunday -> Monday.
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 9),
            RoomAvailability.nextSlot(LocalDateTime.of(2026, 8, 9, 11, 0)),
        )
    }

    @Test
    fun `the next slot skips a weekend the timetable does not use`() {
        val weekdaysOnly = RoomAvailability.weekdaysFor(
            timetable.filterNot { it.dayOfWeek == DayOfWeek.SATURDAY },
        )

        // Friday evening with no Saturday classes -> Monday.
        assertEquals(
            SlotQuery(DayOfWeek.MONDAY, 9),
            RoomAvailability.nextSlot(LocalDateTime.of(2026, 8, 7, 19, 0), weekdaysOnly),
        )
        // ...but Saturday is offered when the timetable uses it.
        assertEquals(
            SlotQuery(DayOfWeek.SATURDAY, 9),
            RoomAvailability.nextSlot(
                LocalDateTime.of(2026, 8, 7, 19, 0),
                RoomAvailability.weekdaysFor(timetable),
            ),
        )
    }

    // ---- joint classes ------------------------------------------------------

    @Test
    fun `a class printed on two cohorts' pages folds into one line`() {
        // How the real timetable reads: a 3rd-year elective appears on the ECE-A and
        // ECE-B pages identically, so it arrives as two rows for one class.
        val bookings = listOf(
            booking(startHour = 16, units = 2, room = "214", subject = "ECA", section = "3rd Yr ECE-A", faculty = "RJS"),
            booking(startHour = 16, units = 2, room = "214", subject = "ECA", section = "3rd Yr ECE-B", faculty = "RJS"),
        )

        val group = RoomAvailability.groupsAt(bookings, SlotQuery(DayOfWeek.MONDAY, 16)).single()

        assertTrue(group.isShared)
        assertEquals(listOf("3rd Yr ECE-A", "3rd Yr ECE-B"), group.sections)
        assertEquals("3rd Yr ECE-A, 3rd Yr ECE-B · RJS", group.subtitle)
        assertEquals("ECA", group.booking.subject)
    }

    @Test
    fun `a real clash is not folded away`() {
        val bookings = listOf(
            booking(startHour = 9, room = "204", subject = "ADEC", section = "2nd Yr CSE-A"),
            booking(startHour = 9, room = "204", subject = "TOC", section = "3rd Yr CSE-A"),
        )

        val groups = RoomAvailability.groupsAt(bookings, SlotQuery(DayOfWeek.MONDAY, 9))

        assertEquals(listOf("ADEC", "TOC"), groups.map { it.booking.subject })
        assertTrue(groups.none { it.isShared })
    }

    @Test
    fun `two batches of the same subject stay apart`() {
        // EH (P) B1 and B2 are different classes, even in the same room at the same hour.
        val bookings = listOf(
            booking(startHour = 16, units = 2, room = "R1", subject = "EH", batch = "B1", kind = SessionKind.PRACTICAL),
            booking(startHour = 16, units = 2, room = "R1", subject = "EH", batch = "B2", kind = SessionKind.PRACTICAL),
        )

        val groups = RoomAvailability.groupsAt(bookings, SlotQuery(DayOfWeek.MONDAY, 16))

        assertEquals(listOf("B1", "B2"), groups.map { it.booking.batch })
    }

    @Test
    fun `differing lengths or faculty are not the same class`() {
        // The Monday workshop runs 9-11 with one teacher and 11-1 with another.
        val bookings = listOf(
            booking(startHour = 9, units = 2, room = "212", subject = "EW", faculty = "JP"),
            booking(startHour = 9, units = 1, room = "212", subject = "EW", faculty = "JP"),
            booking(startHour = 9, units = 2, room = "212", subject = "EW", faculty = "AKS"),
        )

        assertEquals(3, RoomAvailability.groupBookings(bookings).size)
    }

    @Test
    fun `a room's status folds joint cohorts too`() {
        val bookings = listOf(
            booking(startHour = 9, units = 2, room = "313", subject = "NN-B", section = "3rd Yr EE", faculty = "US"),
            booking(startHour = 9, units = 2, room = "313", subject = "NN-B", section = "3rd Yr ECE-A", faculty = "US"),
            booking(startHour = 9, units = 2, room = "313", subject = "NN-B", section = "3rd Yr ECE-B", faculty = "US"),
        )

        val status = RoomAvailability.statusAt(bookings, "313", SlotQuery(DayOfWeek.MONDAY, 10))

        assertEquals(3, status.bookings.size)
        assertEquals("3rd Yr ECE-A, 3rd Yr ECE-B, 3rd Yr EE", status.groups.single().sectionsLabel)
    }

    @Test
    fun `groups come out in room then slot order`() {
        val bookings = listOf(
            booking(startHour = 9, room = "312", subject = "EH"),
            booking(startHour = 9, room = "204", subject = "TOC"),
            booking(startHour = 9, room = "204", subject = "CN"),
        )

        val groups = RoomAvailability.groupBookings(bookings)

        assertEquals(listOf("204", "204", "312"), groups.map { it.booking.room })
        assertEquals(listOf("CN", "TOC", "EH"), groups.map { it.booking.subject })
    }

    // ---- seeding courses ----------------------------------------------------

    @Test
    fun `one section's timetable comes back in weekly order`() {
        val section = RoomAvailability.forSection(timetable, "3rd Yr CSE-A")

        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.MONDAY),
            section.map { it.dayOfWeek },
        )
        assertEquals(listOf(9, 10), section.map { it.startHour })
        assertEquals(listOf("TOC", "DMW"), section.map { it.subject })
    }

    @Test
    fun `practical and tutorial tags survive into the label`() {
        val lab = booking(
            startHour = 14,
            units = 2,
            room = "Basement Lab",
            subject = "ADEC",
            kind = SessionKind.PRACTICAL,
            batch = "A1",
        )

        assertEquals("ADEC (P) — A1", lab.label)
        assertEquals("2nd Yr CSE-A · AP", lab.subtitle)
        assertEquals("2–4 PM", lab.slotLabel)
    }

    // ---- classes that occupy no room ----------------------------------------

    /** SFL, the sports hour: printed on the section's page in no room at all. */
    private val sportsHour = booking(
        day = DayOfWeek.MONDAY,
        startHour = 9,
        units = 2,
        room = null,
        subject = "SFL",
    )

    private val withSportsHour = timetable + sportsHour

    @Test
    fun `a class with no room adds no room to the list`() {
        assertFalse(sportsHour.occupiesARoom)
        assertEquals(RoomAvailability.rooms(timetable), RoomAvailability.rooms(withSportsHour))
    }

    @Test
    fun `a class with no room leaves every room as free as it was`() {
        val query = SlotQuery(DayOfWeek.MONDAY, 9)

        // The whole point of keeping it out: 312 is free at 9 on Monday, and a sports hour
        // in no room must not be able to change that answer for any room.
        assertEquals(
            RoomAvailability.statusesAt(timetable, query),
            RoomAvailability.statusesAt(withSportsHour, query),
        )
        assertEquals(
            RoomAvailability.freeRoomsAt(timetable, query).map { it.room },
            RoomAvailability.freeRoomsAt(withSportsHour, query).map { it.room },
        )
    }

    @Test
    fun `a class with no room is in no slot's booking list`() {
        val at9 = RoomAvailability.bookingsAt(withSportsHour, SlotQuery(DayOfWeek.MONDAY, 9))

        assertTrue(at9.none { it.subject == "SFL" })
        assertTrue(at9.all { it.occupiesARoom })
        assertTrue(
            RoomAvailability.groupsAt(withSportsHour, SlotQuery(DayOfWeek.MONDAY, 9))
                .none { it.booking.subject == "SFL" },
        )
    }

    @Test
    fun `a class with no room is in no room's week`() {
        assertEquals(
            RoomAvailability.allWeeks(timetable),
            RoomAvailability.allWeeks(withSportsHour),
        )
    }

    @Test
    fun `a class with no room is still the section's class`() {
        // Which is why it is kept at all: this is the list the seeder reads.
        assertTrue(sportsHour in RoomAvailability.forSection(withSportsHour, "2nd Yr CSE-A"))
        assertTrue("2nd Yr CSE-A" in RoomAvailability.sections(withSportsHour))
    }
}
