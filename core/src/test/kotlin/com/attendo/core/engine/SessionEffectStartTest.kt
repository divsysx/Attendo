package com.attendo.core.engine

import com.attendo.core.data.TimetableMigration
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * What a pattern's `effectiveFrom` does to generation, at the revision boundary.
 *
 * The faculty reissued the timetable on 17 September 2026, six weeks into a term that began on
 * 28 July. [SessionGenerator] has always honoured `effectiveFrom` — `isEffectiveOn` is consulted
 * per date before a draft is produced — so the engine was never the problem. What was wrong was
 * the date two call sites handed it: both dated a *new* pattern from `calendar.termStart`, which
 * has a subject the September edition introduces claiming six weeks of classes it was never
 * taught. Those rows arrive as review work nobody can answer for, and become absences the moment
 * somebody answers them.
 *
 * ### Why the dates here are written out
 *
 * Every date in this file is a literal. An earlier version of it derived the new course's start
 * from the helper it was testing, which is how a regression test quietly stops being one: a rule
 * that returned the wrong answer for *everything* would have moved the fixture and the
 * expectation together and passed every assertion. The fixtures are what the correct dates are
 * — 28 July for a course that ran, 17 September for one that did not exist until then — and
 * [the edition dates an introduced subject from the 17th and nothing else from anything] is the
 * separate claim that the rule produces them.
 *
 * The three cases the brief names are covered from both sides, because the whole point is that
 * they differ:
 *
 *  * **A** a course that was already running keeps every week it ran ([the weeks before…]);
 *  * **B** a slot the revision replaced stops the day before its replacement starts ([a replaced
 *    slot…]);
 *  * **C** a course the revision introduces has nothing before the revision ([the six weeks…]).
 *
 * A test that only asserted C would pass just as happily against an implementation that dropped
 * pre-revision history for everything, which is the destructive fix the brief rules out.
 */
class SessionEffectStartTest {

    /** The July edition's term start. Written out, not read from the rule under test. */
    private val termStart = LocalDate.of(2026, 7, 28)

    /** The day the faculty reissued the grid. Written out for the same reason. */
    private val edition = LocalDate.of(2026, 9, 17)

    private val termEnd = LocalDate.of(2026, 11, 20)

    private val calendar = AcademicCalendar(termStart, termEnd)

    private val newCourse = Course(id = 7L, name = "Machine Learning and Deep Learning", code = "MLDL")
    private val oldCourse = Course(id = 1L, name = "Digital Empowerment", code = "DE-1")

    private fun pattern(
        courseId: Long,
        id: Long = 0L,
        day: DayOfWeek = DayOfWeek.MONDAY,
        startHour: Int = 9,
        units: Int = 1,
        effectiveFrom: LocalDate,
        effectiveTo: LocalDate? = null,
    ) = SessionPattern(
        id = id,
        courseId = courseId,
        dayOfWeek = day,
        startHour = startHour,
        units = units,
        kind = SessionKind.LECTURE,
        room = "204",
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
    )

    private fun generate(
        patterns: List<SessionPattern>,
        existing: List<ClassSession> = emptyList(),
    ): List<SessionGenerator.Draft> =
        SessionGenerator.draftsFor(patterns, existing, termStart, termEnd, calendar)

    /** Every [day] from [from] to the end of term, inclusive — what the grid should produce. */
    private fun teachingDays(day: DayOfWeek, from: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(termEnd) }
            .filter { it.dayOfWeek == day && calendar.isTeachingDay(it) }
            .toList()

    /** The first Monday on or after the revision, which is the week the new grid takes over. */
    private val firstMondayOfTheNewGrid: LocalDate =
        generateSequence(edition) { it.plusDays(1) }
            .first { it.dayOfWeek == DayOfWeek.MONDAY }

    // ---- 0. the rule the two call sites ask ----------------------------------

    @Test
    fun `the edition dates an introduced subject from the 17th and nothing else from anything`() {
        // The independent half of the fixtures above. If `effectiveFromFor` ever goes back to a
        // rule that moves every course — the `maxOf(termStart, EFFECTIVE_FROM)` this replaced —
        // the first line still passes and the second fails, which is exactly the regression.
        assertEquals(LocalDate.of(2026, 9, 17), edition)
        assertEquals(LocalDate.of(2026, 9, 17), TimetableMigration.effectiveFromFor("MLDL", termStart))
        assertEquals(LocalDate.of(2026, 7, 28), TimetableMigration.effectiveFromFor("DE-1", termStart))
        assertEquals(LocalDate.of(2026, 7, 28), TimetableMigration.effectiveFromFor("DBMS", termStart))
        assertEquals(LocalDate.of(2026, 7, 28), TimetableMigration.effectiveFromFor("FIT INDIA", termStart))
    }

    @Test
    fun `a term that begins after the edition is never backdated`() {
        val spring = LocalDate.of(2027, 1, 11)

        assertEquals(
            "the later of the two wins, so an introduced subject cannot pull a future term back",
            spring,
            TimetableMigration.effectiveFromFor("MLDL", spring),
        )
        assertEquals(
            "and a course the July grid taught is dated from its term, whenever that term is",
            spring,
            TimetableMigration.effectiveFromFor("DBMS", spring),
        )
    }

    @Test
    fun `there is no global start date any more`() {
        // The fix this file was rewritten for. A single `patternStart(calendar)` that answered
        // for every course collapsed case A into case C, so its absence is asserted directly
        // rather than left to a reviewer to notice.
        val source = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { java.io.File(it, "src/main/kotlin/com/attendo/core/data/TimetableMigration.kt") }
            .first(java.io.File::exists)
            .readText()

        assertFalse(
            "One date for every course is the bug; the answer has to be asked for by code.",
            source.contains("patternStart"),
        )
    }

    // ---- 1. a course the September edition introduces ------------------------

    @Test
    fun `the six weeks before the revision are not this course's weeks`() {
        val mldl = pattern(newCourse.id, id = 21L, effectiveFrom = edition)

        val dates = generate(listOf(mldl)).map { it.date }

        assertTrue("the fixture must produce something, or this proves nothing", dates.isNotEmpty())
        assertTrue(
            "no class may be proposed between the term start and the day before the revision",
            dates.none { it in termStart..edition.minusDays(1) },
        )
        assertTrue(
            "and nothing at all before the revision",
            dates.none { it.isBefore(edition) },
        )
    }

    @Test
    fun `and the weeks from the revision on are exactly what the timetable says`() {
        val mldl = pattern(newCourse.id, id = 21L, effectiveFrom = edition)

        val dates = generate(listOf(mldl)).map { it.date }

        assertEquals(teachingDays(DayOfWeek.MONDAY, firstMondayOfTheNewGrid), dates)
    }

    @Test
    fun `a course dated from the term start is the bug, and it is seven weeks larger`() {
        // The counterfactual, stated as a test so that the fix cannot be quietly reverted: the
        // only difference between the two lines below is the date. If this ever stops being
        // true, the two call sites have stopped asking the edition.
        val dated = pattern(newCourse.id, id = 21L, effectiveFrom = edition)
        val asItWas = dated.copy(effectiveFrom = termStart)

        val correct = generate(listOf(dated))
        val inflated = generate(listOf(asItWas))

        assertTrue(
            "dating a new course from the term start must invent weeks — that is why it was wrong",
            inflated.size > correct.size,
        )
        assertEquals(
            "the difference is precisely the weeks before the revision",
            inflated.size - correct.size,
            inflated.count { it.date.isBefore(edition) },
        )
        assertEquals(0, correct.count { it.date.isBefore(edition) })
    }

    // ---- 2. a course that was already running --------------------------------

    @Test
    fun `the weeks before the revision stay the existing course's weeks`() {
        val de = pattern(oldCourse.id, id = 11L, effectiveFrom = termStart)

        val dates = generate(listOf(de)).map { it.date }

        // The contrast with the new course, and the half a destructive fix would get wrong.
        assertTrue(
            "a course that ran from the term start keeps every week it ran",
            dates.count { it.isBefore(edition) } == teachingDays(DayOfWeek.MONDAY, termStart)
                .count { it.isBefore(edition) },
        )
        assertEquals(teachingDays(DayOfWeek.MONDAY, termStart), dates)
    }

    @Test
    fun `a day already recorded is never proposed again`() {
        val de = pattern(oldCourse.id, id = 11L, effectiveFrom = termStart)
        val august = LocalDate.of(2026, 8, 3)
        val recorded = ClassSession(
            id = 1L,
            courseId = oldCourse.id,
            patternId = 11L,
            date = august,
            startHour = 9,
            unitsPlanned = 1,
            unitsMask = UnitMask.allPresent(1),
            status = SessionStatus.HELD,
            kind = SessionKind.LECTURE,
            room = "204",
        )

        val drafts = generate(listOf(de), listOf(recorded))

        assertTrue("history is not re-proposed", drafts.none { it.date == august })
        assertEquals(recorded, listOf(recorded).single())
        assertTrue("and the rest of the term is still generated", drafts.isNotEmpty())
    }

    // ---- 3. a slot the revision replaced -------------------------------------

    @Test
    fun `a replaced slot stops the day before its replacement starts`() {
        val before = pattern(
            oldCourse.id,
            id = 11L,
            startHour = 11,
            effectiveFrom = termStart,
            effectiveTo = edition.minusDays(1),
        )
        val after = pattern(oldCourse.id, id = 12L, startHour = 12, effectiveFrom = edition)

        val byPattern = generate(listOf(before, after)).groupBy { it.patternId }
        val old = byPattern.getValue(11L)
        val new = byPattern.getValue(12L)

        assertTrue("the old slot must produce something", old.isNotEmpty())
        assertTrue("the new slot must produce something", new.isNotEmpty())
        assertTrue(
            "the old slot generates only through the day before the changeover",
            old.all { !it.date.isAfter(edition.minusDays(1)) },
        )
        assertTrue(
            "the replacement generates only from the changeover",
            new.all { !it.date.isBefore(edition) },
        )
        assertEquals(
            "and the two do not overlap on a single day",
            0,
            old.map { it.date to it.startHour }.intersect(new.map { it.date to it.startHour }.toSet()).size,
        )
        assertEquals(firstMondayOfTheNewGrid, new.first().date)
    }

    // ---- 4. the denominator ------------------------------------------------

    @Test
    fun `the weeks a new course did not exist never reach the denominator`() {
        val mldl = pattern(newCourse.id, id = 21L, effectiveFrom = edition)
        val expected = teachingDays(DayOfWeek.MONDAY, firstMondayOfTheNewGrid).size

        // Mark every generated class absent. This is the harshest case the brief names: once a
        // row is answered it stops being invisible and starts counting, so if the pre-revision
        // weeks were generated at all they would land in the denominator as failures.
        val sessions = generate(listOf(mldl)).map {
            it.toSession().copy(status = SessionStatus.HELD, unitsMask = UnitMask.NONE)
        }

        val stats = AttendanceEngine.courseStats(newCourse, sessions, today = termEnd)

        assertEquals("nothing was attended, so the numerator is zero", 0, stats.tally.unitsAttended)
        assertEquals("one hour a week for the weeks it ran", expected, stats.tally.unitsHeld)
        assertEquals("every row was answered", 0, stats.sessionsAwaitingReview)
    }

    @Test
    fun `and the count would have been seven weeks larger had it been dated from July`() {
        val mldl = pattern(newCourse.id, id = 21L, effectiveFrom = edition)
        val asItWas = mldl.copy(effectiveFrom = termStart)

        fun denominatorFor(p: SessionPattern): Int {
            val sessions = generate(listOf(p)).map {
                it.toSession().copy(status = SessionStatus.HELD, unitsMask = UnitMask.NONE)
            }
            return AttendanceEngine.courseStats(newCourse, sessions, today = termEnd).tally.unitsHeld
        }

        val correct = denominatorFor(mldl)
        val inflated = denominatorFor(asItWas)

        assertTrue(
            "the denominator is the figure a student is judged on, so the invented weeks matter",
            inflated > correct,
        )
        assertEquals(
            "exactly the weeks between the term start and the revision",
            teachingDays(DayOfWeek.MONDAY, termStart).count { it.isBefore(edition) },
            inflated - correct,
        )
    }

    // ---- 5. idempotence ------------------------------------------------------

    @Test
    fun `generating twice over the same timetable proposes nothing the second time`() {
        val mldl = pattern(newCourse.id, id = 21L, effectiveFrom = edition)

        val first = generate(listOf(mldl))
        assertTrue(first.isNotEmpty())

        val stored = first.mapIndexed { index, draft -> draft.toSession().copy(id = index + 1L) }

        assertTrue("the second pass has nothing left to do", generate(listOf(mldl), stored).isEmpty())
    }

    @Test
    fun `the revision date is the edition's, and the term start is untouched`() {
        assertEquals(LocalDate.of(2026, 9, 17), TimetableMigration.EFFECTIVE_FROM)
        assertEquals(LocalDate.of(2026, 7, 28), AcademicCalendar.DEFAULT_2026_27.termStart)
        assertTrue(TimetableMigration.EFFECTIVE_FROM.isAfter(termStart))
    }
}
