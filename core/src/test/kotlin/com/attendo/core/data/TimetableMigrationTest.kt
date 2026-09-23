package com.attendo.core.data

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.Course
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Reconciling a seeded install against the 17 September 2026 timetable.
 *
 * The bundled grid changed under installs that had already seeded from the July edition, so
 * the swap is a migration rather than a replacement: a slot the page no longer prints is
 * closed the day before the revision and a slot it prints instead opens on it, through the
 * same `effectiveFrom`/`effectiveTo` model the rest of the app uses.
 *
 * The rename case is checked against the real bundled files, because that is the one where the
 * fixture has to be true rather than convenient: `SFL` was dropped from the printed grid, so an
 * install that seeded in July is holding a course no page mentions any more, and the sports hour
 * is the same hours a week under a new code. The slot arithmetic is checked with built proposals,
 * because the July edition is not in the repository to diff against — what is being pinned there
 * is the rule, not the data.
 *
 * Most of what follows is about what the plan *refuses* to do: no course is deleted, no
 * attendance is touched, nothing is created, and a finished term is left alone.
 */
class TimetableMigrationTest {

    private fun asset(name: String): String {
        val file = File("../app/src/main/assets/$name")
        assertTrue("missing bundled asset ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The July edition's term start, which is not the date this revision took effect. */
    private val termStart = LocalDate.of(2026, 7, 28)

    private val imported = TimetableCsv.parse(asset("timetable.csv"))
    private val subjects = Glossary.parse(asset("subjects.csv"))

    /** What the new page offers 2nd Yr ECE-A in batch A1 — the incoming half of every plan. */
    private val page =
        SectionSeeder.plan(imported.bookings, "2nd Yr ECE-A", termStart, "A1", subjects).proposals

    private fun course(id: Long, code: String, name: String = code, archived: Boolean = false) =
        Course(id = id, name = name, code = code, archived = archived)

    private fun pattern(
        courseId: Long,
        day: DayOfWeek,
        startHour: Int,
        units: Int = 1,
        kind: SessionKind = SessionKind.LECTURE,
        room: String? = "204",
        effectiveFrom: LocalDate = termStart,
        effectiveTo: LocalDate? = null,
        id: Long = 0L,
    ) = SessionPattern(
        id = id,
        courseId = courseId,
        dayOfWeek = day,
        startHour = startHour,
        units = units,
        kind = kind,
        room = room,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
    )

    /** One slot as the seeder would propose it — the course, and so the id, still to be decided. */
    private fun offered(
        day: DayOfWeek,
        startHour: Int,
        units: Int = 1,
        kind: SessionKind = SessionKind.LECTURE,
        room: String? = "204",
    ) = pattern(
        courseId = 0L,
        day = day,
        startHour = startHour,
        units = units,
        kind = kind,
        room = room,
    )

    /** A proposal for [code] as the new page makes it, for the slot arithmetic below. */
    private fun onOffer(code: String, name: String = code, vararg slots: SessionPattern) =
        SeedProposal(course = Course(name = name, code = code), patterns = slots.toList())

    private fun plan(
        courses: List<Course>,
        patterns: List<SessionPattern>,
        incoming: List<SeedProposal> = page,
    ) = TimetableMigration.plan(courses, patterns, incoming)

    // ---- the date the change takes effect ------------------------------------

    @Test
    fun `the revision date is not the term start`() {
        // The term began on 28 July under the July timetable; this edition changed the slots
        // from the 17th of September. Everything recorded before it stays as it was recorded,
        // so the two dates must not drift into one another.
        assertEquals(LocalDate.of(2026, 9, 17), TimetableMigration.EFFECTIVE_FROM)
        assertEquals(LocalDate.of(2026, 7, 28), AcademicCalendar.DEFAULT_2026_27.termStart)
        assertTrue(TimetableMigration.EFFECTIVE_FROM.isAfter(AcademicCalendar.DEFAULT_2026_27.termStart))
    }

    @Test
    fun `a slot the revision adds starts on the revision date`() {
        // A slot opened retroactively would claim sessions in weeks that had already been
        // recorded under the old arrangement.
        val incoming = listOf(onOffer("VLSI", slots = arrayOf(offered(DayOfWeek.TUESDAY, 10))))

        val change = plan(
            courses = listOf(course(1L, "VLSI")),
            patterns = listOf(pattern(courseId = 1L, day = DayOfWeek.THURSDAY, startHour = 14, units = 2, room = "312", id = 8L)),
            incoming = incoming,
        ).changes.single()

        assertEquals(
            "the slot the page prints, and only that one",
            listOf(DayOfWeek.TUESDAY to 10),
            change.added.map { it.dayOfWeek to it.startHour },
        )
        assertEquals(TimetableMigration.EFFECTIVE_FROM, change.added.single().effectiveFrom)
        assertEquals("the slot is stamped with the course it now belongs to", 1L, change.added.single().courseId)
        assertTrue("an opening slot is open-ended", change.added.single().isOpenEnded)
        assertEquals(listOf(8L), change.retired.map { it.id })
    }

    @Test
    fun `the old slot closes the day before the new one opens`() {
        // The two dates are adjacent and both inclusive: 16 September is the last day the class
        // was at the old hour, 17 September the first at the new one. A one-day gap would leave
        // a class the student attends with no slot to generate from.
        val incoming = listOf(
            onOffer("VLSI", slots = arrayOf(offered(DayOfWeek.TUESDAY, 10, room = "203"), offered(DayOfWeek.THURSDAY, 14, units = 2, room = "312"))),
        )
        val held = listOf(
            pattern(courseId = 1L, day = DayOfWeek.TUESDAY, startHour = 10, room = "203", id = 7L),
            pattern(courseId = 1L, day = DayOfWeek.TUESDAY, startHour = 14, units = 2, room = "312", id = 8L),
        )

        val courses = listOf(course(1L, "VLSI"))
        val change = plan(courses, held, incoming).changes.single()
        assertEquals(
            "only the hour that moved",
            listOf(DayOfWeek.TUESDAY to 14),
            change.retired.map { it.dayOfWeek to it.startHour },
        )
        assertEquals(
            "only the hour it moved to",
            listOf(DayOfWeek.THURSDAY to 14),
            change.added.map { it.dayOfWeek to it.startHour },
        )

        val applied = apply(courses, held, plan(courses, held, incoming))

        assertEquals(
            "the old hour's last day",
            LocalDate.of(2026, 9, 16),
            applied.patterns.single { it.id == 8L }.effectiveTo,
        )
        assertEquals(
            "and the replacement's first day is the one after it",
            LocalDate.of(2026, 9, 17),
            applied.patterns.single { it.id !in setOf(7L, 8L) }.effectiveFrom,
        )
        assertEquals("the hour that did not move is left alone", 3, applied.patterns.size)
    }

    // ---- the rename ---------------------------------------------------------

    @Test
    fun `the sports hour is renamed, not retired and re-offered`() {
        // SFL and FIT INDIA are the same two hours a week. A code-to-code join cannot see that,
        // and would close the course the student has been marking all term and offer the sports
        // hour again as a brand new elective — leaving them with two courses, one of them frozen
        // and holding their history. One change, no slots moved, nothing to add.
        val change = plan(
            courses = listOf(course(1L, "SFL", "Sports for Life / Fit India")),
            patterns = listOf(pattern(courseId = 1L, day = DayOfWeek.WEDNESDAY, startHour = 15, units = 2, room = null)),
        ).changes.single()

        assertTrue(change.renamed)
        assertEquals("SFL", change.fromCode)
        assertEquals("FIT INDIA", change.toCode)
        assertEquals("Fit India", change.toName)
        assertEquals(emptyList<SessionPattern>(), change.retired)
        assertEquals(emptyList<SessionPattern>(), change.added)
        assertFalse(change.discontinued)
    }

    @Test
    fun `a renamed subject renames its lab and its tutorial too`() {
        // The seeder splits one printed subject into up to three courses, so a subject the
        // faculty renamed renames all three — and only those. A code that merely begins with the
        // old one is a different subject.
        assertEquals("FIT INDIA", TimetableMigration.renamed("SFL"))
        assertEquals("FIT INDIA Lab", TimetableMigration.renamed("SFL Lab"))
        assertEquals("FIT INDIA Tutorial", TimetableMigration.renamed("SFL Tutorial"))
        assertEquals("SFLX", TimetableMigration.renamed("SFLX"))
        assertEquals("VLSI", TimetableMigration.renamed("VLSI"))
    }

    // ---- slots that moved ---------------------------------------------------

    @Test
    fun `a lab that changed room is reopened rather than quietly edited`() {
        // A student standing outside the wrong door is the failure this exists to prevent, so
        // the room counts as part of the class: 314 to 216 is a new slot, not the same one — even
        // though the day, the hour and the length are all unchanged.
        val incoming = listOf(
            onOffer("DE-1 Lab", "Digital Electronics-I Lab", offered(DayOfWeek.MONDAY, 14, units = 2, kind = SessionKind.PRACTICAL, room = "216")),
        )
        val old = pattern(
            courseId = 1L,
            day = DayOfWeek.MONDAY,
            startHour = 14,
            units = 2,
            kind = SessionKind.PRACTICAL,
            room = "314",
            id = 3L,
        )

        val change = plan(listOf(course(1L, "DE-1 Lab", "Digital Electronics-I Lab")), listOf(old), incoming).changes.single()

        assertEquals(listOf(3L), change.retired.map { it.id })
        assertEquals(listOf("216"), change.added.map { it.room })
        assertEquals(TimetableMigration.EFFECTIVE_FROM, change.added.single().effectiveFrom)
        assertFalse(change.discontinued)
    }

    // ---- what it refuses to do ---------------------------------------------

    @Test
    fun `a subject the new page stops teaching keeps its course and its history`() {
        // The timetable stopped listing it; the student did not drop it. The slots stop from the
        // revision date, and the course stays — with its sessions, its notes and its figures —
        // for the student to archive themselves.
        val live = pattern(courseId = 4L, day = DayOfWeek.MONDAY, startHour = 9, units = 2, room = "313", id = 11L)
        val history = pattern(
            courseId = 4L,
            day = DayOfWeek.MONDAY,
            startHour = 9,
            units = 2,
            room = "313",
            effectiveTo = LocalDate.of(2026, 8, 1),
            id = 12L,
        )
        val dbms = course(4L, "DBMS", "Database and Management Systems")

        val plan = plan(listOf(dbms), listOf(live, history))

        val change = plan.changes.single()
        assertTrue(change.discontinued)
        assertEquals("the code and name are left as the student has them", "DBMS", change.toCode)
        assertEquals("Database and Management Systems", change.toName)
        assertEquals("only the slot still open is closed", listOf(11L), change.retired.map { it.id })
        assertEquals("nothing is added for a subject the page no longer teaches", emptyList<SessionPattern>(), change.added)
        assertEquals(1, plan.discontinuedCount)
        assertFalse("and the course is not in the list of things to offer", "DBMS" in plan.newlyAvailable)
    }

    @Test
    fun `a subject that never had a slot here is not a course to close`() {
        // A code the student shares with another section, or an earlier term's paper, is not
        // dragged into this term's figures by a timetable revision.
        val closed = pattern(
            courseId = 4L,
            day = DayOfWeek.MONDAY,
            startHour = 9,
            units = 2,
            effectiveTo = LocalDate.of(2026, 8, 1),
            id = 12L,
        )

        val result = plan(listOf(course(4L, "DBMS")), listOf(closed))

        assertEquals(emptyList<CourseChange>(), result.changes)
        assertTrue(result.isEmpty)
        assertEquals("nothing changed", result.summary)
    }

    @Test
    fun `an archived course is left exactly as it was`() {
        // A finished term is closed: its slots are part of what its figures were measured
        // against, and a timetable revision is about the term in progress.
        val live = pattern(courseId = 5L, day = DayOfWeek.MONDAY, startHour = 9, units = 2, room = "313", id = 21L)

        val result = plan(listOf(course(5L, "DBMS", archived = true)), listOf(live))

        assertEquals(emptyList<CourseChange>(), result.changes)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `the new subjects of this edition are offered, never created`() {
        // An elective is taught to a section; being in a section is not the same as taking the
        // elective. Reaching the course list is still a tick on the seed screen, so the plan
        // reports what the page teaches and leaves it at that.
        val result = plan(
            courses = listOf(course(1L, "SFL", "Sports for Life / Fit India")),
            patterns = listOf(pattern(courseId = 1L, day = DayOfWeek.WEDNESDAY, startHour = 15, units = 2, room = null)),
        )

        assertTrue("VLSI is taught to this section and the student has no course for it", "VLSI" in result.newlyAvailable)
        assertTrue("FIT INDIA is covered by the rename, so it is not also on offer", "FIT INDIA" !in result.newlyAvailable)
        assertTrue(
            "nothing is created for what is merely on offer",
            result.changes.none { it.toCode in result.newlyAvailable || it.fromCode in result.newlyAvailable },
        )
        assertEquals("the only change is the rename", listOf(1L), result.changes.map { it.courseId })
    }

    @Test
    fun `a course the revision leaves alone produces no change at all`() {
        // Most courses are most of the time untouched. A change record for one of them would be
        // a write to the database for nothing.
        val unchanged = pattern(courseId = 1L, day = DayOfWeek.TUESDAY, startHour = 10, room = "203", id = 7L)
        val incoming = listOf(onOffer("VLSI", slots = arrayOf(offered(DayOfWeek.TUESDAY, 10, room = "203"))))

        val result = plan(listOf(course(1L, "VLSI")), listOf(unchanged), incoming)

        assertEquals(emptyList<CourseChange>(), result.changes)
        assertTrue(result.isEmpty)
        assertEquals("nothing changed", result.summary)
    }

    // ---- and the second run -------------------------------------------------

    /**
     * What the repository does with a plan, in miniature.
     *
     * Renamed courses take the new code and name, retired slots are closed the day before the
     * revision — clamped so a slot added after it closes on the day it opened rather than before
     * it — and added slots are inserted open-ended under new ids. Nothing is deleted.
     */
    private fun apply(
        courses: List<Course>,
        patterns: List<SessionPattern>,
        plan: MigrationPlan,
    ): Applied {
        var nextCourses = courses
        var nextPatterns = patterns
        var nextId = 900L
        plan.changes.forEach { change ->
            if (change.renamed) {
                nextCourses = nextCourses.map {
                    if (it.id == change.courseId) it.copy(code = change.toCode, name = change.toName) else it
                }
            }
            change.retired.forEach { retired ->
                nextPatterns = nextPatterns.map { held ->
                    if (held.id != retired.id) held
                    else held.retiredAfter(maxOf(plan.effectiveFrom.minusDays(1), held.effectiveFrom))
                }
            }
            nextPatterns = nextPatterns + change.added.map { it.copy(id = nextId++) }
        }
        return Applied(nextCourses, nextPatterns)
    }

    private data class Applied(val courses: List<Course>, val patterns: List<SessionPattern>)

    @Test
    fun `running the migration twice leaves the second run with nothing to do`() {
        // Which is what lets the runner gate it on a stored revision without having to be right
        // about exactly when the revision changed.
        val courses = listOf(
            course(1L, "SFL", "Sports for Life / Fit India"),
            course(2L, "VLSI Lab", "VLSI Lab"),
            course(3L, "DBMS", "Database and Management Systems"),
        )
        val patterns = listOf(
            pattern(courseId = 1L, day = DayOfWeek.WEDNESDAY, startHour = 15, units = 2, room = null, id = 1L),
            pattern(courseId = 2L, day = DayOfWeek.TUESDAY, startHour = 14, units = 2, kind = SessionKind.PRACTICAL, room = "312", id = 2L),
            pattern(courseId = 3L, day = DayOfWeek.MONDAY, startHour = 9, units = 2, room = "313", id = 3L),
        )

        val first = plan(courses, patterns)
        assertTrue("the fixture has something to do", !first.isEmpty)
        assertEquals(1, first.renamedCount)
        assertEquals(1, first.discontinuedCount)

        val applied = apply(courses, patterns, first)
        val second = TimetableMigration.plan(applied.courses, applied.patterns, page)

        assertEquals(emptyList<CourseChange>(), second.changes)
        assertTrue(second.isEmpty)
        assertEquals("the second run offers the same subjects", first.newlyAvailable, second.newlyAvailable)
        assertEquals("nothing was deleted on the way through", courses.size, applied.courses.size)
        assertEquals(
            "and no slot was deleted either — the ones that moved were joined by replacements",
            patterns.size + first.addedCount,
            applied.patterns.size,
        )
        assertEquals(
            "the course that stopped being taught keeps the slot it was taught on, closed",
            LocalDate.of(2026, 9, 16),
            applied.patterns.single { it.id == 3L }.effectiveTo,
        )
    }
}
