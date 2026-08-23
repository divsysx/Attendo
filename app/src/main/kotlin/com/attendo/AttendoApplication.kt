package com.attendo

import android.app.Application
import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.attendo.data.AppVersion
import com.attendo.data.AttendanceRepository
import com.attendo.data.BackupRepository
import com.attendo.data.DocumentStore
import com.attendo.data.RolloverRepository
import com.attendo.data.SafetySnapshotStore
import com.attendo.data.SemesterRepository
import com.attendo.data.SettingsStore
import com.attendo.data.TimetableRepository
import com.attendo.data.db.AttendoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-rolled dependency container.
 *
 * A handful of singletons and no injection framework: the graph is shallow, entirely
 * process-scoped, and adding Hilt would mean a KSP processor and a lifecycle of
 * annotations to save a few `by lazy` lines. Everything is lazy, and [warmUp] pulls the
 * two that touch the filesystem onto a background thread at startup, so no screen ever
 * opens a file on the main thread to get going.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    // Held as the delegate as well as the property so [checkpoint] can ask whether the
    // database was ever opened, instead of opening it in order to find out.
    private val databaseDelegate = lazy { AttendoDatabase.build(appContext) }

    /** Outlives every screen, so a checkpoint is not cancelled by the activity going away. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AttendoDatabase by databaseDelegate

    val attendance: AttendanceRepository by lazy { AttendanceRepository(database) }

    val semesters: SemesterRepository by lazy { SemesterRepository(database) }

    val timetable: TimetableRepository by lazy {
        TimetableRepository(appContext.assets)
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    /** Android's file picker, on the other side of a `content://` URI. */
    val documents: DocumentStore by lazy { DocumentStore(appContext.contentResolver) }

    /** This build's own name and code — recorded in backups, and shown under About. */
    val version: AppVersion by lazy { versionOf(appContext) }

    val backup: BackupRepository by lazy {
        BackupRepository(
            database = database,
            settings = settings,
            appVersion = version,
            // Private storage: the pre-restore copy is an undo for the next minute, not a file
            // the student manages. Exports are what leave the app.
            safety = SafetySnapshotStore(File(appContext.filesDir, "backup")),
        )
    }

    /**
     * The one place a semester is established, exported, or rolled over.
     *
     * Held here alongside [backup] because the two share the same transactional discipline — a
     * destructive write runs inside one Room transaction, with the settings written only after it
     * commits — and because the rollover screen's exports reach into both this repository and
     * [backup]. It is lazy for the same reason the others are: nothing should pay for it until the
     * detection flow actually asks.
     */
    val rollover: RolloverRepository by lazy {
        RolloverRepository(database, settings, attendance)
    }

    /**
     * Touches the two singletons the first screen needs, on a background thread.
     *
     * Both are lazy, and both do blocking work the first time they are asked for: Room
     * opens `attendo.db`, and [SettingsStore] reads its preferences file. Left alone, the
     * first access happens inside a `ViewModel` factory — which the framework runs on the
     * main thread, during the first frame. That is a cold start spending its frame budget
     * on file I/O, and it shows.
     *
     * Warming them here does not make the work cheaper; it moves it off the thread that is
     * trying to draw. Nothing waits on this: a screen that gets there first simply blocks
     * as it always did, and by the time one is on screen the values are built and shared.
     */
    fun warmUp() {
        ioScope.launch {
            runCatching {
                settings.current
                database.openHelper.readableDatabase
            }
        }
    }

    /**
     * Folds the write-ahead log back into `attendo.db`.
     *
     * The database runs in WAL mode, so a committed write lands in `attendo.db-wal` and
     * only moves into `attendo.db` when SQLite checkpoints — which it does on close, or
     * once the log passes about a thousand pages. This app writes a handful of rows a day
     * and is rarely closed, so weeks of marks can sit in the log.
     *
     * That matters because a backup copies the files as they sit on disk. The log is
     * backed up too, but a checkpoint whenever the app leaves the foreground means the
     * `.db` alone is already complete, which is the state anything reading it from outside
     * the app — a restore, a device transfer, an export — can rely on.
     */
    fun checkpoint() {
        if (!databaseDelegate.isInitialized() || !database.isOpen) return
        ioScope.launch {
            // A pragma that returns a row has to be stepped for the checkpoint to happen;
            // execSQL would prepare it and never run it.
            runCatching {
                database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }
        }
    }

    /**
     * The build's own name and code, read from the installed package.
     *
     * From `PackageManager` rather than `BuildConfig` so it is the version actually
     * installed, and so this file does not depend on a generated class.
     */
    private fun versionOf(context: Context): AppVersion {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return AppVersion(
            name = info?.versionName ?: "unknown",
            code = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
        )
    }
}

class AttendoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }
}
