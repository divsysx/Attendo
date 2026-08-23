package com.attendo.core.data

import com.attendo.core.engine.RoomAvailability
import com.attendo.core.engine.SlotQuery
import com.attendo.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Checks the timetable that actually ships, not a fixture.
 *
 * The bundled CSV was retyped from a 22-page printed grid, so the interesting failures
 * are transcription slips rather than logic bugs. Two of these tests do the work the eye
 * cannot: the import must come back with *zero* problems, and every subject and faculty
 * code used must resolve in the acronym keys — which is what catches a mistyped code,
 * since a typo will not be in the key.
 *
 * The third kind of slip is a cell that was read and then dropped, which no count of
 * problems will show. SFL, printed without a room, was omitted for a whole release for
 * that reason, so the room-less classes are pinned here cell by cell.
 *
 * Re-run after every semester re-import; a failure here names the bad line.
 */
class BundledTimetableTest {

    private fun asset(name: String): String {
        // Tests run with the module directory as the working directory.
        val file = File("../app/src/main/assets/$name")
        assertTrue("missing bundled asset ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val imported = TimetableCsv.parse(asset("timetable.csv"))
    private val subjects = Glossary.parse(asset("subjects.csv"))
    private val faculty = Glossary.parse(asset("faculty.csv"))

    @Test
    fun `the bundled timetable imports without a single problem`() {
        assertEquals(
            imported.problems.joinToString("\n"),
            emptyList<ImportProblem>(),
            imported.problems,
        )
        assertTrue(imported.summary, imported.bookings.size > 300)
    }

    @Test
    fun `the bundled file already writes a two hour block as one row`() {
        // The app runs mergeAdjacent over the import anyway, for a hand-retyped file that
        // splits a block across two hourly columns. On this file it must find nothing to
        // do — every multi-hour class is already a single row with its own unit count —
        // so re-importing next semester's file cannot quietly change what the room
        // screens report. Order is not part of the claim; mergeAdjacent re-sorts.
        val merged = TimetableCsv.mergeAdjacent(imported.bookings)

        assertEquals(imported.bookings.size, merged.size)
        assertEquals(imported.bookings.toSet(), merged.toSet())
    }

    @Test
    fun `every room in the building is accounted for`() {
        assertEquals(
            listOf(
                "203", "204", "211", "212", "213", "214", "217", "219",
                "303", "304", "311", "312", "313", "314", "317", "513",
                "Basement Lab", "R1", "R2", "R3", "R4",
            ),
            RoomAvailability.rooms(imported.bookings),
        )
    }

    @Test
    fun `all twenty two cohorts are present and named consistently`() {
        assertEquals(
            listOf(
                "1st Yr CSE-A", "1st Yr CSE-B", "1st Yr ECE-A", "1st Yr ECE-B", "1st Yr EE-A", "1st Yr EE-B",
                "2nd Yr CSE-A", "2nd Yr CSE-B", "2nd Yr ECE-A", "2nd Yr ECE-B", "2nd Yr EE-A", "2nd Yr EE-B",
                "3rd Yr CSE-A", "3rd Yr CSE-B", "3rd Yr ECE-A", "3rd Yr ECE-B", "3rd Yr EE",
                "4th Yr CSE-A", "4th Yr CSE-B", "4th Yr ECE-A", "4th Yr ECE-B", "4th Yr EE",
            ),
            RoomAvailability.sections(imported.bookings),
        )
    }

    @Test
    fun `the timetable is a five day week`() {
        // The printed grid has a Saturday row, but it is empty on every page.
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            RoomAvailability.daysInUse(imported.bookings),
        )
        assertTrue(DayOfWeek.SATURDAY !in RoomAvailability.weekdaysFor(imported.bookings))
    }

    // ---- the acronym keys ---------------------------------------------------

    @Test
    fun `every subject code used resolves in the subject key`() {
        val unresolved = imported.bookings
            .map { it.subject }
            .distinct()
            .filter { subjects[it] == null }
            .sorted()

        assertEquals("subject codes missing from subjects.csv", emptyList<String>(), unresolved)
    }

    @Test
    fun `every faculty code used resolves in the faculty key`() {
        val unresolved = imported.bookings
            .mapNotNull { it.faculty }
            .flatMap { cell -> cell.split('/').map { it.trim() } }
            .filter { it.isNotEmpty() }
            .distinct()
            .filter { faculty[it] == null }
            .sorted()

        assertEquals("faculty codes missing from faculty.csv", emptyList<String>(), unresolved)
    }

    @Test
    fun `an elective's group suffix still finds its subject name`() {
        assertEquals("Neural Networks", subjects["NN-B"])
        assertEquals("Digital Image Processing", subjects["DIP-A"])
        assertEquals("Probability and Statistics for Computer Science", subjects["PSCS-B"])
        assertEquals("Reinforcement Learning", subjects["RL-A"])
        assertEquals("Edge Computing", subjects["EDGE-B"])
    }

    @Test
    fun `a jointly taught workshop names both teachers`() {
        val workshop = imported.bookings.first { it.subject == "AEW-I" }

        assertEquals("Prof. A.K. Tandon & Dr. Arjun Tyagi", faculty.label(workshop.faculty))
    }

    // ---- spot checks against the printed grid -------------------------------

    @Test
    fun `the monday morning electrical workshop reads as printed`() {
        val block = RoomAvailability
            .bookingsAt(imported.bookings, SlotQuery(DayOfWeek.MONDAY, 9))
            .single { it.room == "212" }

        assertEquals("EW", block.subject)
        assertEquals("1st Yr EE-A", block.section)
        assertEquals("A2", block.batch)
        assertEquals("JP", block.faculty)
        assertEquals(2, block.units)
        assertEquals("9–11 AM", block.slotLabel)
    }

    @Test
    fun `a shared third year elective folds its cohorts into one line`() {
        // ECA is one class in 214 on Wednesday afternoon, printed on four pages.
        val eca = RoomAvailability
            .groupsAt(imported.bookings, SlotQuery(DayOfWeek.WEDNESDAY, 16))
            .single { it.booking.room == "214" }

        assertTrue(eca.isShared)
        assertEquals(
            listOf("3rd Yr CSE-A", "3rd Yr CSE-B", "3rd Yr ECE-B", "3rd Yr EE"),
            eca.sections,
        )
        assertEquals("ECA", eca.booking.subject)
        assertEquals("RJS", eca.booking.faculty)
    }

    @Test
    fun `the tuesday morning VLSI elective is one class not four clashes`() {
        val at10 = RoomAvailability.statusAt(imported.bookings, "204", SlotQuery(DayOfWeek.TUESDAY, 10))

        assertEquals(4, at10.bookings.size)
        assertEquals(1, at10.groups.size)
        assertEquals("AVLSI", at10.groups.single().booking.subject)
        // 204 runs AVLSI, EM-I, AVFD, IEHV and ADEC back to back, so it never frees up.
        assertEquals(18, at10.busyUntilHour)
        assertEquals("Busy for the rest of the day", at10.summary)
    }

    @Test
    fun `a practical keeps its batch and lab`() {
        val lab = imported.bookings.single {
            it.subject == "MEV" && it.kind == SessionKind.PRACTICAL && it.section == "4th Yr EE"
        }

        assertEquals("513", lab.room)
        assertEquals(DayOfWeek.FRIDAY, lab.dayOfWeek)
        assertEquals(11, lab.startHour)
        assertEquals("Modeling and Simulation of EHV", subjects.nameOf(lab.subject))
    }

    @Test
    fun `the source timetable's own double booking stays visible`() {
        // 2nd Yr CSE-A has DSD (A2) and DBMS (A1) both in R4 on Wednesday at 2 PM.
        // That is a clash in the printed grid, not an import bug, so the app must show it.
        val clash = RoomAvailability
            .groupsAt(imported.bookings, SlotQuery(DayOfWeek.WEDNESDAY, 14))
            .filter { it.booking.room == "R4" }

        assertEquals(listOf("DBMS", "DSD"), clash.map { it.booking.subject }.sorted())
    }

    // ---- seeding a real section off the shipped file -------------------------

    private val termStart = LocalDate.of(2026, 7, 28)

    private fun seed(section: String, batch: String?) =
        SectionSeeder.plan(imported.bookings, section, termStart, batch, subjects)

    @Test
    fun `a real section is offered only its own batch numbering`() {
        // 2nd Yr ECE-A splits its branch labs A1/A2, and its statistics lab is batch B3,
        // pooled with 2nd Yr EE-A. B3 is not a choice this section makes, so offering it —
        // as a plain list of the labels on the page would — invites an answer that then
        // deletes the student's other three labs.
        assertEquals(
            listOf("A1", "A2"),
            SectionSeeder.batchesFor(imported.bookings, "2nd Yr ECE-A"),
        )
        assertEquals(listOf("A1", "A2"), seed("2nd Yr ECE-A", null).batchOptions)
    }

    @Test
    fun `a real section's theory lab and tutorial come out as separate courses`() {
        // PSCS is printed "PSCS-B" for the lectures this section attends and plain "PSCS"
        // for the lab: one subject, two courses, and neither of them called PSCS-B. IEHV is
        // the section's one tutorial, and it is a third course rather than an extra hour on
        // the IEHV lectures.
        val codes = seed("2nd Yr ECE-A", "A1").proposals.map { it.course.code }

        assertEquals(
            listOf(
                "DE-1", "DE-1 Lab", "EDC", "EDC Lab", "EVS-2", "IEHV", "IEHV Tutorial",
                "NAS", "NAS Lab", "PSCS", "PSCS Lab", "SFL", "VLSI", "VLSI Lab",
            ),
            codes,
        )
        assertTrue(codes.none { it.endsWith("-A") || it.endsWith("-B") || it.endsWith("-T") })
    }

    @Test
    fun `a real section keeps the lab it is pooled into, whichever batch it picks`() {
        listOf("A1", "A2").forEach { batch ->
            val lab = seed("2nd Yr ECE-A", batch).proposals.single { it.course.code == "PSCS Lab" }

            assertEquals("PSCS lab for $batch", listOf("B3"), lab.batches)
            assertEquals("PSCS lab for $batch", 1, lab.patterns.size)
        }
    }

    @Test
    fun `a real section's own labs do follow the batch it picks`() {
        // Both DE-1 labs are on Monday in 317; A1 has the 2 PM block and A2 the 4 PM one.
        assertEquals(
            listOf(14),
            seed("2nd Yr ECE-A", "A1").proposals
                .single { it.course.code == "DE-1 Lab" }
                .patterns
                .map { it.startHour },
        )
        assertEquals(
            listOf(16),
            seed("2nd Yr ECE-A", "A2").proposals
                .single { it.course.code == "DE-1 Lab" }
                .patterns
                .map { it.startHour },
        )
    }

    @Test
    fun `the most heavily double numbered section loses no lab to its batch choice`() {
        // 3rd Yr CSE-B is the worst case in the file: four subjects numbered B1/B2 and
        // three numbered A1/A2, on one page. B1/B2 wins the vote and is what the student is
        // asked, so the A-numbered labs have to survive that answer — under a plain
        // `batch == chosen` filter all three would vanish.
        assertEquals(listOf("B1", "B2"), SectionSeeder.batchesFor(imported.bookings, "3rd Yr CSE-B"))

        val proposals = seed("3rd Yr CSE-B", "B1").proposals.associateBy { it.course.code }

        assertEquals(listOf("A2"), proposals.getValue("DIP Lab").batches)
        assertEquals(listOf("A2"), proposals.getValue("NN Lab").batches)
        // ECA's two lectures are for the whole section; only its tutorial is batched, and it
        // is printed for both A1 and A2. A B1 answer said nothing about which, so it is kept
        // whole and flagged instead of halved on a guess — and splitting the tutorial off
        // keeps that flag off the lectures, which were never in doubt.
        assertEquals(emptyList<String>(), proposals.getValue("ECA").batches)
        assertEquals(listOf("A1", "A2"), proposals.getValue("ECA Tutorial").batches)
        assertTrue(proposals.getValue("ECA Tutorial").needsBatchCheck)
    }

    @Test
    fun `the one page printed with both lecture groups is left as the grid has it`() {
        // 3rd Yr CSE-B is the only section carrying both NN-A and NN-B: three group-A hours
        // plus a lone Tuesday 5 PM group-B lecture, the hour the ECE and EE pages give
        // their group-B students. Folding the tag away makes them one course, which is
        // right, and leaves that fourth hour in it, which may not be — but it is what the
        // printed grid says for this section, and a seeder that quietly dropped rows it
        // found surprising would be the worse failure. Cancel the hour you do not attend.
        //
        // If a re-import ever settles this, update the expectation here.
        val nn = seed("3rd Yr CSE-B", "B1").proposals.single { it.course.code == "NN" }

        assertEquals(
            listOf(
                DayOfWeek.MONDAY to 16,
                DayOfWeek.TUESDAY to 16,
                DayOfWeek.TUESDAY to 17,
            ),
            nn.patterns.map { it.dayOfWeek to it.startHour },
        )
    }

    // ---- the classes printed without a room ---------------------------------

    /** The six pages of the printed grid that carry an SFL block, and its faculty. */
    private val sportsHour = mapOf(
        "2nd Yr EE-A" to Triple(DayOfWeek.WEDNESDAY, 15, "RHS"),
        "2nd Yr EE-B" to Triple(DayOfWeek.WEDNESDAY, 15, "RHS"),
        "2nd Yr ECE-A" to Triple(DayOfWeek.WEDNESDAY, 15, "ANJ"),
        "2nd Yr ECE-B" to Triple(DayOfWeek.MONDAY, 15, "ANJ"),
        "2nd Yr CSE-A" to Triple(DayOfWeek.TUESDAY, 15, "AP"),
        "2nd Yr CSE-B" to Triple(DayOfWeek.THURSDAY, 15, "AZK"),
    )

    @Test
    fun `every second year page's sports hour is transcribed`() {
        // SFL is printed with no room in brackets, and was left out of the CSV for exactly
        // that reason — which quietly cost every second-year student a course they attend
        // two hours a week. It is a class first and a non-booking second, so it belongs in
        // the file with an empty room column. This test is the reason it cannot go missing
        // again: a re-import that drops room-less cells fails right here.
        assertEquals(
            sportsHour.keys.sorted(),
            imported.bookings.filter { it.subject == "SFL" }.map { it.section }.sorted(),
        )

        sportsHour.forEach { (section, printed) ->
            val (day, startHour, faculty) = printed
            val sfl = imported.bookings.single { it.subject == "SFL" && it.section == section }

            assertEquals(section, day, sfl.dayOfWeek)
            assertEquals(section, startHour, sfl.startHour)
            assertEquals(section, 2, sfl.units)
            assertEquals(section, faculty, sfl.faculty)
            assertEquals(section, SessionKind.LECTURE, sfl.kind)
            assertNull("$section: SFL is printed in no room", sfl.room)
        }
    }

    @Test
    fun `the sports hour is the only class with no room, and it books none`() {
        val roomless = imported.bookings.filterNot { it.occupiesARoom }

        assertEquals(sportsHour.size, roomless.size)
        assertEquals(listOf("SFL"), roomless.map { it.subject }.distinct())
        // The room list is derived from the file, so a blank room column must not create a
        // nameless room — and no room may be reported busy by a class that is not in one.
        assertTrue(RoomAvailability.rooms(imported.bookings).none { it.isBlank() })
        sportsHour.forEach { (_, printed) ->
            val (day, startHour, _) = printed
            assertTrue(
                "no room is booked for SFL",
                RoomAvailability.bookingsAt(imported.bookings, SlotQuery(day, startHour))
                    .none { it.subject == "SFL" },
            )
        }
        assertTrue(
            "no room's week contains SFL",
            RoomAvailability.allWeeks(imported.bookings).none { week ->
                week.bookingsByDay.values.any { day -> day.any { it.subject == "SFL" } }
            },
        )
    }

    @Test
    fun `a section with a sports hour is offered it as a course to keep`() {
        // The point of transcribing it: it has to reach the seeder. One course, one weekly
        // pattern, two hours, and no room to put on the pattern.
        sportsHour.forEach { (section, printed) ->
            val (day, startHour, _) = printed
            val batch = SectionSeeder.batchesFor(imported.bookings, section).firstOrNull()
            val sfl = seed(section, batch).proposals.single { it.course.code == "SFL" }

            assertEquals(section, "Sports for Life / Fit India", sfl.course.name)
            assertEquals(section, 2, sfl.unitsPerWeek)
            assertEquals(section, emptyList<String>(), sfl.batches)
            val pattern = sfl.patterns.single()
            assertEquals(section, day, pattern.dayOfWeek)
            assertEquals(section, startHour, pattern.startHour)
            assertEquals(section, 2, pattern.units)
            assertNull("$section: no room to invent", pattern.room)
        }
    }

    @Test
    fun `the sports hour does not read as a clash with anything`() {
        // It is timetabled in an hour the section has otherwise free on every page. If a
        // future grid overlaps it with a lecture, that is a real clash and should show up
        // here rather than be filtered away as a quirk of the room-less row.
        sportsHour.keys.forEach { section ->
            val clashes = seed(section, SectionSeeder.batchesFor(imported.bookings, section).firstOrNull())
                .clashes
                .filter { clash -> clash.subjects.contains("SFL") }

            assertEquals(section, emptyList<SeedClash>(), clashes)
        }
    }

    // ---- what the room screens will actually answer -------------------------

    @Test
    fun `tuesday at eleven is the one slot with nowhere free`() {
        // Worth pinning down: every other hour of the week has somewhere to sit, so the
        // "right now" view is only ever empty-handed in this one slot. If a re-import
        // changes that, the room screens have a new edge case to show.
        val slots = RoomAvailability.daysInUse(imported.bookings).flatMap { day ->
            (9..17).map { SlotQuery(day, it) }
        }

        val fullyBooked = slots.filter { RoomAvailability.freeRoomsAt(imported.bookings, it).isEmpty() }

        assertEquals(listOf(SlotQuery(DayOfWeek.TUESDAY, 11)), fullyBooked)
    }

    @Test
    fun `every room's week adds up to the hours it is not booked`() {
        val weeks = RoomAvailability.allWeeks(imported.bookings)
        val days = RoomAvailability.weekdaysFor(imported.bookings).size

        weeks.forEach { week ->
            val bookedHours = week.freeByDay.keys.sumOf { day ->
                week.bookingsOn(day).flatMap { it.occupiedHours }.distinct().size
            }
            assertEquals(
                "free + booked should cover ${week.room}'s week",
                days * 9,
                week.totalFreeHours + bookedHours,
            )
        }
    }
}
