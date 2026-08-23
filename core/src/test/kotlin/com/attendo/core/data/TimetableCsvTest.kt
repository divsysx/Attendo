package com.attendo.core.data

import com.attendo.core.model.RoomBooking
import com.attendo.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The semester re-import path. The input is retyped by hand from a printed grid, so
 * these tests are mostly about being forgiving where it is safe and loud where it is
 * not.
 */
class TimetableCsvTest {

    @Test
    fun `a full row parses every field`() {
        val result = TimetableCsv.parse("Mon,9,2,204,ADEC,2nd Yr CSE-A,RHS,L,")

        assertTrue(result.problems.toString(), result.isClean)
        val booking = result.bookings.single()
        assertEquals(DayOfWeek.MONDAY, booking.dayOfWeek)
        assertEquals(9, booking.startHour)
        assertEquals(2, booking.units)
        assertEquals("204", booking.room)
        assertEquals("ADEC", booking.subject)
        assertEquals("2nd Yr CSE-A", booking.section)
        assertEquals("RHS", booking.faculty)
        assertEquals(SessionKind.LECTURE, booking.kind)
        assertNull(booking.batch)
        assertEquals("9–11 AM", booking.slotLabel)
    }

    @Test
    fun `comments blank lines and a header row are skipped`() {
        val csv = """
            # Faculty of Technology timetable, Odd 2026-27
            ${TimetableCsv.HEADER}

            Mon,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            # Tue,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,   <- withdrawn
            Tue,11,1,211,PSA,2nd Yr CSE-A,SKG,L,
        """.trimIndent()

        val result = TimetableCsv.parse(csv)

        assertTrue(result.problems.toString(), result.isClean)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), result.bookings.map { it.dayOfWeek })
    }

    @Test
    fun `omitted length kind faculty and batch fall back sensibly`() {
        val result = TimetableCsv.parse("Wed,11,,312,TOC,3rd Yr CSE-A")

        assertTrue(result.problems.toString(), result.isClean)
        val booking = result.bookings.single()
        assertEquals(1, booking.units)
        assertEquals(SessionKind.LECTURE, booking.kind)
        assertNull(booking.faculty)
        assertNull(booking.batch)
    }

    @Test
    fun `practicals and tutorials keep their kind and batch`() {
        val result = TimetableCsv.parse(
            """
            Tue,11,2,Basement Lab,ADEC,2nd Yr CSE-A,RHS,P,A1
            Thu,15,1,217,PSA,2nd Yr CSE-A,SKG,tutorial,
            """.trimIndent(),
        )

        assertTrue(result.problems.toString(), result.isClean)
        val (lab, tutorial) = result.bookings
        assertEquals(SessionKind.PRACTICAL, lab.kind)
        assertEquals("A1", lab.batch)
        assertEquals("ADEC (P) — A1", lab.label)
        assertEquals(SessionKind.TUTORIAL, tutorial.kind)
        assertNull(tutorial.batch)
    }

    @Test
    fun `day names are read in any of the printed spellings`() {
        assertEquals(DayOfWeek.MONDAY, TimetableCsv.parseDay("Mon"))
        assertEquals(DayOfWeek.MONDAY, TimetableCsv.parseDay("monday"))
        assertEquals(DayOfWeek.MONDAY, TimetableCsv.parseDay(" MON "))
        assertEquals(DayOfWeek.THURSDAY, TimetableCsv.parseDay("Thurs"))
        assertEquals(DayOfWeek.THURSDAY, TimetableCsv.parseDay("TH"))
        assertEquals(DayOfWeek.SATURDAY, TimetableCsv.parseDay("Sat"))
        assertNull(TimetableCsv.parseDay("Funday"))
        assertNull(TimetableCsv.parseDay(""))
    }

    @Test
    fun `a bare afternoon hour is read as the afternoon`() {
        // The printed grid has a "2-3" column; on a 9-to-6 day that can only be 2 PM.
        assertEquals(14, TimetableCsv.parseHour("2"))
        assertEquals(14, TimetableCsv.parseHour("2 PM"))
        assertEquals(14, TimetableCsv.parseHour("14"))
        assertEquals(14, TimetableCsv.parseHour("14:00"))
        assertEquals(17, TimetableCsv.parseHour("5"))
        assertEquals(13, TimetableCsv.parseHour("1:00"))
    }

    @Test
    fun `morning hours are read as written`() {
        assertEquals(9, TimetableCsv.parseHour("9"))
        assertEquals(9, TimetableCsv.parseHour("09"))
        assertEquals(9, TimetableCsv.parseHour("9:00"))
        assertEquals(9, TimetableCsv.parseHour("9 AM"))
        assertEquals(11, TimetableCsv.parseHour("11"))
        assertEquals(12, TimetableCsv.parseHour("12"))
        assertEquals(12, TimetableCsv.parseHour("12 PM"))
    }

    @Test
    fun `times outside the teaching grid are refused rather than shifted`() {
        assertNull(TimetableCsv.parseHour("18"))
        assertNull(TimetableCsv.parseHour("6"))      // would be 6 PM, past the last slot
        assertNull(TimetableCsv.parseHour("8"))      // would be 8 PM
        assertNull(TimetableCsv.parseHour("8 AM"))   // before the day starts
        assertNull(TimetableCsv.parseHour("noon"))
        assertNull(TimetableCsv.parseHour(""))
    }

    // ---- bad lines ----------------------------------------------------------

    @Test
    fun `one bad line costs that line and nothing else`() {
        val csv = """
            Mon,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            Funday,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            Tue,9,1,211,PSA,2nd Yr CSE-A,SKG,L,
        """.trimIndent()

        val result = TimetableCsv.parse(csv)

        assertEquals(2, result.bookings.size)
        assertFalse(result.isClean)
        val problem = result.problems.single()
        assertEquals(2, problem.line)
        assertTrue(problem.reason, problem.reason.contains("Funday"))
    }

    @Test
    fun `a class that would run past six is reported`() {
        val result = TimetableCsv.parse("Mon,5,2,204,ADEC,2nd Yr CSE-A,RHS,L,")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.problems.single().reason.contains("teaching day"))
    }

    @Test
    fun `an hour off the grid is reported with its own message`() {
        val result = TimetableCsv.parse("Mon,7,1,204,ADEC,2nd Yr CSE-A,RHS,L,")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.problems.single().reason.contains("start time"))
    }

    @Test
    fun `a room-less cell is a class with no room, not a bad line`() {
        // SFL — the sports hour — is printed without a room in brackets. It is still two
        // hours the section is expected to turn up for, so dropping the row (which is what
        // calling it a problem did) lost a course the seeder should have offered.
        val result = TimetableCsv.parse("Fri,16,2,,SFL,2nd Yr CSE-A,,L,")

        assertTrue(result.problems.joinToString(), result.isClean)
        val sfl = result.bookings.single()
        assertNull(sfl.room)
        assertFalse(sfl.occupiesARoom)
        assertEquals("SFL", sfl.subject)
        assertEquals(2, sfl.units)
        // And it is not a room: nothing free gets reported busy because of it.
        assertEquals(emptyList<String>(), result.rooms)
    }

    @Test
    fun `a room column of nothing but spaces is the same as an empty one`() {
        val result = TimetableCsv.parse("Fri,16,2,   ,SFL,2nd Yr CSE-A,,L,")

        // Blank is not a room name — a booking against a room called " " would show up in
        // the room list as a nameless row.
        assertTrue(result.isClean)
        assertNull(result.bookings.single().room)
    }

    @Test
    fun `a truncated line names the fields it wanted`() {
        val result = TimetableCsv.parse("Mon,9,1,204")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.problems.single().reason.contains("at least 6"))
    }

    @Test
    fun `an unrecognised kind is an error rather than a guess`() {
        val result = TimetableCsv.parse("Mon,9,1,204,ADEC,2nd Yr CSE-A,RHS,seminar,")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.problems.single().reason.contains("seminar"))
    }

    @Test
    fun `a non-numeric length is reported`() {
        val result = TimetableCsv.parse("Mon,9,two,204,ADEC,2nd Yr CSE-A,RHS,L,")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.problems.single().reason.contains("whole number"))
    }

    @Test
    fun `a missing subject is reported`() {
        val result = TimetableCsv.parse("Mon,9,1,204,,2nd Yr CSE-A,RHS,L,")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.problems.single().reason.contains("subject"))
    }

    // ---- the CSV itself -----------------------------------------------------

    @Test
    fun `a quoted field may contain a comma`() {
        val result = TimetableCsv.parse("""Mon,9,1,"Room 5, Annexe",ADEC,"CSE-A, B1",RHS,L,""")

        assertTrue(result.problems.toString(), result.isClean)
        val booking = result.bookings.single()
        assertEquals("Room 5, Annexe", booking.room)
        assertEquals("CSE-A, B1", booking.section)
    }

    @Test
    fun `an escaped quote survives the round trip`() {
        val quoted = TimetableCsv.splitCsv("""a,"b""c",d""")

        assertEquals(listOf("a", "b\"c", "d"), quoted)
    }

    @Test
    fun `what is written can be read back unchanged`() {
        val bookings = listOf(
            RoomBooking(DayOfWeek.MONDAY, 9, 2, "204", "ADEC", "2nd Yr CSE-A", "RHS"),
            RoomBooking(
                DayOfWeek.TUESDAY, 11, 2, "Basement Lab", "ADEC", "2nd Yr CSE-A", "RHS",
                SessionKind.PRACTICAL, "A1",
            ),
            RoomBooking(DayOfWeek.FRIDAY, 16, 1, "Room 5, Annexe", "EH", "3rd Yr CSE-B", null),
        )

        val reread = TimetableCsv.parse(TimetableCsv.write(bookings))

        assertTrue(reread.problems.toString(), reread.isClean)
        assertEquals(bookings.toSet(), reread.bookings.toSet())
    }

    @Test
    fun `written rows come out in weekly reading order`() {
        val csv = TimetableCsv.write(
            listOf(
                RoomBooking(DayOfWeek.FRIDAY, 9, 1, "204", "EH", "A"),
                RoomBooking(DayOfWeek.MONDAY, 14, 1, "204", "CN", "A"),
                RoomBooking(DayOfWeek.MONDAY, 9, 1, "211", "ADEC", "A"),
            ),
        )

        val rows = csv.lines().filterNot { it.startsWith("#") || it.isBlank() }
        assertEquals(listOf("Mon,9,1,211,ADEC,A,,L,", "Mon,14,1,204,CN,A,,L,", "Fri,9,1,204,EH,A,,L,"), rows)
    }

    @Test
    fun `an empty file imports cleanly as nothing`() {
        val result = TimetableCsv.parse("\n# nothing yet\n\n")

        assertTrue(result.bookings.isEmpty())
        assertTrue(result.isClean)
        assertEquals("0 classes, 0 rooms, 0 sections", result.summary)
    }

    @Test
    fun `the summary counts what was imported and what failed`() {
        val result = TimetableCsv.parse(
            """
            Mon,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            Mon,10,1,211,PSA,3rd Yr CSE-A,SKG,L,
            Funday,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            """.trimIndent(),
        )

        assertEquals("2 classes, 2 rooms, 2 sections, 1 problem", result.summary)
        assertEquals(listOf("204", "211"), result.rooms)
        assertEquals(listOf("2nd Yr CSE-A", "3rd Yr CSE-A"), result.sections)
    }

    // ---- merging split blocks ----------------------------------------------

    @Test
    fun `two adjacent rows of the same class become one block`() {
        // How the printed grid actually reads: one class spread over two columns.
        val split = TimetableCsv.parse(
            """
            Mon,9,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            Mon,10,1,204,ADEC,2nd Yr CSE-A,RHS,L,
            """.trimIndent(),
        ).bookings

        val merged = TimetableCsv.mergeAdjacent(split)

        val block = merged.single()
        assertEquals(9, block.startHour)
        assertEquals(2, block.units)
        assertEquals(11, block.endHour)
        assertEquals("9–11 AM", block.slotLabel)
    }

    @Test
    fun `three adjacent hours become one three hour block`() {
        val split = (9..11).map { RoomBooking(DayOfWeek.TUESDAY, it, 1, "Basement Lab", "ADEC", "A", "RHS", SessionKind.PRACTICAL) }

        val merged = TimetableCsv.mergeAdjacent(split)

        assertEquals(1, merged.size)
        assertEquals(3, merged.single().units)
        assertEquals("9 AM–12 PM", merged.single().slotLabel)
    }

    @Test
    fun `different classes back to back are left alone`() {
        val bookings = listOf(
            RoomBooking(DayOfWeek.MONDAY, 9, 1, "211", "TOC", "3rd Yr CSE-A", "RHS"),
            RoomBooking(DayOfWeek.MONDAY, 10, 1, "211", "DMW", "3rd Yr CSE-A", "SKG"),
        )

        val merged = TimetableCsv.mergeAdjacent(bookings)

        assertEquals(2, merged.size)
        assertEquals(listOf("TOC", "DMW"), merged.map { it.subject })
    }

    @Test
    fun `the same class in two rooms or for two batches stays separate`() {
        val bookings = listOf(
            RoomBooking(DayOfWeek.MONDAY, 9, 1, "204", "ADEC", "2nd Yr CSE-A", "RHS"),
            RoomBooking(DayOfWeek.MONDAY, 10, 1, "211", "ADEC", "2nd Yr CSE-A", "RHS"),
            RoomBooking(DayOfWeek.TUESDAY, 9, 1, "Lab", "ADEC", "2nd Yr CSE-A", "RHS", SessionKind.PRACTICAL, "A1"),
            RoomBooking(DayOfWeek.TUESDAY, 10, 1, "Lab", "ADEC", "2nd Yr CSE-A", "RHS", SessionKind.PRACTICAL, "A2"),
        )

        assertEquals(4, TimetableCsv.mergeAdjacent(bookings).size)
    }

    @Test
    fun `a gap between two hours of the same class is preserved`() {
        val bookings = listOf(
            RoomBooking(DayOfWeek.MONDAY, 9, 1, "204", "ADEC", "2nd Yr CSE-A", "RHS"),
            RoomBooking(DayOfWeek.MONDAY, 11, 1, "204", "ADEC", "2nd Yr CSE-A", "RHS"),
        )

        val merged = TimetableCsv.mergeAdjacent(bookings)

        assertEquals(listOf(9, 11), merged.map { it.startHour })
        assertTrue(merged.all { it.units == 1 })
    }

    @Test
    fun `an already merged block is left as it is`() {
        val bookings = listOf(RoomBooking(DayOfWeek.MONDAY, 9, 2, "204", "ADEC", "2nd Yr CSE-A", "RHS"))

        assertEquals(bookings, TimetableCsv.mergeAdjacent(bookings))
    }

    @Test
    fun `merging leaves the list in weekly reading order`() {
        val bookings = listOf(
            RoomBooking(DayOfWeek.FRIDAY, 16, 1, "312", "EH", "A"),
            RoomBooking(DayOfWeek.MONDAY, 10, 1, "204", "ADEC", "A"),
            RoomBooking(DayOfWeek.MONDAY, 9, 1, "204", "ADEC", "A"),
            RoomBooking(DayOfWeek.MONDAY, 9, 1, "211", "PSA", "B"),
        )

        val merged = TimetableCsv.mergeAdjacent(bookings)

        assertEquals(
            listOf(DayOfWeek.MONDAY to 9, DayOfWeek.MONDAY to 9, DayOfWeek.FRIDAY to 16),
            merged.map { it.dayOfWeek to it.startHour },
        )
        assertEquals(2, merged.first { it.room == "204" }.units)
    }
}
