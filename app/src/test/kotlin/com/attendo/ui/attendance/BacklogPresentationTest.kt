package com.attendo.ui.attendance

import com.attendo.ui.shortLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BacklogPresentationTest {

    private val today = LocalDate.of(2026, 9, 19) // Sat 19 Sep
    private val friday = LocalDate.of(2026, 9, 18) // Fri 18 Sep
    private val monday = LocalDate.of(2026, 9, 14) // Mon 14 Sep

    /** REVIEW 16: pending = [today] names today, not "earlier dates". */
    @Test
    fun `Case 1 - only today pending review`() {
        val dates = listOf(today)
        val description = backlogDescription(dates, today)
        val action = backlogActionLabel(dates, today)
        val dialog = backlogRangeDialogPhrase(dates, today)

        assertTrue(description.startsWith("Today's classes are awaiting review."))
        assertEquals("Review today", action)
        assertEquals("from today", dialog)
    }

    /** REVIEW 17: pending = [one previous date] names that date. */
    @Test
    fun `Case 2 - exactly one previous day pending review`() {
        val dates = listOf(friday)
        val description = backlogDescription(dates, today)
        val action = backlogActionLabel(dates, today)
        val dialog = backlogRangeDialogPhrase(dates, today)

        assertTrue(description.startsWith("Classes from Fri 18 Sep are awaiting review."))
        assertEquals("Review Fri 18 Sep", action)
        assertEquals("from Fri 18 Sep", dialog)
    }

    /** REVIEW 18: pending = multiple previous dates names an accurate range. */
    @Test
    fun `Case 3 - multiple previous dates pending review`() {
        val dates = listOf(monday, friday)
        val description = backlogDescription(dates, today)
        val action = backlogActionLabel(dates, today)
        val dialog = backlogRangeDialogPhrase(dates, today)

        assertTrue(description.startsWith("Classes from Mon 14 Sep to Fri 18 Sep are awaiting review."))
        assertEquals("Review Mon 14 Sep", action)
        assertEquals("from Mon 14 Sep to Fri 18 Sep", dialog)
    }

    /** REVIEW 19: pending includes today + previous dates — today is not called an "earlier date". */
    @Test
    fun `Case 4 - today plus previous dates pending review`() {
        val dates = listOf(friday, today)
        val description = backlogDescription(dates, today)
        val action = backlogActionLabel(dates, today)
        val dialog = backlogRangeDialogPhrase(dates, today)

        assertTrue(description.startsWith("Classes from Fri 18 Sep to today are awaiting review."))
        assertEquals("Review Fri 18 Sep", action)
        assertEquals("from Fri 18 Sep to today", dialog)
    }

    /** REVIEW 20: pending = empty produces no copy (and the card is not shown). */
    @Test
    fun `Case 5 - no dates pending review`() {
        val dates = emptyList<LocalDate>()
        val description = backlogDescription(dates, today)
        val action = backlogActionLabel(dates, today)
        val dialog = backlogRangeDialogPhrase(dates, today)

        assertEquals("", description)
        assertEquals("", action)
        assertEquals("", dialog)
        // No target either, so the card's navigation lambda opens nothing rather than
        // reaching for `first()` on an empty list.
        assertNull(backlogReviewTarget(dates))
    }

    // ---- the label and the tap open the same day ------------------------------

    /**
     * REVIEW 21: the date the button *names* is the date the button *opens*.
     *
     * Both now read [backlogReviewTarget], so this is a property of the code rather than a
     * coincidence of two `first()` calls that happen to agree — and it is asserted for every
     * shape the backlog can take, including the one where "today" is in the list but is not
     * the oldest day (Case 4), which is where a label built from the wrong end would show.
     */
    @Test
    fun `the review button names the day it opens`() {
        val cases = listOf(
            listOf(today),
            listOf(friday),
            listOf(monday, friday),
            listOf(monday, friday, today),
            listOf(friday, today),
        )

        for (dates in cases) {
            val target = backlogReviewTarget(dates)
            assertEquals("the oldest pending day is the one to review", dates.first(), target)

            val label = backlogActionLabel(dates, today)
            val named = if (target == today) "today" else target!!.shortLabel()
            assertTrue(
                "a button opening $target must name it, but said \"$label\"",
                label.contains(named),
            )
        }
    }

    /**
     * Case 4's specific trap: today in the list, but not the oldest day.
     *
     * The button must send the student to the *oldest* day — review is done forwards, and
     * opening today would leave the older classes unmarked while the card kept claiming they
     * were waiting.
     */
    @Test
    fun `today in the backlog does not become the review target`() {
        val dates = listOf(monday, friday, today)

        assertEquals(monday, backlogReviewTarget(dates))
        assertEquals("Review Mon 14 Sep", backlogActionLabel(dates, today))
    }
}
