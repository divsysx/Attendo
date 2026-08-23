package com.attendo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The college's slot grid, checked against the printed timetable header:
 * 9-10 AM | 10-11 AM | 11 AM-12 PM | 12-1 PM | 1-2 PM | 2-3 PM | 3-4 PM | 4-5 PM | 5-6 PM
 */
class TimeGridTest {

    @Test
    fun `there are nine slots, nine to six`() {
        assertEquals(9, TimeGrid.startHours.size)
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16, 17), TimeGrid.startHours)
        assertEquals(9, TimeGrid.MAX_UNITS_PER_SESSION)
    }

    @Test
    fun `slot labels match the printed header exactly`() {
        assertEquals(
            listOf(
                "9–10 AM",
                "10–11 AM",
                "11 AM–12 PM",
                "12–1 PM",
                "1–2 PM",
                "2–3 PM",
                "3–4 PM",
                "4–5 PM",
                "5–6 PM",
            ),
            TimeGrid.startHours.map(TimeGrid::slotLabel),
        )
    }

    @Test
    fun `a two hour block reads as one range`() {
        assertEquals("9–11 AM", TimeGrid.rangeLabel(9, 2))
        assertEquals("10 AM–12 PM", TimeGrid.rangeLabel(10, 2))
        assertEquals("11 AM–1 PM", TimeGrid.rangeLabel(11, 2))
        assertEquals("12–2 PM", TimeGrid.rangeLabel(12, 2))
        assertEquals("2–4 PM", TimeGrid.rangeLabel(14, 2))
        assertEquals("4–6 PM", TimeGrid.rangeLabel(16, 2))
    }

    @Test
    fun `longer blocks read the same way`() {
        assertEquals("9 AM–12 PM", TimeGrid.rangeLabel(9, 3))
        assertEquals("2–5 PM", TimeGrid.rangeLabel(14, 3))
        assertEquals("9 AM–6 PM", TimeGrid.rangeLabel(9, 9))
    }

    @Test
    fun `unit labels walk the session hour by hour`() {
        assertEquals("9–10 AM", TimeGrid.unitLabel(9, 0))
        assertEquals("10–11 AM", TimeGrid.unitLabel(9, 1))
        assertEquals("11 AM–12 PM", TimeGrid.unitLabel(9, 2))
        assertEquals("2–3 PM", TimeGrid.unitLabel(14, 0))
        assertEquals("3–4 PM", TimeGrid.unitLabel(14, 1))
    }

    @Test
    fun `only the nine printed hours are valid starts`() {
        assertTrue(TimeGrid.isValidStartHour(9))
        assertTrue(TimeGrid.isValidStartHour(17))
        assertFalse(TimeGrid.isValidStartHour(8))
        assertFalse(TimeGrid.isValidStartHour(18))
        assertFalse(TimeGrid.isValidStartHour(0))
    }

    @Test
    fun `a session must end by six`() {
        assertTrue(TimeGrid.fits(17, 1))
        assertFalse(TimeGrid.fits(17, 2))
        assertTrue(TimeGrid.fits(16, 2))
        assertTrue(TimeGrid.fits(9, TimeGrid.MAX_UNITS_PER_SESSION))
        assertFalse(TimeGrid.fits(9, TimeGrid.MAX_UNITS_PER_SESSION + 1))
    }

    @Test
    fun `a session must have at least one unit and start inside the day`() {
        assertFalse(TimeGrid.fits(9, 0))
        assertFalse(TimeGrid.fits(9, -1))
        assertFalse(TimeGrid.fits(8, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero length range has no label`() {
        TimeGrid.rangeLabel(9, 0)
    }
}
