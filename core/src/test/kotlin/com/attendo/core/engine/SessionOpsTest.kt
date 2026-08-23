package com.attendo.core.engine

import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionOrigin
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The per-session edits available from the review and history screens, and the
 * invariants that keep them from corrupting the percentage.
 */
class SessionOpsTest {

    private val now: Instant = Instant.parse("2026-08-03T10:15:00Z")
    private val later: Instant = now.plusSeconds(3600)
    private val monday: LocalDate = LocalDate.of(2026, 8, 3)

    private fun generated(
        units: Int = 2,
        startHour: Int = 9,
        status: SessionStatus = SessionStatus.SCHEDULED,
    ) = ClassSession(
        id = 42L,
        courseId = 1L,
        patternId = 7L,
        date = monday,
        startHour = startHour,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(units),
        status = status,
    )

    // ---- shortening ---------------------------------------------------------

    @Test
    fun `shortening a fully present block drops the hours that never happened`() {
        // The bug this guards: a 2-hour class marked present is mask 0b11. Cut it to
        // one hour and an unmasked popcount would report 2 units attended out of 1
        // planned — 200% attendance for that session.
        val full = SessionOps.markPresent(generated(units = 2), now)
        assertEquals(2, full.unitsAttended)

        val shortened = SessionOps.resize(full, unitsPlanned = 1, now = later)

        assertEquals(1, shortened.unitsPlanned)
        assertEquals(1, shortened.unitsAttended)
        assertEquals(Percent.FULL, shortened.sessionPercent)
    }

    @Test
    fun `shortening keeps the first hour when only the first was attended`() {
        val partial = SessionOps.record(generated(units = 2), UnitMask.of(0), now)
        assertEquals(Percent.ofPercent(50.0), partial.sessionPercent)

        // The class was cut short, so the hour that never ran should not be held
        // against the student: 1 of 1 rather than 1 of 2.
        val shortened = SessionOps.resize(partial, unitsPlanned = 1, now = later)

        assertEquals(Percent.FULL, shortened.sessionPercent)
    }

    @Test
    fun `shortening away the only attended hour leaves a clean absence`() {
        val secondHourOnly = SessionOps.record(generated(units = 2), UnitMask.of(1), now)
        assertEquals(Percent.ofPercent(50.0), secondHourOnly.sessionPercent)

        val shortened = SessionOps.resize(secondHourOnly, unitsPlanned = 1, now = later)

        assertEquals(0, shortened.unitsAttended)
        assertEquals(Percent.ZERO, shortened.sessionPercent)
    }

    @Test
    fun `extending a session leaves the new hours unattended`() {
        val oneHour = SessionOps.markPresent(generated(units = 1), now)

        val extended = SessionOps.resize(oneHour, unitsPlanned = 2, now = later)

        assertEquals(2, extended.unitsPlanned)
        assertEquals(1, extended.unitsAttended)
        assertEquals(Percent.ofPercent(50.0), extended.sessionPercent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a session cannot be resized past the end of the teaching day`() {
        SessionOps.resize(generated(units = 1, startHour = 17), unitsPlanned = 3, now = now)
    }

    // ---- per-unit toggles ---------------------------------------------------

    @Test
    fun `toggling an hour leaves the session pending so the whole day can be reviewed first`() {
        val session = generated(units = 2)

        val edited = SessionOps.toggleUnit(session, unitIndex = 1, now = now)

        assertEquals(SessionStatus.SCHEDULED, edited.status)
        assertTrue(edited.isUnitAttended(0))
        assertFalse(edited.isUnitAttended(1))
        assertNull(edited.approvedAt)
        assertEquals(now, edited.lastEditedAt)
    }

    @Test
    fun `toggling is reversible`() {
        val session = generated(units = 2)

        val off = SessionOps.toggleUnit(session, 0, now)
        val backOn = SessionOps.toggleUnit(off, 0, later)

        assertEquals(session.unitsMask, backOn.unitsMask)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an hour outside the session cannot be toggled`() {
        SessionOps.toggleUnit(generated(units = 2), unitIndex = 2, now = now)
    }

    @Test
    fun `unit labels follow the college grid`() {
        assertEquals(listOf("9–10 AM", "10–11 AM"), generated(units = 2, startHour = 9).unitLabels)
        assertEquals(listOf("11 AM–12 PM", "12–1 PM"), generated(units = 2, startHour = 11).unitLabels)
        assertEquals(listOf("5–6 PM"), generated(units = 1, startHour = 17).unitLabels)
    }

    // ---- approval -----------------------------------------------------------

    @Test
    fun `approving commits the mask the student left in place`() {
        val halfAttended = SessionOps.toggleUnit(generated(units = 2), 1, now)

        val approved = SessionOps.approve(halfAttended, later)

        assertEquals(SessionStatus.HELD, approved.status)
        assertEquals(later, approved.approvedAt)
        assertEquals(Percent.ofPercent(50.0), approved.sessionPercent)
        assertTrue(approved.countsTowardAttendance)
    }

    @Test
    fun `approve all touches only the pending rows and preserves earlier approvals`() {
        val pending = generated(units = 2, startHour = 9)
        val alreadyHeld = SessionOps.markPresent(generated(units = 1, startHour = 11), now)
        val cancelled = SessionOps.cancel(generated(units = 1, startHour = 12), now = now)

        val result = SessionOps.approveAll(listOf(pending, alreadyHeld, cancelled), later)

        assertEquals(SessionStatus.HELD, result[0].status)
        assertEquals(later, result[0].approvedAt)
        // An already-approved row keeps its original timestamp rather than being restamped.
        assertEquals(now, result[1].approvedAt)
        assertEquals(SessionStatus.CANCELLED, result[2].status)
    }

    @Test
    fun `re-approving does not overwrite the original approval time`() {
        val approved = SessionOps.approve(generated(), now)

        val reApproved = SessionOps.approve(approved, later)

        assertEquals(now, reApproved.approvedAt)
        assertEquals(later, reApproved.lastEditedAt)
    }

    // ---- cancelling and reopening ------------------------------------------

    @Test
    fun `cancelling clears the mask so a later reopen cannot reinstate a stale present`() {
        val session = SessionOps.markPresent(generated(units = 2), now)

        val cancelled = SessionOps.cancel(session, CancellationReason.FACULTY_CANCELLED, later)

        assertEquals(SessionStatus.CANCELLED, cancelled.status)
        assertEquals(UnitMask.NONE, cancelled.unitsMask)
        assertEquals(0, cancelled.unitsAttended)
        assertFalse(cancelled.countsTowardAttendance)
        assertNull(cancelled.sessionPercent)
    }

    @Test
    fun `reopening restores the pending state pre-filled as present`() {
        val cancelled = SessionOps.cancel(generated(units = 2), CancellationReason.HOLIDAY, now)

        val reopened = SessionOps.reopen(cancelled, later)

        assertEquals(SessionStatus.SCHEDULED, reopened.status)
        assertNull(reopened.cancellationReason)
        assertNull(reopened.approvedAt)
        assertEquals(UnitMask.allPresent(2), reopened.unitsMask)
        assertNull(reopened.movedToSessionId)
    }

    // ---- rescheduling ------------------------------------------------------

    @Test
    fun `rescheduling cancels the original and lands an ad-hoc session at the new slot`() {
        val original = generated(units = 2, startHour = 9)
        val thursday = monday.plusDays(3)

        val (cancelled, replacement) = SessionOps.reschedule(
            session = original,
            newDate = thursday,
            newStartHour = 14,
            now = now,
        )

        assertEquals(SessionStatus.CANCELLED, cancelled.status)
        assertEquals(CancellationReason.RESCHEDULED, cancelled.cancellationReason)
        assertTrue(cancelled.wasRescheduledAway)

        assertEquals(thursday, replacement.date)
        assertEquals(14, replacement.startHour)
        assertEquals(2, replacement.unitsPlanned)
        assertEquals(SessionStatus.SCHEDULED, replacement.status)
        assertEquals(original.courseId, replacement.courseId)
        // Ad-hoc, so the next generation run does not treat the new slot as timetabled.
        assertNull(replacement.patternId)
        assertEquals(SessionOrigin.ADHOC, replacement.origin)
        assertEquals(original.id, replacement.movedFromSessionId)
    }

    @Test
    fun `a rescheduled class is counted once, at its new slot`() {
        val original = generated(units = 2, startHour = 9)
        val (cancelled, replacement) = SessionOps.reschedule(original, monday.plusDays(3), 14, now = now)

        val attendedAtNewSlot = SessionOps.markPresent(replacement, later)
        val tally = AttendanceEngine.tallyOf(listOf(cancelled, attendedAtNewSlot))

        // Two rows, one class: 2 units held, not 4.
        assertEquals(Tally(unitsAttended = 2, unitsHeld = 2), tally)
    }

    @Test
    fun `the original keeps the generator's slot so regeneration cannot recreate it`() {
        val original = generated(units = 2, startHour = 9)

        val (cancelled, _) = SessionOps.reschedule(original, monday.plusDays(3), 14, now = now)

        // Had the date been mutated instead, (patternId, date) would be free again
        // and the next generation run would put the class back on Monday.
        assertEquals(monday, cancelled.date)
        assertEquals(9, cancelled.startHour)
        assertEquals(7L, cancelled.patternId)
    }

    @Test
    fun `rescheduling can also change the length of the class`() {
        val (_, replacement) = SessionOps.reschedule(
            session = generated(units = 2, startHour = 9),
            newDate = monday.plusDays(1),
            newStartHour = 16,
            newUnits = 1,
            now = now,
        )

        assertEquals(1, replacement.unitsPlanned)
        assertEquals(UnitMask.allPresent(1), replacement.unitsMask)
    }

    @Test
    fun `linking the replacement closes the trail in both directions`() {
        val (cancelled, replacement) = SessionOps.reschedule(generated(), monday.plusDays(3), 14, now = now)

        val linked = SessionOps.linkReplacement(cancelled, replacementId = 99L)

        assertEquals(99L, linked.movedToSessionId)
        assertEquals(42L, replacement.movedFromSessionId)
    }

    // ---- ad-hoc sessions ---------------------------------------------------

    @Test
    fun `an ad-hoc session is pending, pre-filled present, and has no pattern`() {
        val extra = SessionOps.adhoc(
            courseId = 1L,
            date = monday,
            startHour = 16,
            unitsPlanned = 2,
            kind = SessionKind.PRACTICAL,
            room = "312",
        )

        assertEquals(SessionOrigin.ADHOC, extra.origin)
        assertNull(extra.patternId)
        assertEquals(SessionStatus.SCHEDULED, extra.status)
        assertEquals(UnitMask.allPresent(2), extra.unitsMask)
        assertEquals(SessionKind.PRACTICAL, extra.kind)
        assertEquals("312", extra.room)
        assertEquals("4–6 PM", extra.slotLabel)
    }

    // ---- one-step recording ------------------------------------------------

    @Test
    fun `recording marks a session held in a single step`() {
        val session = generated(units = 2)

        val recorded = SessionOps.record(session, UnitMask.of(1), now)

        assertEquals(SessionStatus.HELD, recorded.status)
        assertNotNull(recorded.approvedAt)
        assertEquals(1, recorded.unitsAttended)
        assertFalse(recorded.isUnitAttended(0))
        assertTrue(recorded.isUnitAttended(1))
    }

    @Test
    fun `recording clamps a mask wider than the session`() {
        val session = generated(units = 1)

        val recorded = SessionOps.record(session, UnitMask.allPresent(4), now)

        assertEquals(1, recorded.unitsAttended)
        assertEquals(Percent.FULL, recorded.sessionPercent)
    }

    @Test
    fun `marking absent on a previously approved session keeps the first approval time`() {
        val present = SessionOps.markPresent(generated(units = 2), now)

        val corrected = SessionOps.markAbsent(present, later)

        assertEquals(SessionStatus.HELD, corrected.status)
        assertEquals(0, corrected.unitsAttended)
        assertEquals(now, corrected.approvedAt)
        assertEquals(later, corrected.lastEditedAt)
    }

    @Test
    fun `moving a session within the day keeps its attendance`() {
        val half = SessionOps.record(generated(units = 2, startHour = 9), UnitMask.of(0), now)

        val moved = SessionOps.moveSlot(half, startHour = 14, now = later)

        assertEquals(14, moved.startHour)
        assertEquals(Percent.ofPercent(50.0), moved.sessionPercent)
        assertEquals("2–4 PM", moved.slotLabel)
    }
}
