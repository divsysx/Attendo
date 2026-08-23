package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-hour attendance bits, and the clamping that keeps them honest. */
class UnitMaskTest {

    @Test
    fun `all present sets one bit per unit`() {
        assertEquals(0b1, UnitMask.allPresent(1).bits)
        assertEquals(0b11, UnitMask.allPresent(2).bits)
        assertEquals(0b111, UnitMask.allPresent(3).bits)
        assertEquals(0, UnitMask.allPresent(0).bits)
    }

    @Test
    fun `a mask remembers which hour was missed, not just how many`() {
        // This is why attendance is a bitmask rather than a count: reopening the
        // session must show that it was the *second* hour that was missed.
        val firstHourOnly = UnitMask.of(0)
        val secondHourOnly = UnitMask.of(1)

        assertEquals(1, firstHourOnly.countAttended(2))
        assertEquals(1, secondHourOnly.countAttended(2))
        assertTrue(firstHourOnly.isAttended(0))
        assertFalse(firstHourOnly.isAttended(1))
        assertFalse(secondHourOnly.isAttended(0))
        assertTrue(secondHourOnly.isAttended(1))
    }

    @Test
    fun `counting is limited to the planned length`() {
        // The 200% guard: bits left over from a longer session are ignored rather
        // than counted.
        val twoHoursPresent = UnitMask.allPresent(2)

        assertEquals(2, twoHoursPresent.countAttended(2))
        assertEquals(1, twoHoursPresent.countAttended(1))
        assertEquals(0, twoHoursPresent.countAttended(0))
        assertEquals(2, twoHoursPresent.countAttended(5))
    }

    @Test
    fun `clamping discards the hours that never happened`() {
        val clamped = UnitMask.allPresent(3).clampedTo(1)

        assertEquals(0b1, clamped.bits)
        assertEquals(1, clamped.countAttended(1))
        assertEquals(1, clamped.countAttended(3))
    }

    @Test
    fun `clamping keeps the surviving hours only`() {
        assertEquals(UnitMask.NONE, UnitMask.of(2).clampedTo(2))
        assertEquals(UnitMask.of(0), UnitMask.of(0, 2).clampedTo(2))
        assertEquals(UnitMask.NONE, UnitMask.allPresent(4).clampedTo(0))
    }

    @Test
    fun `setting and toggling a bit is reversible`() {
        val start = UnitMask.NONE

        val on = start.with(1, true)
        assertTrue(on.isAttended(1))

        val off = on.with(1, false)
        assertEquals(start, off)

        assertEquals(on, start.toggled(1))
        assertEquals(start, start.toggled(1).toggled(1))
    }

    @Test
    fun `setting the same bit twice is idempotent`() {
        val once = UnitMask.NONE.with(0, true)

        assertEquals(once, once.with(0, true))
    }

    @Test
    fun `attended indices come back in order`() {
        assertEquals(listOf(0, 2), UnitMask.of(2, 0).attendedIndices(3))
        assertEquals(listOf(0), UnitMask.of(2, 0).attendedIndices(1))
        assertEquals(emptyList<Int>(), UnitMask.NONE.attendedIndices(4))
    }

    @Test
    fun `fully attended and fully missed are relative to the planned length`() {
        val onlyFirst = UnitMask.of(0)

        assertTrue(onlyFirst.isFullyAttended(1))
        assertFalse(onlyFirst.isFullyAttended(2))
        assertFalse(onlyFirst.isFullyMissed(1))
        assertTrue(UnitMask.NONE.isFullyMissed(2))
        // A zero-length session is not "fully attended" — there is nothing to attend.
        assertFalse(UnitMask.NONE.isFullyAttended(0))
    }

    @Test
    fun `reading a bit outside the mask is false rather than an error`() {
        // Reads are lenient so a stale mask cannot crash a list row...
        assertFalse(UnitMask.allPresent(2).isAttended(-1))
        assertFalse(UnitMask.allPresent(2).isAttended(UnitMask.MAX_UNITS))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writing a bit outside the mask is rejected`() {
        // ...but writes are strict, because that only happens through a UI bug.
        UnitMask.NONE.with(UnitMask.MAX_UNITS, true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writing a negative index is rejected`() {
        UnitMask.NONE.with(-1, true)
    }

    @Test
    fun `all present saturates at the ceiling instead of overflowing the shift`() {
        val saturated = UnitMask.allPresent(UnitMask.MAX_UNITS + 8)

        assertEquals(UnitMask.MAX_UNITS, saturated.countAttended(UnitMask.MAX_UNITS + 8))
        assertTrue(saturated.bits > 0)
    }

    @Test
    fun `the longest real session fits comfortably`() {
        val wholeDay = UnitMask.allPresent(TimeGrid.MAX_UNITS_PER_SESSION)

        assertEquals(9, wholeDay.countAttended(TimeGrid.MAX_UNITS_PER_SESSION))
        assertTrue(TimeGrid.MAX_UNITS_PER_SESSION <= UnitMask.MAX_UNITS)
    }
}
