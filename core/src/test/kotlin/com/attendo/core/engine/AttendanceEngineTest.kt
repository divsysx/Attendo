package com.attendo.core.engine

import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The fractional-attendance rules. These tests are the specification: a 2-hour
 * class half attended is 50%, subject percentages sum units rather than averaging
 * sessions, and the overall figure is unit-weighted rather than a mean of subject
 * percentages.
 */
class AttendanceEngineTest {

    private val monday: LocalDate = LocalDate.of(2026, 8, 3)

    private fun heldSession(
        courseId: Long = ADEC_ID,
        date: LocalDate = monday,
        startHour: Int = 9,
        units: Int = 1,
        attendedUnits: Int = units,
        status: SessionStatus = SessionStatus.HELD,
        cancellationReason: CancellationReason? = null,
    ) = ClassSession(
        id = 0L,
        courseId = courseId,
        patternId = 1L,
        date = date,
        startHour = startHour,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(attendedUnits),
        status = status,
        cancellationReason = cancellationReason,
    )

    // ---- rule 1: per session ------------------------------------------------

    @Test
    fun `two hour class with one hour attended is exactly fifty percent`() {
        val session = heldSession(units = 2, attendedUnits = 1)

        assertEquals(1, session.unitsAttended)
        assertEquals(1, session.unitsMissed)
        assertEquals(Percent.ofPercent(50.0), session.sessionPercent)
    }

    @Test
    fun `one hour class is all or nothing`() {
        assertEquals(Percent.FULL, heldSession(units = 1, attendedUnits = 1).sessionPercent)
        assertEquals(Percent.ZERO, heldSession(units = 1, attendedUnits = 0).sessionPercent)
    }

    @Test
    fun `three hour lab with two hours attended is sixty six point six seven percent`() {
        val session = heldSession(units = 3, attendedUnits = 2)

        assertEquals(Percent.ofBasisPoints(6667), session.sessionPercent)
    }

    @Test
    fun `session percent is undefined for sessions that do not count`() {
        assertNull(heldSession(status = SessionStatus.SCHEDULED).sessionPercent)
        assertNull(
            heldSession(
                status = SessionStatus.CANCELLED,
                attendedUnits = 0,
                cancellationReason = CancellationReason.FACULTY_CANCELLED,
            ).sessionPercent
        )
    }

    // ---- rule 2: per subject -----------------------------------------------

    @Test
    fun `subject percent sums units rather than averaging sessions`() {
        // A half-attended 2-unit block plus a fully attended 1-unit class.
        // Averaging the two sessions would give (50 + 100) / 2 = 75%.
        // Summing units gives 2 of 3 = 66.67%, which is the honest figure.
        val sessions = listOf(
            heldSession(units = 2, attendedUnits = 1, startHour = 9),
            heldSession(units = 1, attendedUnits = 1, startHour = 13),
        )

        val tally = AttendanceEngine.tallyOf(sessions)

        assertEquals(Tally(unitsAttended = 2, unitsHeld = 3), tally)
        assertEquals(Percent.ofBasisPoints(6667), tally.percent)
    }

    @Test
    fun `scheduled sessions are invisible to the percentage`() {
        val sessions = listOf(
            heldSession(units = 2, attendedUnits = 2),
            // Never reviewed — must not land in either numerator or denominator,
            // even though it is pre-filled as present.
            heldSession(units = 2, attendedUnits = 2, status = SessionStatus.SCHEDULED, startHour = 14),
        )

        assertEquals(Tally(2, 2), AttendanceEngine.tallyOf(sessions))
    }

    @Test
    fun `cancelled sessions are excluded from both numerator and denominator`() {
        val sessions = listOf(
            heldSession(units = 2, attendedUnits = 1),
            heldSession(
                units = 2,
                attendedUnits = 0,
                startHour = 14,
                status = SessionStatus.CANCELLED,
                cancellationReason = CancellationReason.FACULTY_CANCELLED,
            ),
        )

        // A class that never happened cannot be one the student failed to attend:
        // the denominator stays at 2, so the percentage remains 50% not 25%.
        assertEquals(Tally(1, 2), AttendanceEngine.tallyOf(sessions))
        assertEquals(Percent.ofPercent(50.0), AttendanceEngine.tallyOf(sessions).percent)
    }

    @Test
    fun `fully absent is held but attended zero, unlike cancelled`() {
        val absent = heldSession(units = 2, attendedUnits = 0)

        assertEquals(Tally(0, 2), AttendanceEngine.tallyOf(listOf(absent)))
        assertEquals(Percent.ZERO, absent.sessionPercent)
    }

    @Test
    fun `course stats count held, cancelled, pending and partial sessions`() {
        val sessions = listOf(
            heldSession(units = 2, attendedUnits = 1, startHour = 9),   // partial
            heldSession(units = 1, attendedUnits = 1, startHour = 11),  // full
            heldSession(units = 1, attendedUnits = 0, startHour = 12),  // absent
            heldSession(units = 2, attendedUnits = 2, startHour = 14, status = SessionStatus.SCHEDULED),
            heldSession(
                units = 1,
                attendedUnits = 0,
                startHour = 16,
                status = SessionStatus.CANCELLED,
                cancellationReason = CancellationReason.HOLIDAY,
            ),
        )

        val stats = AttendanceEngine.courseStats(adec, sessions)

        assertEquals(Tally(unitsAttended = 2, unitsHeld = 4), stats.tally)
        assertEquals(Percent.ofPercent(50.0), stats.percent)
        assertEquals(3, stats.sessionsHeld)
        assertEquals(1, stats.sessionsCancelled)
        assertEquals(1, stats.sessionsAwaitingReview)
        assertEquals(1, stats.sessionsPartial)
    }

    @Test
    fun `course stats ignore sessions belonging to another course`() {
        val sessions = listOf(
            heldSession(courseId = ADEC_ID, units = 2, attendedUnits = 1),
            heldSession(courseId = PSA_ID, units = 4, attendedUnits = 4, startHour = 13),
        )

        assertEquals(Tally(1, 2), AttendanceEngine.courseStats(adec, sessions).tally)
    }

    // ---- rule 3: overall is unit-weighted ----------------------------------

    @Test
    fun `overall percent is weighted by units held, not the mean of subject percents`() {
        val sessions = listOf(
            // ADEC: 1 of 2 units = 50%
            heldSession(courseId = ADEC_ID, units = 2, attendedUnits = 1),
            // PSA: 28 of 30 units = 93.33%, spread over several sessions
            heldSession(courseId = PSA_ID, units = 2, attendedUnits = 0, startHour = 11),
            heldSession(courseId = PSA_ID, units = 9, attendedUnits = 9, startHour = 9, date = monday.plusDays(1)),
            heldSession(courseId = PSA_ID, units = 9, attendedUnits = 9, startHour = 9, date = monday.plusDays(2)),
            heldSession(courseId = PSA_ID, units = 9, attendedUnits = 9, startHour = 9, date = monday.plusDays(3)),
            heldSession(courseId = PSA_ID, units = 1, attendedUnits = 1, startHour = 9, date = monday.plusDays(4)),
        )

        val overall = AttendanceEngine.overallStats(listOf(adec, psa), sessions)

        assertEquals(Percent.ofPercent(50.0), overall.perCourse.single { it.course.id == ADEC_ID }.percent)
        assertEquals(Percent.ofBasisPoints(9333), overall.perCourse.single { it.course.id == PSA_ID }.percent)

        // Mean of subject percentages would be (50.00 + 93.33) / 2 = 71.67%, which
        // lets a 2-unit subject outvote a 30-unit one. Unit-weighted is 29/32.
        assertEquals(Tally(unitsAttended = 29, unitsHeld = 32), overall.tally)
        assertEquals(Percent.ofBasisPoints(9063), overall.percent)
    }

    @Test
    fun `overall stats with no held sessions leaves percent undefined`() {
        val overall = AttendanceEngine.overallStats(listOf(adec, psa), emptyList())

        assertEquals(Tally.EMPTY, overall.tally)
        assertNull(overall.percent)
        assertTrue(overall.tally.isEmpty)
    }

    @Test
    fun `courses below their own target are surfaced`() {
        val strictPsa = psa.copy(targetPercent = Percent.ofPercent(90.0))
        val sessions = listOf(
            heldSession(courseId = ADEC_ID, units = 4, attendedUnits = 4),
            heldSession(courseId = PSA_ID, units = 4, attendedUnits = 3, startHour = 13),
        )

        val overall = AttendanceEngine.overallStats(listOf(adec, strictPsa), sessions)

        // PSA is at 75% — fine against the default threshold, short of its own 90%.
        assertEquals(listOf(PSA_ID), overall.coursesBelowTarget.map { it.course.id })
    }

    // ---- target tracker: exact integer boundaries ---------------------------

    @Test
    fun `sitting exactly on the target counts as meeting it`() {
        // 60 of 80 is exactly 75%. Computed in floating point this is the classic
        // spot for a 74.99999999999999 false negative.
        val advice = AttendanceEngine.advise(Tally(60, 80), Percent.ofPercent(75.0))

        assertTrue(advice.meetsTarget)
        assertEquals(Percent.ofPercent(75.0), advice.current)
        assertEquals(0, advice.unitsCanSkip)
        assertEquals(0, advice.unitsMustAttend)
    }

    @Test
    fun `one unit below the target does not count as meeting it`() {
        val advice = AttendanceEngine.advise(Tally(59, 80), Percent.ofPercent(75.0))

        assertFalse(advice.meetsTarget)
        assertEquals(Percent.ofBasisPoints(7375), advice.current)
    }

    @Test
    fun `units that can still be skipped is the largest safe number`() {
        val advice = AttendanceEngine.advise(Tally(7, 8), Percent.ofPercent(75.0))

        assertEquals(1, advice.unitsCanSkip)
        // Verify the boundary from both sides: skipping 1 keeps the target, 2 breaks it.
        assertTrue(AttendanceEngine.advise(Tally(7, 9), Percent.ofPercent(75.0)).meetsTarget)
        assertFalse(AttendanceEngine.advise(Tally(7, 10), Percent.ofPercent(75.0)).meetsTarget)
    }

    @Test
    fun `units that must be attended is the smallest sufficient number`() {
        val advice = AttendanceEngine.advise(Tally(5, 8), Percent.ofPercent(75.0))

        assertFalse(advice.meetsTarget)
        assertEquals(4, advice.unitsMustAttend)
        assertEquals(0, advice.unitsCanSkip)
        // Attending 4 more reaches exactly 75%; 3 falls short.
        assertTrue(AttendanceEngine.advise(Tally(9, 12), Percent.ofPercent(75.0)).meetsTarget)
        assertFalse(AttendanceEngine.advise(Tally(8, 11), Percent.ofPercent(75.0)).meetsTarget)
    }

    @Test
    fun `no classes held yet meets the target vacuously and advises nothing`() {
        val advice = AttendanceEngine.advise(Tally.EMPTY, Percent.ofPercent(75.0))

        assertTrue(advice.meetsTarget)
        assertNull(advice.current)
        assertEquals(0, advice.unitsCanSkip)
        assertEquals(0, advice.unitsMustAttend)
        assertTrue(advice.targetReachable)
    }

    @Test
    fun `a hundred percent target becomes unreachable once a unit is missed`() {
        val advice = AttendanceEngine.advise(Tally(3, 4), Percent.FULL)

        assertFalse(advice.meetsTarget)
        assertFalse(advice.targetReachable)
    }

    @Test
    fun `a hundred percent target is still met with a clean record`() {
        val advice = AttendanceEngine.advise(Tally(4, 4), Percent.FULL)

        assertTrue(advice.meetsTarget)
        assertTrue(advice.targetReachable)
        assertEquals(0, advice.unitsCanSkip)
    }

    @Test
    fun `a zero percent target can never be missed`() {
        val advice = AttendanceEngine.advise(Tally(0, 20), Percent.ZERO)

        assertTrue(advice.meetsTarget)
        assertEquals(Int.MAX_VALUE, advice.unitsCanSkip)
    }

    @Test
    fun `thresholds are compared exactly, never against a rounded percentage`() {
        // 2 of 3 is 66.666...%. A target of 66.66% is met by it; 66.67% — which is
        // what the UI prints for that very ratio — is genuinely a hair above it.
        // Rounding before comparing would blur the two.
        assertTrue(AttendanceEngine.advise(Tally(2, 3), Percent.ofPercent(66.66)).meetsTarget)
        assertFalse(AttendanceEngine.advise(Tally(2, 3), Percent.ofBasisPoints(6667)).meetsTarget)
        assertEquals(Percent.ofBasisPoints(6667), Tally(2, 3).percent)
    }

    @Test
    fun `projections bracket where the percentage can land`() {
        val tally = Tally(6, 8) // 75%

        assertEquals(Percent.ofBasisPoints(8333), AttendanceEngine.projectIfAllAttended(tally, 4))
        assertEquals(Percent.ofPercent(50.0), AttendanceEngine.projectIfAllMissed(tally, 4))
        assertEquals(tally.percent, AttendanceEngine.projectIfAllAttended(tally, 0))
    }

    // ---- calendar day marks -------------------------------------------------

    @Test
    fun `day with every unit attended is marked full`() {
        val sessions = listOf(
            heldSession(units = 2, attendedUnits = 2, startHour = 9),
            heldSession(units = 1, attendedUnits = 1, startHour = 13),
        )

        val day = AttendanceEngine.dayAttendance(monday, sessions)

        assertEquals(DayMark.FULL, day.mark)
        assertEquals(Tally(3, 3), day.tally)
    }

    @Test
    fun `day with a half attended block is marked partial`() {
        val day = AttendanceEngine.dayAttendance(monday, listOf(heldSession(units = 2, attendedUnits = 1)))

        assertEquals(DayMark.PARTIAL, day.mark)
        assertEquals(Percent.ofPercent(50.0), day.percent)
    }

    @Test
    fun `day with nothing attended is marked absent`() {
        val day = AttendanceEngine.dayAttendance(monday, listOf(heldSession(units = 2, attendedUnits = 0)))

        assertEquals(DayMark.ABSENT, day.mark)
    }

    @Test
    fun `day with no sessions is marked as having no class`() {
        val day = AttendanceEngine.dayAttendance(monday, emptyList())

        assertEquals(DayMark.NO_CLASS, day.mark)
        assertNull(day.percent)
    }

    @Test
    fun `day whose sessions are all unreviewed is marked awaiting review`() {
        val day = AttendanceEngine.dayAttendance(
            monday,
            listOf(heldSession(units = 2, attendedUnits = 2, status = SessionStatus.SCHEDULED)),
        )

        assertEquals(DayMark.AWAITING_REVIEW, day.mark)
        assertEquals(2, day.unitsAwaitingReview)
        assertTrue(day.tally.isEmpty)
    }

    @Test
    fun `day whose sessions were all cancelled is distinguished from an absence`() {
        val day = AttendanceEngine.dayAttendance(
            monday,
            listOf(
                heldSession(
                    units = 2,
                    attendedUnits = 0,
                    status = SessionStatus.CANCELLED,
                    cancellationReason = CancellationReason.HOLIDAY,
                )
            ),
        )

        assertEquals(DayMark.ALL_CANCELLED, day.mark)
        assertEquals(2, day.unitsCancelled)
    }

    @Test
    fun `a pending class does not change the colour earned by the held ones`() {
        val sessions = listOf(
            heldSession(units = 2, attendedUnits = 2, startHour = 9),
            heldSession(units = 2, attendedUnits = 2, startHour = 14, status = SessionStatus.SCHEDULED),
        )

        val day = AttendanceEngine.dayAttendance(monday, sessions)

        assertEquals(DayMark.FULL, day.mark)
        // ...but the pending work is still reported so the UI can add a dot.
        assertEquals(2, day.unitsAwaitingReview)
    }

    @Test
    fun `calendar marks cover only dates inside the requested window`() {
        val sessions = listOf(
            heldSession(date = monday.minusDays(3)),
            heldSession(date = monday),
            heldSession(date = monday.plusDays(1)),
            heldSession(date = monday.plusDays(30)),
        )

        val marks = AttendanceEngine.calendarMarks(sessions, monday, monday.plusDays(7))

        assertEquals(listOf(monday, monday.plusDays(1)), marks.keys.toList())
    }

    @Test
    fun `days awaiting review lists past dates oldest first and leaves an unfinished today out`() {
        val today = monday.plusDays(7)
        val sessions = listOf(
            heldSession(date = monday, status = SessionStatus.SCHEDULED),
            heldSession(date = monday.plusDays(3), status = SessionStatus.SCHEDULED),
            heldSession(date = monday.plusDays(3), status = SessionStatus.SCHEDULED, startHour = 14),
            heldSession(date = today, status = SessionStatus.SCHEDULED), // 9–10, still to come
            heldSession(date = today.plusDays(1), status = SessionStatus.SCHEDULED),
            heldSession(date = monday.plusDays(4)), // already held
        )

        // Nine sharp: today's 9–10 class has not ended, so today stays off the list.
        val backlog = AttendanceEngine.daysAwaitingReview(sessions, today.atTime(9, 0))

        assertEquals(listOf(monday, monday.plusDays(3)), backlog)
    }

    private companion object {
        const val ADEC_ID = 1L
        const val PSA_ID = 2L

        val adec = Course(id = ADEC_ID, name = "Analog and Digital Electronic Circuits", code = "ADEC")
        val psa = Course(id = PSA_ID, name = "Power System Analysis", code = "PSA")
    }
}
