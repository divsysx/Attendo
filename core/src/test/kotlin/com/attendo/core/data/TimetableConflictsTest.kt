package com.attendo.core.data

import com.attendo.core.engine.ConflictKind
import com.attendo.core.engine.TimetableConflicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The complete room/faculty sweep of the timetable that ships.
 *
 * The migration audit left this as the one required pre-import step it could not do:
 * "A complete clash sweep of NEW has not been run... Any clash found must be reported, not
 * filtered." This is that sweep, and it is a test rather than a script so a re-import cannot
 * quietly introduce a double booking.
 *
 * It has to answer two questions that look the same, which is why [TimetableConflicts] exists
 * at all:
 *
 * - a class printed on every cohort's page arrives as several rows naming one room and one
 *   hour, and folding those is the *normal* case — this timetable is overwhelmingly clubbed;
 * - a room or a teacher genuinely named twice at one hour is the *abnormal* case, and the
 *   sweep's whole value is that the abnormal list is short.
 *
 * So the assertions are two-sided on purpose: the clubbed cases must be recognised as clubbed
 * (a classifier that reported everything would pass only the second half), and the genuine
 * findings are pinned exactly — a new one is a deliberate act, not a diff to skim past.
 */
class TimetableConflictsTest {

    private fun asset(name: String): String {
        val file = File("../app/src/main/assets/$name")
        assertTrue("missing bundled asset ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val imported = TimetableCsv.parse(asset("timetable.csv"))
    private val conflicts = TimetableConflicts.inUse(imported.bookings)

    private val clubbed get() = conflicts.filter { it.kind == ConflictKind.CLUBBED }
    private val genuine get() = conflicts.filter { it.kind == ConflictKind.GENUINE }

    // ---- the headline: what a validation run reports ------------------------

    @Test
    fun `the sweep reports exactly one thing to look at`() {
        // 4th Yr CSE-A's Tuesday page prints ECFC [JP] (311) twice over 2-4 PM: once as the
        // 2-hour class the other three sections have, and again as a lone 3-4 PM row on the
        // parallel line under it. Faithful to the printed grid, and not foldable — the
        // section would be given two overlapping ECFC patterns — so it is reported.
        assertEquals(
            listOf(
                "Tue 3 PM, Room 311: ECFC — 4th Yr CSE-A twice " +
                    "(4th Yr CSE-A, 4th Yr CSE-B, 4th Yr ECE-B, 4th Yr EE)",
            ),
            genuine.map { it.label },
        )
        assertEquals(listOf("4th Yr CSE-A"), genuine.single().repeated)
        assertTrue(genuine.single().bookings.count { it.section == "4th Yr CSE-A" } == 2)
    }

    @Test
    fun `no teacher is in two places at once`() {
        // The room sweep alone would miss a teacher double-booked *across* rooms, which is
        // the one clash a student cannot resolve by sitting somewhere else. There is none;
        // the paired-teaching cells (`[AKT / SG]`) are each checked under both codes.
        assertEquals(emptyList<String>(), genuine.filter { it.room == null }.map { it.label })
        assertTrue(imported.bookings.any { it.faculty?.contains('/') == true })
    }

    @Test
    fun `every other busy hour of the week is cohorts sharing one class`() {
        assertEquals(116, clubbed.size)
        assertTrue(clubbed.all { it.sections.size == it.bookings.size })
        assertTrue(clubbed.all { it.subjects.size == 1 })
        assertTrue(conflicts.size == clubbed.size + genuine.size)
    }

    // ---- the case the classifier exists to get right ------------------------

    @Test
    fun `the DASIC practical is one clubbed class, not four room conflicts`() {
        // 4th Yr ECE-A batch A1 and 4th Yr ECE-B batch B1 both attend DASIC [KS] in Room 314,
        // Monday 11-1 and Tuesday 4-6 — the four cells that were reported as room conflicts.
        // Each section numbers its own half differently, so the rows disagree on batch and
        // agree on everything else; that is why batch is not part of the class key.
        val dasic = clubbed.filter { it.room == "314" && it.subjects == listOf("DASIC") }

        assertEquals(
            listOf("Mon 11 AM–1 PM", "Tue 4 PM–6 PM"),
            dasic.map { "${it.dayLabel} ${it.slotLabel}" },
        )
        assertTrue(dasic.all { it.sections == listOf("4th Yr ECE-A", "4th Yr ECE-B") })
        assertTrue(dasic.all { it.bookings.map { b -> b.batch }.toSet() == setOf("A1", "B1") })
        assertTrue(dasic.all { it.repeated.isEmpty() })
        assertTrue("DASIC must not be a genuine finding", genuine.none { "DASIC" in it.subjects })
    }

    @Test
    fun `a clubbed run of hours is one finding, not one per hour`() {
        // Worth pinning: two hours on each of two days is four cells, and a report that
        // listed four lines for one clubbed practical would bury the genuine findings.
        assertEquals(2, clubbed.count { it.room == "314" && it.subjects == listOf("DASIC") })
    }

    @Test
    fun `one cohort with two bookings in the same room is not folded away`() {
        // The inverse of the clubbing rule, and the reason it is drawn where it is. Two
        // *sections* in one room at one hour is the grid's way of writing a shared class;
        // two bookings for *one* section is not, whoever they are for — the room cannot hold
        // both, and the cohort has been given two classes at once.
        val eh = imported.bookings
            .filter { it.subject == "EH" && it.room == "R1" }
            .map { it.copy(section = "3rd Yr CSE-A", batch = it.batch ?: "A1") }

        assertTrue(eh.size > 1)
        val found = TimetableConflicts.genuine(eh)

        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.room == "R1" })
        assertTrue(found.all { it.sections == listOf("3rd Yr CSE-A") })
        assertTrue(found.all { it.repeated == listOf("3rd Yr CSE-A") })
    }

    @Test
    fun `two different classes in one room are reported, not folded`() {
        // The other half of the same rule: rows that disagree on the subject are two classes,
        // whatever else matches. Nothing in the shipped file does this — see
        // `every other busy hour of the week is cohorts sharing one class` — so the case is
        // built here, which is also what keeps that assertion honest.
        val eh = imported.bookings.filter { it.subject == "EH" && it.room == "R1" }
        val stranger = imported.bookings.first { it.subject == "EW" }
        val clash = eh.take(1) + stranger.copy(
            dayOfWeek = eh.first().dayOfWeek,
            startHour = eh.first().startHour,
            units = eh.first().units,
            room = eh.first().room,
        )

        assertTrue(clash.map { it.subject }.distinct().size > 1)
        val found = TimetableConflicts.genuine(clash)

        assertTrue(found.any { it.subjects.size > 1 })
        assertTrue(found.all { it.room == "R1" })
    }
}
