package com.attendo.data

import androidx.room.withTransaction
import com.attendo.core.backup.AttendanceCsv
import com.attendo.core.backup.Backup
import com.attendo.core.backup.BackupCodec
import com.attendo.core.backup.BackupMeta
import com.attendo.core.backup.BackupPreferences
import com.attendo.core.backup.BackupProblem
import com.attendo.core.backup.BackupReadResult
import com.attendo.core.backup.BackupSnapshot
import com.attendo.core.backup.BackupSummary
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.toEntity
import com.attendo.data.db.toModel
import java.time.Instant
import java.time.LocalDate

/** Which build wrote a backup file. Recorded in the file so a restore can say where it came from. */
data class AppVersion(val name: String, val code: Long)

/** What a restore did. */
sealed interface RestoreResult {

    /** Everything in the file is now in the database, and was read back to prove it. */
    data class Restored(val summary: BackupSummary) : RestoreResult

    /** The file was refused before anything was written. Nothing changed. */
    data class Refused(val problems: List<BackupProblem>) : RestoreResult {
        init {
            require(problems.isNotEmpty()) { "a refusal must say why" }
        }

        val message: String get() = problems.first().message
    }

    /**
     * The restore itself failed.
     *
     * [dataUnchanged] is the part the student needs: a failure inside the transaction leaves
     * the install exactly as it was, and saying so is the difference between "try again" and
     * "check what survived".
     */
    data class Failed(val message: String, val dataUnchanged: Boolean) : RestoreResult
}

/**
 * Export and restore, in whole-database units.
 *
 * ### What a backup is for
 *
 * Attendo holds a semester of small daily decisions that cannot be reconstructed from
 * anything else — which hour of a two-hour lab was attended, which class was cancelled and
 * why, which one was moved and where it landed. Losing the phone loses the term. So the
 * export is the *complete logical state* rather than the visible one, and the restore is
 * all-or-nothing rather than best-effort.
 *
 * ### REPLACE, and why it is the only mode here
 *
 * [restore] replaces everything. That is the operation a student moving to a new phone
 * actually wants, and it is the only one whose result is predictable: merging two
 * independently-marked copies of the same class means choosing which record of a morning is
 * true, and this app has no basis for choosing. The safety snapshot below exists so that
 * replacing is reversible rather than trusted.
 *
 * ### The order of operations
 *
 * 1. Read the file and validate it completely — [BackupCodec.decode] returns nothing
 *    partial, so an unreadable backup never reaches the database.
 * 2. Write a safety snapshot of the current install to private storage.
 * 3. In one transaction: delete every row, insert the backup's rows, read them back, and
 *    compare against what was meant to be written. A mismatch throws, which rolls the
 *    transaction back.
 * 4. Only then write the settings, and check those too.
 *
 * Steps 3 and 4 are in that order because a failed restore must not leave last term's
 * holidays applied to a semester that never had them.
 */
class BackupRepository(
    private val database: AttendoDatabase,
    private val settings: SettingsStore,
    private val appVersion: AppVersion,
    private val safety: SafetySnapshotStore,
    private val now: () -> Instant = Instant::now,
    private val today: () -> LocalDate = LocalDate::now,
) {

    private val courseDao = database.courseDao()
    private val patternDao = database.patternDao()
    private val sessionDao = database.sessionDao()
    private val semesterDao = database.semesterDao()

    // ---- reading the install ------------------------------------------------

    /** Everything this install knows, in `:core` types. */
    suspend fun snapshot(): BackupSnapshot {
        val current = settings.current
        return BackupSnapshot(
            courses = courseDao.all().map { it.toModel() },
            patterns = patternDao.all().map { it.toModel() },
            sessions = sessionDao.all().map { it.toModel() },
            calendar = current.calendar,
            preferences = BackupPreferences(
                overallTarget = current.overallTarget,
                courseTarget = current.courseTarget,
                // Blank is not a section. The reader normalises blanks to null, so leaving one
                // here would make a file that does not read back as what was written.
                section = current.section?.ifBlank { null },
                batch = current.batch?.ifBlank { null },
                displayName = current.displayName?.trim()?.ifBlank { null },
                // A late admission's joining date. Without it a restore would silently put the
                // student back on the university-wide figure, which counts weeks they were not
                // enrolled for — a number that looks like attendance and is not theirs.
                attendanceStart = current.attendanceStart,
            ),
            // Semesters travel with the courses that belong to them. A restore that dropped
            // them would leave every course orphaned, and per-semester figures would have
            // nothing to group by — which is how "never mix semesters" quietly stops holding.
            semesters = semesterDao.all().map { it.toModel() },
        )
    }

    /** A preview of what is on this phone now, for comparing against a file about to replace it. */
    suspend fun currentSummary(): BackupSummary =
        Backup(meta = metaNow(), snapshot = snapshot()).summary

    // ---- export -------------------------------------------------------------

    /** The whole install as a backup file. */
    suspend fun exportBackup(): String = BackupCodec.encode(
        snapshot = snapshot(),
        appVersionName = appVersion.name,
        appVersionCode = appVersion.code,
        exportedAt = now(),
    )

    /** The attendance record as a spreadsheet. Human-readable, and not a restorable backup. */
    suspend fun exportAttendanceCsv(): String = AttendanceCsv.export(snapshot())

    fun suggestedBackupFileName(): String = BackupCodec.suggestedFileName(today())

    fun suggestedCsvFileName(): String = AttendanceCsv.suggestedFileName(today())

    // ---- import -------------------------------------------------------------

    /**
     * Reads a backup file without writing anything, for the preview.
     *
     * Kept separate from [restore] so the student sees what a file contains — and can refuse
     * it — before a single row is touched.
     */
    fun read(text: String): BackupReadResult = BackupCodec.decode(text)

    /**
     * Replaces this install's data with [backup].
     *
     * Takes an already-decoded [Backup] rather than the file's text, so the thing being
     * restored is provably the same thing that was previewed.
     */
    suspend fun restore(backup: Backup): RestoreResult {
        val snapshotBefore = runCatching { snapshot() }.getOrElse {
            return RestoreResult.Failed(
                "Attendo could not read its own data, so it will not replace it. " +
                    (it.message ?: "").trim(),
                dataUnchanged = true,
            )
        }

        // A copy of what is about to be overwritten, written before anything is overwritten.
        // A failure here stops the restore: replacing a semester with no way back is the one
        // outcome this whole class exists to prevent.
        runCatching {
            safety.write(
                BackupCodec.encode(
                    snapshot = snapshotBefore,
                    appVersionName = appVersion.name,
                    appVersionCode = appVersion.code,
                    exportedAt = now(),
                ),
            )
        }.onFailure {
            return RestoreResult.Failed(
                "Attendo could not save a copy of your current data first, so nothing was " +
                    "changed. Free up some storage and try again.",
                dataUnchanged = true,
            )
        }

        return write(backup.snapshot)
    }

    /**
     * Puts back the data that the last [restore] replaced.
     *
     * One level, and it is consumed: the snapshot is deleted once it has been put back, so
     * "restore previous data" cannot turn into an accidental redo of the import.
     */
    val canUndo: Boolean get() = safety.exists()

    suspend fun undoLastRestore(): RestoreResult {
        val text = safety.read()
            ?: return RestoreResult.Failed(
                "There is no saved copy of your previous data to go back to.",
                dataUnchanged = true,
            )

        // Read through the same validation as any other file. It is our own file, but a
        // half-written one is exactly the case where trusting it would do the most damage.
        val outcome = when (val result = read(text)) {
            is BackupReadResult.Failed -> RestoreResult.Refused(result.problems)
            is BackupReadResult.Ok -> write(result.backup.snapshot)
        }
        if (outcome is RestoreResult.Restored) safety.clear()
        return outcome
    }

    // ---- the write itself ---------------------------------------------------

    private suspend fun write(incoming: BackupSnapshot): RestoreResult {
        val plan = incoming.readyToWrite()

        val failure = runCatching {
            database.withTransaction {
                sessionDao.deleteAll()
                patternDao.deleteAll()
                courseDao.deleteAll()
                // Semesters last on the way out, first on the way in: courses reference them.
                // No foreign key enforces that — see the note on CourseEntity.semesterId — so
                // the order here is the only thing keeping a restore from writing courses that
                // point at semester ids the file's own semesters were never given.
                semesterDao.deleteAll()

                semesterDao.insertAll(plan.semesters.map { it.toEntity() })
                // Courses next: sessions and patterns carry a foreign key to them.
                courseDao.insertAll(plan.courses.map { it.toEntity() })
                patternDao.insertAll(plan.patterns.map { it.toEntity() })
                sessionDao.insertAll(plan.sessions.map { it.toEntity() })

                // Read back inside the transaction, so a mismatch can still be undone by
                // throwing. Compared as normalised snapshots: what has to match is the data,
                // not the row ids the database happened to hand out.
                val stored = BackupSnapshot(
                    courses = courseDao.all().map { it.toModel() },
                    patterns = patternDao.all().map { it.toModel() },
                    sessions = sessionDao.all().map { it.toModel() },
                    calendar = plan.calendar,
                    preferences = plan.preferences,
                    semesters = semesterDao.all().map { it.toModel() },
                )
                check(stored.normalised() == plan.normalised()) {
                    "the restored data does not match the backup " +
                        "(${stored.courses.size}/${stored.patterns.size}/${stored.sessions.size}/" +
                        "${stored.semesters.size} rows stored against " +
                        "${plan.courses.size}/${plan.patterns.size}/${plan.sessions.size}/" +
                        "${plan.semesters.size} expected)"
                }
            }
        }.exceptionOrNull()

        if (failure != null) {
            return RestoreResult.Failed(
                "The restore was cancelled and your data was left as it was. " +
                    (failure.message?.trim()?.ifBlank { null } ?: "The backup could not be written."),
                dataUnchanged = true,
            )
        }

        // Settings live in SharedPreferences, outside the transaction. They go last so that a
        // failed row write leaves the term dates alone as well as the classes.
        settings.replaceAll(
            AppSettings(
                overallTarget = plan.preferences.overallTarget,
                courseTarget = plan.preferences.courseTarget,
                calendar = plan.calendar,
                section = plan.preferences.section,
                batch = plan.preferences.batch,
                displayName = plan.preferences.displayName,
                attendanceStart = plan.preferences.attendanceStart,
            ),
        )

        val storedSettings = settings.current
        if (storedSettings.calendar != plan.calendar ||
            storedSettings.overallTarget != plan.preferences.overallTarget ||
            storedSettings.courseTarget != plan.preferences.courseTarget ||
            storedSettings.section != plan.preferences.section ||
            storedSettings.batch != plan.preferences.batch ||
            storedSettings.displayName != plan.preferences.displayName ||
            storedSettings.attendanceStart != plan.preferences.attendanceStart
        ) {
            return RestoreResult.Failed(
                "Your classes were restored, but the term dates and targets were not. " +
                    "Check them in Settings.",
                dataUnchanged = false,
            )
        }

        return RestoreResult.Restored(Backup(meta = metaNow(), snapshot = plan).summary)
    }

    private fun metaNow() = BackupMeta(
        formatVersion = BackupCodec.FORMAT_VERSION,
        appVersionName = appVersion.name,
        appVersionCode = appVersion.code,
        exportedAt = now(),
    )
}

/**
 * The snapshot as it will be stored: canonical ids, with one adjustment the database needs.
 *
 * [BackupSnapshot.normalised] numbers courses, patterns and sessions from 1, which is exactly
 * what a restore wants — Room writes a non-zero primary key as given, so the references
 * between the rows survive. The adjustment is for *orphan* pattern ids: a session whose
 * pattern was deleted keeps its `patternId`, and `normalised` gives those ids numbers just
 * past the real patterns. Stored as-is, the next timetable slot the student adds would be
 * handed one of those numbers by SQLite and would silently adopt an old class.
 *
 * So orphan references are moved to negative ids, which no autoincremented key can ever
 * reach. The mapping is order-preserving — the largest orphan id becomes −1 — so normalising
 * the stored rows again lands on the same numbering, which is what lets a restore verify
 * itself by comparison.
 */
internal fun BackupSnapshot.readyToWrite(): BackupSnapshot {
    val canonical = normalised()
    val realIds = canonical.patterns.mapTo(mutableSetOf()) { it.id }
    val orphans = canonical.sessions.mapNotNull { it.patternId }
        .distinct()
        .filter { it !in realIds }
    if (orphans.isEmpty()) return canonical

    val moved = orphans.sortedDescending().withIndex().associate { (index, id) -> id to -(index + 1L) }
    return canonical.copy(
        sessions = canonical.sessions.map { session ->
            session.copy(patternId = session.patternId?.let { moved[it] ?: it })
        },
    )
}
