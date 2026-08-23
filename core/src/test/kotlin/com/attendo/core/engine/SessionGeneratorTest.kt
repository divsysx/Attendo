package com.attendo.core.engine

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Materialising recurring patterns into dated sessions.
 *
 * The rule that matters most here is idempotency: generation runs often, and it
 * must never duplicate a session or undo a decision the student already made.
 */
class SessionGeneratorTest {

    private val termStart: LocalDate = LocalDate.of(2026, 7, 28) // a Tuesday
    private val calendar = AcademicCalendar(
        termStart = termStart,
        termEnd = LocalDate.of(2026, 12, 15),
    )
    private val now: Instant = Instant.parse("2026-08-03T09:00:00Z")

    /** ADEC: Monday 9-11, a 2-hour block in room 204. */
    private val mondayBlock = SessionPattern(
        id = 1L,
        courseId = ADEC_ID,
        dayOfWeek = DayOfWeek.MONDAY,
        startHour = 9,
        units = 2,
        room = "204",
        effectiveFrom = termStart,
    )

    /** PSA: Wednesday 2-3, a single hour. */
    private val wednesdayHour = SessionPattern(
        id = 2L,
        courseId = PSA_ID,
        dayOfWeek = DayOfWeek.WEDNESDAY,
        startHour = 14,
        units = 1,
        room = "312",
        effectiveFrom = termStart,
    )

    private val firstMonday: LocalDate = LocalDate.of(2026, 8, 3)
    private val firstWednesday: LocalDate = LocalDate.of(2026, 8, 5)

    // ---- basic materialisation ---------------------------------------------

    @Test
    fun `patterns produce one session per matching weekday`() {
        val drafts = SessionGenerator.draftsFor(
            patterns = listOf(mondayBlock, wednesdayHour),
            existing = emptyList(),
            from = firstMonday,
            to = firstMonday.plusDays(6),
            calendar = calendar,
        )

        assertEquals(2, drafts.size)
        assertEquals(firstMonday, drafts[0].date)
        assertEquals(9, drafts[0].startHour)
        assertEquals(2, drafts[0].unitsPlanned)
        assertEquals("204", drafts[0].room)
        assertEquals(firstWednesday, drafts[1].date)
        assertEquals(1, drafts[1].unitsPlanned)
    }

    @Test
    fun `drafts arrive pending and pre-filled as present so approve all needs no edits`() {
        val draft = SessionGenerator.draftsFor(
            listOf(mondayBlock), emptyList(), firstMonday, firstMonday, calendar,
        ).single()

        val session = draft.toSession()

        assertEquals(SessionStatus.SCHEDULED, session.status)
        assertEquals(UnitMask.allPresent(2), session.unitsMask)
        // Pre-filled but not yet counted — an unreviewed row is invisible either way.
        assertFalse(session.countsTowardAttendance)
        assertEquals(2, session.unitsPlanned)
    }

    @Test
    fun `drafts are ordered by date then slot`() {
        val lateMonday = mondayBlock.copy(id = 3L, startHour = 16, units = 1)

        val drafts = SessionGenerator.draftsFor(
            patterns = listOf(lateMonday, wednesdayHour, mondayBlock),
            existing = emptyList(),
            from = firstMonday,
            to = firstMonday.plusDays(6),
            calendar = calendar,
        )

        assertEquals(listOf(firstMonday, firstMonday, firstWednesday), drafts.map { it.date })
        assertEquals(listOf(9, 16, 14), drafts.map { it.startHour })
    }

    // ---- idempotency --------------------------------------------------------

    @Test
    fun `running generation twice produces nothing the second time`() {
        val first = SessionGenerator.draftsFor(
            listOf(mondayBlock, wednesdayHour), emptyList(), firstMonday, firstMonday.plusDays(6), calendar,
        )
        val stored = first.map { it.toSession().copy(id = it.date.dayOfMonth.toLong()) }

        val second = SessionGenerator.draftsFor(
            listOf(mondayBlock, wednesdayHour), stored, firstMonday, firstMonday.plusDays(6), calendar,
        )

        assertTrue(second.isEmpty())
    }

    @Test
    fun `a cancelled class is never resurrected by a later generation run`() {
        // The single most important property here: the student cancels Monday's
        // lecture, reopens the app, and generation runs again. The row still occupies
        // (patternId, date), so nothing comes back.
        val cancelled = SessionOps.cancel(
            session = SessionGenerator.draftsFor(
                listOf(mondayBlock), emptyList(), firstMonday, firstMonday, calendar,
            ).single().toSession().copy(id = 10L),
            reason = CancellationReason.FACULTY_CANCELLED,
            now = now,
        )

        val regenerated = SessionGenerator.draftsFor(
            listOf(mondayBlock), listOf(cancelled), firstMonday, firstMonday, calendar,
        )

        assertTrue(regenerated.isEmpty())
    }

    @Test
    fun `an approved class is not regenerated`() {
        val approved = SessionOps.markPresent(
            SessionGenerator.draftsFor(listOf(mondayBlock), emptyList(), firstMonday, firstMonday, calendar)
                .single().toSession().copy(id = 11L),
            now,
        )

        val regenerated = SessionGenerator.draftsFor(
            listOf(mondayBlock), listOf(approved), firstMonday, firstMonday, calendar,
        )

        assertTrue(regenerated.isEmpty())
    }

    @Test
    fun `an ad-hoc session does not suppress the timetabled class in the same slot`() {
        // An extra class added by hand must not make the generator think Monday's
        // regular lecture already exists — ad-hoc rows hold no (patternId, date) key.
        val extra = SessionOps.adhoc(
            courseId = ADEC_ID, date = firstMonday, startHour = 9, unitsPlanned = 2,
        ).copy(id = 12L)

        val drafts = SessionGenerator.draftsFor(
            listOf(mondayBlock), listOf(extra), firstMonday, firstMonday, calendar,
        )

        assertEquals(1, drafts.size)
        assertEquals(1L, drafts.single().patternId)
    }

    @Test
    fun `sessions from a different pattern in the same slot are generated independently`() {
        val clashing = wednesdayHour.copy(id = 4L, dayOfWeek = DayOfWeek.MONDAY, startHour = 9, units = 2)
        val existingFromFirstPattern = SessionGenerator.draftsFor(
            listOf(mondayBlock), emptyList(), firstMonday, firstMonday, calendar,
        ).single().toSession().copy(id = 13L)

        val drafts = SessionGenerator.draftsFor(
            listOf(mondayBlock, clashing), listOf(existingFromFirstPattern), firstMonday, firstMonday, calendar,
        )

        assertEquals(listOf(4L), drafts.map { it.patternId })
    }

    // ---- calendar rules -----------------------------------------------------

    @Test
    fun `sundays never produce classes`() {
        val sundayPattern = mondayBlock.copy(id = 5L, dayOfWeek = DayOfWeek.SUNDAY)

        val drafts = SessionGenerator.draftsFor(
            listOf(sundayPattern), emptyList(), firstMonday, firstMonday.plusDays(13), calendar,
        )

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `saturdays produce classes only when listed as working`() {
        val saturdayPattern = mondayBlock.copy(id = 6L, dayOfWeek = DayOfWeek.SATURDAY)
        val firstSaturday = LocalDate.of(2026, 8, 8)
        val secondSaturday = LocalDate.of(2026, 8, 15)

        val withoutWorkingSaturdays = SessionGenerator.draftsFor(
            listOf(saturdayPattern), emptyList(), firstMonday, firstMonday.plusDays(20), calendar,
        )
        assertTrue(withoutWorkingSaturdays.isEmpty())

        val withOne = SessionGenerator.draftsFor(
            patterns = listOf(saturdayPattern),
            existing = emptyList(),
            from = firstMonday,
            to = firstMonday.plusDays(20),
            calendar = calendar.copy(workingSaturdays = setOf(secondSaturday)),
        )

        assertEquals(listOf(secondSaturday), withOne.map { it.date })
        assertFalse(withOne.any { it.date == firstSaturday })
    }

    @Test
    fun `holidays suppress generation`() {
        val holidayCalendar = calendar.copy(holidays = setOf(firstMonday))

        val drafts = SessionGenerator.draftsFor(
            listOf(mondayBlock), emptyList(), firstMonday, firstMonday.plusDays(13), holidayCalendar,
        )

        assertEquals(listOf(firstMonday.plusDays(7)), drafts.map { it.date })
    }

    @Test
    fun `generation is clipped to the term`() {
        val shortTerm = calendar.copy(termEnd = firstMonday.plusDays(2))

        val drafts = SessionGenerator.draftsFor(
            listOf(mondayBlock), emptyList(), firstMonday.minusDays(30), firstMonday.plusDays(60), shortTerm,
        )

        assertEquals(listOf(firstMonday), drafts.map { it.date })
    }

    @Test
    fun `a request entirely outside the term yields nothing`() {
        val drafts = SessionGenerator.draftsFor(
            listOf(mondayBlock), emptyList(),
            from = LocalDate.of(2027, 3, 1), to = LocalDate.of(2027, 3, 31),
            calendar = calendar,
        )

        assertTrue(drafts.isEmpty())
    }

    // ---- pattern date ranges ------------------------------------------------

    @Test
    fun `a retired pattern stops generating after its last effective date`() {
        // Mid-semester revision: the Monday block is retired after the first week and
        // a replacement takes over, so history keeps pointing at the right pattern.
        val retired = mondayBlock.retiredAfter(firstMonday)
        val replacement = SessionPattern(
            id = 20L,
            courseId = ADEC_ID,
            dayOfWeek = DayOfWeek.MONDAY,
            startHour = 14,
            units = 2,
            room = "203",
            effectiveFrom = firstMonday.plusDays(1),
        )

        val drafts = SessionGenerator.draftsFor(
            listOf(retired, replacement), emptyList(), firstMonday, firstMonday.plusDays(13), calendar,
        )

        assertEquals(
            listOf(firstMonday to 9, firstMonday.plusDays(7) to 14),
            drafts.map { it.date to it.startHour },
        )
        assertEquals(listOf(1L, 20L), drafts.map { it.patternId })
    }

    @Test
    fun `a pattern starting later in the term does not backfill`() {
        val startsLate = mondayBlock.copy(id = 21L, effectiveFrom = firstMonday.plusDays(7))

        val drafts = SessionGenerator.draftsFor(
            listOf(startsLate), emptyList(), firstMonday, firstMonday.plusDays(13), calendar,
        )

        assertEquals(listOf(firstMonday.plusDays(7)), drafts.map { it.date })
    }

    @Test
    fun `patterns detect overlapping weekly slots`() {
        val nineToEleven = mondayBlock
        val tenToEleven = mondayBlock.copy(id = 30L, startHour = 10, units = 1)
        val elevenToTwelve = mondayBlock.copy(id = 31L, startHour = 11, units = 1)
        val sameSlotOtherDay = mondayBlock.copy(id = 32L, dayOfWeek = DayOfWeek.TUESDAY)

        assertTrue(nineToEleven.overlaps(tenToEleven))
        assertFalse(nineToEleven.overlaps(elevenToTwelve))
        assertFalse(nineToEleven.overlaps(sameSlotOtherDay))
        // Retiring the first before the second begins removes the clash.
        assertFalse(nineToEleven.retiredAfter(firstMonday).overlaps(tenToEleven.copy(effectiveFrom = firstMonday.plusDays(1))))
    }

    // ---- the review screen's day plan --------------------------------------

    @Test
    fun `day plan merges stored rows with the ones still missing`() {
        val alreadyThere = SessionGenerator.draftsFor(
            listOf(mondayBlock), emptyList(), firstMonday, firstMonday, calendar,
        ).single().toSession().copy(id = 40L)
        val extra = SessionOps.adhoc(ADEC_ID, firstMonday, 16, 1).copy(id = 41L)
        val alsoMonday = wednesdayHour.copy(id = 50L, dayOfWeek = DayOfWeek.MONDAY, startHour = 12)

        val plan = SessionGenerator.dayPlan(
            date = firstMonday,
            patterns = listOf(mondayBlock, alsoMonday),
            existing = listOf(alreadyThere, extra),
            calendar = calendar,
        )

        assertEquals(2, plan.stored.size)
        assertEquals(listOf(50L), plan.missing.map { it.patternId })
        assertTrue(plan.hasAnything)
        assertFalse(plan.isFullyReviewed)
        // 2 pending stored units + 1 ad-hoc + 1 still-missing unit.
        assertEquals(4, plan.unitsPendingApproval)
    }

    @Test
    fun `a fully reviewed day reports nothing pending`() {
        val approved = SessionOps.markPresent(
            SessionGenerator.draftsFor(listOf(mondayBlock), emptyList(), firstMonday, firstMonday, calendar)
                .single().toSession().copy(id = 42L),
            now,
        )

        val plan = SessionGenerator.dayPlan(firstMonday, listOf(mondayBlock), listOf(approved), calendar)

        assertTrue(plan.isFullyReviewed)
        assertEquals(0, plan.unitsPendingApproval)
        assertTrue(plan.hasAnything)
    }

    @Test
    fun `a day plan on a holiday reports nothing to do`() {
        val plan = SessionGenerator.dayPlan(
            date = firstMonday,
            patterns = listOf(mondayBlock),
            existing = emptyList(),
            calendar = calendar.copy(holidays = setOf(firstMonday)),
        )

        assertFalse(plan.isTeachingDay)
        assertFalse(plan.hasAnything)
        assertTrue(plan.isFullyReviewed)
    }

    @Test
    fun `a day plan ignores sessions on other dates`() {
        val otherDay: ClassSession = SessionOps.adhoc(ADEC_ID, firstWednesday, 9, 1).copy(id = 43L)

        val plan = SessionGenerator.dayPlan(firstMonday, listOf(mondayBlock), listOf(otherDay), calendar)

        assertTrue(plan.stored.isEmpty())
        assertEquals(1, plan.missing.size)
    }

    // ---- calendar helpers ---------------------------------------------------

    @Test
    fun `teaching days exclude weekends and holidays`() {
        val week = calendar.copy(holidays = setOf(LocalDate.of(2026, 8, 5)))
            .teachingDaysBetween(firstMonday, firstMonday.plusDays(6))

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 3), // Mon
                LocalDate.of(2026, 8, 4), // Tue
                // Wed is a holiday
                LocalDate.of(2026, 8, 6), // Thu
                LocalDate.of(2026, 8, 7), // Fri
            ),
            week,
        )
    }

    @Test
    fun `dates outside the term are not teaching days`() {
        assertFalse(calendar.isTeachingDay(termStart.minusDays(1)))
        assertTrue(calendar.isTeachingDay(termStart))
        assertFalse(calendar.isTeachingDay(calendar.termEnd.plusDays(1)))
    }

    @Test
    fun `the bundled default calendar matches the supplied timetable`() {
        val default = AcademicCalendar.DEFAULT_2026_27

        assertEquals(LocalDate.of(2026, 7, 28), default.termStart)
        assertTrue(default.isTeachingDay(LocalDate.of(2026, 7, 28)))
        assertNull(default.holidays.firstOrNull())
    }

    @Test
    fun `patterns of a course with no sessions in range yield nothing`() {
        val drafts = SessionGenerator.draftsFor(
            patterns = emptyList(),
            existing = emptyList(),
            from = firstMonday,
            to = firstMonday.plusDays(30),
            calendar = calendar,
        )

        assertTrue(drafts.isEmpty())
    }

    // ---- the restore backfill -----------------------------------------------
    //
    // A backup restore writes the rows the file carried and then runs syncSessions to backfill
    // the sessions the restored patterns imply up to today. syncSessions is a thin database
    // wrapper around draftsFor: it asks for [termStart, today] and inserts each draft IGNORE.
    // The rule that makes a restored past class appear as review work — and a restored future
    // class not to — is therefore draftsFor's (what dates it covers) crossed with
    // isReviewableOn's (date-bound), both pure. These tests pin that intersection so a change
    // to either half cannot quietly break the post-restore review queue without failing here.

    @Test
    fun `a restore backfill through today generates only past or today drafts, all reviewable`() {
        // A restored term: one Monday lecture, and a single already-reviewed session a week ago.
        // Everything else the pattern implies up to today is missing and must be generated.
        val today = firstMonday.plusDays(14) // 2026-08-17, a Monday inside the term
        val lastWeek = today.minusDays(7) // 2026-08-10
        val alreadyReviewed = SessionOps.markPresent(
            SessionGenerator.draftsFor(listOf(mondayBlock), emptyList(), lastWeek, lastWeek, calendar)
                .single().toSession().copy(id = 70L),
            now,
        )

        val drafts = SessionGenerator.draftsFor(
            patterns = listOf(mondayBlock),
            existing = listOf(alreadyReviewed),
            from = calendar.termStart,
            to = today,
            calendar = calendar,
        )

        // Only the Mondays at or before today are missing; nothing after today is generated.
        assertTrue(drafts.all { !it.date.isAfter(today) })
        assertTrue(drafts.all { it.toSession().isReviewableOn(today) })
        // The already-reviewed last-week class is not regenerated — review state survives.
        assertFalse(drafts.any { it.date == lastWeek })
    }

    @Test
    fun `drafts the restore would generate beyond today are not reviewable`() {
        // syncSessions never asks past today, so this never runs in the restore path. It is here
        // to pin the other half: had a caller asked draftsFor through a future date, the drafts
        // it produced would be SCHEDULED-but-future and therefore NOT reviewable — the same
        // status as a restored future class that the file itself carried. Neither kind of future
        // SCHEDULED row reaches the review queue, however it was created.
        val today = firstMonday
        val future = today.plusDays(7)

        val drafts = SessionGenerator.draftsFor(
            patterns = listOf(mondayBlock),
            existing = emptyList(),
            from = today,
            to = future,
            calendar = calendar,
        )

        assertTrue(drafts.any { it.date.isAfter(today) })
        assertTrue(drafts.all { it.toSession().status == SessionStatus.SCHEDULED })
        assertFalse(drafts.filter { it.date.isAfter(today) }.any { it.toSession().isReviewableOn(today) })
    }

    @Test
    fun `practical patterns keep their kind and room through generation`() {
        val lab = SessionPattern(
            id = 60L,
            courseId = ADEC_ID,
            dayOfWeek = DayOfWeek.MONDAY,
            startHour = 14,
            units = 2,
            kind = SessionKind.PRACTICAL,
            room = "Basement Lab",
            effectiveFrom = termStart,
        )

        val session = SessionGenerator.draftsFor(listOf(lab), emptyList(), firstMonday, firstMonday, calendar)
            .single().toSession()

        assertEquals(SessionKind.PRACTICAL, session.kind)
        assertEquals("Basement Lab", session.room)
        assertEquals("2–4 PM", session.slotLabel)
    }

    private companion object {
        const val ADEC_ID = 1L
        const val PSA_ID = 2L
    }
}
