package com.attendo.core.model

import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.Tally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full valid target range, swept end to end.
 *
 * Targets are user-choosable at whole-percent granularity (0% through 100%), so
 * "the engine handles whatever Percent can hold" must be true for *every* whole
 * percentage, not just the presets the pickers happen to offer. These tests
 * characterise the existing basis-points representation: they assert what the
 * model already guarantees, and would catch any future change that quietly
 * narrows the range or breaks a boundary.
 */
class PercentFullRangeTest {

    @Test
    fun `every whole percentage from 0 to 100 round-trips exactly`() {
        for (percent in 0..100) {
            val p = Percent.ofPercent(percent.toDouble())

            assertEquals("basis points of $percent%", percent * 100, p.basisPoints)
            assertEquals("back to the same whole percent", percent.toDouble(), p.asDouble, 0.0)
            assertEquals("format(0) renders without decoration", "$percent", p.format(0))
        }
    }

    @Test
    fun `ofBasisPoints accepts the entire 0 to 10000 range`() {
        // Every basis point a target may legally hold, including the endpoints.
        for (basisPoints in 0..Percent.BP_FULL) {
            assertEquals(basisPoints, Percent.ofBasisPoints(basisPoints).basisPoints)
        }
    }

    @Test
    fun `negative targets are rejected`() {
        val belowZero = listOf(-1, -100, -10_000, Int.MIN_VALUE)

        belowZero.forEach { basisPoints ->
            try {
                Percent.ofBasisPoints(basisPoints)
                throw AssertionError("ofBasisPoints($basisPoints) should have thrown")
            } catch (expected: IllegalArgumentException) {
                // the only acceptable outcome
            }
        }
    }

    @Test
    fun `the range endpoints order correctly against everything`() {
        for (percent in 1..100) {
            val p = Percent.ofPercent(percent.toDouble())

            assertTrue(Percent.ZERO < p)
            assertTrue(p <= Percent.FULL)
        }
        // 0% and 100% compare as themselves.
        assertEquals(0, Percent.ZERO.compareTo(Percent.ofPercent(0.0)))
        assertEquals(0, Percent.FULL.compareTo(Percent.ofPercent(100.0)))
    }

    @Test
    fun `advise is sound at the extreme whole-percentage targets`() {
        // A mid-range tally that meets some targets and misses others.
        val tally = Tally(7, 10)

        // 0%: unmissable, everything skippable.
        val atZero = AttendanceEngine.advise(tally, Percent.ofPercent(0.0))
        assertTrue(atZero.meetsTarget)
        assertEquals(Int.MAX_VALUE, atZero.unitsCanSkip)
        assertEquals(0, atZero.unitsMustAttend)
        assertTrue(atZero.targetReachable)

        // 1%: met (70% > 1%), and the skip allowance is the exact integer answer.
        val atOne = AttendanceEngine.advise(tally, Percent.ofPercent(1.0))
        assertTrue(atOne.meetsTarget)
        assertEquals(690, atOne.unitsCanSkip) // (7*10000 - 100*10) / 100
        assertEquals(0, atOne.unitsMustAttend)

        // 50%: met, 4 more units can be missed (7/14 is exactly 50%).
        val atHalf = AttendanceEngine.advise(tally, Percent.ofPercent(50.0))
        assertTrue(atHalf.meetsTarget)
        assertEquals(4, atHalf.unitsCanSkip)

        // 99%: missed, recovery is finite and exact ((7+k)/(10+k) >= 0.99 → k = 290).
        val atNinetyNine = AttendanceEngine.advise(tally, Percent.ofPercent(99.0))
        assertFalse(atNinetyNine.meetsTarget)
        assertEquals(290, atNinetyNine.unitsMustAttend)
        assertTrue(atNinetyNine.targetReachable)
        // And attending exactly that many units lands precisely on the target.
        assertTrue(
            AttendanceEngine.advise(Tally(7 + 290, 10 + 290), Percent.ofPercent(99.0)).meetsTarget,
        )

        // 100%: missed and unreachable — the one corner the model names explicitly.
        val atFull = AttendanceEngine.advise(tally, Percent.FULL)
        assertFalse(atFull.meetsTarget)
        assertEquals(0, atFull.unitsMustAttend)
        assertFalse(atFull.targetReachable)
    }

    @Test
    fun `every whole percentage target yields a consistent meetsTarget verdict`() {
        // 60 of 80 is exactly 75%: the verdict must flip at exactly the 75% mark
        // for every whole-percentage target, with no off-by-one at either end.
        val tally = Tally(60, 80)

        for (percent in 0..75) {
            assertTrue(
                "60/80 must meet a $percent% target",
                AttendanceEngine.advise(tally, Percent.ofPercent(percent.toDouble())).meetsTarget,
            )
        }
        for (percent in 76..100) {
            assertFalse(
                "60/80 must miss a $percent% target",
                AttendanceEngine.advise(tally, Percent.ofPercent(percent.toDouble())).meetsTarget,
            )
        }
    }
}
