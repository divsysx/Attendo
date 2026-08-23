package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exactness guarantees the whole engine rests on. If basis points drift, every
 * "can I skip the next class?" answer drifts with them.
 */
class PercentTest {

    @Test
    fun `whole percentages convert without loss`() {
        assertEquals(7500, Percent.ofPercent(75.0).basisPoints)
        assertEquals(0, Percent.ZERO.basisPoints)
        assertEquals(10_000, Percent.FULL.basisPoints)
        assertEquals(7500, Percent.DEFAULT_TARGET.basisPoints)
    }

    @Test
    fun `half percentages survive the round trip`() {
        val p = Percent.ofPercent(82.5)

        assertEquals(8250, p.basisPoints)
        assertEquals(82.5, p.asDouble, 0.0)
    }

    @Test
    fun `a ratio at exactly the target lands exactly on it`() {
        // The floating-point trap: 60.0 / 80.0 * 100 is fine, but many such ratios
        // are not, and comparing them against 75.0 is where a student on precisely
        // 75% gets told they are short.
        assertEquals(Percent.ofPercent(75.0), Percent.ofRatio(60, 80))
        assertEquals(Percent.ofPercent(75.0), Percent.ofRatio(3, 4))
        assertEquals(Percent.ofPercent(50.0), Percent.ofRatio(1, 2))
        assertEquals(Percent.FULL, Percent.ofRatio(17, 17))
        assertEquals(Percent.ZERO, Percent.ofRatio(0, 9))
    }

    @Test
    fun `repeating ratios round to the nearest basis point`() {
        assertEquals(6667, Percent.ofRatio(2, 3)!!.basisPoints)
        assertEquals(3333, Percent.ofRatio(1, 3)!!.basisPoints)
        assertEquals(9333, Percent.ofRatio(28, 30)!!.basisPoints)
        assertEquals(1667, Percent.ofRatio(1, 6)!!.basisPoints)
    }

    @Test
    fun `nothing held yet is undefined rather than zero percent`() {
        // "No classes yet" and "attended none of them" are different states and the
        // dashboard must not print 0% for the first one.
        assertNull(Percent.ofRatio(0, 0))
    }

    @Test
    fun `percentages order by value`() {
        assertTrue(Percent.ofPercent(74.99) < Percent.DEFAULT_TARGET)
        assertTrue(Percent.ofPercent(75.01) > Percent.DEFAULT_TARGET)
        assertFalse(Percent.ofPercent(75.0) < Percent.DEFAULT_TARGET)
        assertEquals(
            listOf(2500, 5000, 7500),
            listOf(Percent.ofPercent(75.0), Percent.ofPercent(25.0), Percent.ofPercent(50.0))
                .sorted()
                .map { it.basisPoints },
        )
    }

    @Test
    fun `fractions feed a progress bar directly`() {
        assertEquals(0.75f, Percent.ofPercent(75.0).asFraction, 0.0f)
        assertEquals(1f, Percent.FULL.asFraction, 0.0f)
        assertEquals(0f, Percent.ZERO.asFraction, 0.0f)
    }

    @Test
    fun `formatting drops trailing zeros but keeps real decimals`() {
        assertEquals("75", Percent.ofPercent(75.0).format())
        assertEquals("82.5", Percent.ofPercent(82.5).format())
        assertEquals("100", Percent.FULL.format())
        assertEquals("0", Percent.ZERO.format())
        assertEquals("66.7", Percent.ofBasisPoints(6667).format())
        assertEquals("66.67", Percent.ofBasisPoints(6667).format(decimals = 2))
        assertEquals("67", Percent.ofBasisPoints(6667).format(decimals = 0))
        assertEquals("75%", Percent.ofPercent(75.0).toString())
    }

    @Test
    fun `formatting is locale independent`() {
        // A device set to a comma-decimal locale must still render "82.5".
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("82.5", Percent.ofPercent(82.5).format())
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative percentage is rejected`() {
        Percent.ofPercent(-1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative basis points are rejected`() {
        Percent.ofBasisPoints(-1)
    }

    @Test
    fun `large tallies do not overflow the ratio arithmetic`() {
        // Int arithmetic would overflow at numerator * 10_000 above ~214k units;
        // the computation is done in Long for exactly this reason.
        assertEquals(Percent.ofPercent(75.0), Percent.ofRatio(3_000_000, 4_000_000))
    }
}
