package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate every target entered by hand goes through.
 *
 * A student types a percentage or drags a slider, and both paths end at
 * [Percent.ofWholePercent]. It is the one place that decides what a typed number means, so the
 * properties worth pinning are the ones a picker depends on: the whole range is valid at both
 * ends, anything outside it is refused rather than clipped, and the result is never a fraction
 * — a target that came out of here is exactly the number the student chose.
 *
 * Refusing rather than clamping is the deliberate part. "150" is a typo, and a control that
 * silently turned it into 100 would leave the field showing a number the stored target does not
 * have; the picker would then have to explain a discrepancy it created itself. `null` lets the
 * field keep showing what was typed and write nothing.
 *
 * The second half is about the other kind of stored target. An install restored from a backup,
 * or upgraded from a build that offered 72.5%, holds basis points that are not a whole percent,
 * and no screen is allowed to round those away to suit a widget. Nothing here can produce such
 * a value — that is the point — and nothing here destroys one either.
 */
class WholePercentTargetTest {

    // ---- the whole range -----------------------------------------------------

    @Test
    fun `every whole percentage from 0 to 100 is accepted`() {
        for (percent in 0..Percent.MAX_WHOLE_PERCENT) {
            val parsed = Percent.ofWholePercent(percent)

            assertEquals("$percent was refused", percent * 100, parsed?.basisPoints)
        }
    }

    @Test
    fun `both ends of the range are real targets`() {
        // 0 because "I only care that the classes happened" is an answer, and 100 because a
        // student aiming at a perfect record is not making an error.
        assertEquals(0, Percent.ofWholePercent(0)?.basisPoints)
        assertEquals(Percent.BP_FULL, Percent.ofWholePercent(100)?.basisPoints)
        assertEquals(Percent.ZERO, Percent.ofWholePercent(0))
        assertEquals(Percent.FULL, Percent.ofWholePercent(100))
    }

    @Test
    fun `the highest whole percentage is 100`() {
        assertEquals(100, Percent.MAX_WHOLE_PERCENT)
        assertEquals(Percent.MAX_WHOLE_PERCENT * Percent.BP_PER_PERCENT, Percent.BP_FULL)
    }

    // ---- what is refused -----------------------------------------------------

    @Test
    fun `a percentage above the range is refused, not clipped`() {
        val refused = listOf(101, 150, 999)

        refused.forEach { percent ->
            assertNull("$percent should not be a target", Percent.ofWholePercent(percent))
        }
    }

    @Test
    fun `a negative percentage is refused`() {
        assertNull(Percent.ofWholePercent(-1))
        assertNull(Percent.ofWholePercent(-75))
    }

    @Test
    fun `a refused value is distinguished from a valid zero`() {
        // The whole reason the gate returns null instead of 0 for bad input: a caller that
        // collapsed the two would set a target of 0% every time somebody mistyped.
        assertNull(Percent.ofWholePercent(101))
        assertEquals(Percent.ZERO, Percent.ofWholePercent(0))
    }

    // ---- what it can never produce -------------------------------------------

    @Test
    fun `nothing it returns is a fraction of a percent`() {
        for (percent in 0..Percent.MAX_WHOLE_PERCENT) {
            val basisPoints = Percent.ofWholePercent(percent)!!.basisPoints

            assertEquals("$percent% is not whole", 0, basisPoints % Percent.BP_PER_PERCENT)
        }
    }

    @Test
    fun `a whole percentage is never the same value as the fraction beside it`() {
        // The gate must not be able to reach a stored 72.5% by any input, or the picker
        // would report a change it did not make.
        val stored = Percent.ofPercent(72.5)

        assertNotEquals(stored, Percent.ofWholePercent(72))
        assertNotEquals(stored, Percent.ofWholePercent(73))
        assertEquals(7250, stored.basisPoints)
    }

    // ---- a target that is already stored -------------------------------------

    @Test
    fun `a stored fractional target keeps its exact value`() {
        // Reading a target back is not a place a value may change. `ofBasisPoints` is the
        // read path the store and the sync rows use, and it is lossless by construction.
        val stored = Percent.ofBasisPoints(7250)

        assertEquals(7250, stored.basisPoints)
        assertEquals(72.5, stored.asDouble, 0.0)
        assertEquals("72.5", stored.format())
        assertEquals("72.5%", stored.toString())
    }

    @Test
    fun `the read path holds values outside the whole range without complaint`() {
        // A restored backup is not validated against a range the student's own screen offers;
        // refusing a row here would turn an old file into an unreadable one. Nothing in the
        // app can *set* these, but it can still be asked to read them.
        assertEquals(10_500, Percent.ofBasisPoints(10_500).basisPoints)
        assertEquals(105.0, Percent.ofBasisPoints(10_500).asDouble, 0.0)
    }

    @Test
    fun `a fractional target is not disturbed by rounding it for display`() {
        // The headline and the row round for layout; the stored value is what the engine
        // compares against, and the two are different numbers on purpose.
        val stored = Percent.ofBasisPoints(7250)

        assertEquals("73", stored.format(0))
        assertEquals(7250, stored.basisPoints)
    }

    @Test
    fun `the engine still compares a fractional target exactly`() {
        // 72.5% as a threshold: 29 of 40 hours is 72.5% exactly, so it meets the target and
        // does not fall below it. Binary floating point would put this on the wrong side.
        val target = Percent.ofBasisPoints(7250)

        assertEquals(target, Percent.ofRatio(29, 40))
        assertTrue(Percent.ofRatio(29, 40)!! >= target)
        assertTrue(Percent.ofRatio(28, 40)!! < target)
    }
}
