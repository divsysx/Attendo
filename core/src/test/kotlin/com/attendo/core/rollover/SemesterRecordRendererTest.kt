package com.attendo.core.rollover

import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What goes into a Semester Record, and what stays out.
 *
 * The record is a printed copy of the figures the student has been looking at all term, so the
 * one thing every test here is really checking is that the record's numbers come from the same
 * place the screen's numbers do: [com.attendo.core.engine.AttendanceEngine.semesterStats]. The
 * renderer is the thin mapping from that engine output to print-ready rows; it must not
 * recompute anything, and these tests hold it to that.
 *
 * ### The two bounds that keep a record honest
 *
 * A semester's record holds only that semester's courses and only that semester's sessions.
 * [SemesterRecordRenderer.render] achieves both through the engine — `semesterStats` narrows
 * courses to the ones whose `semesterId` matches and bounds the window to the semester's dates —
 * and the tests pin both halves so neither can quietly start leaking another term's data.
 */
class SemesterRecordRendererTest {

    private val semester: Semester = Semester(
        id = SEMESTER_ID,
        year = 2026,
        type = SemesterType.ODD,
        startDate = LocalDate.of(2026, 7, 28),
        endDate = LocalDate.of(2026, 11, 20),
    )

    private val target: Percent = Percent.ofPercent(75.0)

    /** "Today" sits inside the term so the review counts are bounded the way they are on screen. */
    private val today: LocalDate = LocalDate.of(2026, 9, 14)

    private val exportedOn: LocalDate = LocalDate.of(2026, 12, 1)

    private val courseA = Course(
        id = A_ID,
        name = "Analog and Digital Electronic Circuits",
        code = "ADEC",
        semesterId = SEMESTER_ID,
    )

    private val courseB = Course(
        id = B_ID,
        name = "Power System Analysis",
        code = "PSA",
        semesterId = SEMESTER_ID,
    )

    /** A course that belongs to a different semester — must never appear in this record. */
    private val otherTermCourse = Course(
        id = C_ID,
        name = "Data Structures",
        code = "DS",
        semesterId = SEMESTER_ID + 1,
    )

    // ---- header fields --------------------------------------------------------

    @Test
    fun `the header carries the semester label academic year range and export date`() {
        val doc = render(courses = listOf(courseA), sessions = emptyList())

        assertEquals("Attendo — Semester Record", doc.title)
        assertEquals("Odd semester 2026–27", doc.semesterLabel)
        assertEquals("2026–27", doc.academicYear)
        assertEquals("28 Jul 2026 – 20 Nov 2026", doc.dateRange)
        assertEquals(exportedOn, doc.exportedOn)
        assertEquals(target, doc.target)
    }

    @Test
    fun `a blank display name is treated as absent`() {
        val doc = render(displayName = "   ")

        assertNull(doc.displayName)
    }

    @Test
    fun `a set display name is trimmed and carried through`() {
        val doc = render(displayName = "  Divyansh Sharma  ")

        assertEquals("Divyansh Sharma", doc.displayName)
    }

    @Test
    fun `blank section and batch are treated as absent`() {
        val doc = render(section = " ", batch = "")

        assertNull(doc.section)
        assertNull(doc.batch)
    }

    @Test
    fun `section and batch are carried through when set`() {
        val doc = render(section = "B", batch = "M1")

        assertEquals("B", doc.section)
        assertEquals("M1", doc.batch)
    }

    // ---- overall figures ------------------------------------------------------

    @Test
    fun `nothing held leaves overall as undefined not zero percent`() {
        // The same "nothing held" convention the dashboard renders as an em dash. The record must
        // not turn an undefined percentage into 0%, because that reads as "you attended nothing".
        val doc = render(courses = listOf(courseA), sessions = emptyList())

        assertNull(doc.overallPercent)
        assertEquals(0, doc.overallUnitsHeld)
        assertEquals(0, doc.overallUnitsAttended)
    }

    @Test
    fun `the overall tallies sum every held unit across every course in the semester`() {
        // courseA: 2 held, 1 attended (50% of one 2-hour block).
        // courseB: 1 held, 1 attended (100% of a 1-hour class).
        // Overall: 3 held, 2 attended → 66.7%, unit-weighted rather than averaged.
        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 1),
            held(B_ID, LocalDate.of(2026, 8, 4), 10, 1, attended = 1),
        )

        val doc = render(courses = listOf(courseA, courseB), sessions = sessions)

        assertEquals(3, doc.overallUnitsHeld)
        assertEquals(2, doc.overallUnitsAttended)
        // The exact ratio the engine computes (2/3 rounds to 6667 bp); comparing to ofPercent(66.7)
        // (6670 bp) would fail even though both format to "66.7%".
        assertEquals(Percent.ofRatio(2, 3), doc.overallPercent)
    }

    @Test
    fun `a course in another semester never reaches the overall tally`() {
        // otherTermCourse has a held session on a date inside *this* semester's window. The
        // window alone would let it through; only the per-course filter keeps it out.
        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 2),
            held(C_ID, LocalDate.of(2026, 8, 5), 9, 2, attended = 2),
        )

        val doc = render(courses = listOf(courseA, otherTermCourse), sessions = sessions)

        assertEquals(2, doc.overallUnitsHeld)
        assertEquals(2, doc.overallUnitsAttended)
        assertEquals(Percent.FULL, doc.overallPercent)
        assertFalse(doc.courses.any { it.code == otherTermCourse.code })
    }

    // ---- per-course rows -----------------------------------------------------

    @Test
    fun `each course row carries the courses code name units percentage and target status`() {
        val sessions = listOf(
            // ADEC: two 2-hour blocks, one fully attended, one half. 3 of 4 → 75%.
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 2),
            held(A_ID, LocalDate.of(2026, 8, 10), 9, 2, attended = 1),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.courses.single { it.code == "ADEC" }

        assertEquals("ADEC", row.code)
        assertEquals("Analog and Digital Electronic Circuits", row.name)
        assertEquals(4, row.unitsHeld)
        assertEquals(3, row.unitsAttended)
        assertEquals(Percent.ofPercent(75.0), row.percent)
        assertEquals(target, row.target)
        assertTrue(row.meetsTarget)
        assertEquals(2, row.sessionsHeld)
        assertEquals(0, row.sessionsCancelled)
        assertEquals(0, row.sessionsAwaitingReview)
    }

    @Test
    fun `a course below target reports it is below target`() {
        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 0),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.courses.single { it.code == "ADEC" }

        assertEquals(Percent.ofPercent(0.0), row.percent)
        assertFalse(row.meetsTarget)
    }

    @Test
    fun `a course that has only held classes still carries a row with its percentage`() {
        // The row filter keeps a course with at least one held/cancelled/awaiting session. A
        // course with only held classes is the ordinary case and must appear.
        val sessions = listOf(held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 2))

        val doc = render(courses = listOf(courseA), sessions = sessions)

        assertEquals(1, doc.courses.size)
        assertEquals(Percent.FULL, doc.courses.single().percent)
    }

    @Test
    fun `a course with no activity of any kind is omitted from the record`() {
        // courseB has no sessions at all. Including it as an empty 0/0 row would read as "0%"
        // rather than "nothing held", and the record is for a finished term — an empty course
        // is a course that was never taken up, not one attended at 0%.
        val doc = render(courses = listOf(courseA, courseB), sessions = emptyList())

        assertTrue(doc.courses.isEmpty())
    }

    @Test
    fun `a course that only had a cancelled session keeps a row but adds nothing held`() {
        // A cancelled class is real history — the slot existed and was called off — so the course
        // keeps its row, but the cancellation contributes no held units and so no percentage.
        val sessions = listOf(
            cancelled(A_ID, LocalDate.of(2026, 8, 3), 9, 2),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.courses.single { it.code == "ADEC" }

        assertEquals(0, row.unitsHeld)
        assertEquals(0, row.unitsAttended)
        assertNull(row.percent)
        assertEquals(1, row.sessionsCancelled)
    }

    @Test
    fun `a course that only has unreviewed sessions keeps a row and reports them awaiting review`() {
        val sessions = listOf(
            scheduled(A_ID, LocalDate.of(2026, 8, 3), 9, 2),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.courses.single { it.code == "ADEC" }

        assertEquals(0, row.unitsHeld)
        assertEquals(0, row.unitsAttended)
        assertNull(row.percent)
        assertEquals(1, row.sessionsAwaitingReview)
    }

    @Test
    fun `course rows are ordered by code then name`() {
        val courses = listOf(courseB, courseA)
        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 2),
            held(B_ID, LocalDate.of(2026, 8, 4), 10, 1, attended = 1),
        )

        val doc = render(courses = courses, sessions = sessions)

        assertEquals(listOf("ADEC", "PSA"), doc.courses.map { it.code })
    }

    // ---- the session appendix -------------------------------------------------

    @Test
    fun `sessions are listed oldest-first and within a day by their start hour`() {
        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 10), 14, 1, attended = 1),
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 2),
            held(A_ID, LocalDate.of(2026, 8, 3), 11, 1, attended = 1),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 3) to 9,
                LocalDate.of(2026, 8, 3) to 11,
                LocalDate.of(2026, 8, 10) to 14,
            ),
            doc.sessions.map { it.date to it.startHour },
        )
    }

    @Test
    fun `a held session is labelled Held and carries its planned and attended units`() {
        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 1),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.sessions.single()

        assertEquals(LocalDate.of(2026, 8, 3), row.date)
        assertEquals("ADEC", row.courseCode)
        assertEquals(2, row.unitsPlanned)
        assertEquals(1, row.unitsAttended)
        assertEquals("Held", row.statusLabel)
    }

    @Test
    fun `a cancelled session is labelled Cancelled and contributes no attended units`() {
        val sessions = listOf(
            cancelled(A_ID, LocalDate.of(2026, 8, 3), 9, 2),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.sessions.single()

        assertEquals(2, row.unitsPlanned)
        assertEquals(0, row.unitsAttended)
        assertEquals("Cancelled", row.statusLabel)
    }

    @Test
    fun `a scheduled session is labelled Scheduled`() {
        val sessions = listOf(
            scheduled(A_ID, LocalDate.of(2026, 8, 3), 9, 2),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)
        val row = doc.sessions.single()

        assertEquals("Scheduled", row.statusLabel)
        assertEquals(0, row.unitsAttended)
    }

    @Test
    fun `a session outside the semester window is excluded from the appendix`() {
        // A session dated before the term began, even on a course that belongs to the semester,
        // is not part of this record. (The personal-start window is intersected with the semester,
        // so anything outside the term is out either way.)
        val sessions = listOf(
            held(A_ID, semester.startDate.minusDays(1), 9, 2, attended = 2),
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 2),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions)

        assertEquals(1, doc.sessions.size)
        assertEquals(LocalDate.of(2026, 8, 3), doc.sessions.single().date)
    }

    @Test
    fun `a session whose course is not in the passed list is labelled with an em dash`() {
        // Defensive: the renderer resolves a session's code by looking the course up. A session
        // whose course was not passed in (a stale row, or a caller mistake) must not crash or
        // silently claim a wrong course — it prints an em dash.
        val orphan = held(999L, LocalDate.of(2026, 8, 3), 9, 2, attended = 2)

        val doc = render(courses = listOf(courseA), sessions = listOf(orphan))

        assertEquals(1, doc.sessions.size)
        assertEquals("—", doc.sessions.single().courseCode)
    }

    // ---- the personal start ---------------------------------------------------

    @Test
    fun `a personal start before the term is ignored in favour of the semester start`() {
        // Joining before the semester began cannot widen the window backward into another term.
        val before = AttendanceStart(joinedOn = semester.startDate.minusWeeks(1))

        val sessions = listOf(
            held(A_ID, semester.startDate, 9, 2, attended = 2),
            held(A_ID, semester.startDate.minusDays(1), 9, 2, attended = 2),
        )

        val doc = render(courses = listOf(courseA), sessions = sessions, start = before)

        // The day before the term is excluded; the term's first day counts.
        assertEquals(1, doc.sessions.size)
        assertEquals(semester.startDate, doc.sessions.single().date)
        assertEquals(2, doc.overallUnitsHeld)
    }

    @Test
    fun `a personal start inside the term excludes the classes before it`() {
        // A mid-semester admission: the personal start narrows the window forward to the joining
        // date, so a class held before it drops out of both the appendix and the percentage. The
        // record reflects the student's own view of the term — the same personal-start window the
        // dashboard already counts from — rather than the classes they were not enrolled for.
        val joined = LocalDate.of(2026, 9, 1)
        val start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = joined)

        val sessions = listOf(
            held(A_ID, LocalDate.of(2026, 8, 3), 9, 2, attended = 0), // before joining — excluded
            held(A_ID, joined, 9, 2, attended = 2), // on joining date — counts
        )

        val doc = render(courses = listOf(courseA), sessions = sessions, start = start)

        // Only the joining-date session is inside the personal window.
        assertEquals(1, doc.sessions.size)
        assertEquals(joined, doc.sessions.single().date)
        // ...and only its units count toward the percentage.
        assertEquals(2, doc.overallUnitsHeld)
        assertEquals(2, doc.overallUnitsAttended)
    }

    // ---- the suggested file name ---------------------------------------------

    @Test
    fun `the suggested file name carries the semester identity and export date`() {
        val name = SemesterRecordRenderer.suggestedFileName(semester, exportedOn)

        assertEquals("attendo-semester-odd-2026-2026-27-2026-12-01.pdf", name)
    }

    @Test
    fun `the suggested file name uses an ASCII hyphen in the academic year`() {
        // A file system that balks at the en dash in "2026–27" must not see it here.
        val even = Semester(
            year = 2027,
            type = SemesterType.EVEN,
            startDate = LocalDate.of(2027, 1, 5),
            endDate = LocalDate.of(2027, 5, 15),
        )

        val name = SemesterRecordRenderer.suggestedFileName(even, exportedOn)

        assertFalse(name.contains("–"))
        assertEquals("attendo-semester-even-2027-2026-27-2026-12-01.pdf", name)
    }

    @Test
    fun `the mime type is pdf`() {
        assertEquals("application/pdf", SemesterRecordRenderer.MIME_TYPE)
    }

    // ---- fixtures -------------------------------------------------------------

    private fun render(
        courses: List<Course> = listOf(courseA),
        sessions: List<ClassSession> = emptyList(),
        start: AttendanceStart = AttendanceStart(),
        displayName: String? = null,
        section: String? = null,
        batch: String? = null,
    ): SemesterRecordDoc = SemesterRecordRenderer.render(
        semester = semester,
        courses = courses,
        sessions = sessions,
        overallTarget = target,
        start = start,
        today = today,
        displayName = displayName,
        section = section,
        batch = batch,
        exportedOn = exportedOn,
    )

    /** A HELD session of [units] hours with [attended] of them present (the rest absent). */
    private fun held(courseId: Long, date: LocalDate, startHour: Int, units: Int, attended: Int): ClassSession =
        ClassSession(
            courseId = courseId,
            patternId = courseId, // any stable non-null key; tests don't exercise generation here
            date = date,
            startHour = startHour,
            unitsPlanned = units,
            unitsMask = attendedMask(attended),
            status = SessionStatus.HELD,
            kind = SessionKind.LECTURE,
        )

    private fun cancelled(courseId: Long, date: LocalDate, startHour: Int, units: Int): ClassSession =
        ClassSession(
            courseId = courseId,
            patternId = courseId,
            date = date,
            startHour = startHour,
            unitsPlanned = units,
            unitsMask = UnitMask.NONE,
            status = SessionStatus.CANCELLED,
            cancellationReason = CancellationReason.FACULTY_CANCELLED,
            kind = SessionKind.LECTURE,
        )

    private fun scheduled(courseId: Long, date: LocalDate, startHour: Int, units: Int): ClassSession =
        ClassSession(
            courseId = courseId,
            patternId = courseId,
            date = date,
            startHour = startHour,
            unitsPlanned = units,
            unitsMask = UnitMask.allPresent(units),
            status = SessionStatus.SCHEDULED,
            kind = SessionKind.LECTURE,
        )

    /** The first [n] units attended, the rest absent. Independent of any session's planned length. */
    private fun attendedMask(n: Int): UnitMask {
        var mask = UnitMask.NONE
        repeat(n) { i -> mask = mask.with(i, true) }
        return mask
    }

    private companion object {
        const val SEMESTER_ID = 1L
        const val A_ID = 10L
        const val B_ID = 20L
        const val C_ID = 30L
    }
}
