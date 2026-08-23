package com.attendo.core.engine

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.SessionPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The Attendance tab must stay anchored to the actual date the student is looking at and never
 * auto-advance to Monday or the next teaching day because today is a Sunday, a holiday, or a
 * day with no classes. There is no advancement code path in Attendance — `DashboardViewModel`
 * anchors `today = clock()` and `DayReviewViewModel` moves the date only through manual
 * `goTo`/`previousDay`/`nextDay`/`goToToday` — so these tests pin the pure logic the screens
 * lean on: `SessionGenerator.dayPlan` returns a plan whose [DayPlan.date] is exactly the date
 * asked for, with the right `isTeachingDay` / `hasAnything` for each kind of day.
 *
 * Each case asserts the date is unchanged first; the calendar flags are the secondary check.
 */
class AttendanceDateAnchoringTest {

    private val termStart: LocalDate = LocalDate.of(2026, 7, 28) // a Tuesday
    private val calendar = AcademicCalendar(
        termStart = termStart,
        termEnd = LocalDate.of(2026, 11, 20),
    )

    /** ADEC: Monday 9-11 in room 204. */
    private val mondayBlock = SessionPattern(
        id = 1L,
        courseId = 100L,
        dayOfWeek = DayOfWeek.MONDAY,
        startHour = 9,
        units = 2,
        room = "204",
        effectiveFrom = termStart,
    )

    /** A Saturday-only class, so a working Saturday has something to show. */
    private val saturdayHour = SessionPattern(
        id = 2L,
        courseId = 100L,
        dayOfWeek = DayOfWeek.SATURDAY,
        startHour = 9,
        units = 1,
        room = "211",
        effectiveFrom = termStart,
    )

    @Test
    fun `a Sunday stays on Sunday and reports nothing`() {
        val sunday = LocalDate.of(2026, 8, 9) // a Sunday inside the term

        val plan = SessionGenerator.dayPlan(
            date = sunday,
            patterns = listOf(mondayBlock),
            existing = emptyList(),
            calendar = calendar,
        )

        assertEquals(sunday, plan.date) // never Monday
        assertFalse(plan.isTeachingDay)
        assertFalse(plan.hasAnything)
    }

    @Test
    fun `a non-working Saturday stays on Saturday`() {
        val saturday = LocalDate.of(2026, 8, 8) // a Saturday not listed as working

        val plan = SessionGenerator.dayPlan(
            date = saturday,
            patterns = listOf(saturdayHour),
            existing = emptyList(),
            calendar = calendar,
        )

        assertEquals(saturday, plan.date) // never Monday
        assertFalse(plan.isTeachingDay)
        assertFalse(plan.hasAnything) // the pattern does not apply on a non-working Saturday
    }

    @Test
    fun `a working Saturday shows its classes`() {
        val workingSaturday = LocalDate.of(2026, 8, 15)
        val withWorkingSaturday = calendar.copy(workingSaturdays = setOf(workingSaturday))

        val plan = SessionGenerator.dayPlan(
            date = workingSaturday,
            patterns = listOf(saturdayHour),
            existing = emptyList(),
            calendar = withWorkingSaturday,
        )

        assertEquals(workingSaturday, plan.date)
        assertTrue(plan.isTeachingDay)
        assertTrue(plan.hasAnything) // the Saturday pattern generated a draft
    }

    @Test
    fun `a holiday stays on the holiday`() {
        val holiday = LocalDate.of(2026, 8, 3) // a Monday declared a holiday
        val withHoliday = calendar.copy(holidays = setOf(holiday))

        val plan = SessionGenerator.dayPlan(
            date = holiday,
            patterns = listOf(mondayBlock),
            existing = emptyList(),
            calendar = withHoliday,
        )

        assertEquals(holiday, plan.date) // never the next Monday
        assertFalse(plan.isTeachingDay)
        assertFalse(plan.hasAnything)
    }

    @Test
    fun `a working day with no classes stays on that day`() {
        val tuesday = LocalDate.of(2026, 8, 4) // a Tuesday inside the term, no Tuesday pattern

        val plan = SessionGenerator.dayPlan(
            date = tuesday,
            patterns = listOf(mondayBlock), // Monday only — Tuesday has nothing
            existing = emptyList(),
            calendar = calendar,
        )

        assertEquals(tuesday, plan.date) // never advanced to a day that has classes
        assertTrue(plan.isTeachingDay)
        assertFalse(plan.hasAnything)
    }

    @Test
    fun `a normal working day is shown unchanged`() {
        val monday = LocalDate.of(2026, 8, 3) // a working Monday with a matching pattern

        val plan = SessionGenerator.dayPlan(
            date = monday,
            patterns = listOf(mondayBlock),
            existing = emptyList(),
            calendar = calendar,
        )

        assertEquals(monday, plan.date)
        assertTrue(plan.isTeachingDay)
        assertTrue(plan.hasAnything) // the Monday block generated a draft
    }
}
