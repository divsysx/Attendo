package com.attendo.core.engine

import com.attendo.core.model.ClassSession
import com.attendo.core.model.Percent
import java.time.LocalDate
import java.time.YearMonth

/**
 * One line of the per-course attendance detail screen.
 *
 * The screen exists to be *audited*, so every logged occurrence appears — including
 * cancelled and not-yet-reviewed ones. Only [ClassSession.countsTowardAttendance]
 * rows move [runningTally], which is what makes the running figure on any row equal
 * the subject percentage as it stood after that class.
 */
data class HistoryRow(
    val session: ClassSession,
    /** Cumulative tally over this course up to and including this session. */
    val runningTally: Tally,
) {
    /** Units attended / units held for this session alone; null when it doesn't count. */
    val sessionPercent: Percent? get() = session.sessionPercent

    /** The subject percentage as it stood after this session. */
    val runningPercent: Percent? get() = runningTally.percent

    /** "1 / 2" — the fraction the screen prints next to the slot. */
    val unitsLabel: String get() = "${session.unitsAttended} / ${session.unitsPlanned}"
}

/** A month's worth of history rows, for the sticky headers on the detail screen. */
data class HistoryMonth(
    val month: YearMonth,
    val rows: List<HistoryRow>,
    /** Tally for this month alone, not cumulative. */
    val tally: Tally,
)

/**
 * Builds the scrollable audit trail for a single subject.
 *
 * The running tally is always accumulated oldest-first — reversing the list for
 * display must not change what each row says the percentage was at the time.
 */
object CourseHistory {

    /**
     * Every logged session for [courseId], newest first by default.
     *
     * Sessions are ordered by date then slot, so two classes on the same day read in
     * the order they happened.
     *
     * [today] bounds the audit trail to what has happened: a future SCHEDULED row is an
     * upcoming class, not a class awaiting review, and showing it labelled "To mark" puts
     * work into the backlog that has not happened yet. When non-null, sessions dated after
     * [today] are dropped from the list — never from the database. The comparison is the
     * date half of [ClassSession.isReviewableOn]: today is kept, tomorrow onward is not.
     *
     * The running tally is unaffected, because future SCHEDULED rows never moved it to
     * begin with — only HELD rows count. Dropping them simply removes them from the
     * display, so a future HELD row produced by a what-if still tallies in the figures
     * computed elsewhere (see [AttendanceEngine.courseStats]). Default null keeps the
     * unbounded behaviour the rest of the app relied on before this screen grew a today.
     */
    fun rows(
        sessions: Iterable<ClassSession>,
        courseId: Long,
        newestFirst: Boolean = true,
        today: LocalDate? = null,
    ): List<HistoryRow> {
        val chronological = sessions
            .filter { it.courseId == courseId }
            .filter { today == null || !it.date.isAfter(today) }
            .sortedWith(compareBy({ it.date }, { it.startHour }))

        var running = Tally.EMPTY
        val rows = chronological.map { session ->
            if (session.countsTowardAttendance) {
                running += Tally(session.unitsAttended, session.unitsPlanned)
            }
            HistoryRow(session = session, runningTally = running)
        }
        return if (newestFirst) rows.asReversed() else rows
    }

    /**
     * The same rows grouped into month sections, newest month first when [rows] is
     * newest-first. Group order follows the order of [rows] rather than re-sorting,
     * so the caller's chosen direction is preserved.
     */
    fun groupedByMonth(rows: List<HistoryRow>): List<HistoryMonth> =
        rows.groupBy { YearMonth.from(it.session.date) }
            .map { (month, monthRows) ->
                HistoryMonth(
                    month = month,
                    rows = monthRows,
                    tally = AttendanceEngine.tallyOf(monthRows.map { it.session }),
                )
            }

    /** Convenience for the screen: rows plus month sections in one pass. */
    fun monthsFor(
        sessions: Iterable<ClassSession>,
        courseId: Long,
        newestFirst: Boolean = true,
        today: LocalDate? = null,
    ): List<HistoryMonth> = groupedByMonth(rows(sessions, courseId, newestFirst, today))
}
