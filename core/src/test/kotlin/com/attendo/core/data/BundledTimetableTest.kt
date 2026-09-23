package com.attendo.core.data

import com.attendo.core.engine.RoomAvailability
import com.attendo.core.engine.SlotQuery
import com.attendo.core.model.RecurringSaturdays
import com.attendo.core.model.SessionKind
import com.attendo.core.model.TimeGrid
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
 * problems will show. The room-less classes were omitted for a whole release for that
 * reason, so they are pinned here cell by cell.
 *
 * Three things about this revision of the grid are load-bearing enough to be pinned on
 * their own, because each is a decision rather than a transcription:
 *
 * - **8-9 AM.** Twelve cells of the printed grid sit in a column the college does not teach
 *   in, and they have no representation here — the grid starts at 9. See
 *   `the eight to nine column of the printed grid is dropped, not shifted`.
 * - **Saturday.** The row is no longer empty: nine sections are taught on it every week.
 * - **Clubbed classes.** Most hours of this grid are one class printed on several cohorts'
 *   pages. That is normal, and telling it apart from a genuine double booking is
 *   [com.attendo.core.engine.TimetableConflicts]'s job, pinned in `TimetableConflictsTest`.
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
        // One row per printed cell per section — the same class is a row on each cohort's
        // page that carries it, so this is not a count of distinct classes.
        assertEquals(630, imported.bookings.size)
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

    /** Every hour the shipped file gives [subject] on [section]'s page, in weekly order. */
    private fun hoursOf(subject: String, section: String): List<Pair<DayOfWeek, Int>> =
        imported.bookings
            .filter { it.subject == subject && it.section == section }
            .map { it.dayOfWeek to it.startHour }
            .sortedWith(compareBy({ it.first.value }, { it.second }))

    @Test
    fun `the eight to nine column of the printed grid is dropped, not shifted`() {
        // Twelve cells of the printed grid sit in an 8-9 AM column, and the college does not
        // teach in that hour: three first-year pages carry `FMB A (P) [KJL] (313)` there on
        // Friday, the 3rd Yr ECE-B page carries `ACS ECE-B [VS] (314)` across three days,
        // and the three fourth-year pages carry `MIC [SJY] (214)`. A class placed there is a
        // class nobody attends, and reading the column as 8 PM — which is what the 9-to-6
        // grid would otherwise make of a bare "8" — is not a repair either.
        //
        // So there is nothing to transcribe and the app has no way to invent it. What must
        // not happen is a shift: an 8-9 cell becoming the 9-10 hour it sits beside, putting
        // a class on a student's morning that they do not have.
        assertNull("a bare 8 is the 8 PM column of a 9-to-6 grid", TimetableCsv.parseHour("8"))
        assertNull("nor is 8 AM a slot the grid has", TimetableCsv.parseHour("8 AM"))
        assertEquals(TimeGrid.FIRST_START_HOUR, imported.bookings.minOf { it.startHour })
        assertTrue(
            "nothing occupies the hour before the first teaching hour",
            imported.bookings.none { it.occupiesHour(TimeGrid.FIRST_START_HOUR - 1) },
        )

        // The hours each of those three classes is left with, which is what the 9-to-6 part
        // of the grid gives them and no more. The FMB practical is the interesting one: the
        // grid draws its block across the 8-9 and 9-10 columns both, so the extraction finds
        // a cell in each and the hour kept is the one the college teaches in.
        assertEquals(
            listOf(DayOfWeek.TUESDAY to 16, DayOfWeek.FRIDAY to 9, DayOfWeek.FRIDAY to 10),
            hoursOf("FMB", "1st Yr ECE-A"),
        )
        assertEquals(listOf(DayOfWeek.TUESDAY to 11, DayOfWeek.THURSDAY to 14), hoursOf("ACS", "3rd Yr ECE-B"))
        assertEquals(listOf(DayOfWeek.THURSDAY to 9, DayOfWeek.THURSDAY to 13), hoursOf("MIC", "4th Yr EE"))
        assertEquals(listOf(DayOfWeek.THURSDAY to 9, DayOfWeek.THURSDAY to 13), hoursOf("MIC", "4th Yr ECE-A"))
        assertEquals(listOf(DayOfWeek.MONDAY to 16, DayOfWeek.THURSDAY to 13), hoursOf("MIC", "4th Yr ECE-B"))
        // Nothing in the file was written at eight, in any subject.
        assertEquals(emptyList<Pair<DayOfWeek, Int>>(), imported.bookings.filter { it.startHour < 9 }.map { it.dayOfWeek to it.startHour })
    }

    @Test
    fun `every room in the building is accounted for`() {
        // Three rooms are new to this revision — 216, 503 and G01 — and R4 has gone: no page
        // prints it any more, so nothing may still claim it exists. The list is derived from
        // the file, so both halves of that are a statement about the grid, not the code.
        assertEquals(
            listOf(
                "203", "204", "211", "212", "213", "214", "216", "217", "219",
                "303", "304", "311", "312", "313", "314", "317", "503", "513",
                "Basement Lab", "G01", "R1", "R2", "R3",
            ),
            RoomAvailability.rooms(imported.bookings),
        )
        assertTrue("R4 is no longer printed on any page", "R4" !in RoomAvailability.rooms(imported.bookings))
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
    fun `the timetable runs six days, and Saturday is taught on nine sections`() {
        // The printed grid's Saturday row is no longer empty. Nine sections are timetabled
        // on it, which is what makes Saturday a teaching day for them rather than an
        // occasional working Saturday — and the list is decided, not read off the grid, so
        // the two have to agree in both directions or [RecurringSaturdays] and the timetable
        // have drifted apart.
        val taughtOnSaturday = imported.bookings
            .filter { it.dayOfWeek == DayOfWeek.SATURDAY }
            .map { it.section }
            .distinct()
            .sorted()

        assertEquals(
            listOf(
                "1st Yr CSE-A", "1st Yr CSE-B", "1st Yr ECE-A", "1st Yr ECE-B", "1st Yr EE-A", "1st Yr EE-B",
                "4th Yr ECE-A", "4th Yr ECE-B", "4th Yr EE",
            ),
            taughtOnSaturday,
        )
        assertEquals(
            "the timetable no longer teaches Saturday to a section the app calls recurring",
            taughtOnSaturday,
            taughtOnSaturday.filter { RecurringSaturdays.appliesTo(it) },
        )
        assertEquals(
            "the app calls a section recurring that the timetable never teaches on Saturday",
            emptyList<String>(),
            RoomAvailability.sections(imported.bookings)
                .filter { RecurringSaturdays.appliesTo(it) && it !in taughtOnSaturday }
                .sorted(),
        )
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
            ),
            RoomAvailability.daysInUse(imported.bookings),
        )
        assertEquals(
            RoomAvailability.daysInUse(imported.bookings),
            RoomAvailability.weekdaysFor(imported.bookings),
        )
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

    /**
     * The two courses the subject key has to keep apart, and the ones it must not.
     *
     * `ECA-A` and `ECA-B` are separate courses that happen to be spelled the way a lecture
     * group tag is spelled, so the key is the only thing that can tell them from `DIP-A`
     * (which folds onto `DIP`, one practical split between two groups of one course). `DE`
     * and `DE-1` are two different subjects with similar names and no relationship at all.
     * Folding any of these pairs together gives a student one course where they attend two,
     * and neither can be undone by a later re-import.
     */
    @Test
    fun `two courses that only look like one subject's groups stay two courses`() {
        assertEquals("ECA-A", SectionSeeder.baseCode("ECA-A", subjects))
        assertEquals("ECA-B", SectionSeeder.baseCode("ECA-B", subjects))
        assertEquals("Digital Image Processing", subjects.nameOf(SectionSeeder.baseCode("DIP-A", subjects)))
        assertEquals("DE", SectionSeeder.baseCode("DE", subjects))
        assertEquals("DE-1", SectionSeeder.baseCode("DE-1", subjects))
        assertEquals("Digital Empowerment", subjects.nameOf("DE"))
        assertEquals("Digital Electronics-I", subjects.nameOf("DE-1"))

        // A section that attends ECA-B is offered ECA-B and nothing called ECA-A: the two
        // are timetabled for different branches and never meet on one page.
        val cse = seed("3rd Yr CSE-B", "B1").proposals.map { it.course.code }
        assertTrue("ECA-B" in cse)
        assertTrue(cse.none { it.startsWith("ECA-A") })

        // And the first years take Digital Empowerment where the second years take Digital
        // Electronics-I, from the same two pages' worth of grid.
        assertEquals(
            "DE",
            seed("1st Yr ECE-A", null).proposals.single { it.course.code.startsWith("DE") }.course.code,
        )
        assertTrue(seed("2nd Yr ECE-A", "A1").proposals.any { it.course.code == "DE-1" })
        assertTrue(seed("2nd Yr ECE-A", "A1").proposals.none { it.course.code == "DE" })
    }

    @Test
    fun `a jointly taught workshop names both teachers`() {
        // AEW-I is taught by a pair, and the pairing differs by batch: AKT with AT for B1,
        // AKT with UJS for B2. Both codes are in the key, and both are named.
        val b1 = imported.bookings.single {
            it.subject == "AEW-I" && it.section == "2nd Yr EE-A" && it.batch == "B1"
        }
        val b2 = imported.bookings.single {
            it.subject == "AEW-I" && it.section == "2nd Yr EE-A" && it.batch == "B2"
        }

        assertEquals("Prof. A.K. Tandon & Dr. Arjun Tyagi", faculty.label(b1.faculty))
        assertEquals("Prof. A.K. Tandon & Dr. Ujjal Sur", faculty.label(b2.faculty))
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
        // ECA-B is one class in 214 on Wednesday afternoon, printed on both CSE pages.
        val eca = RoomAvailability
            .groupsAt(imported.bookings, SlotQuery(DayOfWeek.WEDNESDAY, 16))
            .single { it.booking.room == "214" }

        assertTrue(eca.isShared)
        assertEquals(listOf("3rd Yr CSE-A", "3rd Yr CSE-B"), eca.sections)
        assertEquals("ECA-B", eca.booking.subject)
        assertEquals("RJS", eca.booking.faculty)
    }

    @Test
    fun `a second year elective taught to four cohorts is one class not four clashes`() {
        // VLSI at ten on Tuesday is printed on four second-year pages, all in room 203: one
        // lecture for both branches' first lecture group. Read as rows it is four bookings
        // in one room at one hour; in the room it is one class.
        val vlsi = RoomAvailability.statusAt(imported.bookings, "203", SlotQuery(DayOfWeek.TUESDAY, 10))

        assertEquals(4, vlsi.bookings.size)
        assertEquals(1, vlsi.groups.size)
        assertEquals("VLSI", vlsi.groups.single().booking.subject)
        assertEquals(
            listOf("2nd Yr ECE-A", "2nd Yr ECE-B", "2nd Yr EE-A", "2nd Yr EE-B"),
            vlsi.groups.single().sections,
        )

        // The third years are in 204 at that hour for their own elective, which is a
        // different class in a different room — the two must not be conflated either.
        val avlsi = RoomAvailability.statusAt(imported.bookings, "204", SlotQuery(DayOfWeek.TUESDAY, 10))

        assertEquals("AVLSI", avlsi.groups.single().booking.subject)
        assertEquals(4, avlsi.groups.single().sections.size)
        // 204 runs AVLSI, EM-I, AVFD, IEHV and ADEC back to back, so it never frees up.
        assertEquals(18, avlsi.busyUntilHour)
        assertEquals("Busy for the rest of the day", avlsi.summary)
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
        // 4th Yr CSE-A's Tuesday page prints ECFC in 311 twice over 2-4 PM: once as the
        // two-hour class the other three fourth-year sections have, and again as a lone
        // 3-4 PM row on the parallel line beneath it. That is a clash in the printed grid,
        // not an import bug, so the room screen must show both lines rather than fold them
        // into the one class the room otherwise looks like it holds. Folding them would
        // give the section two ECFC patterns over an overlapping hour and lose the fact
        // that somebody has to settle which one it attends.
        val groups = RoomAvailability
            .groupsAt(imported.bookings, SlotQuery(DayOfWeek.TUESDAY, 15))
            .filter { it.booking.room == "311" }

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.booking.subject == "ECFC" })
        // The same cohort is in both, which is what makes it a clash rather than a joint class.
        assertTrue(groups.all { "4th Yr CSE-A" in it.sections })
        assertEquals(
            listOf("4th Yr CSE-A", "4th Yr CSE-B", "4th Yr ECE-B", "4th Yr EE"),
            groups.first { it.booking.units == 2 }.sections,
        )
        assertEquals(listOf(2, 1), groups.map { it.booking.units })
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
            SectionSeeder.batchesFor(imported.bookings, "2nd Yr ECE-A", subjects),
        )
        assertEquals(listOf("A1", "A2"), seed("2nd Yr ECE-A", null).batchOptions)
    }

    @Test
    fun `a real section's theory lab and tutorial come out as separate courses`() {
        // PSCS is printed "PSCS-B" for the lectures this section attends and plain "PSCS"
        // for the lab: one subject, two courses, and neither of them called PSCS-B. IEHV is
        // the section's one tutorial, and it is a third course rather than an extra hour on
        // the IEHV lectures. The sports hour is a course like any other here — it reaches
        // the seeder because a student attends it, whatever room it is not in.
        val codes = seed("2nd Yr ECE-A", "A1").proposals.map { it.course.code }

        assertEquals(
            listOf(
                "DE-1", "DE-1 Lab", "EDC", "EDC Lab", "EVS-2", "FIT INDIA", "IEHV",
                "IEHV Tutorial", "NAS", "NAS Lab", "PSCS", "PSCS Lab", "VLSI", "VLSI Lab",
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
        // 3rd Yr CSE-B is the worst case in the file: four subjects numbered B1/B2 and two
        // numbered A2, on one page. B1/B2 wins the vote and is what the student is asked, so
        // the A2-numbered labs have to survive that answer — under a plain `batch == chosen`
        // filter both would vanish, and with them two lab hours a week the student attends.
        assertEquals(listOf("B1", "B2"), SectionSeeder.batchesFor(imported.bookings, "3rd Yr CSE-B", subjects))

        val b1 = seed("3rd Yr CSE-B", "B1").proposals.associateBy { it.course.code }

        assertEquals(listOf("A2"), b1.getValue("DIP Lab").batches)
        assertEquals(listOf("A2"), b1.getValue("NN Lab").batches)

        // The B-numbered labs really do follow the answer, and the page crosses them: the
        // AIML practical is B1 on Tuesday where the CN practical is B2, and the other way
        // round on Monday. Picking B1 must keep Tuesday's and drop Monday's.
        assertEquals(
            listOf(DayOfWeek.TUESDAY to "R2"),
            b1.getValue("AIML Lab").patterns.map { it.dayOfWeek to it.room },
        )
        assertEquals(
            listOf(DayOfWeek.MONDAY to "211"),
            b1.getValue("CN Lab").patterns.map { it.dayOfWeek to it.room },
        )

        val b2 = seed("3rd Yr CSE-B", "B2").proposals.associateBy { it.course.code }

        assertEquals(
            listOf(DayOfWeek.MONDAY to "213"),
            b2.getValue("AIML Lab").patterns.map { it.dayOfWeek to it.room },
        )
        assertEquals(
            listOf(DayOfWeek.TUESDAY to "304"),
            b2.getValue("CN Lab").patterns.map { it.dayOfWeek to it.room },
        )
    }

    @Test
    fun `an elective's own hours are not lost when a tutorial of the same subject is batched away`() {
        // 3rd Yr CSE-B's page carries ECA-B, the two-hour shared elective the whole section
        // attends, and separately one lone `ECA` tutorial row numbered B2. A B1 answer says
        // nothing about which half of the tutorial that section is in — but it must not
        // touch ECA-B, which is a different course and was never in doubt. Splitting the
        // tutorial off from the elective is what keeps the two decisions apart.
        val b1 = seed("3rd Yr CSE-B", "B1").proposals.associateBy { it.course.code }

        assertEquals(
            listOf(DayOfWeek.WEDNESDAY to 16, DayOfWeek.THURSDAY to 13),
            b1.getValue("ECA-B").patterns.map { it.dayOfWeek to it.startHour },
        )
        assertEquals(emptyList<String>(), b1.getValue("ECA-B").batches)
        assertTrue("a B1 student does not attend the B2 tutorial", "ECA Tutorial" !in b1)

        val b2 = seed("3rd Yr CSE-B", "B2").proposals.associateBy { it.course.code }

        assertEquals(
            listOf(DayOfWeek.FRIDAY to 13),
            b2.getValue("ECA Tutorial").patterns.map { it.dayOfWeek to it.startHour },
        )
        assertEquals(listOf("B2"), b2.getValue("ECA Tutorial").batches)
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

    /**
     * The six pages of the printed grid that carry a FIT INDIA block, and its faculty.
     *
     * Three of them land on the same hour — Wednesday 3-5 PM — which is the closest this
     * grid comes to a shared sports hour, and it is printed as three separate rows because
     * each section is taught its own.
     */
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
        // FIT INDIA is printed with no room in brackets, and rows like it were left out of
        // the CSV for exactly that reason once — which quietly cost every second-year
        // student a course they attend two hours a week. It is a class first and a
        // non-booking second, so it belongs in the file with an empty room column. This
        // test is the reason it cannot go missing again: a re-import that drops room-less
        // cells fails right here.
        assertEquals(
            sportsHour.keys.sorted(),
            imported.bookings.filter { it.subject == "FIT INDIA" }.map { it.section }.sorted(),
        )

        sportsHour.forEach { (section, printed) ->
            val (day, startHour, faculty) = printed
            val fit = imported.bookings.single { it.subject == "FIT INDIA" && it.section == section }

            assertEquals(section, day, fit.dayOfWeek)
            assertEquals(section, startHour, fit.startHour)
            assertEquals(section, 2, fit.units)
            assertEquals(section, faculty, fit.faculty)
            assertEquals(section, SessionKind.LECTURE, fit.kind)
            assertNull("$section: FIT INDIA is printed in no room", fit.room)
        }
    }

    @Test
    fun `the sports hour is the only class with no room, and it books none`() {
        val roomless = imported.bookings.filterNot { it.occupiesARoom }

        assertEquals(sportsHour.size, roomless.size)
        assertEquals(listOf("FIT INDIA"), roomless.map { it.subject }.distinct())
        // The room list is derived from the file, so a blank room column must not create a
        // nameless room — and no room may be reported busy by a class that is not in one.
        assertTrue(RoomAvailability.rooms(imported.bookings).none { it.isBlank() })
        sportsHour.forEach { (_, printed) ->
            val (day, startHour, _) = printed
            assertTrue(
                "no room is booked for FIT INDIA",
                RoomAvailability.bookingsAt(imported.bookings, SlotQuery(day, startHour))
                    .none { it.subject == "FIT INDIA" },
            )
        }
        assertTrue(
            "no room's week contains FIT INDIA",
            RoomAvailability.allWeeks(imported.bookings).none { week ->
                week.bookingsByDay.values.any { day -> day.any { it.subject == "FIT INDIA" } }
            },
        )
    }

    @Test
    fun `a section with a sports hour is offered it as a course to keep`() {
        // The point of transcribing it: it has to reach the seeder. One course, one weekly
        // pattern, two hours, and no room to put on the pattern.
        sportsHour.forEach { (section, printed) ->
            val (day, startHour, _) = printed
            val batch = SectionSeeder.batchesFor(imported.bookings, section, subjects).firstOrNull()
            val fit = seed(section, batch).proposals.single { it.course.code == "FIT INDIA" }

            assertEquals(section, "Fit India", fit.course.name)
            assertEquals(section, 2, fit.unitsPerWeek)
            assertEquals(section, emptyList<String>(), fit.batches)
            val pattern = fit.patterns.single()
            assertEquals(section, day, pattern.dayOfWeek)
            assertEquals(section, startHour, pattern.startHour)
            assertEquals(section, 2, pattern.units)
            assertNull("$section: no room to invent", pattern.room)
        }
    }

    @Test
    fun `the sports hour overlaps a class only where the printed grid says it does`() {
        // Five of the six sections have the hour to themselves, and the seeder reports the
        // sports hour as a clash for none of them. 2nd Yr CSE-B's page is the exception: its
        // Thursday carries FIT INDIA across 3-5 PM on one line and the FSS tutorial at 3-4 PM
        // on the parallel line beneath it, so that section really is in two places at once
        // for the hour. That is an overlap in the printed grid, not a quirk of the room-less
        // row, so it has to survive to the confirmation list and be the student's to settle:
        // dropping either side would take a class off their week, and the room-less row is
        // precisely the one an over-eager filter removes first.
        //
        // If a re-import changes this, the expectation moves with the grid — but a section
        // gaining an overlap it did not have is a fact about the timetable that the seeder
        // must go on reporting rather than a test to relax.
        val overlaps = sportsHour.keys.associateWith { section ->
            val batch = SectionSeeder.batchesFor(imported.bookings, section, subjects).firstOrNull()
            seed(section, batch).clashes
                .filter { clash -> clash.subjects.contains("FIT INDIA") }
                .map { it.label }
        }

        assertEquals(
            mapOf(
                "2nd Yr EE-A" to emptyList<String>(),
                "2nd Yr EE-B" to emptyList<String>(),
                "2nd Yr ECE-A" to emptyList<String>(),
                "2nd Yr ECE-B" to emptyList<String>(),
                "2nd Yr CSE-A" to emptyList<String>(),
                "2nd Yr CSE-B" to listOf("Thu ${TimeGrid.slotLabel(15)}: FIT INDIA vs FSS"),
            ),
            overlaps,
        )
    }

    // ---- what the room screens will actually answer -------------------------

    @Test
    fun `no hour of the week leaves the room list empty`() {
        // Every hour of the week has somewhere to sit. That was not true of the previous
        // revision, where Tuesday at eleven was booked solid across every room — so the
        // "right now" view has no empty-handed slot to show, and a re-import that
        // reintroduces one gives the room screens a case to handle rather than a bug.
        val slots = RoomAvailability.daysInUse(imported.bookings).flatMap { day ->
            (TimeGrid.FIRST_START_HOUR..TimeGrid.LAST_START_HOUR).map { SlotQuery(day, it) }
        }

        val fullyBooked = slots.filter { RoomAvailability.freeRoomsAt(imported.bookings, it).isEmpty() }

        assertEquals(emptyList<SlotQuery>(), fullyBooked)
    }

    @Test
    fun `every room's week adds up to the hours it is not booked`() {
        val weeks = RoomAvailability.allWeeks(imported.bookings)
        val days = RoomAvailability.weekdaysFor(imported.bookings).size

        assertEquals("six teaching days, including Saturday", 6, days)
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
