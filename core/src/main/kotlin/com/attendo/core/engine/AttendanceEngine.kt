package com.attendo.core.engine

import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SessionStatus
import java.time.LocalDate

/**
 * Attended and held units. Units — not classes — are the currency of this app: a
 * 2-hour class contributes 2 to [unitsHeld], which is what lets a half-attended
 * block count as exactly 50% instead of being rounded to a whole present or absent.
 */
data class Tally(val unitsAttended: Int, val unitsHeld: Int) {
    init {
        require(unitsAttended >= 0 && unitsHeld >= 0) { "tally cannot be negative" }
        require(unitsAttended <= unitsHeld) {
            "attended ($unitsAttended) cannot exceed held ($unitsHeld)"
        }
    }

    val unitsMissed: Int get() = unitsHeld - unitsAttended

    /** Null when nothing has been held — an undefined percentage, not 0%. */
    val percent: Percent? get() = Percent.ofRatio(unitsAttended, unitsHeld)

    val isEmpty: Boolean get() = unitsHeld == 0

    operator fun plus(other: Tally): Tally =
        Tally(unitsAttended + other.unitsAttended, unitsHeld + other.unitsHeld)

    companion object {
        val EMPTY: Tally = Tally(0, 0)
    }
}

/**
 * Guidance against an attendance threshold, all in *units*.
 *
 * [unitsCanSkip] and [unitsMustAttend] are mutually exclusive in practice: at or
 * above the target the first is populated, below it the second.
 */
data class TargetAdvice(
    val target: Percent,
    val current: Percent?,
    val meetsTarget: Boolean,
    /**
     * Further units that may be missed while staying at or above [target].
     * `Int.MAX_VALUE` when the target is 0% and therefore unmissable.
     */
    val unitsCanSkip: Int,
    /**
     * Consecutive future units that must be attended to climb back to [target].
     * Meaningful only when [targetReachable] and not [meetsTarget].
     */
    val unitsMustAttend: Int,
    /**
     * False only in the corner case of a 100% target with a miss already banked —
     * no amount of future attendance recovers it.
     */
    val targetReachable: Boolean,
)

/** Per-subject rollup, backing the subject list and the per-course detail screen. */
data class CourseStats(
    val course: Course,
    val tally: Tally,
    val sessionsHeld: Int,
    val sessionsCancelled: Int,
    val sessionsAwaitingReview: Int,
    /** Sessions attended in part — neither fully present nor fully absent. */
    val sessionsPartial: Int,
    val target: TargetAdvice,
) {
    val percent: Percent? get() = tally.percent
    val isBelowTarget: Boolean get() = !target.meetsTarget
}

/** Dashboard rollup across every course. */
data class OverallStats(
    val tally: Tally,
    val perCourse: List<CourseStats>,
    val target: TargetAdvice,
) {
    val percent: Percent? get() = tally.percent
    val coursesBelowTarget: List<CourseStats> get() = perCourse.filter { it.isBelowTarget }
}

/** Colour band for one day in the attendance calendar. */
enum class DayMark {
    /** Nothing scheduled — blank/grey. */
    NO_CLASS,

    /**
     * Classes happened, but outside the window the student's attendance is counted from —
     * before they joined, or in another semester. Shown, greyed, and excluded from every
     * total. The sessions are still there; see [AttendanceWindow].
     */
    NOT_COUNTED,

    /** Classes were timetabled but never reviewed, so they do not count yet. */
    AWAITING_REVIEW,

    /** Every session that day was cancelled — nothing counted. */
    ALL_CANCELLED,

    /** Every held unit attended — green. */
    FULL,

    /** Some but not all units attended — amber. This is the split-class case. */
    PARTIAL,

    /** Held, none attended — red. */
    ABSENT,
}

/** One calendar cell: its colour band plus the numbers and sessions behind it. */
data class DayAttendance(
    val date: LocalDate,
    val mark: DayMark,
    val tally: Tally,
    val unitsAwaitingReview: Int,
    val unitsCancelled: Int,
    val sessions: List<ClassSession>,
) {
    val percent: Percent? get() = tally.percent
}

/**
 * The fractional-attendance engine. Every function is pure and side-effect free,
 * which is what makes the rules here testable in isolation — and they are, in
 * `AttendanceEngineTest`.
 *
 * ### The three rules
 *
 * 1. **Per session** — `unitsAttended / unitsPlanned`. One hour of a 2-hour block
 *    is 50%, not a full absence and not a full present.
 * 2. **Per subject** — `Σ unitsAttended / Σ unitsPlanned` over that subject's HELD
 *    sessions. Fractions flow in as fractions.
 * 3. **Overall** — the same sum taken across *all* subjects, so it is weighted by
 *    units actually held. Deliberately **not** the mean of the per-subject
 *    percentages, which would let a subject with two sessions outvote one with
 *    thirty.
 *
 * Only [SessionStatus.HELD] sessions are counted; SCHEDULED and CANCELLED are
 * invisible to all three.
 *
 * ### The window
 *
 * Every entry point takes an [AttendanceWindow], defaulting to [AttendanceWindow.OPEN]. It is
 * how two things the app must get right are expressed as one rule: a semester's figure counts
 * only that semester's dates, and a student admitted mid-term can count only from the day they
 * arrived. Sessions outside the window are skipped, never removed — they are somebody's real
 * history, and the university's own register still counts the weeks before a late admission.
 * Widening the window brings every one of them straight back.
 */
object AttendanceEngine {

    fun tallyOf(
        sessions: Iterable<ClassSession>,
        window: AttendanceWindow = AttendanceWindow.OPEN,
    ): Tally {
        var attended = 0
        var held = 0
        for (session in sessions) {
            if (!session.countsTowardAttendance) continue
            if (session.date !in window) continue
            attended += session.unitsAttended
            held += session.unitsPlanned
        }
        return Tally(attended, held)
    }

    /**
     * Rolls up one course. [sessions] should be that course's sessions; any
     * belonging to another course are ignored rather than silently mixed in, as is
     * anything outside [window].
     *
     * [today] bounds [CourseStats.sessionsAwaitingReview] alone, and only that field — see
     * [ClassSession.isReviewableOn] for why a future unreviewed row is not review work. Passing
     * null means "no horizon", which counts every unreviewed session whatever its date; it is
     * the default so that a caller asking a purely historical question does not have to invent a
     * date, and every caller that puts the number in front of a student passes one.
     *
     * Nothing else here takes any notice of [today]. A future session that the student has
     * marked in a what-if still counts exactly as it did — the tally, the held and cancelled
     * counts and the target advice are all deliberately date-blind, because bounding them would
     * be changing what a percentage means rather than what the review queue holds.
     */
    fun courseStats(
        course: Course,
        sessions: Iterable<ClassSession>,
        window: AttendanceWindow = AttendanceWindow.OPEN,
        today: LocalDate? = null,
    ): CourseStats {
        val own = sessions.filter { it.courseId == course.id && it.date in window }
        val tally = tallyOf(own)
        var held = 0
        var cancelled = 0
        var awaiting = 0
        var partial = 0
        for (session in own) {
            when (session.status) {
                SessionStatus.HELD -> {
                    held++
                    val attended = session.unitsAttended
                    if (attended > 0 && attended < session.unitsPlanned) partial++
                }
                SessionStatus.CANCELLED -> cancelled++
                SessionStatus.SCHEDULED ->
                    if (today == null || session.isReviewableOn(today)) awaiting++
            }
        }
        return CourseStats(
            course = course,
            tally = tally,
            sessionsHeld = held,
            sessionsCancelled = cancelled,
            sessionsAwaitingReview = awaiting,
            sessionsPartial = partial,
            target = advise(tally, course.targetPercent),
        )
    }

    /**
     * Rolls up every course. [overallTarget] applies to the aggregate figure; each
     * course keeps its own threshold inside [CourseStats].
     *
     * [today] is passed straight through to [courseStats] and means the same thing there.
     */
    fun overallStats(
        courses: List<Course>,
        sessions: Iterable<ClassSession>,
        overallTarget: Percent = Percent.DEFAULT_TARGET,
        window: AttendanceWindow = AttendanceWindow.OPEN,
        today: LocalDate? = null,
    ): OverallStats {
        val byCourse = sessions.groupBy { it.courseId }
        val perCourse = courses.map { course ->
            courseStats(course, byCourse[course.id].orEmpty(), window, today)
        }
        // Summing tallies is what makes this unit-weighted rather than an average
        // of averages.
        val total = perCourse.fold(Tally.EMPTY) { acc, stats -> acc + stats.tally }
        return OverallStats(
            tally = total,
            perCourse = perCourse,
            target = advise(total, overallTarget),
        )
    }


    /**
     * One semester's attendance, and nothing else's.
     *
     * Two filters, and both are needed. [courses] is narrowed to the ones that belong to
     * [semester], which is what stops last term's six subjects appearing in this term's list;
     * the window is narrowed to the semester's dates, which is what stops a session that a
     * carried-over course happened to hold in the break from being counted. Either alone
     * leaves a way for two semesters to meet in one percentage.
     *
     * [start] applies the student's own answer to when their attendance begins, so a spot
     * admission's figure starts at their joining date without either the semester or the
     * sessions before it being altered.
     *
     * [today] carries through to [courseStats] unchanged, so this entry point bounds the review
     * count by the same rule the other two do rather than quietly disagreeing with them.
     */
    fun semesterStats(
        semester: Semester,
        courses: List<Course>,
        sessions: Iterable<ClassSession>,
        overallTarget: Percent = Percent.DEFAULT_TARGET,
        start: AttendanceStart = AttendanceStart(),
        today: LocalDate? = null,
    ): OverallStats = overallStats(
        courses = courses.filter { it.isIn(semester) },
        sessions = sessions,
        overallTarget = overallTarget,
        window = start.windowIn(semester),
        today = today,
    )

    /**
     * Threshold guidance in exact integer arithmetic.
     *
     * Comparisons are cross-multiplied (`attended × 10000 ≥ targetBp × held`)
     * rather than done on a rounded percentage. Comparing a rounded value would
     * misreport the boundary: 59/80 rounds to 73.75% and 60/80 to exactly 75%, and
     * a student on the latter must be told they are *meeting* 75%, not a hair under.
     */
    fun advise(tally: Tally, target: Percent): TargetAdvice {
        val attended = tally.unitsAttended.toLong()
        val held = tally.unitsHeld.toLong()
        val targetBp = target.basisPoints.toLong()
        val full = Percent.BP_FULL.toLong()

        // >= 0 exactly when attended/held >= target. Zero means sitting precisely
        // on the threshold, where nothing more can be skipped.
        val surplus = attended * full - targetBp * held
        val meets = surplus >= 0

        val canSkip: Int = when {
            targetBp == 0L -> Int.MAX_VALUE // a 0% target can never be missed
            !meets -> 0
            // Largest k with attended*full >= targetBp*(held+k).
            else -> (surplus / targetBp).clampToInt()
        }

        val mustAttend: Int = when {
            meets -> 0
            targetBp >= full -> 0 // unreachable; see targetReachable
            // Smallest k with (attended+k)*full >= targetBp*(held+k).
            else -> ceilDiv(-surplus, full - targetBp).clampToInt()
        }

        return TargetAdvice(
            target = target,
            current = tally.percent,
            meetsTarget = meets,
            unitsCanSkip = canSkip,
            unitsMustAttend = mustAttend,
            targetReachable = targetBp < full || meets,
        )
    }

    /** Where the percentage lands if every one of [upcomingUnits] is attended. */
    fun projectIfAllAttended(tally: Tally, upcomingUnits: Int): Percent? =
        Percent.ofRatio(tally.unitsAttended + upcomingUnits, tally.unitsHeld + upcomingUnits)

    /** Where the percentage lands if every one of [upcomingUnits] is missed. */
    fun projectIfAllMissed(tally: Tally, upcomingUnits: Int): Percent? =
        Percent.ofRatio(tally.unitsAttended, tally.unitsHeld + upcomingUnits)

    /**
     * Collapses one date into a calendar cell. [sessions] may be the full set; only
     * those on [date] are considered.
     *
     * The band is decided by the *held* sessions, so a day that also has an
     * unreviewed class still shows its real colour; [DayAttendance.unitsAwaitingReview]
     * is exposed separately for a pending dot.
     *
     * A date outside [window] comes back as [DayMark.NOT_COUNTED] with an empty tally and its
     * sessions still attached. That is deliberate: the day screen must be able to show what
     * happened before a late admission, while the calendar must not paint it red and no total
     * may include it. Reporting it as [DayMark.NO_CLASS] would claim the classes never happened.
     *
     * [today] bounds the *review band* alone, using the date half of
     * [ClassSession.isReviewableOn] (`!date.isAfter(today)`): a future day whose sessions are
     * all still SCHEDULED is an upcoming class, not one awaiting review, so it is marked
     * [DayMark.NO_CLASS] instead of [DayMark.AWAITING_REVIEW]. It is the same line the audit
     * list ([CourseHistory.rows]) and the review queue ([sessionsAwaitingReview]) draw, so the
     * calendar can no longer present a future class as "To review" while the list beside it has
     * already dropped it. Only the pure-SCHEDULED future case is redirected — a future day a
     * what-if turned HELD still resolves through the held branch above with its real attendance
     * colour. The count ([DayAttendance.unitsAwaitingReview]) and the tally are left date-blind,
     * so the pending dot and every figure stay exactly as they were; default null keeps the
     * unbounded behaviour the rest of the app and the existing tests relied on. NO_CLASS is the
     * same neutral state a day with no classes takes, so a future scheduled date reads as a plain
     * future day — no band, no legend entry, no figure — while its sessions stay attached for the
     * what-if math.
     */
    fun dayAttendance(
        date: LocalDate,
        sessions: Iterable<ClassSession>,
        window: AttendanceWindow = AttendanceWindow.OPEN,
        today: LocalDate? = null,
    ): DayAttendance {
        val onDate = sessions.filter { it.date == date }.sortedBy { it.startHour }
        if (onDate.isNotEmpty() && date !in window) {
            return DayAttendance(
                date = date,
                mark = DayMark.NOT_COUNTED,
                tally = Tally.EMPTY,
                unitsAwaitingReview = 0,
                unitsCancelled = 0,
                sessions = onDate,
            )
        }
        val tally = tallyOf(onDate)
        var awaiting = 0
        var cancelled = 0
        for (session in onDate) {
            when (session.status) {
                SessionStatus.SCHEDULED -> awaiting += session.unitsPlanned
                SessionStatus.CANCELLED -> cancelled += session.unitsPlanned
                SessionStatus.HELD -> Unit
            }
        }
        // `today` draws the same line [ClassSession.isReviewableOn] does: a date after today is
        // not review work. A future day with only SCHEDULED sessions would otherwise land in the
        // AWAITING_REVIEW band and read as a class to mark now — exactly the bug the audit list
        // was fixed for. NO_CLASS renders it as the same neutral empty state a no-class future
        // day takes, so no band, no legend entry, and no figure is produced for it; the count and
        // tally above are unchanged, so a future what-if turned HELD still takes the held branch
        // and paints its real colour.
        val isFuture = today != null && date.isAfter(today)
        val mark = when {
            onDate.isEmpty() -> DayMark.NO_CLASS
            tally.unitsHeld > 0 -> when {
                tally.unitsAttended == tally.unitsHeld -> DayMark.FULL
                tally.unitsAttended == 0 -> DayMark.ABSENT
                else -> DayMark.PARTIAL
            }
            awaiting > 0 -> if (isFuture) DayMark.NO_CLASS else DayMark.AWAITING_REVIEW
            else -> DayMark.ALL_CANCELLED
        }
        return DayAttendance(
            date = date,
            mark = mark,
            tally = tally,
            unitsAwaitingReview = awaiting,
            unitsCancelled = cancelled,
            sessions = onDate,
        )
    }

    /**
     * Calendar cells for every date in [from]..[to] that has any session. Dates
     * with nothing scheduled are omitted; the UI renders those as blank.
     *
     * [today], when supplied, is forwarded to [dayAttendance] so the calendar draws the
     * same review boundary the rest of the screen does — no future SCHEDULED day paints the
     * AWAITING_REVIEW band. Default null leaves the cells unbounded, as before.
     */
    fun calendarMarks(
        sessions: Iterable<ClassSession>,
        from: LocalDate,
        to: LocalDate,
        window: AttendanceWindow = AttendanceWindow.OPEN,
        today: LocalDate? = null,
    ): Map<LocalDate, DayAttendance> =
        sessions.asSequence()
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            .groupBy { it.date }
            .mapValues { (date, onDate) -> dayAttendance(date, onDate, window, today) }
            .toSortedMap()

    /**
     * Every class the review workflow may ask about as of [today], in the order it would be
     * worked through — oldest date first, and within a date by the hour it was timetabled.
     *
     * This is the one definition of "awaiting review" in the app. Past and today qualify;
     * anything after [today] never does, however it came to be sitting there unreviewed. See
     * [ClassSession.isReviewableOn] for the case that makes the bound necessary.
     *
     * [window] excludes what is not the student's to mark, the same way it does everywhere
     * else — a spot admission is not handed the fortnight before they enrolled.
     */
    fun sessionsAwaitingReview(
        sessions: Iterable<ClassSession>,
        today: LocalDate,
        window: AttendanceWindow = AttendanceWindow.OPEN,
    ): List<ClassSession> =
        sessions.filter { it.isReviewableOn(today) && it.date in window }
            .sortedWith(compareBy({ it.date }, { it.startHour }))

    /**
     * Past dates still holding unreviewed sessions, oldest first — the backlog
     * behind the "N days awaiting review" nudge. Anything outside [window] is left out: a
     * student is not asked to review the fortnight before they joined.
     *
     * Today is a reviewable date — [sessionsAwaitingReview] includes it — but it is
     * deliberately not a *backlog* date. Today's classes already have the day's own card and
     * its one-tap approve on the screen this list appears on, so listing today here would show
     * a "catch up" nudge every teaching day and point it at something already in front of the
     * student. What is dropped is only the day; the eligibility rule is
     * [ClassSession.isReviewableOn]'s, so no future date can reach this list either.
     */
    fun daysAwaitingReview(
        sessions: Iterable<ClassSession>,
        today: LocalDate,
        window: AttendanceWindow = AttendanceWindow.OPEN,
    ): List<LocalDate> =
        sessionsAwaitingReview(sessions, today, window)
            .asSequence()
            .map { it.date }
            .filterNot { it == today }
            .distinct()
            .sorted()
            .toList()

    private fun ceilDiv(numerator: Long, divisor: Long): Long =
        (numerator + divisor - 1) / divisor

    private fun Long.clampToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
