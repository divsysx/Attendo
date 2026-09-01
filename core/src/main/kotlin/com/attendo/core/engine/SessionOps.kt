package com.attendo.core.engine

import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.TimeGrid
import com.attendo.core.model.UnitMask
import java.time.Instant
import java.time.LocalDate

/**
 * The edits the review screen and the history screen perform on a session, as pure
 * functions returning a new [ClassSession].
 *
 * These are separated from the database layer for one reason: the invariants that
 * keep percentages honest — re-clamping the mask when a class is shortened, keeping
 * a rescheduled class from being counted twice — are exactly the ones worth unit
 * testing, and they must not be reachable only through a DAO.
 *
 * Mask edits and status transitions are deliberately orthogonal. Toggling an hour
 * leaves a session SCHEDULED so the student can tweak the whole day before
 * committing it with [approve].
 */
object SessionOps {

    // ---- mask edits (status preserving) -------------------------------------

    fun setUnitAttended(
        session: ClassSession,
        unitIndex: Int,
        attended: Boolean,
        now: Instant,
    ): ClassSession {
        require(unitIndex in 0 until session.unitsPlanned) {
            "unit $unitIndex outside session of ${session.unitsPlanned} unit(s)"
        }
        return session.copy(
            unitsMask = session.unitsMask.with(unitIndex, attended),
            lastEditedAt = now,
        )
    }

    fun toggleUnit(session: ClassSession, unitIndex: Int, now: Instant): ClassSession =
        setUnitAttended(session, unitIndex, !session.isUnitAttended(unitIndex), now)

    fun setFullyPresent(session: ClassSession, now: Instant): ClassSession =
        session.copy(unitsMask = UnitMask.allPresent(session.unitsPlanned), lastEditedAt = now)

    fun setFullyAbsent(session: ClassSession, now: Instant): ClassSession =
        session.copy(unitsMask = UnitMask.NONE, lastEditedAt = now)

    // ---- status transitions -------------------------------------------------

    /**
     * Commits a session as having happened, keeping whatever mask it carries. This
     * is what "Approve All" applies to every pending row.
     */
    fun approve(session: ClassSession, now: Instant): ClassSession = session.copy(
        status = SessionStatus.HELD,
        cancellationReason = null,
        approvedAt = session.approvedAt ?: now,
        lastEditedAt = now,
    )

    /** Records a definite attendance state in one step, for the history screen. */
    fun record(
        session: ClassSession,
        mask: UnitMask,
        now: Instant,
    ): ClassSession = session.copy(
        unitsMask = mask.clampedTo(session.unitsPlanned),
        status = SessionStatus.HELD,
        cancellationReason = null,
        approvedAt = session.approvedAt ?: now,
        lastEditedAt = now,
    )

    fun markAbsent(session: ClassSession, now: Instant): ClassSession =
        record(session, UnitMask.NONE, now)

    fun markPresent(session: ClassSession, now: Instant): ClassSession =
        record(session, UnitMask.allPresent(session.unitsPlanned), now)

    /**
     * The class did not happen. Excluded from the percentage entirely — the mask is
     * cleared so a later reopen cannot silently reinstate a stale "present".
     */
    fun cancel(
        session: ClassSession,
        reason: CancellationReason = CancellationReason.FACULTY_CANCELLED,
        now: Instant,
        note: String? = session.note,
    ): ClassSession = session.copy(
        status = SessionStatus.CANCELLED,
        cancellationReason = reason,
        unitsMask = UnitMask.NONE,
        approvedAt = session.approvedAt ?: now,
        lastEditedAt = now,
        note = note,
    )

    /**
     * Returns a session to the pending state, pre-filled present again.
     *
     * Callers must delete any [ClassSession.movedToSessionId] replacement first —
     * reopening a rescheduled original while its replacement still exists would
     * count the class twice.
     */
    fun reopen(session: ClassSession, now: Instant): ClassSession = session.copy(
        status = SessionStatus.SCHEDULED,
        cancellationReason = null,
        unitsMask = UnitMask.allPresent(session.unitsPlanned),
        approvedAt = null,
        lastEditedAt = now,
        movedToSessionId = null,
    )

    // ---- structural edits ---------------------------------------------------

    /**
     * The class ran short (or long). The mask is re-clamped to the new length, which
     * is the invariant that stops a 2-hour block marked fully present and then cut
     * to one hour from reporting 2 of 1 units attended.
     */
    fun resize(session: ClassSession, unitsPlanned: Int, now: Instant): ClassSession {
        require(TimeGrid.fits(session.startHour, unitsPlanned)) {
            "${session.startHour} +${unitsPlanned}h does not fit the teaching day"
        }
        return session.copy(
            unitsPlanned = unitsPlanned,
            unitsMask = session.unitsMask.clampedTo(unitsPlanned),
            lastEditedAt = now,
        )
    }

    /** Moves a session within its own day, e.g. a lecture pushed an hour later. */
    fun moveSlot(session: ClassSession, startHour: Int, now: Instant): ClassSession {
        require(TimeGrid.fits(startHour, session.unitsPlanned)) {
            "$startHour +${session.unitsPlanned}h does not fit the teaching day"
        }
        return session.copy(startHour = startHour, lastEditedAt = now)
    }

    /**
     * A class shifted to another day or time.
     *
     * Represented as two rows rather than one moved row: the original is cancelled
     * with reason RESCHEDULED, and an ad-hoc replacement is created at the new slot.
     * Mutating the original's date would break the generator's `(patternId, date)`
     * key and let the next generation run recreate the class on the day it was
     * moved off.
     *
     * The replacement has no id until inserted, so the caller must close the link
     * with [linkReplacement] once the database assigns one.
     */
    fun reschedule(
        session: ClassSession,
        newDate: LocalDate,
        newStartHour: Int,
        newUnits: Int = session.unitsPlanned,
        now: Instant,
        note: String? = null,
    ): Reschedule {
        require(TimeGrid.fits(newStartHour, newUnits)) {
            "$newStartHour +${newUnits}h does not fit the teaching day"
        }
        val cancelled = cancel(
            session = session,
            reason = CancellationReason.RESCHEDULED,
            now = now,
            note = note ?: session.note,
        )
        val replacement = ClassSession(
            courseId = session.courseId,
            patternId = null, // ad-hoc: it is not what the timetable says
            date = newDate,
            startHour = newStartHour,
            unitsPlanned = newUnits,
            unitsMask = UnitMask.allPresent(newUnits),
            status = SessionStatus.SCHEDULED,
            kind = session.kind,
            room = session.room,
            note = note,
            movedFromSessionId = session.id.takeIf { it != 0L },
        )
        return Reschedule(cancelled, replacement)
    }

    /** Completes the back-link once the replacement row has an id. */
    fun linkReplacement(cancelledOriginal: ClassSession, replacementId: Long): ClassSession =
        cancelledOriginal.copy(movedToSessionId = replacementId)

    /**
     * A one-off class with no pattern behind it — an extra lecture, a makeup class,
     * or a slot the timetable does not describe.
     */
    fun adhoc(
        courseId: Long,
        date: LocalDate,
        startHour: Int,
        unitsPlanned: Int,
        kind: SessionKind = SessionKind.LECTURE,
        room: String? = null,
        note: String? = null,
    ): ClassSession = ClassSession(
        courseId = courseId,
        patternId = null,
        date = date,
        startHour = startHour,
        unitsPlanned = unitsPlanned,
        unitsMask = UnitMask.allPresent(unitsPlanned),
        status = SessionStatus.SCHEDULED,
        kind = kind,
        room = room,
        note = note,
    )

    /** The bulk action behind "Approve All": commits every still-pending row. */
    fun approveAll(sessions: List<ClassSession>, now: Instant): List<ClassSession> =
        sessions.map { if (it.isAwaitingReview) approve(it, now) else it }

    /**
     * The bulk action behind "I missed the whole day": records every still-pending row as
     * held but unattended. Rows already dealt with — attended, cancelled, anything — are
     * left exactly as they are; a decision already made is not overwritten by a bulk one.
     */
    fun markAllAbsent(sessions: List<ClassSession>, now: Instant): List<ClassSession> =
        sessions.map { if (it.isAwaitingReview) markAbsent(it, now) else it }

    /**
     * The bulk action behind "cancel everything still pending": cancels every
     * still-pending row with [reason]. Like [markAllAbsent], rows already dealt with keep
     * whatever the student said about them.
     */
    fun cancelAll(
        sessions: List<ClassSession>,
        reason: CancellationReason,
        now: Instant,
    ): List<ClassSession> = sessions.map { if (it.isAwaitingReview) cancel(it, reason, now) else it }
}

/** The two rows a reschedule produces. */
data class Reschedule(
    /** The original, now CANCELLED with reason RESCHEDULED. */
    val cancelledOriginal: ClassSession,
    /** The new ad-hoc session carrying the attendance; not yet persisted. */
    val replacement: ClassSession,
)
