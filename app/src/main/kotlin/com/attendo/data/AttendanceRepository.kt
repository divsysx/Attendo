package com.attendo.data

import androidx.room.withTransaction
import com.attendo.core.data.SeedProposal
import com.attendo.core.engine.DayPlan
import com.attendo.core.engine.SessionGenerator
import com.attendo.core.engine.SessionOps
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.toEntity
import com.attendo.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The one place the app reads and writes attendance.
 *
 * Every mutation here is a thin wrapper over a pure function in
 * [com.attendo.core.engine.SessionOps]: the rule is computed in `:core`, where it is
 * unit-tested, and this class only persists the result. Nothing in this file decides
 * what a percentage means — it decides when rows are written.
 *
 * Reads come back as `:core` models, so no screen ever sees a `…Entity`.
 */
class AttendanceRepository(
    private val database: AttendoDatabase,
    private val now: () -> Instant = Instant::now,
) {

    private val courseDao = database.courseDao()
    private val patternDao = database.patternDao()
    private val sessionDao = database.sessionDao()

    /**
     * The wall-clock instant for row timestamps. Named so the backlog functions can take
     * a `now: LocalDateTime` parameter — the eligibility moment, which needs the time of
     * day — without shadowing the class's [now].
     */
    private fun nowInstant(): Instant = now()

    // ---- reads --------------------------------------------------------------

    val courses: Flow<List<Course>> =
        courseDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val activeCourses: Flow<List<Course>> =
        courseDao.observeActive().map { rows -> rows.map { it.toModel() } }

    val patterns: Flow<List<SessionPattern>> =
        patternDao.observeAll().map { rows -> rows.map { it.toModel() } }

    val sessions: Flow<List<ClassSession>> =
        sessionDao.observeAll().map { rows -> rows.map { it.toModel() } }

    fun course(id: Long): Flow<Course?> = courseDao.observeById(id).map { it?.toModel() }

    fun patternsForCourse(courseId: Long): Flow<List<SessionPattern>> =
        patternDao.observeForCourse(courseId).map { rows -> rows.map { it.toModel() } }

    fun sessionsForCourse(courseId: Long): Flow<List<ClassSession>> =
        sessionDao.observeForCourse(courseId).map { rows -> rows.map { it.toModel() } }

    fun sessionsOn(date: LocalDate): Flow<List<ClassSession>> =
        sessionDao.observeOn(date).map { rows -> rows.map { it.toModel() } }

    fun sessionsBetween(from: LocalDate, to: LocalDate): Flow<List<ClassSession>> =
        sessionDao.observeBetween(from, to).map { rows -> rows.map { it.toModel() } }

    suspend fun sessionById(id: Long): ClassSession? = sessionDao.byId(id)?.toModel()

    // ---- generation ---------------------------------------------------------

    /**
     * Materialises every session the patterns imply, from the start of term up to
     * [through] — normally today.
     *
     * Safe to call on every app open, which is the point: `(patternId, date)` is unique
     * and [com.attendo.data.db.SessionDao.insertGenerated] ignores conflicts, so a
     * session the student already cancelled stays cancelled rather than reappearing.
     *
     * Generation deliberately stops at today rather than filling the whole term. A future
     * class is not yet "awaiting review", and pre-creating one would both colour the
     * calendar for classes that have not happened and put them in the review backlog.
     *
     * @return how many rows were created.
     */
    suspend fun syncSessions(
        calendar: AcademicCalendar,
        through: LocalDate,
    ): Int = database.withTransaction {
        val patterns = patternDao.all().map { it.toModel() }
        if (patterns.isEmpty()) return@withTransaction 0

        val from = calendar.termStart
        val to = minOf(through, calendar.termEnd)
        if (to.isBefore(from)) return@withTransaction 0

        val existing = sessionDao.between(from, to).map { it.toModel() }
        val drafts = SessionGenerator.draftsFor(patterns, existing, from, to, calendar)
            .filterNot { it.repeatsARetiredClass(existing, patterns) }
        if (drafts.isEmpty()) return@withTransaction 0

        sessionDao.insertGenerated(drafts.map { it.toSession().toEntity() })
            .count { it != -1L }
    }

    /**
     * True when this draft would be the second row for a class the student has already marked.
     *
     * The generator keys on `(patternId, date)`, which is right for the ordinary run — two live
     * slots at the same hour are two slots, and merging them is not generation's business. It
     * leaves one gap, and only one: a *retroactive* mid-semester changeover. The old slot closes
     * the day before the new one opens and its unreviewed rows go with it, but a day the student
     * had already marked keeps its row — under the old pattern's id. The new slot then generates
     * the same lecture again at its new hour, and a two-hour class reads as four hours held.
     *
     * Three things have to be true at once, which is what keeps this from suppressing a real
     * class: the same course already has a **reviewed** class on this date, the slot that produced
     * it no longer applies on this date, and this draft's own slot opened only once that one had
     * closed. A second lecture of the same subject that has run all term alongside the moved one
     * fails the third test — its pattern started at the beginning of term, not at the changeover —
     * so it still generates.
     *
     * Hours are deliberately not compared. The specification's own example moves a lecture from
     * 9 AM to 11 AM, which does not overlap itself; what makes it one class rather than two is the
     * slot it came from, not the clock.
     */
    private fun SessionGenerator.Draft.repeatsARetiredClass(
        existing: List<ClassSession>,
        patterns: List<SessionPattern>,
    ): Boolean {
        val opensAt = patterns.firstOrNull { it.id == patternId }?.effectiveFrom ?: return false
        return existing.any { session ->
            if (session.courseId != courseId || session.date != date) return@any false
            if (session.status == SessionStatus.SCHEDULED) return@any false
            val closed = session.patternId
                ?.let { id -> patterns.firstOrNull { it.id == id } }
                ?.effectiveTo
                ?: return@any false
            date > closed && opensAt > closed
        }
    }

    /**
     * What one day looks like: the rows already stored plus the ones the timetable says
     * are still missing.
     *
     * Drafts are computed rather than inserted, so opening a future day in the review
     * screen shows what is coming without committing anything to the database.
     */
    suspend fun dayPlan(date: LocalDate, calendar: AcademicCalendar): DayPlan {
        val patterns = patternDao.all().map { it.toModel() }
        val stored = sessionDao.on(date).map { it.toModel() }
        return SessionGenerator.dayPlan(date, patterns, stored, calendar)
    }

    /**
     * Writes a draft out as a real (still unreviewed) session and returns its id.
     *
     * Idempotent on `(patternId, date)`: if the row already exists — a second tap landing
     * before the flow re-emitted, or a sync running in parallel — its existing id comes
     * back instead of a constraint failure, so the edit that prompted this still lands on
     * the right row.
     */
    suspend fun materialise(draft: SessionGenerator.Draft): Long = database.withTransaction {
        val inserted = sessionDao.insertIfAbsent(draft.toSession().toEntity())
        if (inserted != -1L) inserted
        else sessionDao.byPatternAndDate(draft.patternId, draft.date)?.id ?: -1L
    }

    // ---- marking ------------------------------------------------------------

    suspend fun toggleUnit(session: ClassSession, unitIndex: Int) =
        save(SessionOps.toggleUnit(session, unitIndex, now()))

    suspend fun setUnitAttended(session: ClassSession, unitIndex: Int, attended: Boolean) =
        save(SessionOps.setUnitAttended(session, unitIndex, attended, now()))

    suspend fun setFullyPresent(session: ClassSession) =
        save(SessionOps.setFullyPresent(session, now()))

    suspend fun setFullyAbsent(session: ClassSession) =
        save(SessionOps.setFullyAbsent(session, now()))

    suspend fun markPresent(session: ClassSession) = save(SessionOps.markPresent(session, now()))

    suspend fun markAbsent(session: ClassSession) = save(SessionOps.markAbsent(session, now()))

    suspend fun record(session: ClassSession, mask: UnitMask) =
        save(SessionOps.record(session, mask, now()))

    suspend fun approve(session: ClassSession) = save(SessionOps.approve(session, now()))

    suspend fun setNote(session: ClassSession, note: String?) =
        save(session.copy(note = note?.ifBlank { null }, lastEditedAt = now()))

    /**
     * The one-tap-a-day path: commits everything the day still owes, drafts included.
     *
     * Drafts arrive pre-filled fully present, so they are approved *before* insertion —
     * one write per row rather than an insert followed by an update.
     */
    suspend fun approveDay(date: LocalDate, calendar: AcademicCalendar): Int =
        database.withTransaction {
            val instant = now()
            val plan = dayPlan(date, calendar)

            val fresh = plan.missing.map { draft ->
                SessionOps.approve(draft.toSession(), instant).toEntity()
            }
            val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

            val pending = plan.awaitingReview.map { SessionOps.approve(it, instant).toEntity() }
            sessionDao.updateAll(pending)

            inserted + pending.size
        }

    /**
     * The missed-the-whole-day path: commits everything the day still owes as attended
     * by nobody — held, so the hours count on both sides of the fraction, with an empty
     * mask, so they count as missed.
     *
     * Only the day's own scheduled classes are touched, and only the ones still awaiting
     * a decision: a class already marked, cancelled or resized that day keeps what the
     * student already said about it.
     *
     * @return how many rows were written.
     */
    suspend fun markDayAbsent(date: LocalDate, calendar: AcademicCalendar): Int =
        database.withTransaction {
            val instant = now()
            val plan = dayPlan(date, calendar)

            val fresh = plan.missing.map { draft ->
                SessionOps.markAbsent(draft.toSession(), instant).toEntity()
            }
            val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

            val pending = plan.awaitingReview.map { SessionOps.markAbsent(it, instant).toEntity() }
            sessionDao.updateAll(pending)

            inserted + pending.size
        }

    // ---- exceptions ---------------------------------------------------------

    /**
     * Cancels the still-unmarked classes on a date that has just been declared a holiday.
     *
     * Marked classes are deliberately left alone. The student said they were there, and a
     * calendar edit made weeks later is not evidence that they were not — silently erasing
     * attended hours from a percentage is the one thing this app must never do.
     *
     * Cancelling rather than deleting is what makes the day legible afterwards: the
     * calendar cell reads "Holiday" instead of going blank.
     *
     * @return how many rows were cancelled.
     */
    suspend fun cancelDayAsHoliday(date: LocalDate): Int = database.withTransaction {
        val instant = now()
        val cancelled = sessionDao.on(date)
            .map { it.toModel() }
            .filter { it.isAwaitingReview }
            .map { SessionOps.cancel(it, CancellationReason.HOLIDAY, instant, it.note).toEntity() }
        sessionDao.updateAll(cancelled)
        cancelled.size
    }

    /**
     * The inverse, for a date that is a working day again.
     *
     * Only rows this app cancelled *as a holiday* are reopened, so a class the faculty
     * actually cancelled that day stays cancelled. Such a row can never be a reschedule
     * original either, so [SessionOps.reopen]'s invariant about orphaning a replacement
     * cannot be violated here.
     *
     * @return how many rows were reopened.
     */
    suspend fun restoreHoliday(date: LocalDate): Int = database.withTransaction {
        val instant = now()
        val reopened = sessionDao.on(date)
            .map { it.toModel() }
            .filter { it.isCancelled && it.cancellationReason == CancellationReason.HOLIDAY }
            .map { SessionOps.reopen(it, instant).toEntity() }
        sessionDao.updateAll(reopened)
        reopened.size
    }

    // ---- bulk actions over the review backlog -------------------------------

    /**
     * Everything past [through] that is still sitting unreviewed, drafts included.
     *
     * This is the raw material for the two bulk actions on the backlog card: the days a
     * student opens the app after a fortnight and owes decisions on. The window is the
     * caller's to apply — the dashboard already knows which dates are the student's to
     * mark and which pre-date their joining.
     */
    /**
     * The unreviewed classes and still-to-create drafts the backlog actions work on,
     * between [from] and [through] — restricted to what is *eligible* as of [now].
     *
     * Eligibility is [ClassSession.isBacklogEligibleOn]'s: a class dated today counts
     * only once its scheduled end time has passed, so a bulk sweep over a range whose
     * last date is today can never record a class as missed while it is still running
     * or still to come. Drafts are held to the same rule as stored rows — a class the
     * generator would create for a slot that has not finished yet is not backlog either.
     */
    suspend fun backlogBetween(
        from: LocalDate,
        through: LocalDate,
        calendar: AcademicCalendar,
        now: LocalDateTime,
    ): Pair<List<ClassSession>, List<SessionGenerator.Draft>> {
        val patterns = patternDao.all().map { it.toModel() }
        val existing = sessionDao.between(from, through).map { it.toModel() }
        val pending = existing.filter { it.isBacklogEligibleOn(now) }
        val drafts = SessionGenerator.draftsFor(patterns, existing, from, through, calendar)
            .filterNot { it.repeatsARetiredClass(existing, patterns) }
            .filter { it.toSession().isBacklogEligibleOn(now) }
        return pending to drafts
    }

    /**
     * The bulk action for "I was absent for all of it": every unreviewed *eligible* class
     * between [from] and [through], drafts included, becomes held-with-nobody-there.
     *
     * Nothing is ever *left out* of the percentage by this — each hour lands in both the
     * numerator's debt and the denominator, exactly as if it had been marked missed one
     * class at a time. That is the honest reading of an unmarked past for a student who
     * attended none of it, and it is why there is deliberately no "ignore these" option
     * anywhere in the app. Classes already decided — present, missed, cancelled — are
     * never touched, and neither is a class that has not finished yet.
     *
     * @return how many rows were written.
     */
    suspend fun markBacklogAbsent(
        from: LocalDate,
        through: LocalDate,
        calendar: AcademicCalendar,
        now: LocalDateTime,
    ): Int = database.withTransaction {
        val instant = nowInstant()
        val (pending, drafts) = backlogBetween(from, through, calendar, now)

        val fresh = drafts.map { SessionOps.markAbsent(it.toSession(), instant).toEntity() }
        val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

        val updated = SessionOps.markAllAbsent(pending, instant).map { it.toEntity() }
        sessionDao.updateAll(updated)

        inserted + updated.size
    }

    /**
     * The bulk action for "none of them happened": every unreviewed *eligible* class
     * between [from] and [through], drafts included, is cancelled with [reason].
     *
     * Cancelling removes hours from both sides of the fraction, which is the correct
     * reading of a stretch the department declared off — and the only bulk escape from
     * the denominator the app offers, precisely because here the hours were genuinely
     * never held. As with [markBacklogAbsent], only eligible classes are touched.
     *
     * @return how many rows were written.
     */
    suspend fun cancelBacklog(
        from: LocalDate,
        through: LocalDate,
        reason: CancellationReason,
        calendar: AcademicCalendar,
        now: LocalDateTime,
    ): Int = database.withTransaction {
        val instant = nowInstant()
        val (pending, drafts) = backlogBetween(from, through, calendar, now)

        val fresh = drafts.map {
            SessionOps.cancel(it.toSession(), reason, instant).toEntity()
        }
        val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

        val updated = SessionOps.cancelAll(pending, reason, instant).map { it.toEntity() }
        sessionDao.updateAll(updated)

        inserted + updated.size
    }

    suspend fun cancel(
        session: ClassSession,
        reason: CancellationReason = CancellationReason.FACULTY_CANCELLED,
        note: String? = session.note,
    ) = save(SessionOps.cancel(session, reason, now(), note))

    /**
     * Puts a session back into the pending state.
     *
     * A rescheduled original owns the replacement it created, so that row is deleted
     * first — reopening the original while its replacement still stood would count the
     * same class twice, which is the invariant [SessionOps.reopen] documents but cannot
     * enforce on its own.
     */
    suspend fun reopen(session: ClassSession) = database.withTransaction {
        session.movedToSessionId?.let { sessionDao.deleteById(it) }
        sessionDao.update(SessionOps.reopen(session, now()).toEntity())
    }

    suspend fun resize(session: ClassSession, unitsPlanned: Int) =
        save(SessionOps.resize(session, unitsPlanned, now()))

    suspend fun moveSlot(session: ClassSession, startHour: Int) =
        save(SessionOps.moveSlot(session, startHour, now()))

    /**
     * Moves a class to another day or time as two rows — cancelled original, ad-hoc
     * replacement — and closes the link between them once the replacement has an id.
     */
    suspend fun reschedule(
        session: ClassSession,
        newDate: LocalDate,
        newStartHour: Int,
        newUnits: Int = session.unitsPlanned,
        note: String? = null,
    ) = database.withTransaction {
        val moved = SessionOps.reschedule(
            session = session,
            newDate = newDate,
            newStartHour = newStartHour,
            newUnits = newUnits,
            now = now(),
            note = note,
        )
        val replacementId = sessionDao.insert(moved.replacement.toEntity())
        sessionDao.update(SessionOps.linkReplacement(moved.cancelledOriginal, replacementId).toEntity())
    }

    suspend fun addAdhoc(
        courseId: Long,
        date: LocalDate,
        startHour: Int,
        unitsPlanned: Int,
        kind: SessionKind = SessionKind.LECTURE,
        room: String? = null,
        note: String? = null,
    ): Long = sessionDao.insert(
        SessionOps.adhoc(courseId, date, startHour, unitsPlanned, kind, room, note).toEntity(),
    )

    /**
     * Removes a session outright.
     *
     * Only ad-hoc rows should reach this: deleting a generated row frees its
     * `(patternId, date)` slot and the next sync would recreate it, which is why the
     * review screen offers *cancel* for timetabled classes and *delete* only for extras.
     * A rescheduled original's replacement goes with it.
     */
    suspend fun deleteSession(session: ClassSession) = database.withTransaction {
        session.movedToSessionId?.let { sessionDao.deleteById(it) }
        sessionDao.deleteById(session.id)
    }

    // ---- courses ------------------------------------------------------------

    suspend fun addCourse(course: Course): Long = courseDao.insert(course.toEntity())

    suspend fun updateCourse(course: Course) = courseDao.update(course.toEntity())

    suspend fun setArchived(course: Course, archived: Boolean) =
        courseDao.update(course.copy(archived = archived).toEntity())

    /** Deletes a course and, by cascade, its patterns and every session it ever had. */
    suspend fun deleteCourse(course: Course) = courseDao.deleteById(course.id)

    // ---- patterns -----------------------------------------------------------

    suspend fun addPattern(pattern: SessionPattern): Long = patternDao.insert(pattern.toEntity())

    /**
     * Edits a slot without rewriting history.
     *
     * The old pattern is retired at [lastEffective] and a replacement starts the next
     * day, so sessions already recorded keep pointing at the pattern that actually
     * produced them. Unreviewed rows past the cut-off are dropped — they describe a
     * timetable that no longer applies — while anything already marked or cancelled stays
     * exactly as it was.
     */
    suspend fun replacePattern(
        old: SessionPattern,
        replacement: SessionPattern,
        lastEffective: LocalDate,
    ): Long = database.withTransaction {
        patternDao.update(old.retiredAfter(lastEffective).toEntity())
        sessionDao.deleteUnreviewedAfter(old.id, lastEffective)
        patternDao.insert(
            replacement.copy(
                id = 0L,
                courseId = old.courseId,
                effectiveFrom = maxOf(replacement.effectiveFrom, lastEffective.plusDays(1)),
            ).toEntity(),
        )
    }

    suspend fun updatePattern(pattern: SessionPattern) = patternDao.update(pattern.toEntity())

    /**
     * Retires a pattern from [lastEffective] onwards without replacing it — a slot that
     * has simply stopped happening.
     */
    suspend fun retirePattern(pattern: SessionPattern, lastEffective: LocalDate) =
        database.withTransaction {
            patternDao.update(pattern.retiredAfter(lastEffective).toEntity())
            sessionDao.deleteUnreviewedAfter(pattern.id, lastEffective)
        }

    /**
     * Deletes a pattern that should never have existed, along with the unreviewed
     * sessions it generated. Reviewed ones survive as history — see
     * [com.attendo.data.db.SessionDao.deleteUnreviewedForPattern].
     */
    suspend fun deletePattern(pattern: SessionPattern) = database.withTransaction {
        sessionDao.deleteUnreviewedForPattern(pattern.id)
        patternDao.deleteById(pattern.id)
    }

    // ---- seeding ------------------------------------------------------------

    /**
     * Creates the courses and patterns the student ticked in the seeder.
     *
     * Each course is inserted first so its patterns can be stamped with the id the
     * database assigned — the reason [SeedProposal.withCourseId] exists. The whole set
     * lands in one transaction: a half-seeded timetable is worse than none.
     *
     * [semesterId] is the term the new courses belong to. The seeder works from a printed
     * timetable and has no way to know it, and a course belonging to no semester is a course
     * every per-semester figure has to guess about, so the caller that *does* know says here.
     * Null leaves whatever the proposal carried, which is how the pre-semester seed screen
     * keeps working unchanged.
     *
     * @return the number of courses created.
     */
    suspend fun applySeed(
        proposals: List<SeedProposal>,
        semesterId: Long? = null,
    ): Int = database.withTransaction {
        proposals.forEach { proposal ->
            val course = proposal.course.copy(
                id = 0L,
                semesterId = semesterId ?: proposal.course.semesterId,
            )
            val courseId = courseDao.insert(course.toEntity())
            patternDao.insertAll(
                proposal.withCourseId(courseId).patterns.map { it.copy(id = 0L).toEntity() },
            )
        }
        proposals.size
    }

    private suspend fun save(session: ClassSession) = sessionDao.update(session.toEntity())
}
