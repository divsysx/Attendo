package com.attendo.ui.attendance

import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * What the course editor lists as a course's slots.
 *
 * Removing a slot that has classes behind it retires the pattern instead of deleting it, so
 * the database keeps the row as history. The editor used to list every pattern the database
 * returned, retired ones included, with no way to tell them apart — so pressing remove on a
 * slot with history retired it invisibly and the row sat there looking untouched, which is
 * exactly how "you cannot remove slots at all" reads on a phone. The fix is this filter, and
 * this test is what keeps it from quietly regressing.
 */
class EditableSlotsTest {

    private val termStart = LocalDate.of(2026, 7, 28)

    private fun pattern(
        id: Long,
        day: DayOfWeek = DayOfWeek.MONDAY,
        hour: Int = 9,
        effectiveTo: LocalDate? = null,
    ): SessionPattern = SessionPattern(
        id = id,
        courseId = 1L,
        dayOfWeek = day,
        startHour = hour,
        units = 2,
        kind = SessionKind.LECTURE,
        room = "204",
        effectiveFrom = termStart,
        effectiveTo = effectiveTo,
    )

    @Test
    fun `a retired slot leaves the editor's list`() {
        val retired = pattern(1L, effectiveTo = LocalDate.of(2026, 9, 12))
        val running = pattern(2L, day = DayOfWeek.THURSDAY, hour = 14)

        val slots = editableSlots(listOf(retired, running))

        assertEquals(listOf(2L), slots.map { it.patternId })
    }

    @Test
    fun `a slot closed the day it started is still history`() {
        // Retirement sets effectiveTo to today, the last day the slot applies — inclusive, so
        // a closed range is closed however short it was. Only null is open-ended.
        val oneDay = pattern(1L, effectiveTo = termStart)
        val running = pattern(2L, day = DayOfWeek.THURSDAY, hour = 14)

        val slots = editableSlots(listOf(oneDay, running))

        assertEquals(listOf(2L), slots.map { it.patternId })
    }

    @Test
    fun `a course with every slot retired offers an empty list`() {
        val slots = editableSlots(
            listOf(
                pattern(1L, effectiveTo = LocalDate.of(2026, 9, 11)),
                pattern(2L, day = DayOfWeek.WEDNESDAY, effectiveTo = LocalDate.of(2026, 9, 12)),
            ),
        )

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `a course with no retired slots is listed whole`() {
        val patterns = listOf(
            pattern(1L),
            pattern(2L, day = DayOfWeek.THURSDAY, hour = 14),
            pattern(3L, day = DayOfWeek.FRIDAY, hour = 10),
        )

        val slots = editableSlots(patterns)

        assertEquals(listOf(1L, 2L, 3L), slots.map { it.patternId })
        assertEquals(listOf("204", "204", "204"), slots.map { it.room })
    }
}
