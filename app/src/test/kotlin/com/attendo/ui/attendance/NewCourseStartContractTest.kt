package com.attendo.ui.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a *new* pattern gets its `effectiveFrom`, held as source.
 *
 * This is the release-blocking bug this file exists for. The faculty reissued the timetable on
 * 17 September 2026, six weeks into a term that began on 28 July, and two call sites dated a
 * pattern taken from the September file from `calendar.termStart`. `SessionGenerator` has always
 * honoured `effectiveFrom`, so nothing downstream could catch it: the generator was handed a July
 * date and dutifully produced six weeks of classes for a paper that was not taught, all of them
 * arriving as review work and becoming absences the moment they were answered.
 *
 * ### The first fix was wrong, and this file says why
 *
 * Both sites were moved to a single `TimetableMigration.patternStart(calendar)` returning
 * `maxOf(termStart, EFFECTIVE_FROM)` — one date for every course, always September. That is not
 * the rule either. A course that has been running since July keeps July; only a subject the
 * September edition *introduces* starts in September; a course whose slots were revised keeps its
 * history and gains a replacement on the 17th. One date collapses the first of those into the
 * third, and re-seeding in September would have thrown away a term of a student's figures.
 *
 * So the question is not "what date?" but "what date, for this code?" — and the answer belongs to
 * the edition, in [com.attendo.core.data.TimetableMigration], not to the current date and not to
 * either screen. These tests hold the three lines that ask it.
 *
 * A behavioural test in `:core` can pin the rule; it cannot pin *who calls it*. The lines below
 * are the actual defect, so they are what is held.
 */
class NewCourseStartContractTest {

    private fun source(relative: String): String {
        val file = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
        return file.readText()
    }

    private fun allAppSources(): List<File> {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "src/main/kotlin") }
            .first(File::exists)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val seed: String get() = source("src/main/kotlin/com/attendo/ui/attendance/SeedViewModel.kt")
    private val editor: String get() = source("src/main/kotlin/com/attendo/ui/attendance/CourseEditorViewModel.kt")
    private val migration: String get() =
        source("../core/src/main/kotlin/com/attendo/core/data/TimetableMigration.kt")

    @Test
    fun `the seed screen dates what it offers by the edition, and keeps the term start`() {
        // This screen is how a subject the revision introduces reaches the course list at all —
        // the migration deliberately never creates a course. It is also how an existing course is
        // re-added, and it cannot tell the two apart, so it must not answer for both with one
        // date: the term start is what nearly every course wants, and the edition is the
        // exception, asked for by code.
        assertTrue(seed.contains("termStart = calendar.termStart"))
        assertTrue(
            "the introduced subjects are the one thing that must be dated differently",
            seed.contains("startFor = { code -> TimetableMigration.effectiveFromFor(code, calendar.termStart) }"),
        )
    }

    @Test
    fun `the course editor asks the same question about the course's code`() {
        // A student adding a course by hand in September is adding a course that was not in the
        // app; that says nothing about whether it was being *taught*. So the current date is not
        // consulted and neither is the term start alone — the code is, and the edition answers.
        assertTrue(editor.contains("TimetableMigration.effectiveFromFor("))
        assertFalse(
            "The raw term start is what caused the bug.",
            editor.contains("effectiveFrom = settings.current.calendar.termStart"),
        )
        assertTrue(
            "and the rule is handed the code it is being asked about",
            editor.contains("code = courseCode,"),
        )
    }

    @Test
    fun `no pattern anywhere is dated from the term start without asking the edition`() {
        // The general form of the bug, so that a third call site cannot reintroduce it. Every
        // legitimate way a pattern gets its start date is still allowed: carried over from the
        // pattern it replaces, taken from an entity being mapped, floored by the repository, or
        // the migration's own edition date. What is not allowed is a bare term start.
        val datedFromTermStart = Regex("""effectiveFrom\s*=[^\n]*\btermStart\b""")
        val asksTheEdition = Regex("""effectiveFromFor""")

        val offenders = allAppSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> datedFromTermStart.containsMatchIn(line) }
                .filter { (_, line) -> !asksTheEdition.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }

        assertEquals("A new pattern must not claim weeks that ran before it existed.", emptyList<String>(), offenders)
    }

    @Test
    fun `the one global date rule is gone`() {
        // `patternStart(calendar)` answered for every course at once, which is the collapse this
        // fix undoes. Its absence is asserted rather than assumed: a helper that is still there
        // is a helper the next call site will reach for.
        assertFalse("One date for every course is what went wrong.", migration.contains("patternStart"))
        assertTrue(
            "the answer has to be asked for by code",
            migration.contains("fun effectiveFromFor(code: String, termStart: LocalDate): LocalDate"),
        )
    }

    @Test
    fun `the term start itself is untouched at 28 July`() {
        // The brief's other fixed point. The fix is the date given to a *new* pattern; the term
        // still begins where it began, and nothing recorded before the revision moves.
        val calendar = source("../core/src/main/kotlin/com/attendo/core/model/AcademicCalendar.kt")
        assertTrue(calendar.contains("termStart = LocalDate.of(2026, 7, 28)"))
    }
}
