package com.attendo.core.data

import com.attendo.core.model.Course
import com.attendo.core.model.SessionPattern
import java.time.LocalDate

/**
 * What one tracked course has to become for the timetable now in force.
 *
 * [retired] are live slots the new page no longer prints; [added] are slots it prints that the
 * course does not have. Both are empty for a course the revision left alone, which is most of
 * them — [isEmpty] is what keeps the runner from touching a course for nothing.
 */
data class CourseChange(
    val courseId: Long,
    /** The code as it stands. */
    val fromCode: String,
    /** The code after the change, which differs only where the timetable renamed a subject. */
    val toCode: String,
    /** The name to record under [toCode]. */
    val toName: String,
    val retired: List<SessionPattern> = emptyList(),
    val added: List<SessionPattern> = emptyList(),
    /**
     * True when the new page does not teach this subject to the student's section at all.
     *
     * The course is kept — this is a timetable that stopped listing a paper, not a student who
     * dropped it, and the attendance already recorded against it is a record of classes that
     * were held. Only the slots stop, from [MigrationPlan.effectiveFrom].
     */
    val discontinued: Boolean = false,
) {
    val renamed: Boolean get() = toCode != fromCode

    val isEmpty: Boolean get() = retired.isEmpty() && added.isEmpty() && !renamed
}

/**
 * Everything one edition of the timetable changes, in the terms the repository already writes.
 *
 * There is deliberately nothing here about creating courses. A revision says what the faculty
 * teaches; which of it a student is enrolled in is theirs to say, on the seed screen, exactly as
 * on a fresh install. [newlyAvailable] is that half of the answer — reported so it can be
 * offered, never created.
 */
data class MigrationPlan(
    val effectiveFrom: LocalDate,
    val changes: List<CourseChange> = emptyList(),
    /** Subject codes the new page teaches to this section that no tracked course covers. */
    val newlyAvailable: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = changes.none { !it.isEmpty }

    val retiredCount: Int get() = changes.sumOf { it.retired.size }

    val addedCount: Int get() = changes.sumOf { it.added.size }

    val renamedCount: Int get() = changes.count { it.renamed }

    val discontinuedCount: Int get() = changes.count { it.discontinued }

    /** "3 slots replaced, 1 added, 1 subject renamed" — for a log line or a screen. */
    val summary: String
        get() = buildList {
            if (retiredCount > 0) add("$retiredCount slot${plural(retiredCount)} replaced")
            if (addedCount > 0) add("$addedCount added")
            if (renamedCount > 0) add("$renamedCount subject${plural(renamedCount)} renamed")
            if (discontinuedCount > 0) add("$discontinuedCount no longer taught")
        }.joinToString(", ").ifEmpty { "nothing changed" }

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}

/**
 * Reconciles the courses a student tracks against a new edition of the printed timetable.
 *
 * The faculty reissued the timetable on 17 September 2026. An install that had already seeded
 * from the July edition is holding slots that no longer describe the term — a lab that moved
 * rooms, an hour that shifted, a paper that stopped being taught to that section — and swapping
 * the bundled CSV underneath it would leave every one of them wrong, silently, for the rest of
 * the term. So a revision is a *migration*: the old slot is closed the day before [EFFECTIVE_FROM]
 * and the new one opens on it, through the same `effectiveFrom`/`effectiveTo` model the rest of
 * the app uses.
 *
 * ### What it will not do
 *
 * It does not delete anything. A retired slot keeps every session it produced; sessions already
 * marked or cancelled survive untouched, and only the unreviewed ones a withdrawn slot left
 * behind are dropped, because they describe a class that is no longer on the timetable.
 *
 * It does not delete a course. A subject the new page stops teaching is retired slot by slot and
 * left in place, with its history, for the student to archive themselves.
 *
 * It does not create a course. New subjects — this edition adds several — are [newlyAvailable],
 * and reaching the student's course list is still a tick on the seed screen. An elective is
 * taught to a section; being in a section is not the same as taking the elective, and the app
 * has never been able to tell the two apart, so it does not guess now. What this object does
 * supply is [INTRODUCED], the fact the seed screen needs when the tick does come: a subject the
 * edition introduces has no July to be dated from.
 *
 * It does not touch an archived course. A finished term is closed: its slots are part of what its
 * figures were measured against.
 *
 * ### Running it twice
 *
 * [plan] is a pure function of what the student has and what the timetable says, and the change
 * it describes is the difference between them. Applying it leaves nothing to do, so a second run
 * is empty — which is what lets the caller gate it on a stored revision without having to be
 * right about exactly when the revision changed.
 */
object TimetableMigration {

    /**
     * The date the 17 September 2026 timetable takes effect.
     *
     * Not the term start, and not to be confused with it: the term began on 28 July under the
     * July timetable, and this edition changed the slots from the 17th. Everything recorded
     * before it stays as it was recorded.
     */
    val EFFECTIVE_FROM: LocalDate = LocalDate.of(2026, 9, 17)

    /**
     * Subjects this edition renamed rather than replaced, old code to new.
     *
     * A rename is the one change a code-to-code match cannot see: the student's `SFL` and the
     * page's `FIT INDIA` are the same sports hour, and joining on the code alone would retire
     * the first as withdrawn and offer the second as a brand new elective — leaving a student
     * who had been marking it all term with two courses, one of them frozen.
     */
    val RENAMED: Map<String, String> = mapOf("SFL" to "FIT INDIA")

    /**
     * What [courses] have to become for [incoming] — the proposals the new page makes for the
     * student's own section and batch.
     *
     * [patterns] is every pattern the student has, live or retired; a pattern already closed is
     * history and is not reconsidered.
     */
    fun plan(
        courses: List<Course>,
        patterns: List<SessionPattern>,
        incoming: List<SeedProposal>,
        effectiveFrom: LocalDate = EFFECTIVE_FROM,
    ): MigrationPlan {
        // Only courses with a slot still open. That is exactly the current term's courses —
        // an earlier term's were deleted with its patterns at rollover, and an archived one is
        // a closed record — so a subject last term's student happens to share a code with is
        // not dragged into this term's figures by a timetable revision.
        val active = courses
            .filterNot { it.archived }
            .filter { course -> patterns.any { it.courseId == course.id && it.isOpenEnded } }
        val byCode = incoming.associateBy { it.course.code }

        val changes = active.mapNotNull { course ->
            val code = renamed(course.code)
            val proposal = byCode[code]
            val live = patterns.filter { it.courseId == course.id && it.isOpenEnded }

            if (proposal == null) {
                // The page no longer teaches this subject to this section. If it never had a
                // slot there is nothing to close and the course is simply left alone.
                if (live.isEmpty()) return@mapNotNull null
                CourseChange(
                    courseId = course.id,
                    fromCode = course.code,
                    toCode = code,
                    toName = course.name,
                    retired = live,
                    discontinued = true,
                )
            } else {
                val wanted = proposal.patterns.map { it.copy(courseId = course.id) }
                val added = wanted.filter { want -> live.none { it.sameSlotAs(want) } }
                val retired = live.filter { have -> wanted.none { have.sameSlotAs(it) } }
                val name = proposal.course.name
                val change = CourseChange(
                    courseId = course.id,
                    fromCode = course.code,
                    toCode = code,
                    toName = name,
                    retired = retired,
                    added = added.map { it.copy(effectiveFrom = effectiveFrom) },
                )
                change.takeIf { !it.isEmpty }
            }
        }

        val tracked = active.mapTo(mutableSetOf()) { renamed(it.code) }
        val newlyAvailable = incoming
            .map { it.course.code }
            .filterNot { it in tracked }
            .distinct()
            .sorted()

        return MigrationPlan(
            effectiveFrom = effectiveFrom,
            changes = changes.sortedBy { it.toCode },
            newlyAvailable = newlyAvailable,
        )
    }

    /**
     * Subjects this edition introduces — codes the July edition never printed at all.
     *
     * This is the third case, and the one a date rule cannot see. An install that seeded in July
     * holds slots for everything the July grid taught; a slot the September grid prints for a
     * code that was not on the July grid is not a revision of anything, it is a new paper. There
     * is no `effectiveFrom` to carry over and no pattern to retire, because there was no course.
     *
     * So these are the only codes whose first pattern is dated from [EFFECTIVE_FROM] rather than
     * from the term start. Everything else — a course running since July, a course whose slots
     * changed, a course a student adds by hand in September — keeps the term start, which is what
     * makes this a fact about the edition rather than a rule about the date.
     *
     * ### What is deliberately not here
     *
     * `FIT INDIA` is the one code the September grid prints that is *not* new: it is the July
     * grid's `SFL`, renamed, and it is in [RENAMED] instead. The sports hour ran from July under
     * another name, so dating its patterns from September would delete a term of it. The test is
     * whether the July grid taught the subject, not whether the September grid spells it the same
     * way — which is also why `DE-1` is absent while `DE` is present, and why `ECA`, which the
     * July grid taught as one shared 3rd-year elective, is absent while the `ECA-A` / `ECA-B`
     * groups this edition splits it into are present.
     *
     * Each code is matched exactly or as a prefix before a space, so the seeder's ` Lab` and
     * ` Tutorial` courses are covered without `DE-1` being caught by `DE`.
     */
    val INTRODUCED: Set<String> = setOf(
        "DADV", // 4th Yr EE, ECE-A, ECE-B
        "DE", // 1st Yr, all six sections — not the 2nd year's DE-1
        "ECA-A", // 3rd Yr, split out of the shared ECA
        "ECA-B",
        "EF-1", // 1st Yr, all six sections
        "FL", // 1st Yr, all six sections
        "FMB", // 1st Yr, all six sections
        "MLDL", // 4th Yr CSE-A, CSE-B
    )

    /**
     * Whether this edition introduces [code] — see [INTRODUCED].
     *
     * `MLDL`, `MLDL Lab` and `MLDL Tutorial` are one subject taught three ways, so a code the
     * seeder has suffixed is still introduced. `DE-1` is not `DE`: the hyphen is part of the
     * code, so the prefix rule cannot reach it.
     */
    fun introduces(code: String): Boolean =
        code in INTRODUCED || INTRODUCED.any { code.startsWith("$it ") }

    /**
     * The `effectiveFrom` the first pattern for [code] should carry.
     *
     * The term start for everything the July grid taught. For a subject this edition introduces,
     * [EFFECTIVE_FROM] instead: a pattern dated from July for a paper nobody taught until the
     * 17th has [SessionGenerator] produce six weeks of classes that did not happen — rows nobody
     * could answer, sitting in the review queue as work to do, and entering the denominator as
     * absences the moment they are marked.
     *
     * The later of the two wins where the term starts after the edition, so a term that begins in
     * January is not backdated to the previous September. That is a floor on [EFFECTIVE_FROM] for
     * these codes alone — not a rule about patterns in general, which is why it is asked for by
     * code and why every other course keeps the term start unchanged.
     */
    fun effectiveFromFor(code: String, termStart: LocalDate): LocalDate =
        if (introduces(code)) maxOf(termStart, EFFECTIVE_FROM) else termStart

    /**
     * [code] under this edition's names, keeping any " Lab" or " Tutorial" the seeder put on it.
     *
     * The suffix is the seeder's, not the timetable's: the page prints one subject and the
     * seeder splits it into three courses, so a renamed subject renames all three.
     */
    fun renamed(code: String): String {
        RENAMED.forEach { (from, to) ->
            if (code == from) return to
            if (code.startsWith("$from ")) return to + code.removePrefix(from)
        }
        return code
    }

    /**
     * Whether two slots are the same class.
     *
     * The room counts. A lab that moved from 314 to 216 is the same hour of the same subject in
     * a different place, and a student standing outside the wrong door is exactly what this
     * migration exists to prevent — so the old slot closes and the new one opens, rather than
     * the room being quietly edited on a slot that already has history behind it.
     */
    private fun SessionPattern.sameSlotAs(other: SessionPattern): Boolean =
        dayOfWeek == other.dayOfWeek &&
            startHour == other.startHour &&
            units == other.units &&
            kind == other.kind &&
            room?.trim().orEmpty() == other.room?.trim().orEmpty()
}
