package com.attendo.data

import androidx.room.withTransaction
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.Semester
import com.attendo.core.rollover.SemesterRecordDoc
import com.attendo.core.rollover.SemesterRecordRenderer
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.SemesterEntity
import com.attendo.data.db.toEntity
import com.attendo.data.db.toModel
import com.attendo.data.sync.AttendanceSyncStore
import java.time.Instant
import java.time.LocalDate

/**
 * What starting a new semester did.
 *
 * Mirrors [RestoreResult]: a failure inside the transaction leaves the install exactly as it was
 * ([dataUnchanged] = true); a failure *after* the rows were committed — the settings write — has
 * already changed the data and says so ([dataUnchanged] = false). That distinction is the one
 * piece of information a student standing in front of a half-finished rollover actually needs.
 */
sealed interface RolloverResult {

    /** The previous semester was cleared and the bundled one is now the only active semester. */
    data object Done : RolloverResult

    /** The rollover failed. See [dataUnchanged] for whether anything was written. */
    data class Failed(val message: String, val dataUnchanged: Boolean) : RolloverResult
}

/**
 * The one place a semester is established, exported, or rolled over.
 *
 * Attendo keeps exactly one active semester. Three things can happen to it, and this class is the
 * boundary for all three:
 *
 * 1. **Establish silently** — a fresh or seeded install has no semester row. There is nothing to
 *    lose, so the bundled one is adopted without a prompt and the courses that predate it are
 *    taken in. This is the body of the old `SemesterRepository.currentOrEstablish`, generalised
 *    from "derive the semester from the settings calendar" to "take the bundled semester the
 *    build carries" — the detection that decides *whether* to call this lives in
 *    [com.attendo.core.rollover.detectRollover].
 * 2. **Snapshot for a record** — a pure read of the still-present previous semester, handed to
 *    [SemesterRecordRenderer] to become a PDF. It touches nothing; a snapshot never clears.
 * 3. **Roll over** — the destructive one. The previous semester's courses, patterns, sessions and
 *    semester rows are deleted and the bundled semester is put in their place, all in one
 *    transaction; the settings are then reset to the bundled term with the student's targets and
 *    name preserved. This mirrors [BackupRepository.write]'s discipline for exactly the same
 *    reason: a half-cleared database is the one outcome worth designing against.
 *
 * ### Why the transaction and the settings are separate
 *
 * Room's transaction covers the database rows. The settings live in SharedPreferences, which is
 * not transactional with Room — so the settings are written *after* the transaction commits, the
 * same ordering [BackupRepository.write] uses. A throw inside the transaction rolls the rows back
 * and leaves the settings untouched; a settings failure after a successful row write is reported
 * with `dataUnchanged = false`, because the data did change even though the term dates did not.
 *
 * ### What is preserved, replaced and reset
 *
 * Preserved across the rollover (copied into the new settings): `displayName`, `overallTarget`,
 * `courseTarget`, `section`, `batch`. Replaced: the calendar (→ the bundled term). Reset:
 * `attendanceStart` (→ university basis, no joining date — a new semester starts counting from
 * its own first day). Not preserved: the previous holidays and working Saturdays — the bundled
 * default carries none, and the student re-adds the ones that apply to the new term in Settings.
 */
class RolloverRepository(
    private val database: AttendoDatabase,
    private val settings: SettingsStore,
    private val attendance: AttendanceRepository,
    private val clock: () -> LocalDate = LocalDate::now,
    private val now: () -> Instant = Instant::now,
) {

    private val courseDao = database.courseDao()
    private val patternDao = database.patternDao()
    private val sessionDao = database.sessionDao()
    private val semesterDao = database.semesterDao()

    /**
     * Stamps a freshly established semester as something the cloud has not been told about.
     *
     * The two columns are the outbox: `clientUpdatedAt` is what decides which device's copy of
     * this term wins, and `dirty` is what an incremental push reads once this account's initial
     * push is done. A semester inserted with neither is a row the sync can never see — the
     * student's new term would exist on one phone and nowhere else, and the second phone would
     * refuse the pull with a uniqueness violation instead of adopting it. See
     * [com.attendo.data.sync.AttendanceSyncStore.applySemesters].
     *
     * `cloudId` is deliberately left null: identity is assigned at the first push, which is the
     * same rule every other attendance row follows.
     */
    private fun SemesterEntity.owed(at: Instant): SemesterEntity =
        copy(clientUpdatedAt = at.toEpochMilli(), dirty = true)

    // ---- establishing the first semester ------------------------------------

    /**
     * Adopts [bundled] as the active semester when there is nothing to lose.
     *
     * Called only when [com.attendo.core.rollover.detectRollover] returned
     * [com.attendo.core.rollover.RolloverDecision.EstablishSilently] — i.e. there is no current
     * semester. Idempotent: an install that already holds the bundled term gets it back
     * untouched, and orphans are re-adopted either way, because a course with no semester on an
     * install that now has one is a question this answers the same way every time.
     *
     * [courseDao.adoptOrphans] is called with the established id even when the semester already
     * existed: it is a no-op when there are no orphans, and the one place a course written by the
     * seeder or editor without a semester id is taken into the term it was actually taught in.
     */
    suspend fun establishSilently(bundled: Semester): Semester = database.withTransaction {
        val existing = semesterDao.current()?.toModel()
        val established = if (existing != null && existing.isSameTermAs(bundled)) {
            existing
        } else {
            val id = semesterDao.insert(bundled.copy(id = 0L, archived = false).toEntity().owed(now()))
            bundled.copy(id = id, archived = false)
        }
        courseDao.adoptOrphans(established.id)
        established
    }

    // ---- the read-only record ------------------------------------------------

    /**
     * Builds the [SemesterRecordDoc] for [semester] from the data still on the device.
     *
     * A pure read: it never writes, never clears, and is safe to call whether or not a rollover
     * follows. Every figure comes from [SemesterRecordRenderer.render], which delegates to
     * [com.attendo.core.engine.AttendanceEngine.semesterStats] — the same source the dashboard
     * reads — so the record is a printed copy of the screen rather than a second calculation.
     *
     * [today] bounds which sessions are reviewable (a future class is not yet "awaiting review"),
     * and [exportedOn] is the date stamped on the record. Both are the export day: a finished term
     * has no future sessions, so every class is in the past and counts.
     */
    suspend fun snapshotFor(semester: Semester): SemesterRecordDoc {
        val courses = courseDao.all().map { it.toModel() }
        val sessions = sessionDao.all().map { it.toModel() }
        val current = settings.current
        val today = clock()
        return SemesterRecordRenderer.render(
            semester = semester,
            courses = courses,
            sessions = sessions,
            overallTarget = current.overallTarget,
            start = current.attendanceStart,
            today = today,
            displayName = current.displayName,
            section = current.section,
            batch = current.batch,
            exportedOn = today,
        )
    }

    // ---- the destructive rollover -------------------------------------------

    /**
     * Clears the previous semester and establishes [bundled] in its place.
     *
     * The delete+insert runs in one [database.withTransaction]; a throw rolls it back and leaves
     * everything as it was. Settings are written only after the transaction commits, because
     * SharedPreferences is not transactional with Room — the same rule
     * [BackupRepository.write] follows, for the same reason: a failed row write must not leave
     * last term's holidays applied to a semester that was never established.
     *
     * The caller is responsible for having offered an export first; this method clears without
     * further prompt. [snapshotFor] is the read that feeds that export, and it must run before
     * this — once the transaction commits, the previous semester's data is gone.
     */
    suspend fun rolloverTo(bundled: Semester): RolloverResult {
        val preserved = settings.current
        val bundledCalendar = bundled.calendarWith()

        val failure = runCatching {
            database.withTransaction {
                // Read first, delete second. A `DELETE` cannot report what it removed, and every
                // one of these rows has a cloud id — so without the read the cloud keeps the whole
                // previous term, and the next pull (or a second phone, or the web client) brings
                // it back beside the new one. The student would have two of every course and a
                // history that counted last term twice.
                attendance.recordRemoval(
                    AttendanceSyncStore.DeletedRows(
                        semesters = semesterDao.all(),
                        courses = courseDao.all(),
                        patterns = patternDao.all(),
                        sessions = sessionDao.all(),
                    ),
                    now(),
                )

                // Semesters last on the way out, first on the way in: courses reference them, and
                // the order here is the only thing enforcing that — see CourseEntity.semesterId,
                // deliberately not a foreign key so a cascade can never wipe a term's history.
                sessionDao.deleteAll()
                patternDao.deleteAll()
                courseDao.deleteAll()
                semesterDao.deleteAll()

                semesterDao.insert(bundled.copy(id = 0L, archived = false).toEntity().owed(now()))

                // Read back inside the transaction so a mismatch can still be undone by throwing.
                val active = semesterDao.current()?.toModel()
                check(active != null && active.isSameTermAs(bundled)) {
                    "the new semester was not established as the active one"
                }
            }
        }.exceptionOrNull()

        if (failure != null) {
            return RolloverResult.Failed(
                message = "The new semester could not be started, so your previous data was " +
                    "left exactly as it was. " +
                    (failure.message?.trim()?.ifBlank { null } ?: "Try again."),
                dataUnchanged = true,
            )
        }

        // Settings live outside the transaction. Written last so a failed row write leaves the term
        // dates alone as well as the classes; a failure here is reported with dataUnchanged =
        // false, because the rows did change even though the settings did not.
        settings.replaceAll(
            AppSettings(
                overallTarget = preserved.overallTarget,
                courseTarget = preserved.courseTarget,
                calendar = bundledCalendar,
                section = preserved.section,
                batch = preserved.batch,
                displayName = preserved.displayName,
                // A new semester counts from its own first day, not a joining date that belonged
                // to the one just cleared.
                attendanceStart = AttendanceStart(),
            ),
        )

        val stored = settings.current
        if (stored.calendar != bundledCalendar ||
            stored.overallTarget != preserved.overallTarget ||
            stored.courseTarget != preserved.courseTarget ||
            stored.section != preserved.section ||
            stored.batch != preserved.batch ||
            stored.displayName != preserved.displayName ||
            stored.attendanceStart != AttendanceStart()
        ) {
            return RolloverResult.Failed(
                message = "Your previous semester was cleared and the new one started, but the " +
                    "semester dates and targets were not saved. Check them in Settings.",
                dataUnchanged = false,
            )
        }

        // Idempotent backfill: the new term has no patterns yet (they were cleared), so this is a
        // no-op until the student re-seeds — but running it mirrors a restore, so the new semester
        // starts in the same state a fresh install would.
        attendance.syncSessions(settings.current.effectiveCalendar, clock())

        return RolloverResult.Done
    }
}
