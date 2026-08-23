package com.attendo.core.engine

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.ClassSession
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import java.time.LocalDate

/**
 * Turns recurring [SessionPattern]s into concrete [ClassSession] rows for a date
 * range.
 *
 * ### Idempotency
 *
 * `(patternId, date)` is the key. Generation is run freely — on app open, on date
 * change, after editing a pattern — so it must be safe to repeat, and in particular
 * it must never **resurrect** a session the student already dealt with. A row
 * already occupying the key blocks regeneration *whatever its status*: cancelling
 * Monday's lecture and reopening the app must not bring it back.
 *
 * The generator is pure — it reports what is missing and lets the repository do the
 * inserting — so this rule is testable without a database.
 */
object SessionGenerator {

    /**
     * A session that should exist but doesn't yet.
     *
     * Drafts are born **pre-filled as fully present** ([UnitMask.allPresent]) while
     * still [SessionStatus.SCHEDULED]. That is what makes the review screen's
     * one-tap "Approve All" work: the happy path needs no edits, and because
     * SCHEDULED sessions are invisible to the percentage, an optimistic mask on an
     * unreviewed row cannot inflate anything.
     */
    data class Draft(
        val courseId: Long,
        val patternId: Long,
        val date: LocalDate,
        val startHour: Int,
        val unitsPlanned: Int,
        val kind: SessionKind,
        val room: String?,
    ) {
        fun toSession(): ClassSession = ClassSession(
            courseId = courseId,
            patternId = patternId,
            date = date,
            startHour = startHour,
            unitsPlanned = unitsPlanned,
            unitsMask = UnitMask.allPresent(unitsPlanned),
            status = SessionStatus.SCHEDULED,
            kind = kind,
            room = room,
        )
    }

    /**
     * Sessions missing between [from] and [to] inclusive, clipped to the term and
     * skipping non-teaching days.
     *
     * [existing] is consulted only through the `(patternId, date)` key, so ad-hoc
     * sessions never block generation of a timetabled one.
     */
    fun draftsFor(
        patterns: List<SessionPattern>,
        existing: Iterable<ClassSession>,
        from: LocalDate,
        to: LocalDate,
        calendar: AcademicCalendar,
    ): List<Draft> {
        if (patterns.isEmpty()) return emptyList()

        val start = maxOf(from, calendar.termStart)
        val end = minOf(to, calendar.termEnd)
        if (end.isBefore(start)) return emptyList()

        val occupied: Set<Pair<Long, LocalDate>> = existing.asSequence()
            .mapNotNull { session -> session.patternId?.let { it to session.date } }
            .toSet()

        val patternsByDay = patterns.groupBy { it.dayOfWeek }
        val drafts = mutableListOf<Draft>()

        for (date in calendar.teachingDaysBetween(start, end)) {
            for (pattern in patternsByDay[date.dayOfWeek].orEmpty()) {
                if (!pattern.isEffectiveOn(date)) continue
                if ((pattern.id to date) in occupied) continue
                drafts += Draft(
                    courseId = pattern.courseId,
                    patternId = pattern.id,
                    date = date,
                    startHour = pattern.startHour,
                    unitsPlanned = pattern.units,
                    kind = pattern.kind,
                    room = pattern.room,
                )
            }
        }

        return drafts.sortedWith(compareBy({ it.date }, { it.startHour }))
    }

    /**
     * Convenience for the daily review screen: everything already stored for [date]
     * plus drafts for anything still missing, in slot order.
     */
    fun dayPlan(
        date: LocalDate,
        patterns: List<SessionPattern>,
        existing: Iterable<ClassSession>,
        calendar: AcademicCalendar,
    ): DayPlan {
        val stored = existing.filter { it.date == date }
        val drafts = draftsFor(patterns, stored, date, date, calendar)
        return DayPlan(
            date = date,
            stored = stored.sortedBy { it.startHour },
            missing = drafts,
            isTeachingDay = calendar.isTeachingDay(date),
        )
    }
}

/**
 * What the review screen shows for one day: rows that already exist plus rows that
 * still need creating.
 */
data class DayPlan(
    val date: LocalDate,
    val stored: List<ClassSession>,
    val missing: List<SessionGenerator.Draft>,
    val isTeachingDay: Boolean,
) {
    val hasAnything: Boolean get() = stored.isNotEmpty() || missing.isNotEmpty()

    /** Sessions still needing a decision before the day is fully reviewed. */
    val awaitingReview: List<ClassSession> get() = stored.filter { it.isAwaitingReview }

    val isFullyReviewed: Boolean get() = missing.isEmpty() && awaitingReview.isEmpty()

    /** Units that "Approve All" would commit right now, counting drafts. */
    val unitsPendingApproval: Int
        get() = awaitingReview.sumOf { it.unitsPlanned } + missing.sumOf { it.unitsPlanned }
}
