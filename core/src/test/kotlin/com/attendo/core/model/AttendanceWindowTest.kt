package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The read-time filter that keeps one semester's figures out of another's, and lets a student who
 * joined in week three see their own percentage.
 *
 * The single claim these tests defend is that nothing here deletes anything. Every assertion about
 * a session being excluded is paired with the session still being present, because that pairing is
 * the whole reason the window exists rather than a delete.
 */
class AttendanceWindowTest {

    // ---- the window itself ---------------------------------------------------

    @Test
    fun `an open window counts everything and says so`() {
        assertTrue(AttendanceWindow.OPEN.isOpen)
        assertTrue(LocalDate.of(1999, 1, 1) in AttendanceWindow.OPEN)
        assertTrue(LocalDate.of(2099, 1, 1) in AttendanceWindow.OPEN)
        assertEquals("all dates", AttendanceWindow.OPEN.label)
    }

    @Test
    fun `both bounds are inclusive`() {
        val window = AttendanceWindow(aug(12), aug(20))

        assertTrue(aug(12) in window)
        assertTrue(aug(20) in window)
        assertFalse(aug(11) in window)
        assertFalse(aug(21) in window)
        assertFalse(window.isOpen)
    }

    @Test
    fun `a one-sided window leaves the other side open`() {
        val since = AttendanceWindow.since(aug(12))
        val until = AttendanceWindow.until(aug(20))

        assertFalse(aug(11) in since)
        assertTrue(aug(12) in since)
        assertTrue(LocalDate.of(2099, 1, 1) in since)

        assertTrue(LocalDate.of(1999, 1, 1) in until)
        assertTrue(aug(20) in until)
        assertFalse(aug(21) in until)
    }

    @Test
    fun `a zero-length window counts exactly one day`() {
        val window = AttendanceWindow(aug(12), aug(12))

        assertTrue(aug(12) in window)
        assertFalse(aug(11) in window)
        assertFalse(aug(13) in window)
    }

    @Test
    fun `a backwards window is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AttendanceWindow(aug(20), aug(12))
        }
    }

    @Test
    fun `the label reads as dates a student would recognise`() {
        assertEquals("12 Aug 2026 – 20 Aug 2026", AttendanceWindow(aug(12), aug(20)).label)
        assertEquals("from 12 Aug 2026", AttendanceWindow.since(aug(12)).label)
        assertEquals("up to 20 Aug 2026", AttendanceWindow.until(aug(20)).label)
    }

    // ---- filtering, which is not deleting ------------------------------------

    @Test
    fun `filtering hides the sessions outside the window and keeps the ones inside`() {
        val sessions = listOf(session(1L, aug(5)), session(2L, aug(12)), session(3L, aug(19)))

        val counted = AttendanceWindow.since(aug(12)).filter(sessions)

        assertEquals(listOf(2L, 3L), counted.map { it.id })
        // The point of the whole mechanism: the excluded session is untouched, not gone.
        assertEquals(3, sessions.size)
        assertEquals(session(1L, aug(5)), sessions.first())
    }

    @Test
    fun `filtering keeps the order it was given`() {
        val sessions = listOf(session(3L, aug(19)), session(1L, aug(12)), session(2L, aug(14)))

        assertEquals(
            listOf(3L, 1L, 2L),
            AttendanceWindow.since(aug(12)).filter(sessions).map { it.id },
        )
    }

    @Test
    fun `an open window passes every session through untouched`() {
        val sessions = listOf(session(1L, aug(5)), session(2L, aug(12)))

        assertEquals(sessions, AttendanceWindow.OPEN.filter(sessions))
    }

    @Test
    fun `a session is tested on its date`() {
        val window = AttendanceWindow.since(aug(12))

        assertTrue(session(1L, aug(12)) in window)
        assertFalse(session(2L, aug(11)) in window)
    }

    // ---- intersecting -------------------------------------------------------

    @Test
    fun `intersecting takes the later start and the earlier end`() {
        val semester = AttendanceWindow(aug(1), aug(31))
        val personal = AttendanceWindow(aug(12), LocalDate.of(2026, 9, 30))

        assertEquals(AttendanceWindow(aug(12), aug(31)), semester.intersect(personal))
    }

    @Test
    fun `intersecting with an open window changes nothing`() {
        val semester = AttendanceWindow(aug(1), aug(31))

        assertEquals(semester, semester.intersect(AttendanceWindow.OPEN))
        assertEquals(semester, AttendanceWindow.OPEN.intersect(semester))
    }

    @Test
    fun `an empty overlap counts nothing rather than throwing`() {
        // A semester that ended before the student joined. Zero classes is the right answer, and
        // the constructor would refuse a backwards window, so the overlap collapses to a day.
        val lastSemester = AttendanceWindow(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 5, 15))
        val joinedSince = AttendanceWindow.since(aug(12))

        val overlap = lastSemester.intersect(joinedSince)

        assertEquals(AttendanceWindow(aug(12), aug(12)), overlap)
        val marchClass = listOf(session(1L, LocalDate.of(2026, 3, 4)))
        assertEquals(1, lastSemester.filter(marchClass).size)
        assertTrue(overlap.filter(marchClass).isEmpty())
    }

    @Test
    fun `intersecting is symmetric`() {
        val a = AttendanceWindow(aug(1), aug(31))
        val b = AttendanceWindow(aug(12), LocalDate.of(2026, 9, 30))

        assertEquals(a.intersect(b), b.intersect(a))
    }

    // ---- the student's choice -----------------------------------------------

    @Test
    fun `the university reading starts at the semester start whatever the joining date says`() {
        val start = AttendanceStart(basis = AttendanceBasis.UNIVERSITY, joinedOn = aug(12))

        assertEquals(termStart, start.effectiveFrom(termStart))
    }

    @Test
    fun `the personal reading starts at the joining date`() {
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12))

        assertEquals(aug(12), start.effectiveFrom(termStart))
    }

    @Test
    fun `a joining date before the semester started is ignored in favour of the semester start`() {
        // Honouring it would widen this semester's window backwards into the previous one's, and
        // the student cannot have attended classes that had not happened yet.
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = LocalDate.of(2026, 3, 4))

        assertEquals(termStart, start.effectiveFrom(termStart))
    }

    @Test
    fun `a personal reading with no date falls back to the semester start`() {
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL)

        assertEquals(termStart, start.effectiveFrom(termStart))
        assertTrue(start.isIncomplete)
    }

    @Test
    fun `a joining date is the only bound when no semester is set up`() {
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12))

        assertEquals(aug(12), start.effectiveFrom(null))
        assertNull(AttendanceStart().effectiveFrom(null))
    }

    @Test
    fun `only a personal start with no date is incomplete`() {
        assertTrue(AttendanceStart(basis = AttendanceBasis.PERSONAL).isIncomplete)
        assertFalse(AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12)).isIncomplete)
        assertFalse(AttendanceStart().isIncomplete)
        // The date is kept under the university reading so switching never asks for it again.
        assertFalse(AttendanceStart(joinedOn = aug(12)).isIncomplete)
        assertEquals(aug(12), AttendanceStart(joinedOn = aug(12)).joinedOn)
    }

    @Test
    fun `the default is the university reading with no date`() {
        val default = AttendanceStart()

        assertEquals(AttendanceBasis.UNIVERSITY, default.basis)
        assertNull(default.joinedOn)
        assertEquals("Semester start", default.label)
    }

    @Test
    fun `the label names the date the tally starts from`() {
        assertEquals(
            "My joining date (12 Aug 2026)",
            AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12)).label,
        )
        assertEquals(
            "My joining date (not set)",
            AttendanceStart(basis = AttendanceBasis.PERSONAL).label,
        )
        assertEquals("Semester start", AttendanceStart(joinedOn = aug(12)).label)
    }

    // ---- the window for a semester ------------------------------------------

    @Test
    fun `the university window is the whole semester`() {
        val window = AttendanceStart().windowIn(semester)

        assertEquals(semester.window, window)
        assertEquals(termStart, window.from)
        assertEquals(termEnd, window.to)
    }

    @Test
    fun `the personal window starts at the joining date and still ends with the semester`() {
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12))

        val window = start.windowIn(semester)

        assertEquals(AttendanceWindow(aug(12), termEnd), window)
        // The end bound is what keeps a personal reading from spilling into the next semester.
        assertFalse(termEnd.plusDays(1) in window)
    }

    @Test
    fun `the same choice gives a different window in each semester`() {
        // What "never mix semesters in one attendance calculation" comes down to: the joining
        // date is one setting, but the window it produces is bounded by whichever term is asked
        // about, so last semester's tally cannot reach into this one.
        val joinedLastJanuary = LocalDate.of(2026, 1, 19)
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = joinedLastJanuary)

        val inPrevious = start.windowIn(previousSemester)
        val inCurrent = start.windowIn(semester)

        assertEquals(AttendanceWindow(joinedLastJanuary, previousSemester.endDate), inPrevious)
        // Before this term started, so it is the term start that bounds the current window.
        assertEquals(semester.window, inCurrent)
        assertFalse(termStart in inPrevious)
        assertFalse(LocalDate.of(2026, 3, 4) in inCurrent)
    }

    @Test
    fun `a semester that ended before the student joined counts nothing rather than failing`() {
        // Reachable by nothing more than opening a previous semester's tally after a spot
        // admission. The window collapses; it does not throw, and the sessions stay where they are.
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12))

        val window = start.windowIn(previousSemester)

        val marchClass = listOf(session(1L, LocalDate.of(2026, 3, 4)))
        assertTrue(window.filter(marchClass).isEmpty())
        assertEquals(1, marchClass.size)
    }

    @Test
    fun `with no semester set up the personal window is only its start`() {
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = aug(12))

        assertEquals(AttendanceWindow.since(aug(12)), start.windowIn(null))
        assertTrue(AttendanceStart().windowIn(null).isOpen)
    }

    // ---- helpers ------------------------------------------------------------

    private val termStart = LocalDate.of(2026, 7, 28)

    private val termEnd = LocalDate.of(2026, 12, 15)

    private val semester = Semester(
        id = 88L,
        year = 2026,
        type = SemesterType.ODD,
        startDate = termStart,
        endDate = termEnd,
    )

    private val previousSemester = Semester(
        id = 12L,
        year = 2026,
        type = SemesterType.EVEN,
        startDate = LocalDate.of(2026, 1, 5),
        endDate = LocalDate.of(2026, 5, 15),
        archived = true,
    )

    private fun aug(day: Int) = LocalDate.of(2026, 8, day)

    private fun session(id: Long, date: LocalDate) = ClassSession(
        id = id,
        courseId = 41L,
        patternId = 205L,
        date = date,
        startHour = 9,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
    )
}
