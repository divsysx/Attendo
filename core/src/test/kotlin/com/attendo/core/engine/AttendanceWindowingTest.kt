package com.attendo.core.engine

import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.AttendanceWindow
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
 * Attendance read through a window: a late admission's own figure, and one semester's figure with
 * no trace of another's.
 *
 * ### The fixture, and why its numbers are what they are
 *
 * One student, one odd semester (28 Jul – 15 Dec 2026), who joined on **12 Aug 2026** — two weeks
 * into a timetable that had been running since the first Monday. ADEC's four Monday lectures
 * straddle that date, and they are attended unevenly on purpose:
 *
 * | Date   | Units | Attended | Inside the joining window? |
 * |--------|-------|----------|----------------------------|
 * | 3 Aug  | 2     | 2        | no                         |
 * | 10 Aug | 2     | 0        | no                         |
 * | 17 Aug | 2     | 1        | yes                        |
 * | 24 Aug | 2     | 2        | yes                        |
 *
 * So ADEC reads **5/8 = 62.5%** to the university and **3/4 = 75%** to the student, and TOC adds
 * a subject whose two hours are both inside nobody's doubt. The overall figures come out **70%**
 * and **80%** — two different numbers off one unmodified set of rows, which is the whole claim.
 *
 * Every one of these tests that asserts something is excluded also asserts it is still there.
 * That pairing is the point: the window is a filter, and the register the university keeps still
 * has those first two weeks in it.
 */
class AttendanceWindowingTest {

    // ---- the two readings of a late admission -------------------------------

    @Test
    fun `the university counts every class since the semester began`() {
        val stats = AttendanceEngine.semesterStats(
            semester = currentSemester,
            courses = allCourses,
            sessions = allSessions,
            start = AttendanceStart(basis = AttendanceBasis.UNIVERSITY, joinedOn = joinedOn),
        )

        assertEquals(Percent.ofPercent(70.0), stats.percent)
        assertEquals(Percent.ofPercent(62.5), stats.perCourse.single { it.course.id == ADEC }.percent)
    }

    @Test
    fun `the student's own figure counts only from the day they joined`() {
        val stats = AttendanceEngine.semesterStats(
            semester = currentSemester,
            courses = allCourses,
            sessions = allSessions,
            start = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = joinedOn),
        )

        assertEquals(Percent.ofPercent(80.0), stats.percent)
        assertEquals(Percent.ofPercent(75.0), stats.perCourse.single { it.course.id == ADEC }.percent)
    }

    @Test
    fun `switching the reading back restores the earlier classes, because nothing was removed`() {
        val personal = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = joinedOn)

        val asStudent = AttendanceEngine.semesterStats(currentSemester, allCourses, allSessions, start = personal)
        val backToUniversity = AttendanceEngine.semesterStats(
            semester = currentSemester,
            courses = allCourses,
            sessions = allSessions,
            start = personal.copy(basis = AttendanceBasis.UNIVERSITY),
        )

        assertEquals(Percent.ofPercent(80.0), asStudent.percent)
        assertEquals(Percent.ofPercent(70.0), backToUniversity.percent)
        // The joining date is kept either way, so the switch never asks for it again.
        assertEquals(joinedOn, personal.copy(basis = AttendanceBasis.UNIVERSITY).joinedOn)
    }

    @Test
    fun `a personal reading with no date set behaves as the university one`() {
        // An incomplete setting must not quietly count from nothing or from today.
        val stats = AttendanceEngine.semesterStats(
            semester = currentSemester,
            courses = allCourses,
            sessions = allSessions,
            start = AttendanceStart(basis = AttendanceBasis.PERSONAL),
        )

        assertEquals(Percent.ofPercent(70.0), stats.percent)
    }

    @Test
    fun `the classes before the joining date are still in the data`() {
        val counted = AttendanceEngine.tallyOf(adecSessions, AttendanceWindow.since(joinedOn))
        val all = AttendanceEngine.tallyOf(adecSessions)

        assertEquals(Tally(3, 4), counted)
        assertEquals(Tally(5, 8), all)
        assertEquals(4, adecSessions.count { it.status == SessionStatus.HELD })
    }

    @Test
    fun `a joining date on a teaching day counts that day`() {
        // Inclusive, and it matters: the first class a student sits in is theirs.
        val firstDay = AttendanceEngine.tallyOf(adecSessions, AttendanceWindow.since(aug(17)))

        assertEquals(Tally(3, 4), firstDay)
        assertEquals(Tally(2, 2), AttendanceEngine.tallyOf(adecSessions, AttendanceWindow.since(aug(24))))
    }

    // ---- one course, windowed ----------------------------------------------

    @Test
    fun `a course's session counts follow the window too`() {
        val personal = AttendanceEngine.courseStats(adec, adecSessions, AttendanceWindow.since(joinedOn))
        val university = AttendanceEngine.courseStats(adec, adecSessions)

        assertEquals(2, personal.sessionsHeld)
        assertEquals(4, university.sessionsHeld)
        // The half-attended 17 Aug lecture is inside both, and stays a partial in both.
        assertEquals(1, personal.sessionsPartial)
        assertEquals(1, university.sessionsPartial)
    }

    @Test
    fun `a cancelled class before the joining date is not counted as cancelled either`() {
        val withCancellation = adecSessions + cancelledOn(aug(5))

        val personal = AttendanceEngine.courseStats(adec, withCancellation, AttendanceWindow.since(joinedOn))
        val university = AttendanceEngine.courseStats(adec, withCancellation)

        assertEquals(0, personal.sessionsCancelled)
        assertEquals(1, university.sessionsCancelled)
        // A cancellation never moved the percentage, so neither reading changes.
        assertEquals(Percent.ofPercent(75.0), personal.percent)
        assertEquals(Percent.ofPercent(62.5), university.percent)
    }

    @Test
    fun `target advice is computed from what the window counts`() {
        // The advice a student acts on has to match the figure they are shown, or the app tells
        // them to attend classes it is not counting.
        val personal = AttendanceEngine.courseStats(adec, adecSessions, AttendanceWindow.since(joinedOn))
        val university = AttendanceEngine.courseStats(adec, adecSessions)

        assertTrue(personal.target.meetsTarget)
        assertFalse(university.target.meetsTarget)
        assertEquals(Percent.ofPercent(75.0), personal.target.current)
        assertEquals(Percent.ofPercent(62.5), university.target.current)
    }

    // ---- never mixing semesters --------------------------------------------

    @Test
    fun `a semester's figure lists only that semester's subjects`() {
        val stats = AttendanceEngine.semesterStats(currentSemester, allCourses, allSessions)

        assertEquals(listOf(ADEC, TOC), stats.perCourse.map { it.course.id })
        assertTrue(stats.perCourse.none { it.course.id == LAST_TERM })
    }

    @Test
    fun `a session held in another semester by one of this semester's courses does not count`() {
        // The second filter, and the reason one is not enough: this course *is* in the current
        // semester, so narrowing the course list lets its stray May lecture through. The window
        // is what stops it.
        assertTrue(adec.isIn(currentSemester))
        assertTrue(leftoverFromLastTerm.date in previousSemester)

        val stats = AttendanceEngine.semesterStats(currentSemester, allCourses, allSessions)

        assertEquals(Percent.ofPercent(62.5), stats.perCourse.single { it.course.id == ADEC }.percent)
        assertEquals(Tally(5, 8), stats.perCourse.single { it.course.id == ADEC }.tally)
    }

    @Test
    fun `the mixed figure the app must never show is a different number`() {
        // What an unwindowed roll-up over everything produces: eleven units of sixteen, spanning
        // two terms and a leftover. Nobody has a use for it, and it is what these two filters exist
        // to prevent being displayed by accident.
        val mixed = AttendanceEngine.overallStats(allCourses, allSessions)

        assertEquals(Tally(11, 16), mixed.tally)
        assertEquals(Percent.ofPercent(68.75), mixed.percent)
        assertEquals(Percent.ofPercent(70.0), AttendanceEngine.semesterStats(currentSemester, allCourses, allSessions).percent)
    }

    @Test
    fun `the previous semester keeps its own figure, unchanged by this one`() {
        val previous = AttendanceEngine.semesterStats(previousSemester, allCourses, allSessions)

        assertEquals(listOf(LAST_TERM), previous.perCourse.map { it.course.id })
        assertEquals(Tally(2, 4), previous.tally)
        assertEquals(Percent.ofPercent(50.0), previous.percent)
    }

    @Test
    fun `an archived semester is still readable, and reads the same as before it was archived`() {
        val live = AttendanceEngine.semesterStats(previousSemester, allCourses, allSessions)
        val archived = AttendanceEngine.semesterStats(
            semester = previousSemester.copy(archived = true),
            courses = allCourses,
            sessions = allSessions,
        )

        assertEquals(live.tally, archived.tally)
        assertEquals(live.perCourse.map { it.course.id }, archived.perCourse.map { it.course.id })
    }

    @Test
    fun `a semester with no courses yet reads as no data rather than as zero percent`() {
        val nextTerm = currentSemester.following(LocalDate.of(2027, 1, 4), LocalDate.of(2027, 5, 14)).copy(id = 99L)

        val stats = AttendanceEngine.semesterStats(nextTerm, allCourses, allSessions)

        assertTrue(stats.perCourse.isEmpty())
        assertEquals(Tally.EMPTY, stats.tally)
        assertNull(stats.percent)
    }

    @Test
    fun `a course not yet placed in any semester appears in no semester's figure`() {
        val unplaced = Course(id = 7L, name = "Sports and Fitness Lab", code = "SFL")
        val itsSessions = listOf(held(unplaced.id, aug(21), units = 1, attended = 1))

        val stats = AttendanceEngine.semesterStats(
            semester = currentSemester,
            courses = allCourses + unplaced,
            sessions = allSessions + itsSessions,
        )

        assertTrue(stats.perCourse.none { it.course.id == unplaced.id })
        assertEquals(Tally(5 + 2, 8 + 2), stats.tally)
    }

    // ---- the calendar ------------------------------------------------------

    @Test
    fun `a day before the joining date is shown, greyed, and counted nowhere`() {
        val window = AttendanceWindow.since(joinedOn)

        val day = AttendanceEngine.dayAttendance(aug(3), allSessions, window)

        assertEquals(DayMark.NOT_COUNTED, day.mark)
        assertEquals(Tally.EMPTY, day.tally)
        // Shown: the sessions are attached, so the day screen can say what happened.
        assertEquals(1, day.sessions.size)
        assertEquals(aug(3), day.sessions.single().date)
    }

    @Test
    fun `the same day counts fully under the university reading`() {
        val day = AttendanceEngine.dayAttendance(aug(3), allSessions)

        assertEquals(DayMark.FULL, day.mark)
        assertEquals(Tally(2, 2), day.tally)
    }

    @Test
    fun `a missed day before the joining date is not painted red`() {
        // The distinction NOT_COUNTED exists for. Reporting it as ABSENT would blame the student
        // for a class held before they were admitted; NO_CLASS would claim it never happened.
        val university = AttendanceEngine.dayAttendance(aug(10), allSessions)
        val personal = AttendanceEngine.dayAttendance(aug(10), allSessions, AttendanceWindow.since(joinedOn))

        assertEquals(DayMark.ABSENT, university.mark)
        assertEquals(DayMark.NOT_COUNTED, personal.mark)
        assertEquals(1, personal.sessions.size)
    }

    @Test
    fun `a day with nothing scheduled is blank whatever the window says`() {
        val emptyDay = LocalDate.of(2026, 8, 4)

        assertEquals(DayMark.NO_CLASS, AttendanceEngine.dayAttendance(emptyDay, allSessions).mark)
        assertEquals(
            DayMark.NO_CLASS,
            AttendanceEngine.dayAttendance(emptyDay, allSessions, AttendanceWindow.since(joinedOn)).mark,
        )
    }

    @Test
    fun `a half attended day inside the window keeps its own colour`() {
        val day = AttendanceEngine.dayAttendance(aug(17), allSessions, AttendanceWindow.since(joinedOn))

        assertEquals(DayMark.PARTIAL, day.mark)
        assertEquals(Tally(1, 2), day.tally)
    }

    @Test
    fun `the calendar greys the fortnight before the admission and colours the rest`() {
        val marks = AttendanceEngine.calendarMarks(
            sessions = allSessions,
            from = currentSemester.startDate,
            to = currentSemester.endDate,
            window = AttendanceWindow.since(joinedOn),
        )

        assertEquals(
            listOf(DayMark.NOT_COUNTED, DayMark.NOT_COUNTED, DayMark.NOT_COUNTED, DayMark.NOT_COUNTED),
            listOf(aug(3), aug(5), aug(6), aug(10)).map { marks.getValue(it).mark },
        )
        assertEquals(DayMark.PARTIAL, marks.getValue(aug(17)).mark)
        assertEquals(DayMark.FULL, marks.getValue(aug(19)).mark)
        assertEquals(DayMark.FULL, marks.getValue(aug(24)).mark)
        // Every one of those days is still a cell in the calendar, greyed rather than absent.
        assertTrue(marks.keys.containsAll(listOf(aug(3), aug(5), aug(6), aug(10))))
    }

    @Test
    fun `the calendar's dates stay inside the semester it was asked about`() {
        val marks = AttendanceEngine.calendarMarks(
            sessions = allSessions,
            from = currentSemester.startDate,
            to = currentSemester.endDate,
        )

        assertTrue(marks.keys.all { it in currentSemester })
        assertFalse(marks.containsKey(leftoverFromLastTerm.date))
    }

    // ---- the review backlog -------------------------------------------------

    @Test
    fun `a student is not asked to review the classes from before they arrived`() {
        val today = LocalDate.of(2026, 10, 1)

        val university = AttendanceEngine.daysAwaitingReview(allSessions, today)
        val personal = AttendanceEngine.daysAwaitingReview(allSessions, today, AttendanceWindow.since(joinedOn))

        assertEquals(listOf(aug(6), LocalDate.of(2026, 9, 7)), university)
        assertEquals(listOf(LocalDate.of(2026, 9, 7)), personal)
    }

    @Test
    fun `the unreviewed classes before the joining date are still there to be seen`() {
        val personal = AttendanceWindow.since(joinedOn)

        val day = AttendanceEngine.dayAttendance(aug(6), allSessions, personal)

        assertEquals(DayMark.NOT_COUNTED, day.mark)
        assertEquals(1, day.sessions.size)
        assertEquals(SessionStatus.SCHEDULED, day.sessions.single().status)
    }

    @Test
    fun `an unreviewed class does not count toward either reading`() {
        val awaiting = AttendanceEngine.courseStats(toc, tocSessions)

        assertEquals(2, awaiting.sessionsAwaitingReview)
        assertEquals(Tally(2, 2), awaiting.tally)
        assertEquals(
            1,
            AttendanceEngine.courseStats(toc, tocSessions, AttendanceWindow.since(joinedOn))
                .sessionsAwaitingReview,
        )
    }

    // ---- the fixture --------------------------------------------------------

    private val joinedOn = LocalDate.of(2026, 8, 12)

    private val currentSemester = Semester(
        id = 88L,
        year = 2026,
        type = SemesterType.ODD,
        startDate = LocalDate.of(2026, 7, 28),
        endDate = LocalDate.of(2026, 12, 15),
    )

    private val previousSemester = Semester(
        id = 12L,
        year = 2026,
        type = SemesterType.EVEN,
        startDate = LocalDate.of(2026, 1, 5),
        endDate = LocalDate.of(2026, 5, 15),
    )

    private val adec = Course(
        id = ADEC,
        name = "Analog and Digital Electronic Circuits",
        code = "ADEC",
        targetPercent = Percent.ofPercent(75.0),
        semesterId = currentSemester.id,
    )

    private val toc = Course(
        id = TOC,
        name = "Theory of Computation",
        code = "TOC",
        semesterId = currentSemester.id,
    )

    private val lastTermCourse = Course(
        id = LAST_TERM,
        name = "Data Structures",
        code = "DS",
        semesterId = previousSemester.id,
    )

    /**
     * A lecture this semester's course held during the previous semester.
     *
     * Contrived, but reachable: a course carried over by name, an ad-hoc class entered against
     * the wrong date, a restored backup. It belongs to neither semester's figure — the current
     * one's window excludes it, and the previous one's course list does.
     */
    private val leftoverFromLastTerm = held(ADEC, LocalDate.of(2026, 5, 4), units = 2, attended = 2)

    private val adecSessions = listOf(
        held(ADEC, aug(3), units = 2, attended = 2),
        held(ADEC, aug(10), units = 2, attended = 0),
        held(ADEC, aug(17), units = 2, attended = 1),
        held(ADEC, aug(24), units = 2, attended = 2),
    )

    private val tocSessions = listOf(
        held(TOC, aug(5), units = 1, attended = 1),
        held(TOC, aug(19), units = 1, attended = 1),
        scheduled(TOC, aug(6)),
        scheduled(TOC, LocalDate.of(2026, 9, 7)),
    )

    private val lastTermSessions = listOf(
        held(LAST_TERM, LocalDate.of(2026, 3, 2), units = 2, attended = 0),
        held(LAST_TERM, LocalDate.of(2026, 3, 9), units = 2, attended = 2),
    )

    private val allCourses = listOf(adec, toc, lastTermCourse)

    private val allSessions =
        adecSessions + tocSessions + lastTermSessions + leftoverFromLastTerm

    private fun aug(day: Int) = LocalDate.of(2026, 8, day)

    private fun held(courseId: Long, date: LocalDate, units: Int, attended: Int) = ClassSession(
        id = 0L,
        courseId = courseId,
        patternId = courseId * 100,
        date = date,
        startHour = 9,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(attended),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
    )

    private fun scheduled(courseId: Long, date: LocalDate) = ClassSession(
        id = 0L,
        courseId = courseId,
        patternId = courseId * 100,
        date = date,
        startHour = 9,
        unitsPlanned = 1,
        status = SessionStatus.SCHEDULED,
        kind = SessionKind.LECTURE,
    )

    private fun cancelledOn(date: LocalDate) = ClassSession(
        id = 0L,
        courseId = ADEC,
        patternId = ADEC * 100,
        date = date,
        startHour = 9,
        unitsPlanned = 2,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.FACULTY_CANCELLED,
        kind = SessionKind.LECTURE,
    )

    private companion object {
        const val ADEC = 1L
        const val TOC = 2L
        const val LAST_TERM = 3L
    }
}
