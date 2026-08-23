package com.attendo.core.model

import java.time.LocalDate

/**
 * The dates a tally is allowed to see.
 *
 * ### Why a window rather than a delete
 *
 * Two quite different requirements land on the same mechanism. A semester's attendance must
 * not include another semester's classes, and a student who joined in the third week must be
 * able to see their percentage from the day they arrived. In both cases the sessions outside
 * the window genuinely happened and genuinely belong to somebody's history — the university's
 * register still has those first three weeks — so removing them is the one answer that is
 * wrong. Filtering is reversible; deleting is not. Switch the window back and the whole term
 * is there again, because it was never touched.
 *
 * Both bounds are inclusive and either may be absent. [OPEN] counts everything, which is what
 * every existing caller gets by default.
 */
data class AttendanceWindow(
    /** Earliest date that counts, inclusive. Null for "since the beginning". */
    val from: LocalDate? = null,
    /** Latest date that counts, inclusive. Null for "up to whenever". */
    val to: LocalDate? = null,
) {
    init {
        require(from == null || to == null || !to.isBefore(from)) {
            "an attendance window cannot end ($to) before it starts ($from)"
        }
    }

    /** True when nothing is excluded. */
    val isOpen: Boolean get() = from == null && to == null

    operator fun contains(date: LocalDate): Boolean =
        (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))

    operator fun contains(session: ClassSession): Boolean = contains(session.date)

    fun filter(sessions: Iterable<ClassSession>): List<ClassSession> =
        if (isOpen) sessions.toList() else sessions.filter { contains(it.date) }

    /** Narrows to the overlap of the two. Used to bound a personal start inside a semester. */
    fun intersect(other: AttendanceWindow): AttendanceWindow {
        val start = listOfNotNull(from, other.from).maxOrNull()
        val end = listOfNotNull(to, other.to).minOrNull()
        // An empty overlap is expressed as a zero-length window at the later bound rather
        // than by throwing: a semester that ended before the student joined counts nothing,
        // which is the right answer and not an error.
        return if (start != null && end != null && end.isBefore(start)) {
            AttendanceWindow(start, start)
        } else {
            AttendanceWindow(start, end)
        }
    }

    /** "from 12 Aug 2026", "up to 15 Dec 2026", "12 Aug 2026 – 15 Dec 2026", "all dates" */
    val label: String get() = when {
        from != null && to != null -> "${Semester.dayLabel(from)} – ${Semester.dayLabel(to)}"
        from != null -> "from ${Semester.dayLabel(from)}"
        to != null -> "up to ${Semester.dayLabel(to)}"
        else -> "all dates"
    }

    companion object {
        val OPEN: AttendanceWindow = AttendanceWindow()

        fun since(from: LocalDate): AttendanceWindow = AttendanceWindow(from = from)

        fun until(to: LocalDate): AttendanceWindow = AttendanceWindow(to = to)
    }
}

/** Which date a student's attendance is counted from. */
enum class AttendanceBasis(val label: String, val explanation: String) {
    /** From the start of the semester, matching the register the university keeps. */
    UNIVERSITY(
        "Semester start",
        "Counts every class since the semester began — the figure the university uses.",
    ),

    /** From the day this student joined, for a mid-semester admission. */
    PERSONAL(
        "My joining date",
        "Counts only classes from the day you joined. The earlier ones stay in your history.",
    ),
}

/**
 * The student's answer to "which classes are mine?".
 *
 * A spot admission arrives in week three to a timetable that has been running since week one.
 * The sessions from those first two weeks exist — they were generated from the timetable and
 * the university held them — and the two readings of that are both legitimate: the university's
 * register counts them against the student, and the student's own progress since arriving does
 * not. So this is a *choice*, stored, and applied as an [AttendanceWindow] at read time.
 *
 * [joinedOn] is kept even while [basis] is [AttendanceBasis.UNIVERSITY], so switching between
 * the two readings never asks for the date again.
 */
data class AttendanceStart(
    val basis: AttendanceBasis = AttendanceBasis.UNIVERSITY,
    val joinedOn: LocalDate? = null,
) {
    /** True when a personal start is asked for but no date has been given, so it cannot apply. */
    val isIncomplete: Boolean get() = basis == AttendanceBasis.PERSONAL && joinedOn == null

    /**
     * The first date that counts, given the semester the tally is for.
     *
     * A joining date before the semester started is ignored in favour of the semester start:
     * the student cannot have attended classes that had not happened yet, and honouring it
     * would silently widen a semester's window into the previous one's.
     */
    fun effectiveFrom(semesterStart: LocalDate?): LocalDate? = when {
        basis == AttendanceBasis.UNIVERSITY -> semesterStart
        joinedOn == null -> semesterStart
        semesterStart == null -> joinedOn
        else -> maxOf(joinedOn, semesterStart)
    }

    /**
     * The window for one semester under this choice, or an open one when no semester is set up.
     *
     * Bounded by the semester on both sides, which is what stops one term's figures reaching into
     * another's. The overlap is taken through [AttendanceWindow.intersect] rather than built
     * directly, because a joining date *after* a semester ended is a state a student can reach
     * just by opening a previous semester's tally — and the answer there is "none of it is yours",
     * not a crash.
     */
    fun windowIn(semester: Semester?): AttendanceWindow {
        val term = semester?.window ?: AttendanceWindow.OPEN
        val start = effectiveFrom(semester?.startDate) ?: return term
        return term.intersect(AttendanceWindow.since(start))
    }

    /** "Semester start", "My joining date (12 Aug 2026)" */
    val label: String get() = when {
        basis == AttendanceBasis.UNIVERSITY -> basis.label
        joinedOn == null -> "${basis.label} (not set)"
        else -> "${basis.label} (${Semester.dayLabel(joinedOn)})"
    }
}
