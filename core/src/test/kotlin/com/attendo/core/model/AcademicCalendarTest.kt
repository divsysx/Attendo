package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The teaching calendar that ships with the app, and its default term dates.
 *
 * The default is what an install with no configured dates lands on, so it is the dates a
 * brand-new student's attendance is counted over. Getting it wrong silently counts attendance
 * over the wrong window — exams and prep leave included, or cut short before teaching ends — so
 * the exact day is pinned here.
 */
class AcademicCalendarTest {

    @Test
    fun `the default term runs from 28 July to 20 November 2026`() {
        // 20 November is the dispersal / prep-leave boundary, not the 4 December start of theory
        // exams. Attendance is counted over teaching days; once dispersal begins there are no
        // more classes to attend, so the figure stops here.
        val default = AcademicCalendar.DEFAULT_2026_27

        assertEquals(LocalDate.of(2026, 7, 28), default.termStart)
        assertEquals(LocalDate.of(2026, 11, 20), default.termEnd)
    }

    @Test
    fun `the default ends on a teaching day, not the day after`() {
        val default = AcademicCalendar.DEFAULT_2026_27

        // The boundary day itself is within the term — a class on the 20th still counts.
        assertTrue(default.isWithinTerm(LocalDate.of(2026, 11, 20)))
        assertFalse(default.isWithinTerm(LocalDate.of(2026, 11, 21)))
    }

    @Test
    fun `theory exams in December fall outside the default term`() {
        val default = AcademicCalendar.DEFAULT_2026_27

        // 4 December is when theory exams begin, but it is not a teaching day in the default:
        // the counting window must end with dispersal, not with exams.
        assertFalse(default.isWithinTerm(LocalDate.of(2026, 12, 4)))
    }
}
