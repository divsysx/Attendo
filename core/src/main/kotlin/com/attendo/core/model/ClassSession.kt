package com.attendo.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * How a session came to exist.
 */
enum class SessionOrigin {
    /** Materialised from a [SessionPattern] by the generator. */
    GENERATED,

    /** Added by hand for a one-off class, including the landing slot of a reschedule. */
    ADHOC,
}

/**
 * The lifecycle of a single class occurrence. This enum alone decides whether a
 * session touches the attendance percentage.
 */
enum class SessionStatus {
    /**
     * Generated from the timetable but not yet reviewed. **Invisible to the
     * percentage** — neither numerator nor denominator. A day never reviewed
     * therefore costs nothing, which is the behaviour chosen for this app; the
     * dashboard surfaces a "days awaiting review" nudge instead of silently
     * counting them as absences.
     */
    SCHEDULED,

    /**
     * Confirmed to have happened, with per-unit attendance recorded. **The only
     * status that counts**, contributing `unitsPlanned` to the denominator and
     * `unitsAttended` to the numerator. A fully-absent class is HELD with zero
     * units attended — genuinely different from CANCELLED.
     */
    HELD,

    /**
     * Did not happen. Excluded from both numerator and denominator, because a
     * class that was never held cannot be one a student failed to attend.
     */
    CANCELLED,
}

/** Why a session was cancelled. Purely informational — none of these count. */
enum class CancellationReason {
    /** Faculty cancelled on the day. */
    FACULTY_CANCELLED,

    /** Institute holiday or closure. */
    HOLIDAY,

    /** Moved to another slot; the replacement session carries the attendance. */
    RESCHEDULED,

    OTHER,
}

/**
 * One concrete class occurrence on one date — the thing attendance is actually
 * recorded against.
 *
 * ### How this relates to patterns
 *
 * A [SessionPattern] is a rule; a ClassSession is an instance. The generator walks
 * the calendar and materialises one session per (pattern, date) pair, and
 * `(patternId, date)` is the idempotency key: re-running generation never
 * duplicates and — critically — never resurrects a session the student already
 * cancelled, because the existing row still occupies that key.
 *
 * [patternId] is null exactly when [origin] is ADHOC.
 *
 * ### How exceptions are represented
 *
 * Rather than a parallel "exceptions" table, each deviation is just a field on the
 * instance, which keeps every query one table wide:
 *
 * - **Cancelled** — [status] = CANCELLED with a [cancellationReason].
 * - **Shortened** — [unitsPlanned] drops below the pattern's `units` (a 2-hour
 *   block that ran one hour becomes 1 planned unit, so the missed hour is not
 *   held against the student). The mask is re-clamped on the way in.
 * - **Rescheduled** — modelled as two rows, not a moved one: the original is
 *   CANCELLED with reason RESCHEDULED and [movedToSessionId] pointing at a new
 *   ADHOC session on the new date, which back-references via [movedFromSessionId].
 *   Mutating the original's date instead would break the `(patternId, date)`
 *   idempotency key and let the next generation run recreate the class on the day
 *   it was moved off.
 * - **Extra class** — a plain ADHOC row with no pattern.
 */
data class ClassSession(
    val id: Long = 0L,
    val courseId: Long,
    /** Null for ad-hoc sessions; otherwise the pattern that generated this. */
    val patternId: Long? = null,
    val date: LocalDate,
    /** Slot start on a 24-hour clock, 9..17. */
    val startHour: Int,
    /** Units this occurrence was expected to run — below the pattern when shortened. */
    val unitsPlanned: Int,
    /** Which of those units were attended. Always stored clamped to [unitsPlanned]. */
    val unitsMask: UnitMask = UnitMask.NONE,
    val status: SessionStatus = SessionStatus.SCHEDULED,
    val cancellationReason: CancellationReason? = null,
    val kind: SessionKind = SessionKind.LECTURE,
    val room: String? = null,
    val note: String? = null,
    /** When the student confirmed this session; null while still SCHEDULED. */
    val approvedAt: Instant? = null,
    val lastEditedAt: Instant? = null,
    /** Set on a CANCELLED original, pointing at the session that replaced it. */
    val movedToSessionId: Long? = null,
    /** Set on the ADHOC replacement, pointing back at the cancelled original. */
    val movedFromSessionId: Long? = null,
) {
    init {
        require(TimeGrid.fits(startHour, unitsPlanned)) {
            "session $startHour +${unitsPlanned}h does not fit the teaching day"
        }
        require(unitsPlanned <= UnitMask.MAX_UNITS) {
            "unitsPlanned must be <= ${UnitMask.MAX_UNITS}"
        }
        require(status == SessionStatus.CANCELLED || cancellationReason == null) {
            "cancellationReason is only meaningful on a CANCELLED session"
        }
    }

    val origin: SessionOrigin
        get() = if (patternId == null) SessionOrigin.ADHOC else SessionOrigin.GENERATED

    val endHour: Int get() = startHour + unitsPlanned

    val slotLabel: String get() = TimeGrid.rangeLabel(startHour, unitsPlanned)

    /**
     * Attended units, masked to [unitsPlanned] so a stale bit from a since-shortened
     * session can never inflate the count past what was planned.
     */
    val unitsAttended: Int get() = unitsMask.countAttended(unitsPlanned)

    val unitsMissed: Int get() = unitsPlanned - unitsAttended

    /** Only HELD sessions move the needle — see [SessionStatus]. */
    val countsTowardAttendance: Boolean get() = status == SessionStatus.HELD

    val isCancelled: Boolean get() = status == SessionStatus.CANCELLED

    val isAwaitingReview: Boolean get() = status == SessionStatus.SCHEDULED

    /**
     * Whether the review workflow may ask about this class as of [today].
     *
     * [isAwaitingReview] says only that nobody has decided yet. That is not the same as owing a
     * decision: a SCHEDULED row dated after [today] is an *upcoming* class, and the app can hold
     * one because marking a future class and unmarking it again is how a student tries a
     * what-if — "where does my percentage land if I skip Thursday?". Reopening that row leaves
     * it SCHEDULED on a date that has not happened, and counting it as review work would put a
     * class nobody has attended yet into the queue of things to go and mark.
     *
     * Today is reviewable. A student marking the morning's lecture at lunchtime is the ordinary
     * case, so the day does not have to be over first.
     *
     * The status itself is untouched by any of this — see [SessionStatus]. This is a question
     * about a date, asked of a row whose status already answered the other half.
     */
    fun isReviewableOn(today: LocalDate): Boolean = isAwaitingReview && !date.isAfter(today)

    val wasRescheduledAway: Boolean
        get() = status == SessionStatus.CANCELLED &&
            cancellationReason == CancellationReason.RESCHEDULED

    /**
     * This session's own attendance share, or null when it does not count. A 2-hour
     * class with one hour attended yields exactly 50%.
     */
    val sessionPercent: Percent?
        get() = if (!countsTowardAttendance) null else Percent.ofRatio(unitsAttended, unitsPlanned)

    fun isUnitAttended(unitIndex: Int): Boolean =
        unitIndex < unitsPlanned && unitsMask.isAttended(unitIndex)

    /** Labels for the per-hour toggles, e.g. ["9–10 AM", "10–11 AM"]. */
    val unitLabels: List<String>
        get() = (0 until unitsPlanned).map { TimeGrid.unitLabel(startHour, it) }
}
