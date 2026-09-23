package com.attendo.core.data

import com.attendo.core.engine.RoomAvailability
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.RoomBooking
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.TimeGrid
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One course the seeder is offering to create, with the weekly slots it found.
 *
 * The patterns carry `courseId = 0` because the course does not have a row id until it
 * is inserted; [withCourseId] stamps them once it does.
 */
data class SeedProposal(
    val course: Course,
    val patterns: List<SessionPattern>,
    /** Batch labels this subject is split by — empty when the whole section attends together. */
    val batches: List<String> = emptyList(),
) {
    val unitsPerWeek: Int get() = patterns.sumOf { it.units }

    /** True when the subject is taught to batches, so the student must say which they are in. */
    val isBatchSplit: Boolean get() = batches.isNotEmpty()

    /**
     * True when the subject is *still* split after the student picked a batch.
     *
     * A section can be batched two ways at once: its own A1/A2 for branch labs, and a
     * second numbering for a course pooled with other sections. Choosing A1 says nothing
     * about the second scheme, so both its classes are proposed and this flag asks the
     * student to look — better than guessing and quietly dropping a lab.
     */
    val needsBatchCheck: Boolean get() = batches.size > 1

    /** "Mon 9–11 AM, Wed 2–3 PM" — the pattern summary for the confirmation list. */
    val scheduleLabel: String
        get() = patterns.joinToString(", ") { "${dayAbbrev(it.dayOfWeek)} ${it.slotLabel}" }

    fun withCourseId(id: Long): SeedProposal =
        copy(course = course.copy(id = id), patterns = patterns.map { it.copy(courseId = id) })

    private fun dayAbbrev(day: DayOfWeek): String =
        day.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
}

/**
 * Two subjects the student's own section is expected to be in at once.
 *
 * Usually this means a batch has not been chosen yet — a lab split A1/A2 looks like a
 * clash until you say which batch you are in. Anything left after [SeedPlan.batch] is
 * applied is a genuine conflict in the printed grid, and the student is the only one who
 * can say which class they actually attend.
 */
data class SeedClash(
    val dayOfWeek: DayOfWeek,
    val startHour: Int,
    val bookings: List<RoomBooking>,
) {
    val subjects: List<String> get() = bookings.map { it.subject }.distinct().sorted()

    /** "Wed 2 PM: DBMS (A1) vs DSD (A2)" */
    val label: String
        get() {
            val day = dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            val who = bookings.joinToString(" vs ") { booking ->
                booking.subject + booking.batch?.let { " ($it)" }.orEmpty()
            }
            return "$day ${TimeGrid.slotLabel(startHour)}: $who"
        }
}

/** Everything the "seed my courses" screen needs to show a checklist and ask for a batch. */
data class SeedPlan(
    val section: String,
    val batch: String?,
    val proposals: List<SeedProposal>,
    val clashes: List<SeedClash> = emptyList(),
    /** Every batch label the section is split by, for the batch picker. */
    val batchOptions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = proposals.isEmpty()

    val unitsPerWeek: Int get() = proposals.sumOf { it.unitsPerWeek }

    /** True when picking a batch would change what gets created. */
    val needsBatchChoice: Boolean get() = batch == null && batchOptions.isNotEmpty()

    /** "6 courses, 21 hours a week" */
    val summary: String
        get() = buildString {
            append("${proposals.size} course${if (proposals.size == 1) "" else "s"}")
            append(", $unitsPerWeek hour${if (unitsPerWeek == 1) "" else "s"} a week")
            if (clashes.isNotEmpty()) append(", ${clashes.size} to check")
        }
}

/**
 * Turns one section's page of the room timetable into courses and recurring patterns.
 *
 * This is the shortcut that saves a student typing twenty slots by hand: pick your
 * section, pick your batch, tick the courses you actually take, and the attendance
 * feature is set up. The seeder only ever *proposes* — nothing is created until the
 * student confirms.
 *
 * ### How rows become courses
 *
 * Three courses per subject, at most: the theory, the lab and the tutorial. Each is taught,
 * examined and attended on its own terms — a lab is a different room, a different batch and
 * often a different teacher — and a student who has missed half their labs wants to see
 * that, not have it averaged into a healthy lecture figure.
 *
 * A row is a lab when its kind is practical, and a tutorial when its kind is tutorial or its
 * subject is tagged `-T`. The tag and the column say the same thing in two places, so either
 * is enough; where a page carries both, they agree.
 *
 * ### Subject tags
 *
 * A trailing `-A` or `-B` is usually the lecture group its section sits in: `PSCS-A` and
 * `PSCS-B` are one subject taught in two halls, and `subjects.csv` keys only `PSCS`. A
 * section is assigned to exactly one group, so within one page the tag says nothing and
 * [baseCode] drops it — otherwise a student whose lectures read `PSCS-B` and whose lab
 * reads `PSCS` gets two unrelated courses for one subject.
 *
 * Usually, not always. `ECA-A` and `ECA-B` are separate courses that happen to be spelled
 * the way a group tag is spelled, so [baseCode] asks the key first and only drops the tag
 * when the code it would drop it onto is a subject in its own right.
 *
 * A trailing `-T` is dropped from the code the same way, but unlike a group tag it is not
 * noise: it is what marks the row a tutorial, and it wins over a kind column that says
 * lecture.
 *
 * ### Batches
 *
 * Labs are split A1/A2 or B1/B2 and a student is in one of them. Passing [batch] keeps
 * that batch's labs and drops the other; leaving it null proposes everything and reports
 * [SeedPlan.batchOptions] so the UI can ask first.
 *
 * A section can carry two batch schemes at once, though — its own A1/A2, plus a second
 * numbering for a lab pooled with other sections (`2nd Yr ECE-A`'s statistics lab is
 * batch B3, shared with `2nd Yr EE-A`). Only the section's own scheme is offered as a
 * choice, and a subject numbered outside it is kept whatever the student picks: it is the
 * only class of its kind on their page, so they are in it. Dropping it — which a single
 * global batch filter does — loses a lab the student attends every week.
 */
object SectionSeeder {

    /**
     * The subject as the app should name it, with any trailing tag removed.
     *
     * Only a trailing `-A`, `-B` or `-T` goes: nothing in the subject key ends those ways,
     * while `DE-1`, `EVS-2`, `EM-I` and `AEW-I` are whole subject codes whose suffix is part
     * of the name.
     *
     * A code [subjects] lists is already the subject, and is handed back untouched — which is
     * what keeps `ECA-A` and `ECA-B` two courses rather than one `ECA` taught twice. The key
     * is the only thing that can tell the two apart: identical in shape, `DIP-A` folds onto
     * `DIP` (a practical split between two groups of one course) while `ECA-A` does not,
     * because the timetable prints no bare `ECA` for it to fold onto.
     */
    fun baseCode(subject: String, subjects: Glossary = Glossary.EMPTY): String =
        subject.takeIf { subjects.exactNameOf(it) != null }
            ?: subject.replace(TAG, "").takeIf { it.isNotBlank() }
            ?: subject

    /**
     * Builds a plan for [section]. [termStart] becomes each pattern's `effectiveFrom`,
     * so a mid-semester re-seed does not claim to describe weeks already recorded.
     *
     * [startFor] is for the one case a single term start cannot describe. The grid bundles an
     * edition that was reissued mid-term, so some of what a page prints was not taught under the
     * earlier one — a subject that appears for the first time in September has no July to be
     * dated from, and a pattern given one would generate six weeks of classes that never
     * happened. The caller that knows which edition the file is says so here, per course code;
     * by default every course is dated from [termStart], which is what the overwhelming majority
     * of them want. The rule itself belongs to the edition and lives in
     * [TimetableMigration.effectiveFromFor], not here — this screen is a seeder, not a calendar.
     */
    fun plan(
        bookings: Iterable<RoomBooking>,
        section: String,
        termStart: LocalDate,
        batch: String? = null,
        subjects: Glossary = Glossary.EMPTY,
        defaultTarget: Percent = Percent.DEFAULT_TARGET,
        startFor: (courseCode: String) -> LocalDate = { termStart },
    ): SeedPlan {
        val mine = RoomAvailability.forSection(bookings, section)
        val bySubject = mine.groupBy { baseCode(it.subject, subjects) }
        val ownScheme = ownScheme(bySubject)
        val kept = bySubject.values.flatMap { rows -> attendedBy(rows, batch, ownScheme) }

        val proposals = kept
            .groupBy { baseCode(it.subject, subjects) to partOf(it) }
            .map { (key, rows) ->
                val (code, part) = key
                // The suffix is part of the course code, so the caller's rule sees exactly the
                // code the proposal will carry: `MLDL`, `MLDL Lab`, `MLDL Tutorial`.
                proposalFor(
                    code = code,
                    part = part,
                    rows = rows,
                    subjects = subjects,
                    defaultTarget = defaultTarget,
                    effectiveFrom = startFor(code + part.suffix),
                )
            }
            .filter { it.patterns.isNotEmpty() }
            .sortedBy { it.course.code }

        return SeedPlan(
            section = section,
            batch = batch,
            proposals = proposals,
            clashes = clashesIn(kept, subjects),
            batchOptions = ownScheme,
        )
    }

    /** The batch labels [section] is split by, for the picker shown before [plan]. */
    fun batchesFor(
        bookings: Iterable<RoomBooking>,
        section: String,
        subjects: Glossary = Glossary.EMPTY,
    ): List<String> =
        ownScheme(
            bookings.filter { it.section == section }
                .groupBy { baseCode(it.subject, subjects) },
        )

    /**
     * The batch scheme the section is its own — the labels the student should be asked to
     * choose between.
     *
     * Every batched subject votes with the label set it uses; the set the most subjects
     * agree on wins, ties go to the larger set so a real choice beats a lone label, and
     * anything still tied is settled alphabetically so the chips do not reorder between
     * runs. That leaves `2nd Yr ECE-A` with A1/A2 rather than A1/A2/B3, B3 being the
     * numbering of one lab it shares with another section.
     *
     * A section split two ways at once still only gets asked once; [attendedBy] is careful
     * not to throw away the scheme that lost.
     */
    private fun ownScheme(bySubject: Map<String, List<RoomBooking>>): List<String> =
        bySubject.values
            .map { rows -> rows.mapNotNull { it.batch }.distinct().sorted() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<List<String>, Int>> { it.value }
                    .thenBy { it.key.size }
                    .thenByDescending { it.key.joinToString() },
            )
            ?.key
            .orEmpty()

    /**
     * The rows of one subject a student in [batch] actually attends, given the labels they
     * were [offered] a choice between.
     *
     * Every branch here is a different thing the printed page can mean:
     *
     * - the chosen batch is one of this subject's, so it is the one to keep;
     * - the subject splits two or more ways and the chosen batch is in none of them, so it
     *   is batched on a scheme the student was never asked about — keep it all and let
     *   [SeedProposal.needsBatchCheck] say so, because guessing here deletes a class;
     * - the subject shows a single label that *was* on the menu and the student picked
     *   something else, which is as close to "not your class" as the grid ever gets;
     * - a single label that was never on the menu: the pooled lab this section is dropped
     *   into as a whole, which they attend whatever they answered.
     */
    private fun attendedBy(
        rows: List<RoomBooking>,
        batch: String?,
        offered: List<String>,
    ): List<RoomBooking> {
        if (batch == null) return rows
        val labels = rows.mapNotNull { it.batch }.distinct()
        return when {
            labels.isEmpty() -> rows
            batch in labels -> rows.filter { it.batch == null || it.batch == batch }
            labels.size > 1 -> rows
            labels.single() in offered -> rows.filter { it.batch == null }
            else -> rows
        }
    }

    private fun proposalFor(
        code: String,
        part: Part,
        rows: List<RoomBooking>,
        subjects: Glossary,
        defaultTarget: Percent,
        effectiveFrom: LocalDate,
    ): SeedProposal {
        val course = Course(
            name = subjects.nameOf(code) + part.suffix,
            code = code + part.suffix,
            targetPercent = defaultTarget,
        )
        // The same class can be printed on the page twice — a joint lecture listed under
        // two rooms, or one subject's two lecture groups sharing an hour — so one pattern
        // per distinct slot is what the student expects to see.
        val patterns = rows
            .distinctBy { listOf(it.dayOfWeek, it.startHour, it.units, part.kindOf(it), it.batch) }
            .sortedWith(compareBy({ it.dayOfWeek.value }, { it.startHour }))
            .map { booking ->
                SessionPattern(
                    courseId = 0L,
                    dayOfWeek = booking.dayOfWeek,
                    startHour = booking.startHour,
                    units = booking.units,
                    kind = part.kindOf(booking),
                    room = booking.room,
                    effectiveFrom = effectiveFrom,
                )
            }
        return SeedProposal(
            course = course,
            patterns = patterns,
            batches = rows.mapNotNull { it.batch }.distinct().sorted(),
        )
    }

    /**
     * Which of a subject's three courses a row belongs to.
     *
     * The suffix is both the course code's and its name's: `ECA`, `ECA Lab`, `ECA Tutorial`.
     * Sorting the proposals by code therefore lists them in that order too.
     */
    private enum class Part(val suffix: String) {
        THEORY(""),
        LAB(" Lab"),
        TUTORIAL(" Tutorial"),
        ;

        /**
         * The kind to record for [booking] in this course.
         *
         * A `-T` tag makes a tutorial of a row whose kind column says lecture, and the
         * sessions generated from it should say tutorial too.
         */
        fun kindOf(booking: RoomBooking): SessionKind =
            if (this == TUTORIAL) SessionKind.TUTORIAL else booking.kind
    }

    private fun partOf(booking: RoomBooking): Part = when {
        booking.kind == SessionKind.PRACTICAL -> Part.LAB
        booking.kind == SessionKind.TUTORIAL -> Part.TUTORIAL
        TUTORIAL_TAG.containsMatchIn(booking.subject) -> Part.TUTORIAL
        else -> Part.THEORY
    }

    /**
     * Hours where the section is booked into two different subjects at once.
     *
     * Compared on [baseCode], so one subject's two lecture groups in one hour read as the
     * single class they are rather than as a conflict to resolve.
     */
    private fun clashesIn(
        bookings: List<RoomBooking>,
        subjects: Glossary,
    ): List<SeedClash> =
        bookings
            .flatMap { booking -> booking.occupiedHours.map { hour -> (booking.dayOfWeek to hour) to booking } }
            .groupBy({ it.first }, { it.second })
            .filterValues { rows -> rows.distinctBy { baseCode(it.subject, subjects) }.size > 1 }
            .map { (slot, rows) ->
                SeedClash(
                    dayOfWeek = slot.first,
                    startHour = slot.second,
                    bookings = rows.sortedWith(compareBy({ it.subject }, { it.batch.orEmpty() })),
                )
            }
            .sortedWith(compareBy({ it.dayOfWeek.value }, { it.startHour }))

    private val TAG = Regex("-[ABT]$", RegexOption.IGNORE_CASE)

    private val TUTORIAL_TAG = Regex("-T$", RegexOption.IGNORE_CASE)
}
