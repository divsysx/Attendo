package com.attendo.core.rollover

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.Semester
import com.attendo.core.model.SemesterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * When the app should ask about a new semester.
 *
 * There are exactly three situations, and only one of them is destructive. The decision is a pure
 * function of two semesters — the one stored on the device and the one this build of the app
 * carries — so it is tested here without a database, without settings, and without a clock. The
 * database write that the destructive case triggers has its own discipline in
 * [com.attendo.data.RolloverRepository]; this is only the question it answers.
 *
 * ### The one rule that runs through every case
 *
 * A semester is its year and type ([Semester.isSameTermAs]), never its dates. The student may
 * correct a term's end date in March, and that correction is not a new semester. These tests pin
 * that down explicitly: two semesters identical in year and type but different in every other
 * field still compare as the same term, because detecting a rollover on dates would re-prompt every
 * time a date was edited.
 */
class RolloverDecisionTest {

    private val bundled: Semester = Semester.of(AcademicCalendar.DEFAULT_2026_27)

    // ---- the three situations -------------------------------------------------

    @Test
    fun `no stored semester means adopt the bundled one silently`() {
        // A fresh install, or one seeded with courses that have no semester row yet. There is
        // nothing to lose, so the destructive gate must not appear.
        val decision = detectRollover(current = null, bundled = bundled)

        assertEquals(RolloverDecision.EstablishSilently, decision)
    }

    @Test
    fun `the stored semester being the bundled one means nothing to do`() {
        val decision = detectRollover(current = bundled, bundled = bundled)

        assertEquals(RolloverDecision.None, decision)
    }

    @Test
    fun `a stored semester with the bundled identity means nothing to do`() {
        // The same term even though the row's id and archived flag differ — identity, not
        // row-equality, is what "up to date" means.
        val stored = bundled.copy(id = 7L)

        val decision = detectRollover(current = stored, bundled = bundled)

        assertEquals(RolloverDecision.None, decision)
    }

    @Test
    fun `a stored semester from a later year requires a rollover`() {
        val nextOdd = Semester(
            year = 2027,
            type = SemesterType.ODD,
            startDate = LocalDate.of(2027, 7, 26),
            endDate = LocalDate.of(2027, 12, 14),
        )

        val decision = detectRollover(current = nextOdd, bundled = bundled)

        assertTrue(decision is RolloverDecision.RolloverRequired)
        nextOdd.assertIsRolloverTo(bundled, decision as RolloverDecision.RolloverRequired)
    }

    @Test
    fun `an even semester stored against an odd bundled one requires a rollover`() {
        // The two halves of one academic year are different semesters — odd is not even.
        val even = Semester(
            year = 2027,
            type = SemesterType.EVEN,
            startDate = LocalDate.of(2027, 1, 5),
            endDate = LocalDate.of(2027, 5, 15),
        )

        val decision = detectRollover(current = even, bundled = bundled)

        assertTrue(decision is RolloverDecision.RolloverRequired)
        even.assertIsRolloverTo(bundled, decision as RolloverDecision.RolloverRequired)
    }

    @Test
    fun `an even bundled semester against an odd stored one also requires a rollover`() {
        // The mirror of the case above: the build carries the even semester, the device still
        // holds the odd one. Direction does not matter — any term difference is a rollover.
        val evenBundled = Semester(
            year = 2027,
            type = SemesterType.EVEN,
            startDate = LocalDate.of(2027, 1, 5),
            endDate = LocalDate.of(2027, 5, 15),
        )

        val decision = detectRollover(current = bundled, bundled = evenBundled)

        assertTrue(decision is RolloverDecision.RolloverRequired)
        bundled.assertIsRolloverTo(evenBundled, decision as RolloverDecision.RolloverRequired)
    }

    // ---- identity, not dates --------------------------------------------------

    @Test
    fun `corrected term dates do not trigger a rollover`() {
        // The case the whole detection is built to avoid re-prompting on: the student edited the
        // end date of the bundled semester. Same year, same type — still up to date.
        val asItRan = bundled.copy(endDate = bundled.endDate.plusDays(7))

        val decision = detectRollover(current = asItRan, bundled = bundled)

        assertEquals(RolloverDecision.None, decision)
    }

    @Test
    fun `an archived current semester is still the current one`() {
        // Archiving is a display state, not a new term. A row marked archived but identical in
        // year and type to the bundled semester is up to date — archiving does not roll anything.
        val archived = bundled.copy(archived = true)

        val decision = detectRollover(current = archived, bundled = bundled)

        assertEquals(RolloverDecision.None, decision)
    }

    @Test
    fun `an archived older semester still requires a rollover`() {
        // The archived flag never affects detection on its own: an older archived semester differs
        // in year from the bundled one and is therefore still a rollover.
        val archivedOld = Semester(
            year = 2025,
            type = SemesterType.ODD,
            startDate = LocalDate.of(2025, 7, 28),
            endDate = LocalDate.of(2025, 12, 15),
            archived = true,
        )

        val decision = detectRollover(current = archivedOld, bundled = bundled)

        assertTrue(decision is RolloverDecision.RolloverRequired)
    }

    // ---- the bundled semester the app actually ships with --------------------

    @Test
    fun `the bundled default calendar describes the odd semester of 2026`() {
        // The detection source is a compile-time constant; pin what it is so a change to the
        // bundled calendar is a visible, reviewed act rather than a silent drift.
        assertEquals(SemesterType.ODD, bundled.type)
        assertEquals(2026, bundled.year)
        assertEquals(LocalDate.of(2026, 7, 28), bundled.startDate)
        assertEquals(LocalDate.of(2026, 11, 20), bundled.endDate)
    }

    @Test
    fun `a null current never yields a rollover`() {
        // EstablishSilently is the safe branch; it must be impossible for a null current to reach
        // the destructive one, because there is nothing to export or clear.
        val decision = detectRollover(current = null, bundled = bundled)

        assertFalse(decision is RolloverDecision.RolloverRequired)
    }

    @Test
    fun `the required decision carries both semesters`() {
        // The gate needs the current semester to render its export and the bundled one to name
        // what is coming. Both must survive the decision.
        val current = Semester(
            year = 2025,
            type = SemesterType.ODD,
            startDate = LocalDate.of(2025, 7, 28),
            endDate = LocalDate.of(2025, 12, 15),
        )

        val decision = detectRollover(current = current, bundled = bundled)

        require(decision is RolloverDecision.RolloverRequired)
        assertEquals(current, decision.current)
        assertEquals(bundled, decision.bundled)
    }

    @Test
    fun `null current never carries a semester`() {
        assertNull(null.asDecisionCurrent(bundled))
    }

    // ---- helpers --------------------------------------------------------------

    private fun Semester.assertIsRolloverTo(
        bundled: Semester,
        decision: RolloverDecision.RolloverRequired,
    ) {
        assertEquals(this, decision.current)
        assertEquals(bundled, decision.bundled)
    }

    /** The current semester the decision carries, or null when the decision is not a rollover. */
    private fun Semester?.asDecisionCurrent(bundled: Semester): Semester? {
        val decision = detectRollover(this, bundled)
        return (decision as? RolloverDecision.RolloverRequired)?.current
    }
}
