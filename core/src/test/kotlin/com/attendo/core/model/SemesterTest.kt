package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month

/**
 * The semester: which one it is, when it ran, and what it refuses to be.
 *
 * The odd/even rule is the university's — July to December is odd, January to May is even — and
 * it is worth its own tests because every semester boundary decision in the app reads off it, and
 * because getting it wrong is invisible until January.
 */
class SemesterTest {

    // ---- odd and even -------------------------------------------------------

    @Test
    fun `July to December is the odd semester`() {
        (7..12).forEach { month ->
            assertEquals(
                "month $month",
                SemesterType.ODD,
                SemesterType.forMonth(Month.of(month)),
            )
        }
    }

    @Test
    fun `January to May is the even semester`() {
        (1..5).forEach { month ->
            assertEquals(
                "month $month",
                SemesterType.EVEN,
                SemesterType.forMonth(Month.of(month)),
            )
        }
    }

    @Test
    fun `June belongs to neither, and says so rather than picking one`() {
        assertNull(SemesterType.forMonth(Month.JUNE))
    }

    @Test
    fun `a term starting in June is the odd semester starting early`() {
        // The one default on the boundary month, and it is the start date that names a semester.
        assertEquals(SemesterType.ODD, SemesterType.startingOn(LocalDate.of(2026, 6, 22)))
    }

    @Test
    fun `odd is followed by even, and even by odd`() {
        assertEquals(SemesterType.EVEN, SemesterType.ODD.next)
        assertEquals(SemesterType.ODD, SemesterType.EVEN.next)
    }

    @Test
    fun `the type is read off the start date, not the end date`() {
        // An even semester that runs late into June is still even.
        val runsLate = Semester.spanning(LocalDate.of(2027, 1, 5), LocalDate.of(2027, 6, 8))

        assertEquals(SemesterType.EVEN, runsLate.type)
    }

    // ---- naming -------------------------------------------------------------

    @Test
    fun `both halves of one academic year carry the same academic year label`() {
        val odd = Semester(year = 2026, type = SemesterType.ODD, startDate = jul(2026), endDate = dec(2026))
        val even = Semester(year = 2027, type = SemesterType.EVEN, startDate = jan(2027), endDate = may(2027))

        assertEquals("2026–27", odd.academicYear)
        assertEquals("2026–27", even.academicYear)
        assertEquals("Odd semester 2026–27", odd.label)
        assertEquals("Even semester 2026–27", even.label)
    }

    @Test
    fun `the academic year rolls over the century without losing a digit`() {
        val even = Semester(year = 2100, type = SemesterType.EVEN, startDate = jan(2100), endDate = may(2100))

        assertEquals(2099, even.academicYearStart)
        assertEquals("2099–00", even.academicYear)
    }

    @Test
    fun `the range reads as dates a student would recognise`() {
        val odd = Semester(year = 2026, type = SemesterType.ODD, startDate = jul(2026), endDate = dec(2026))

        assertEquals("28 Jul 2026 – 15 Dec 2026", odd.rangeLabel)
    }

    // ---- the span -----------------------------------------------------------

    @Test
    fun `both ends of the span are inside the semester`() {
        val odd = odd2026()

        assertTrue(jul(2026) in odd)
        assertTrue(dec(2026) in odd)
        assertFalse(jul(2026).minusDays(1) in odd)
        assertFalse(dec(2026).plusDays(1) in odd)
    }

    @Test
    fun `a semester has ended only after its last day, not on it`() {
        val odd = odd2026()

        assertFalse(odd.hasEndedBy(dec(2026)))
        assertTrue(odd.hasEndedBy(dec(2026).plusDays(1)))
    }

    @Test
    fun `the window is exactly the semester's dates`() {
        val odd = odd2026()

        assertEquals(AttendanceWindow(jul(2026), dec(2026)), odd.window)
        assertTrue(odd.startDate in odd.window)
        assertFalse(odd.startDate.minusDays(1) in odd.window)
    }

    @Test
    fun `a semester counts the weeks it actually spans`() {
        // 28 Jul to 24 Aug inclusive is 28 days: four weeks, and the 29th day would start a fifth.
        val fourWeeks = Semester.spanning(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 8, 24))

        assertEquals(4, fourWeeks.weeks)
        assertEquals(5, fourWeeks.copy(endDate = LocalDate.of(2026, 8, 25)).weeks)
        assertEquals(1, Semester.spanning(jul(2026), jul(2026)).weeks)
    }

    @Test
    fun `a one-day semester is allowed, a backwards one is not`() {
        Semester.spanning(jul(2026), jul(2026))

        assertThrows(IllegalArgumentException::class.java) {
            Semester(
                year = 2026,
                type = SemesterType.ODD,
                startDate = dec(2026),
                endDate = jul(2026),
            )
        }
    }

    @Test
    fun `an implausible year is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            Semester(year = 0, type = SemesterType.ODD, startDate = jul(2026), endDate = dec(2026))
        }
        assertTrue(2026 in Semester.PLAUSIBLE_YEARS)
        assertFalse(0 in Semester.PLAUSIBLE_YEARS)
    }

    // ---- identity -----------------------------------------------------------

    @Test
    fun `a semester whose dates get corrected is still the same semester`() {
        val asPlanned = odd2026()
        val asItRan = asPlanned.copy(endDate = LocalDate.of(2026, 12, 22), id = 9L)

        assertTrue(asPlanned.isSameTermAs(asItRan))
    }

    @Test
    fun `the two halves of an academic year are not the same semester`() {
        val odd = odd2026()
        val even = Semester(year = 2027, type = SemesterType.EVEN, startDate = jan(2027), endDate = may(2027))

        assertFalse(odd.isSameTermAs(even))
    }

    @Test
    fun `the next semester's identity is derived even though its dates are only a suggestion`() {
        val next = odd2026().following(jan(2027), may(2027))

        assertEquals(SemesterType.EVEN, next.type)
        assertEquals(2027, next.year)
        assertEquals("2026–27", next.academicYear)
        assertEquals(0L, next.id)
    }

    @Test
    fun `an even semester is followed by the next academic year's odd one`() {
        val even = Semester(year = 2027, type = SemesterType.EVEN, startDate = jan(2027), endDate = may(2027))

        val next = even.following(LocalDate.of(2027, 7, 27), LocalDate.of(2027, 12, 14))

        assertEquals(SemesterType.ODD, next.type)
        assertEquals("2027–28", next.academicYear)
    }

    // ---- the calendar it composes -------------------------------------------

    @Test
    fun `the composed calendar spans the semester and keeps only the days inside it`() {
        val odd = odd2026()
        val inside = LocalDate.of(2026, 10, 2)
        val outside = LocalDate.of(2027, 3, 4)
        val workingSaturday = LocalDate.of(2026, 9, 12)

        val calendar = odd.calendarWith(
            holidays = setOf(inside, outside),
            workingSaturdays = setOf(workingSaturday, LocalDate.of(2027, 3, 6)),
        )

        assertEquals(odd.startDate, calendar.termStart)
        assertEquals(odd.endDate, calendar.termEnd)
        assertEquals(setOf(inside), calendar.holidays)
        assertEquals(setOf(workingSaturday), calendar.workingSaturdays)
    }

    @Test
    fun `a stored calendar is adopted as the semester it describes`() {
        // How an install that predates semesters gets one, without asking the student anything.
        val stored = AcademicCalendar(
            termStart = jul(2026),
            termEnd = dec(2026),
            holidays = setOf(LocalDate.of(2026, 10, 2)),
        )

        val adopted = Semester.of(stored)

        assertEquals(SemesterType.ODD, adopted.type)
        assertEquals(2026, adopted.year)
        assertEquals(stored.termStart, adopted.startDate)
        assertEquals(stored.termEnd, adopted.endDate)
        assertFalse(adopted.archived)
    }

    @Test
    fun `an archived semester keeps every one of its dates`() {
        // Archiving is what makes a semester stop counting, never what changes what it was.
        val archived = odd2026().copy(archived = true)

        assertEquals(jul(2026), archived.startDate)
        assertEquals(dec(2026), archived.endDate)
        assertEquals(odd2026().window, archived.window)
        assertEquals(odd2026().label, archived.label)
    }

    // ---- courses ------------------------------------------------------------

    @Test
    fun `a course belongs to the semester it is linked to and to no other`() {
        val odd = odd2026().copy(id = 4L)
        val even = Semester(
            id = 5L,
            year = 2027,
            type = SemesterType.EVEN,
            startDate = jan(2027),
            endDate = may(2027),
        )
        val course = Course(id = 1L, name = "Theory of Computation", code = "TOC", semesterId = 4L)

        assertTrue(course.isIn(odd))
        assertFalse(course.isIn(even))
    }

    @Test
    fun `a course with no semester belongs to none, rather than to all of them`() {
        val unplaced = Course(id = 1L, name = "Theory of Computation", code = "TOC")

        assertNull(unplaced.semesterId)
        assertFalse(unplaced.isIn(odd2026().copy(id = 4L)))
    }

    // ---- helpers ------------------------------------------------------------

    private fun odd2026() = Semester(
        year = 2026,
        type = SemesterType.ODD,
        startDate = jul(2026),
        endDate = dec(2026),
    )

    private fun jul(year: Int) = LocalDate.of(year, 7, 28)

    private fun dec(year: Int) = LocalDate.of(year, 12, 15)

    private fun jan(year: Int) = LocalDate.of(year, 1, 5)

    private fun may(year: Int) = LocalDate.of(year, 5, 15)
}
