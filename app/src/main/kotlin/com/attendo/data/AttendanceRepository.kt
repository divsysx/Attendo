package com.attendo.data

import androidx.room.withTransaction
import com.attendo.core.data.MigrationPlan
import com.attendo.core.data.SeedProposal
import com.attendo.core.engine.DayPlan
import com.attendo.core.engine.SessionGenerator
import com.attendo.core.engine.SessionOps
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.CourseEntity
import com.attendo.data.db.PatternEntity
import com.attendo.data.db.SessionEntity
import com.attendo.data.db.toEntity
import com.attendo.data.db.toModel
import com.attendo.data.sync.AttendanceSyncStore
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
     * The delete handoff: rows go to it before they are removed, so the cloud can be told.
     *
     * Constructed here rather than injected because it is the local half of the sync, and
     * this class is the only thing that removes attendance rows. A delete that skipped it
     * would be reversed by the next pull — the cloud would still hold the row, and applying
     * it would put it back — which is why every `delete` below reads first and deletes
     * second.
     */
    private val syncStore by lazy { AttendanceSyncStore(database) }

    /**
     * The wall-clock instant for row timestamps. Named so the backlog functions can take
     * a `now: LocalDateTime` parameter — the eligibility moment, which needs the time of
     * day — without shadowing the class's [now].
     */
    private fun nowInstant(): Instant = now()

    // ---- what a local write owes the cloud ----------------------------------

    /**
     * Every write below stamps two columns the rest of the app never reads.
     *
     * `clientUpdatedAt` is the device-clock instant of the edit, and it is the only thing
     * that decides, here and on the server, whether this write beats what the cloud holds.
     * `dirty` says the row has an edit the cloud has not confirmed, and it is what an
     * incremental push reads: after the first push of an install, `dirty` *is* the outbox.
     * A write that set neither would be a write that never reached the cloud and never
     * would, silently, for the life of the account.
     *
     * They are stamped here, at the one boundary every attendance write passes through,
     * rather than in `toEntity()` — the `:core` models carry no cloud identity by design,
     * and `EntitySyncColumnsTest` pins that a model rebuilt into an entity reads as "never
     * synced". Stamping the mapper would put a cloud identity on rows that never had one.
     */
    private fun CourseEntity.owed(at: Long): CourseEntity =
        copy(clientUpdatedAt = at, dirty = true)

    private fun PatternEntity.owed(at: Long): PatternEntity =
        copy(clientUpdatedAt = at, dirty = true)

    private fun SessionEntity.owed(at: Long): SessionEntity =
        copy(clientUpdatedAt = at, dirty = true)

    /**
     * Writes edited rows back without losing the identity the cloud knows them by.
     *
     * `@Update` replaces the whole row, and an entity rebuilt from a `:core` model has no
     * `cloudId` — so a plain update would null it, and the next push would upload the row
     * as a new one under a fresh id. Two rows in the cloud, one class on the phone. The
     * identity is read back from the table and carried forward, which is the only thing an
     * edit needs from the row it replaces. (`deletedAt` needs nothing: a locally present
     * row is never tombstoned — a delete removes it, and a tombstone that arrives from the
     * cloud removes it too — so the column is null on every row these can be handed.)
     *
     * In a transaction because the read and the write must see the same table: the push
     * path assigns identities too, and an assignment landing between these two statements
     * would be overwritten with the null this read did not see.
     */
    private suspend fun updateCourses(rows: List<CourseEntity>, at: Long = nowInstant().toEpochMilli()) =
        database.withTransaction {
            if (rows.isEmpty()) return@withTransaction
            val identities = courseDao.cloudIdentities().associate { it.id to it.cloudId }
            courseDao.updateAll(rows.map { it.copy(cloudId = identities[it.id]).owed(at) })
        }

    private suspend fun updatePatterns(rows: List<PatternEntity>, at: Long = nowInstant().toEpochMilli()) =
        database.withTransaction {
            if (rows.isEmpty()) return@withTransaction
            val identities = patternDao.cloudIdentities().associate { it.id to it.cloudId }
            patternDao.updateAll(rows.map { it.copy(cloudId = identities[it.id]).owed(at) })
        }

    private suspend fun updateSessions(rows: List<SessionEntity>, at: Long = nowInstant().toEpochMilli()) =
        database.withTransaction {
            if (rows.isEmpty()) return@withTransaction
            val identities = sessionDao.cloudIdentities().associate { it.id to it.cloudId }
            sessionDao.updateAll(rows.map { it.copy(cloudId = identities[it.id]).owed(at) })
        }

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

        // Stamped like every other write here, and for a reason that only shows up on the
        // second device: an unmarked session is data — the timetable's own record that the
        // class was held and nobody has said what happened yet. Left unstamped it is invisible
        // to the outbox after this install's initial push, so the term's unmarked days would
        // exist on the phone that generated them and nowhere else, and the web client reading
        // the same account would see a timetable with holes in it. `insertGenerated` ignores
        // conflicts, so re-running this over an existing row still writes nothing.
        val stamp = nowInstant().toEpochMilli()
        sessionDao.insertGenerated(drafts.map { it.toSession().toEntity().owed(stamp) })
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
        val inserted = sessionDao.insertIfAbsent(draft.toSession().toEntity().owed(nowInstant().toEpochMilli()))
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
            val stamp = instant.toEpochMilli()
            val plan = dayPlan(date, calendar)

            val fresh = plan.missing.map { draft ->
                SessionOps.approve(draft.toSession(), instant).toEntity()
            }.map { it.owed(stamp) }
            val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

            val pending = plan.awaitingReview.map { SessionOps.approve(it, instant).toEntity() }
            updateSessions(pending, stamp)

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
            val stamp = instant.toEpochMilli()
            val plan = dayPlan(date, calendar)

            val fresh = plan.missing.map { draft ->
                SessionOps.markAbsent(draft.toSession(), instant).toEntity()
            }.map { it.owed(stamp) }
            val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

            val pending = plan.awaitingReview.map { SessionOps.markAbsent(it, instant).toEntity() }
            updateSessions(pending, stamp)

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
        updateSessions(cancelled, instant.toEpochMilli())
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
        updateSessions(reopened, instant.toEpochMilli())
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
        val stamp = instant.toEpochMilli()
        val (pending, drafts) = backlogBetween(from, through, calendar, now)

        val fresh = drafts.map { SessionOps.markAbsent(it.toSession(), instant).toEntity().owed(stamp) }
        val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

        val updated = SessionOps.markAllAbsent(pending, instant).map { it.toEntity() }
        updateSessions(updated, stamp)

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
        val stamp = instant.toEpochMilli()
        val (pending, drafts) = backlogBetween(from, through, calendar, now)

        val fresh = drafts.map {
            SessionOps.cancel(it.toSession(), reason, instant).toEntity()
        }.map { it.owed(stamp) }
        val inserted = sessionDao.insertGenerated(fresh).count { it != -1L }

        val updated = SessionOps.cancelAll(pending, reason, instant).map { it.toEntity() }
        updateSessions(updated, stamp)

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
        val at = now()
        session.movedToSessionId?.let { id ->
            sessionDao.byId(id)?.let { syncStore.recordDeleted(AttendanceSyncStore.DeletedRows(sessions = listOf(it)), at) }
            sessionDao.deleteById(id)
        }
        updateSessions(listOf(SessionOps.reopen(session, at).toEntity()), at.toEpochMilli())
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
        val instant = now()
        val stamp = instant.toEpochMilli()
        val moved = SessionOps.reschedule(
            session = session,
            newDate = newDate,
            newStartHour = newStartHour,
            newUnits = newUnits,
            now = instant,
            note = note,
        )
        val replacementId = sessionDao.insert(moved.replacement.toEntity().owed(stamp))
        updateSessions(
            listOf(SessionOps.linkReplacement(moved.cancelledOriginal, replacementId).toEntity()),
            stamp,
        )
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
        SessionOps.adhoc(courseId, date, startHour, unitsPlanned, kind, room, note)
            .toEntity()
            .owed(nowInstant().toEpochMilli()),
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
        val at = now()
        // Read before removing: a tombstone carries the row's own columns, so a delete that
        // did not hand them over would leave the row alive in the cloud and the next pull
        // would put it straight back on this phone.
        val removed = listOfNotNull(
            session.movedToSessionId?.let { sessionDao.byId(it) },
            sessionDao.byId(session.id),
        )
        syncStore.recordDeleted(AttendanceSyncStore.DeletedRows(sessions = removed), at)
        session.movedToSessionId?.let { sessionDao.deleteById(it) }
        sessionDao.deleteById(session.id)
    }

    // ---- courses ------------------------------------------------------------

    suspend fun addCourse(course: Course): Long =
        courseDao.insert(course.toEntity().owed(nowInstant().toEpochMilli()))

    suspend fun updateCourse(course: Course) = updateCourses(listOf(course.toEntity()))

    /**
     * Sets one target on every course the student is still tracking, and answers how many.
     *
     * The count is returned rather than left to the caller so the number the confirmation
     * dialog promised and the number actually written come from the same read — a count
     * taken by the UI a moment earlier could describe a list that has since changed.
     *
     * Archived courses are left alone (see [CourseDao.active]). The write goes through
     * [updateCourses], so every affected row is stamped dirty and carries its cloud
     * identity forward: a bulk target change syncs exactly like six individual ones.
     */
    suspend fun setTargetForActiveCourses(target: Percent): Int {
        val rows = courseDao.active()
        if (rows.isEmpty()) return 0
        updateCourses(rows.map { it.copy(targetBasisPoints = target.basisPoints) })
        return rows.size
    }

    suspend fun setArchived(course: Course, archived: Boolean) =
        updateCourses(listOf(course.copy(archived = archived).toEntity()))

    /**
     * Deletes a course and, by cascade, its patterns and every session it ever had.
     *
     * The cascade is exactly why this reads all three tables first. The local delete is
     * one statement, but the cloud has to be told about three tables' worth of rows, and a
     * row the cloud still holds is a row the next pull brings back — a course the student
     * deleted returning from the dead, with its attendance.
     */
    suspend fun deleteCourse(course: Course) = database.withTransaction {
        val at = now()
        syncStore.recordDeleted(
            AttendanceSyncStore.DeletedRows(
                courses = listOfNotNull(courseDao.byId(course.id)),
                patterns = patternDao.all().filter { it.courseId == course.id },
                sessions = sessionDao.forCourse(course.id),
            ),
            at,
        )
        courseDao.deleteById(course.id)
    }

    // ---- patterns -----------------------------------------------------------

    /**
     * Hands a body of rows to the cloud as deletions, before the caller removes them locally.
     *
     * The rollover path's equivalent of the read-before-delete [deleteCourse] does: a term
     * being cleared is thousands of rows across all four tables, and every one of them that the
     * cloud still holds is a row the next pull puts back — including the semester itself, which
     * would arrive beside the new one and give the student two courses of every kind.
     *
     * Exposed rather than duplicated because [AttendanceSyncStore] is this class's, and because
     * the ordering that makes it safe — tombstones committed with the delete, in one
     * transaction — has to be the caller's too. See [com.attendo.data.RolloverRepository].
     */
    suspend fun recordRemoval(rows: AttendanceSyncStore.DeletedRows, at: Instant) =
        syncStore.recordDeleted(rows, at)

    suspend fun addPattern(pattern: SessionPattern): Long =
        patternDao.insert(pattern.toEntity().owed(nowInstant().toEpochMilli()))

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
        val at = now()
        val stamp = at.toEpochMilli()
        // The dropped rows are unreviewed and were generated from a slot that is being
        // rewritten, so a device that has not seen this edit would otherwise regenerate
        // them on its next pass. Read first — see [deleteCourse].
        syncStore.recordDeleted(
            AttendanceSyncStore.DeletedRows(sessions = sessionDao.unreviewedAfter(old.id, lastEffective)),
            at,
        )
        updatePatterns(listOf(old.retiredAfter(lastEffective).toEntity()), stamp)
        sessionDao.deleteUnreviewedAfter(old.id, lastEffective)
        patternDao.insert(
            replacement.copy(
                id = 0L,
                courseId = old.courseId,
                effectiveFrom = maxOf(replacement.effectiveFrom, lastEffective.plusDays(1)),
            ).toEntity().owed(stamp),
        )
    }

    suspend fun updatePattern(pattern: SessionPattern) = updatePatterns(listOf(pattern.toEntity()))

    /**
     * Retires a pattern from [lastEffective] onwards without replacing it — a slot that
     * has simply stopped happening.
     */
    suspend fun retirePattern(pattern: SessionPattern, lastEffective: LocalDate) =
        database.withTransaction {
            val at = now()
            syncStore.recordDeleted(
                AttendanceSyncStore.DeletedRows(sessions = sessionDao.unreviewedAfter(pattern.id, lastEffective)),
                at,
            )
            updatePatterns(listOf(pattern.retiredAfter(lastEffective).toEntity()), at.toEpochMilli())
            sessionDao.deleteUnreviewedAfter(pattern.id, lastEffective)
        }

    /**
     * Deletes a pattern that should never have existed, along with the unreviewed
     * sessions it generated. Reviewed ones survive as history — see
     * [com.attendo.data.db.SessionDao.deleteUnreviewedForPattern].
     */
    suspend fun deletePattern(pattern: SessionPattern) = database.withTransaction {
        val at = now()
        syncStore.recordDeleted(
            AttendanceSyncStore.DeletedRows(
                patterns = listOfNotNull(patternDao.byId(pattern.id)),
                sessions = sessionDao.unreviewedForPattern(pattern.id),
            ),
            at,
        )
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
        val stamp = nowInstant().toEpochMilli()
        proposals.forEach { proposal ->
            val course = proposal.course.copy(
                id = 0L,
                semesterId = semesterId ?: proposal.course.semesterId,
            )
            val courseId = courseDao.insert(course.toEntity().owed(stamp))
            patternDao.insertAll(
                proposal.withCourseId(courseId).patterns.map { it.copy(id = 0L).toEntity().owed(stamp) },
            )
        }
        proposals.size
    }

    private suspend fun save(session: ClassSession) = updateSessions(listOf(session.toEntity()))

    // ---- timetable revisions ------------------------------------------------

    /**
     * Carries out one timetable migration — see [com.attendo.core.data.TimetableMigration].
     *
     * Every write goes through a primitive that already exists, and every one of them is the
     * non-destructive half of the pair: a withdrawn slot is *retired* rather than deleted, so
     * the sessions it produced keep pointing at it and only unreviewed future rows go; a new
     * slot is inserted beside the old one rather than replacing it; a course is renamed, never
     * recreated. Nothing here deletes a course or a marked session, which is the property the
     * whole migration exists to preserve.
     *
     * No enclosing transaction. Each change is one course's worth of writes, committed on its
     * own, so a revision interrupted halfway leaves some courses migrated and the rest still to
     * do — and re-running it finishes the job, because the plan is the difference between what
     * the student has and what the timetable says, which the courses already done no longer
     * contribute to.
     *
     * @return how many courses were changed.
     */
    suspend fun applyTimetableMigration(plan: MigrationPlan): Int {
        var changed = 0
        plan.changes.filterNot { it.isEmpty }.forEach { change ->
            val course = courseDao.byId(change.courseId) ?: return@forEach
            if (change.renamed) {
                updateCourses(
                    listOf(course.copy(code = change.toCode, name = change.toName)),
                )
            }
            change.retired.forEach { pattern ->
                // Clamped so a slot added after the revision date closes on the day it opened
                // rather than before it — SessionPattern rejects an end before its start, and a
                // pattern the student added last week is not one this revision can un-happen.
                retirePattern(pattern, maxOf(plan.effectiveFrom.minusDays(1), pattern.effectiveFrom))
            }
            change.added.forEach { pattern ->
                patternDao.insert(pattern.copy(id = 0L, courseId = change.courseId).toEntity()
                    .owed(nowInstant().toEpochMilli()))
            }
            changed++
        }
        return changed
    }
}
