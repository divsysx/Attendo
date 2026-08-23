package com.attendo.core.data

import com.attendo.core.model.RoomBooking
import com.attendo.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Seeding a student's courses from their section's page of the timetable.
 *
 * The fixture is one plausible section: a subject with both a lecture and a lab, a subject
 * with a lecture and a tutorial, a lab split into two batches, a subject printed with its
 * lecture-group tag, a lab pooled with another section under a batch number of its own, and
 * the batch-split pair that reads as a clash until a batch is chosen.
 */
class SectionSeederTest {

    private val termStart = LocalDate.of(2026, 7, 28)

    private fun booking(
        day: DayOfWeek,
        startHour: Int,
        units: Int = 1,
        room: String? = "204",
        subject: String,
        section: String = "3rd Yr CSE-A",
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

    private val subjects = Glossary.parse(
        """
        ADEC,Analog and Digital Electronic Circuits
        TOC,Theory of Computation
        NN,Neural Networks
        PSCS,Probability and Statistics for Computer Science
        DBMS,Database Management Systems
        DSD,Digital System Design
        """.trimIndent(),
    )

    private val timetable = listOf(
        // ADEC: a lecture and a lab, the lab split by batch.
        booking(DayOfWeek.MONDAY, 9, units = 2, subject = "ADEC"),
        booking(DayOfWeek.WEDNESDAY, 14, units = 2, room = "R1", subject = "ADEC", kind = SessionKind.PRACTICAL, batch = "A1"),
        booking(DayOfWeek.THURSDAY, 14, units = 2, room = "R1", subject = "ADEC", kind = SessionKind.PRACTICAL, batch = "A2"),
        // A plain lecture with a tutorial.
        booking(DayOfWeek.TUESDAY, 11, subject = "TOC"),
        booking(DayOfWeek.FRIDAY, 15, subject = "TOC", kind = SessionKind.TUTORIAL),
        // One subject, two lecture groups sharing an hour: the section is in one of them.
        booking(DayOfWeek.TUESDAY, 9, units = 2, room = "313", subject = "NN-A"),
        booking(DayOfWeek.TUESDAY, 9, units = 2, room = "314", subject = "NN-B"),
        // A pooled course: lectures tagged with this section's group, lab batched B3 with
        // whoever else is in it.
        booking(DayOfWeek.MONDAY, 13, units = 2, room = "R2", subject = "PSCS-B"),
        booking(DayOfWeek.FRIDAY, 9, units = 2, room = "R2", subject = "PSCS", kind = SessionKind.PRACTICAL, batch = "B3"),
        // Batch-split labs at the same hour: a clash only until a batch is picked.
        booking(DayOfWeek.WEDNESDAY, 16, units = 2, room = "R4", subject = "DSD", kind = SessionKind.PRACTICAL, batch = "A2"),
        booking(DayOfWeek.WEDNESDAY, 16, units = 2, room = "R4", subject = "DBMS", kind = SessionKind.PRACTICAL, batch = "A1"),
        // Another section's page must not leak in.
        booking(DayOfWeek.MONDAY, 9, room = "211", subject = "CN", section = "3rd Yr CSE-B"),
    )

    private fun plan(batch: String? = null) =
        SectionSeeder.plan(timetable, "3rd Yr CSE-A", termStart, batch, subjects)

    // ---- what gets proposed -------------------------------------------------

    @Test
    fun `only the chosen section's subjects are proposed`() {
        assertEquals(
            listOf(
                "ADEC", "ADEC Lab", "DBMS Lab", "DSD Lab", "NN",
                "PSCS", "PSCS Lab", "TOC", "TOC Tutorial",
            ),
            plan().proposals.map { it.course.code },
        )
    }

    @Test
    fun `a lab is its own course, separate from the lectures`() {
        // Marked, examined and attended separately — and a term of missed labs should not
        // disappear into a healthy lecture percentage.
        val proposals = plan().proposals.associateBy { it.course.code }

        val theory = proposals.getValue("ADEC")
        assertEquals("Analog and Digital Electronic Circuits", theory.course.name)
        assertEquals(listOf(SessionKind.LECTURE), theory.patterns.map { it.kind })
        assertEquals(2, theory.unitsPerWeek)

        val lab = proposals.getValue("ADEC Lab")
        assertEquals("Analog and Digital Electronic Circuits Lab", lab.course.name)
        assertEquals(
            listOf(SessionKind.PRACTICAL, SessionKind.PRACTICAL),
            lab.patterns.map { it.kind },
        )
        assertEquals(4, lab.unitsPerWeek)
    }

    @Test
    fun `a tutorial is its own course too`() {
        // The third of a subject's three courses. TOC is one lecture hour and one tutorial
        // hour, credited and attended apart, so they are not one two-hour course.
        val proposals = plan().proposals.associateBy { it.course.code }

        val theory = proposals.getValue("TOC")
        assertEquals("Theory of Computation", theory.course.name)
        assertEquals(listOf(SessionKind.LECTURE), theory.patterns.map { it.kind })

        val tutorial = proposals.getValue("TOC Tutorial")
        assertEquals("Theory of Computation Tutorial", tutorial.course.name)
        assertEquals(listOf(SessionKind.TUTORIAL), tutorial.patterns.map { it.kind })
        assertEquals(DayOfWeek.FRIDAY, tutorial.patterns.single().dayOfWeek)
    }

    @Test
    fun `a -T tag marks a tutorial even where the kind column does not`() {
        // The grid records the same fact two ways: a `T` in the kind column, or a `-T` on
        // the subject. Either is enough, and the tag wins over a column that says lecture —
        // otherwise a page that tags instead of coding gets its tutorials silently merged
        // into the theory course.
        val tagged = listOf(
            booking(DayOfWeek.MONDAY, 9, units = 2, subject = "ADEC"),
            booking(DayOfWeek.THURSDAY, 15, subject = "ADEC-T"),
        )

        val proposals = SectionSeeder.plan(tagged, "3rd Yr CSE-A", termStart, subjects = subjects)
            .proposals
            .associateBy { it.course.code }

        assertEquals(setOf("ADEC", "ADEC Tutorial"), proposals.keys)
        assertEquals(
            "Analog and Digital Electronic Circuits Tutorial",
            proposals.getValue("ADEC Tutorial").course.name,
        )
        // Recorded as a tutorial, not as the lecture the kind column claimed.
        assertEquals(
            listOf(SessionKind.TUTORIAL),
            proposals.getValue("ADEC Tutorial").patterns.map { it.kind },
        )
        assertEquals(2, proposals.getValue("ADEC").unitsPerWeek)
    }

    @Test
    fun `a lecture group tag is not part of the subject`() {
        // PSCS-B and PSCS are one subject: the section sits in lecture group B, and the
        // key has only PSCS. Keeping the tag would make the lectures and the lab two
        // unrelated courses.
        val theory = plan().proposals.single { it.course.code == "PSCS" }
        val lab = plan().proposals.single { it.course.code == "PSCS Lab" }

        assertEquals("Probability and Statistics for Computer Science", theory.course.name)
        assertEquals(2, theory.unitsPerWeek)
        assertEquals("Probability and Statistics for Computer Science Lab", lab.course.name)
        assertTrue(plan().proposals.none { it.course.code.endsWith("-B") })
    }

    @Test
    fun `a subject code whose suffix is part of its name survives`() {
        val hyphenated = listOf(
            booking(DayOfWeek.MONDAY, 9, subject = "DE-1"),
            booking(DayOfWeek.TUESDAY, 9, subject = "EVS-2"),
            booking(DayOfWeek.WEDNESDAY, 9, subject = "EM-I"),
            booking(DayOfWeek.THURSDAY, 9, units = 4, subject = "AEW-I"),
        )

        assertEquals(
            listOf("AEW-I", "DE-1", "EM-I", "EVS-2"),
            SectionSeeder.plan(hyphenated, "3rd Yr CSE-A", termStart)
                .proposals
                .map { it.course.code },
        )
    }

    @Test
    fun `one subject's two lecture groups at one hour collapse into one class`() {
        // NN-A in 313 and NN-B in 314 are the same lecture printed for both groups.
        val nn = plan().proposals.single { it.course.code == "NN" }

        assertEquals("Neural Networks", nn.course.name)
        assertEquals(1, nn.patterns.size)
        assertEquals(2, nn.unitsPerWeek)
    }

    @Test
    fun `patterns carry the slot the room and the term start`() {
        val toc = plan().proposals.single { it.course.code == "TOC" }
        val lecture = toc.patterns.first()

        assertEquals(DayOfWeek.TUESDAY, lecture.dayOfWeek)
        assertEquals(11, lecture.startHour)
        assertEquals(1, lecture.units)
        assertEquals("204", lecture.room)
        assertEquals(termStart, lecture.effectiveFrom)
        assertTrue(lecture.isOpenEnded)
    }

    @Test
    fun `patterns come out in weekly order`() {
        val lab = plan().proposals.single { it.course.code == "ADEC Lab" }

        assertEquals(
            listOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            lab.patterns.map { it.dayOfWeek },
        )
        assertEquals("Wed 2–4 PM, Thu 2–4 PM", lab.scheduleLabel)
    }

    @Test
    fun `an unknown code still yields a usable course`() {
        val plan = SectionSeeder.plan(timetable, "3rd Yr CSE-A", termStart, subjects = Glossary.EMPTY)
        val adec = plan.proposals.single { it.course.code == "ADEC" }

        // Course requires a non-blank name, so the code has to stand in for it.
        assertEquals("ADEC", adec.course.name)
        assertEquals("ADEC", adec.course.code)
        assertEquals("ADEC Lab", plan.proposals.single { it.course.code == "ADEC Lab" }.course.name)
    }

    // ---- batches ------------------------------------------------------------

    @Test
    fun `only the section's own batch scheme is offered as a choice`() {
        // B3 is the pooled statistics lab's numbering, not a choice this section makes.
        assertEquals(listOf("A1", "A2"), SectionSeeder.batchesFor(timetable, "3rd Yr CSE-A"))
        assertEquals(listOf("A1", "A2"), plan().batchOptions)
        assertTrue(plan().needsBatchChoice)
    }

    @Test
    fun `choosing a batch keeps that lab and drops the other`() {
        val a1 = plan(batch = "A1")
        val lab = a1.proposals.single { it.course.code == "ADEC Lab" }

        assertEquals(listOf(DayOfWeek.WEDNESDAY), lab.patterns.map { it.dayOfWeek })
        assertEquals(2, lab.unitsPerWeek)
        assertFalse(a1.needsBatchChoice)
    }

    @Test
    fun `whole section classes survive a batch choice`() {
        // Neither TOC hour is batched, so both courses come through whichever batch is
        // picked — a batch filter must not touch a class the whole section attends.
        val a2 = plan(batch = "A2").proposals.associateBy { it.course.code }

        assertEquals(1, a2.getValue("TOC").patterns.size)
        assertEquals(1, a2.getValue("TOC Tutorial").patterns.size)
    }

    @Test
    fun `a lab numbered outside the section's scheme survives any batch choice`() {
        // The B3 statistics lab is the only one on this page: an A1 student is in it, and
        // so is an A2 student. Filtering on the chosen batch alone would delete it.
        listOf("A1", "A2").forEach { batch ->
            val lab = plan(batch = batch).proposals.single { it.course.code == "PSCS Lab" }

            assertEquals("one PSCS lab for batch $batch", 1, lab.patterns.size)
            assertEquals(listOf("B3"), lab.batches)
        }
    }

    @Test
    fun `a course taught only to another batch is not proposed at all`() {
        // DBMS is an A1-only lab here, and A1 is this section's own numbering, so an A2
        // student should never see it.
        val codes = plan(batch = "A2").proposals.map { it.course.code }

        assertTrue("DSD Lab" in codes)
        assertFalse("DBMS Lab" in codes)
    }

    @Test
    fun `a proposal says whether it is batch split`() {
        val proposals = plan().proposals.associateBy { it.course.code }

        assertTrue(proposals.getValue("ADEC Lab").isBatchSplit)
        assertEquals(listOf("A1", "A2"), proposals.getValue("ADEC Lab").batches)
        assertFalse(proposals.getValue("ADEC").isBatchSplit)
        assertFalse(proposals.getValue("TOC").isBatchSplit)
    }

    @Test
    fun `a second batch scheme is flagged rather than guessed at`() {
        val second = listOf(
            booking(DayOfWeek.MONDAY, 9, units = 2, subject = "ADEC", kind = SessionKind.PRACTICAL, batch = "A1"),
            booking(DayOfWeek.TUESDAY, 9, units = 2, subject = "ADEC", kind = SessionKind.PRACTICAL, batch = "A2"),
            // A workshop batched B1/B2 — a choice the A1/A2 answer says nothing about.
            booking(DayOfWeek.THURSDAY, 14, units = 4, subject = "AEW-I", batch = "B1"),
            booking(DayOfWeek.FRIDAY, 14, units = 4, subject = "AEW-I", batch = "B2"),
        )

        val proposals = SectionSeeder.plan(second, "3rd Yr CSE-A", termStart, batch = "A1")
            .proposals
            .associateBy { it.course.code }

        assertFalse(proposals.getValue("ADEC Lab").needsBatchCheck)
        val workshop = proposals.getValue("AEW-I")
        assertTrue(workshop.needsBatchCheck)
        assertEquals(listOf("B1", "B2"), workshop.batches)
    }

    // ---- clashes ------------------------------------------------------------

    @Test
    fun `a batch split pair reads as a clash until a batch is chosen`() {
        val lab = plan().clashes.first { "DSD" in it.subjects }

        assertEquals(listOf("DBMS", "DSD"), lab.subjects)
        assertEquals("Wed 4–5 PM: DBMS (A1) vs DSD (A2)", lab.label)
    }

    @Test
    fun `choosing a batch resolves the batch split clash`() {
        val labSubjects = setOf("DSD", "DBMS")

        assertTrue(plan(batch = "A1").clashes.none { it.subjects.any { s -> s in labSubjects } })
        assertTrue(plan(batch = "A2").clashes.none { it.subjects.any { s -> s in labSubjects } })
    }

    @Test
    fun `a clash is reported for every hour a block overlaps`() {
        // The DSD/DBMS labs are two hours, so both hours conflict.
        assertEquals(listOf(16, 17), plan().clashes.filter { "DSD" in it.subjects }.map { it.startHour })
    }

    @Test
    fun `clashes come out in weekly order`() {
        assertEquals(
            listOf(DayOfWeek.WEDNESDAY to 16, DayOfWeek.WEDNESDAY to 17),
            plan().clashes.map { it.dayOfWeek to it.startHour },
        )
    }

    @Test
    fun `one subject's two lecture groups at one hour are not a clash`() {
        val groups = SectionSeeder.plan(
            listOf(
                booking(DayOfWeek.TUESDAY, 9, units = 2, room = "313", subject = "NN-A"),
                booking(DayOfWeek.TUESDAY, 9, units = 2, room = "314", subject = "NN-B"),
            ),
            "3rd Yr CSE-A",
            termStart,
            subjects = subjects,
        )

        assertEquals(emptyList<SeedClash>(), groups.clashes)
        assertEquals(listOf("NN"), groups.proposals.map { it.course.code })
    }

    // ---- handing the plan to the database -----------------------------------

    @Test
    fun `stamping the course id wires up its patterns`() {
        val lab = plan().proposals.single { it.course.code == "ADEC Lab" }.withCourseId(42L)

        assertEquals(42L, lab.course.id)
        assertEquals(listOf(42L, 42L), lab.patterns.map { it.courseId })
    }

    @Test
    fun `the plan summarises itself for the confirmation screen`() {
        // A1 takes ADEC (2h) + its lab (2h), DBMS Lab (2h), NN (2h), PSCS (2h) + its lab
        // (2h), and TOC as a lecture hour plus a tutorial hour.
        val a1 = plan(batch = "A1")

        assertEquals(8, a1.proposals.size)
        assertEquals(14, a1.unitsPerWeek)
        assertEquals("8 courses, 14 hours a week", a1.summary)
        assertFalse(a1.isEmpty)
    }

    @Test
    fun `a section with nothing on its page yields an empty plan`() {
        val empty = SectionSeeder.plan(timetable, "4th Yr EE", termStart, subjects = subjects)

        assertTrue(empty.isEmpty)
        assertEquals(0, empty.unitsPerWeek)
        assertEquals("0 courses, 0 hours a week", empty.summary)
        assertFalse(empty.needsBatchChoice)
    }

    // ---- a class printed in no room ------------------------------------------

    @Test
    fun `a class with no room is proposed like any other`() {
        // SFL, the sports hour, is printed without a room. It is two hours the student is
        // expected to attend, so it has to be offered — the room is what is missing, not
        // the class.
        val withSportsHour = timetable + booking(
            DayOfWeek.FRIDAY,
            15,
            units = 2,
            room = null,
            subject = "SFL",
        )
        val glossary = Glossary.parse("SFL,Sports for Life / Fit India")

        val sfl = SectionSeeder
            .plan(withSportsHour, "3rd Yr CSE-A", termStart, "A1", glossary)
            .proposals
            .single { it.course.code == "SFL" }

        assertEquals("Sports for Life / Fit India", sfl.course.name)
        assertEquals(2, sfl.unitsPerWeek)
        assertEquals(DayOfWeek.FRIDAY, sfl.patterns.single().dayOfWeek)
        // No room on the pattern either: the sessions it generates say "no room", which is
        // what the printed grid says, rather than a room the student would go to and find
        // somebody else's class in.
        assertNull(sfl.patterns.single().room)
    }
}
