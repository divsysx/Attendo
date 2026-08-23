package com.attendo.core.engine

import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The per-course detail screen: every logged session for one subject, with the
 * fraction marked and the percentage as it stood after that class.
 */
class CourseHistoryTest {

    private fun session(
        courseId: Long = ADEC_ID,
        date: LocalDate,
        startHour: Int = 9,
        units: Int = 1,
        attended: Int = units,
        status: SessionStatus = SessionStatus.HELD,
        cancellationReason: CancellationReason? = null,
    ) = ClassSession(
        courseId = courseId,
        patternId = 1L,
        date = date,
        startHour = startHour,
        unitsPlanned = units,
        unitsMask = UnitMask.allPresent(attended),
        status = status,
        cancellationReason = cancellationReason,
    )

    private val aug3: LocalDate = LocalDate.of(2026, 8, 3)

    @Test
    fun `rows read newest first by default`() {
        val sessions = listOf(
            session(date = aug3),
            session(date = aug3.plusDays(7)),
            session(date = aug3.plusDays(14)),
        )

        val rows = CourseHistory.rows(sessions, ADEC_ID)

        assertEquals(
            listOf(aug3.plusDays(14), aug3.plusDays(7), aug3),
            rows.map { it.session.date },
        )
    }

    @Test
    fun `two classes on the same day read in slot order`() {
        val sessions = listOf(
            session(date = aug3, startHour = 14),
            session(date = aug3, startHour = 9),
        )

        val rows = CourseHistory.rows(sessions, ADEC_ID, newestFirst = false)

        assertEquals(listOf(9, 14), rows.map { it.session.startHour })
    }

    @Test
    fun `the running percentage on a row is the figure as it stood after that class`() {
        val sessions = listOf(
            session(date = aug3, units = 2, attended = 2),               // 2/2 = 100%
            session(date = aug3.plusDays(1), units = 2, attended = 1),   // 3/4 = 75%
            session(date = aug3.plusDays(2), units = 2, attended = 0),   // 3/6 = 50%
        )

        val oldestFirst = CourseHistory.rows(sessions, ADEC_ID, newestFirst = false)

        assertEquals(
            listOf(Percent.FULL, Percent.ofPercent(75.0), Percent.ofPercent(50.0)),
            oldestFirst.map { it.runningPercent },
        )
        // Reversing for display must not change what each row says.
        val newestFirst = CourseHistory.rows(sessions, ADEC_ID)
        assertEquals(oldestFirst.reversed().map { it.runningPercent }, newestFirst.map { it.runningPercent })
        assertEquals(Percent.ofPercent(50.0), newestFirst.first().runningPercent)
    }

    @Test
    fun `each row reports its own fraction and percentage`() {
        val rows = CourseHistory.rows(
            listOf(session(date = aug3, startHour = 9, units = 2, attended = 1)),
            ADEC_ID,
        )

        val row = rows.single()
        assertEquals("1 / 2", row.unitsLabel)
        assertEquals(Percent.ofPercent(50.0), row.sessionPercent)
        assertEquals("9–11 AM", row.session.slotLabel)
    }

    @Test
    fun `cancelled and unreviewed sessions are listed but do not move the running total`() {
        // The screen is an audit trail, so nothing is hidden — but a cancelled class
        // must not dent the percentage on the rows that follow it.
        val sessions = listOf(
            session(date = aug3, units = 2, attended = 2),
            session(
                date = aug3.plusDays(1),
                units = 2,
                attended = 0,
                status = SessionStatus.CANCELLED,
                cancellationReason = CancellationReason.HOLIDAY,
            ),
            session(date = aug3.plusDays(2), units = 2, attended = 2, status = SessionStatus.SCHEDULED),
            session(date = aug3.plusDays(3), units = 2, attended = 1),
        )

        val rows = CourseHistory.rows(sessions, ADEC_ID, newestFirst = false)

        assertEquals(4, rows.size)
        assertEquals(
            listOf(Tally(2, 2), Tally(2, 2), Tally(2, 2), Tally(3, 4)),
            rows.map { it.runningTally },
        )
        assertNull(rows[1].sessionPercent)
        assertNull(rows[2].sessionPercent)
    }

    @Test
    fun `history is scoped to one subject`() {
        val sessions = listOf(
            session(courseId = ADEC_ID, date = aug3),
            session(courseId = PSA_ID, date = aug3, startHour = 11),
        )

        val rows = CourseHistory.rows(sessions, ADEC_ID)

        assertEquals(1, rows.size)
        assertEquals(ADEC_ID, rows.single().session.courseId)
    }

    @Test
    fun `a subject with nothing logged yields an empty list`() {
        assertTrue(CourseHistory.rows(emptyList(), ADEC_ID).isEmpty())
        assertTrue(CourseHistory.monthsFor(emptyList(), ADEC_ID).isEmpty())
    }

    @Test
    fun `month sections follow the display order and tally per month`() {
        val sessions = listOf(
            session(date = LocalDate.of(2026, 8, 24), units = 2, attended = 1),
            session(date = LocalDate.of(2026, 9, 7), units = 2, attended = 2),
            session(date = LocalDate.of(2026, 9, 14), units = 1, attended = 0),
        )

        val newestFirst = CourseHistory.monthsFor(sessions, ADEC_ID)

        assertEquals(
            listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)),
            newestFirst.map { it.month },
        )
        assertEquals(Tally(2, 3), newestFirst[0].tally)
        assertEquals(Tally(1, 2), newestFirst[1].tally)
        assertEquals(2, newestFirst[0].rows.size)

        val oldestFirst = CourseHistory.monthsFor(sessions, ADEC_ID, newestFirst = false)
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9)), oldestFirst.map { it.month })
    }

    @Test
    fun `the last running tally matches the subject rollup`() {
        val sessions = listOf(
            session(date = aug3, units = 2, attended = 1),
            session(date = aug3.plusDays(1), units = 3, attended = 3),
            session(
                date = aug3.plusDays(2),
                units = 2,
                attended = 0,
                status = SessionStatus.CANCELLED,
                cancellationReason = CancellationReason.FACULTY_CANCELLED,
            ),
        )

        val rows = CourseHistory.rows(sessions, ADEC_ID, newestFirst = false)

        assertEquals(AttendanceEngine.tallyOf(sessions), rows.last().runningTally)
    }

    private companion object {
        const val ADEC_ID = 1L
        const val PSA_ID = 2L
    }
}
