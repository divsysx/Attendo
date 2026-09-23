package com.attendo.core.model

import com.attendo.core.data.TimetableCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which sections the timetable teaches on Saturday every week.
 *
 * The 2026-27 revision prints a Saturday column, and nine of the twenty-two section pages
 * carry a full Saturday day in it. Those nine are taught on Saturday as a matter of course,
 * so their Saturday classes come from the weekly pattern — there is no list of dates for a
 * student to maintain, and Settings does not offer them one.
 *
 * The distinction this file exists to hold is that a printed Saturday cell is *not* the same
 * claim. Some 3rd-year pages also carry Saturday content, and whether that is weekly, a
 * one-off, or a working Saturday nobody has confirmed is exactly the question the printed
 * grid does not answer. So the set of sections is written down here rather than derived from
 * the timetable: the count of cells that look like Saturday teaching is evidence, not a
 * decision, and inferring a section from it would enrol students in classes on days the
 * faculty never scheduled for them.
 *
 * These names are column values in `timetable.csv`, which is why the section list is read
 * from the bundled file rather than repeated: a rename would otherwise turn a real section
 * into an unknown one and quietly switch its Saturdays off.
 */
class RecurringSaturdaysTest {

    private fun asset(name: String): String {
        // Tests run with the module directory as the working directory.
        val file = File("../app/src/main/assets/$name")
        assertTrue("missing bundled asset ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val sections: List<String> = TimetableCsv.parse(asset("timetable.csv")).sections

    // ---- the nine ------------------------------------------------------------

    @Test
    fun `every first year section is taught on Saturday`() {
        val firstYear = sections.filter { it.startsWith("1st Yr ") }

        // Six: CSE-A/B, ECE-A/B, EE-A/B. Pinned so a re-import that dropped a division
        // would fail here rather than silently leave that division out of the rule.
        assertEquals(firstYear.toString(), 6, firstYear.size)
        firstYear.forEach { section ->
            assertTrue(section, RecurringSaturdays.appliesTo(section))
        }
    }

    @Test
    fun `the three fourth year sections the timetable teaches on Saturday are included`() {
        assertTrue(RecurringSaturdays.appliesTo("4th Yr EE"))
        assertTrue(RecurringSaturdays.appliesTo("4th Yr ECE-A"))
        assertTrue(RecurringSaturdays.appliesTo("4th Yr ECE-B"))
    }

    @Test
    fun `exactly nine of the bundled sections are taught on Saturday`() {
        val recurring = sections.filter(RecurringSaturdays::appliesTo)

        assertEquals(recurring.toString(), 9, recurring.size)
    }

    // ---- what is deliberately not inferred -----------------------------------

    @Test
    fun `a section with no decision made about it is not taught on Saturday`() {
        // The 3rd-year pages carry Saturday content in the revised grid and nothing has
        // said whether it repeats. Until something does, the honest answer is no: the
        // Working Saturdays setting stays available to these students, and ticking a date
        // is how they get a Saturday class.
        val undecided = sections.filter {
            it.startsWith("3rd Yr ") || it.startsWith("2nd Yr ")
        }

        assertTrue(undecided.isNotEmpty())
        undecided.forEach { section ->
            assertFalse(section, RecurringSaturdays.appliesTo(section))
        }
    }

    @Test
    fun `the fourth year sections outside the three are not taught on Saturday`() {
        // The near miss the rule has to get right: 4th Yr CSE-A sits next to 4th Yr ECE-A
        // in the same year, and only one of them is in the set.
        assertFalse(RecurringSaturdays.appliesTo("4th Yr CSE-A"))
        assertFalse(RecurringSaturdays.appliesTo("4th Yr CSE-B"))
    }

    @Test
    fun `a first year rule does not swallow a section merely beginning with 1`() {
        // "1st Yr " with the space and the year suffix is the whole of the first-year rule.
        // A prefix of "1st Yr" alone would also match a hypothetical "1st Yrly", and a
        // prefix of "1" would match every section whose name starts with a digit.
        assertFalse(RecurringSaturdays.appliesTo("1st Yr"))
        assertFalse(RecurringSaturdays.appliesTo("1st Year EE-A"))
    }

    // ---- a student who has not said ------------------------------------------

    @Test
    fun `a student who has not chosen a section is not assumed to be taught on Saturday`() {
        // The default has to be the model the app already had. Answering true here would
        // generate Saturday classes for every install that has not been seeded yet.
        assertFalse(RecurringSaturdays.appliesTo(null))
        assertFalse(RecurringSaturdays.appliesTo(""))
        assertFalse(RecurringSaturdays.appliesTo("   "))
    }

    @Test
    fun `a section the timetable does not contain is not taught on Saturday`() {
        assertFalse(RecurringSaturdays.appliesTo("5th Yr CSE-A"))
        assertFalse(RecurringSaturdays.appliesTo("Not a section"))
    }

    @Test
    fun `surrounding space does not change the answer`() {
        // The stored section comes from a seeded CSV row, but it is a string the student
        // can set, and " 1st Yr EE-A" must not fall out of the rule.
        assertTrue(RecurringSaturdays.appliesTo(" 1st Yr EE-A "))
        assertTrue(RecurringSaturdays.appliesTo("4th Yr EE "))
    }

    @Test
    fun `the named sections are exactly the three the rule adds to first year`() {
        assertEquals(
            setOf("4th Yr EE", "4th Yr ECE-A", "4th Yr ECE-B"),
            RecurringSaturdays.namedSections,
        )
    }
}
