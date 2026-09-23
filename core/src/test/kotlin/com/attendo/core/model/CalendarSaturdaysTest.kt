package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Every Saturday in the term, and what treating them all as working days does.
 *
 * The 2026-27 timetable teaches nine sections on Saturday every week, and the calendar's
 * existing model says the opposite — Saturday is a holiday unless a date is listed in
 * `workingSaturdays`. Rather than write the term's Saturdays into the stored calendar, which
 * would put a derived fact into the student's own settings and freeze it against any later
 * change of term dates, the dates are generated and the resulting calendar is a separate
 * value. These tests are about that value: the list it is built from, the bound at each end,
 * and the fact that a holiday still wins over it.
 */
class CalendarSaturdaysTest {

    private val term = AcademicCalendar(
        termStart = LocalDate.of(2026, 7, 28), // a Tuesday
        termEnd = LocalDate.of(2026, 11, 20), // a Friday
    )

    // ---- the list the picker offers ------------------------------------------

    @Test
    fun `the term's Saturdays are every one of them, in order`() {
        val saturdays = term.saturdaysInTerm()

        assertTrue(saturdays.isNotEmpty())
        assertTrue(saturdays.all { it.dayOfWeek == DayOfWeek.SATURDAY })
        assertEquals(saturdays.sorted(), saturdays)
        assertEquals(saturdays.distinct(), saturdays)
    }

    @Test
    fun `the first Saturday is the one after the term starts, not the term start`() {
        // 28 July 2026 is a Tuesday, so the first Saturday is the 1st of August. A picker
        // built from "the next Saturday or the term's start day" would offer a Tuesday.
        assertEquals(LocalDate.of(2026, 8, 1), term.saturdaysInTerm().first())
    }

    @Test
    fun `a term that starts on a Saturday includes that day`() {
        val startingOnSaturday = AcademicCalendar(
            termStart = LocalDate.of(2026, 8, 1),
            termEnd = LocalDate.of(2026, 11, 20),
        )

        assertEquals(LocalDate.of(2026, 8, 1), startingOnSaturday.saturdaysInTerm().first())
    }

    @Test
    fun `the last Saturday is inside the term, and the next one is not`() {
        val last = term.saturdaysInTerm().last()

        assertEquals(LocalDate.of(2026, 11, 14), last)
        assertTrue(term.isWithinTerm(last))
        assertFalse(term.isWithinTerm(last.plusWeeks(1)))
    }

    @Test
    fun `a term ending on a Saturday includes that Saturday`() {
        val endingOnSaturday = AcademicCalendar(
            termStart = LocalDate.of(2026, 7, 28),
            termEnd = LocalDate.of(2026, 11, 14),
        )

        assertEquals(LocalDate.of(2026, 11, 14), endingOnSaturday.saturdaysInTerm().last())
    }

    @Test
    fun `a term of one week offers only the Saturdays inside it`() {
        val oneWeek = AcademicCalendar(
            termStart = LocalDate.of(2026, 8, 3), // Monday
            termEnd = LocalDate.of(2026, 8, 9), // Sunday
        )

        assertEquals(listOf(LocalDate.of(2026, 8, 8)), oneWeek.saturdaysInTerm())
    }

    // ---- promoting them ------------------------------------------------------

    @Test
    fun `promoting every Saturday makes each of them a teaching day`() {
        val promoted = term.withEverySaturdayWorking()

        term.saturdaysInTerm().forEach { saturday ->
            assertTrue(saturday.toString(), promoted.isTeachingDay(saturday))
        }
    }

    @Test
    fun `promoting every Saturday changes nothing else`() {
        val promoted = term.withEverySaturdayWorking()

        assertEquals(term.termStart, promoted.termStart)
        assertEquals(term.termEnd, promoted.termEnd)
        assertEquals(term.holidays, promoted.holidays)
        assertFalse(promoted.isTeachingDay(LocalDate.of(2026, 8, 2))) // Sunday, still not one
        assertTrue(promoted.isTeachingDay(LocalDate.of(2026, 8, 3))) // Monday, unchanged
    }

    @Test
    fun `promoting keeps Saturdays a student already ticked`() {
        val ticked = term.copy(workingSaturdays = setOf(LocalDate.of(2026, 8, 1)))

        val promoted = ticked.withEverySaturdayWorking()

        assertTrue(LocalDate.of(2026, 8, 1) in promoted.workingSaturdays)
        assertEquals(term.saturdaysInTerm().toSet(), promoted.workingSaturdays)
    }

    @Test
    fun `promoting twice is the same as promoting once`() {
        // Idempotent, because nothing stops a section being re-derived on every read.
        val once = term.withEverySaturdayWorking()

        assertEquals(once, once.withEverySaturdayWorking())
    }

    @Test
    fun `a holiday still wins over a promoted Saturday`() {
        // The one thing that must survive the derivation: a declared holiday empties a day
        // whatever the patterns say. A closure the faculty announced beats the weekly grid.
        val withHoliday = term.copy(holidays = setOf(LocalDate.of(2026, 8, 8)))

        val promoted = withHoliday.withEverySaturdayWorking()

        assertFalse(promoted.isWorkingDay(LocalDate.of(2026, 8, 8)))
        assertFalse(promoted.isTeachingDay(LocalDate.of(2026, 8, 8)))
        assertTrue(promoted.isTeachingDay(LocalDate.of(2026, 8, 15)))
    }

    @Test
    fun `the term gains exactly its Saturdays as teaching days`() {
        val before = term.teachingDaysBetween(term.termStart, term.termEnd).size
        val promoted = term.withEverySaturdayWorking()
        val after = promoted.teachingDaysBetween(promoted.termStart, promoted.termEnd).size

        assertEquals(term.saturdaysInTerm().size, after - before)
    }

    @Test
    fun `the derived calendar is not the stored one`() {
        // The stored calendar is what the student configured and what the term-date rows
        // and the Saturday picker edit. Deriving must not write to it.
        val derived = term.withEverySaturdayWorking()

        assertTrue(term.workingSaturdays.isEmpty())
        assertTrue(derived.workingSaturdays.isNotEmpty())
    }
}
