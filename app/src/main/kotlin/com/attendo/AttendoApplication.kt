package com.attendo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import com.attendo.core.update.DistributionSource
import com.attendo.data.AndroidBackupStore
import com.attendo.data.AppearanceStore
import com.attendo.data.AppReset
import com.attendo.data.AppVersion
import com.attendo.data.AttendanceQuarantineStore
import com.attendo.data.AttendanceRepository
import com.attendo.data.BackupRepository
import com.attendo.data.DocumentStore
import com.attendo.data.RolloverRepository
import com.attendo.data.SafetySnapshotStore
import com.attendo.data.SemesterRepository
import com.attendo.data.SettingsStore
import com.attendo.data.TimetableRepository
import com.attendo.data.TimetableMigrator
import com.attendo.data.analytics.UsageAnalytics
import com.attendo.data.account.AccountManager
import com.attendo.data.community.CommunityClient
import com.attendo.data.community.CommunityIdentityStore
import com.attendo.data.community.CommunityRepository
import com.attendo.data.community.CommunitySupabaseConfig
import com.attendo.data.community.CommunitySync
import com.attendo.data.sync.AttendanceSyncEngine
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.update.DirectApkUpdateProvider
import com.attendo.data.update.InstallSourceReader
import com.attendo.data.update.UpdateCheckStore
import com.attendo.data.update.UpdateFiles
import com.attendo.data.update.UpdateManager
import com.attendo.data.update.UpdateVerifier
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

    /**
     * The "Automatic backup" toggle — read by this app's screens through the flow, and
     * by [com.attendo.data.AttendoBackupAgent] straight from the same file, in the
     * restricted-mode process Android runs backup passes in.
     */
    val androidBackup: AndroidBackupStore by lazy { AndroidBackupStore(appContext) }

    /**
     * What the app looks like. MainActivity follows this for the whole UI — there is one
     * [com.attendo.ui.theme.AttendoTheme] at the root, not one per screen — which is also
     * why it is held here: a preference that every screen needs is process state.
     */
    val appearance: AppearanceStore by lazy { AppearanceStore(appContext) }

    /**
     * The "Automatic backup" toggle's neighbour and opposite: everything the student has
     * on this phone, and the one action that removes it. Held here because it needs the
     * database and the settings store, and because it must run against the same instances
     * the rest of the app holds — clearing a copy of the data nobody reads would be a
     * convincing no-op.
     */
    val appReset: AppReset by lazy {
        AppReset(
            context = appContext,
            database = { database },
            settings = settings,
            // Wipe the anonymous community identity before the prefs clear — a reset ends
            // the reporter, not just the local evidence of one. Null when this build has
            // no endpoint (nothing to sign out), which AppReset tolerates.
            communityIdentity = communityClient?.let { client ->
                { community.clearForResetWith(client) }
            },
        )
    }

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
     * The copy of a deleted account's attendance, written before its rows are cleared for the
     * account that signs in next. See [AttendanceQuarantineStore] for why a deletion keeps a
     * copy, and [AttendanceSyncEngine] for the one call that writes one.
     *
     * App-private and never exported by any screen. `AppReset` deletes it with the other
     * generated directories, so Clear All still means clear.
     */
    val attendanceQuarantine: AttendanceQuarantineStore by lazy {
        AttendanceQuarantineStore.inFilesDir(appContext.filesDir)
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
     * Aggregate, anonymous usage statistics — the app's entire analytics surface, held
     * here so nothing else in the graph reaches Firebase directly.
     */
    val analytics: UsageAnalytics by lazy { UsageAnalytics(appContext) }

    /**
     * The update system, wired to whichever channel installed this build.
     *
     * A direct APK install (GitHub Releases, `adb`, a file manager) gets the direct APK
     * provider — the flow that checks GitHub, downloads, verifies and hands to Android's
     * installer. Anything installed by Google Play gets no provider at all: the manager
     * then reports itself unsupported, Settings shows no update section, and Play's own
     * in-app updates are the integration point left open for. See
     * [com.attendo.data.update.UpdateProvider] for that boundary.
     */
    val updates: UpdateManager by lazy {
        val source = InstallSourceReader(appContext).current()
        UpdateManager(
            context = appContext,
            provider = if (source == DistributionSource.DIRECT_APK) DirectApkUpdateProvider() else null,
            files = UpdateFiles(appContext),
            verifier = UpdateVerifier(appContext),
            analytics = analytics,
            store = UpdateCheckStore(appContext),
            scope = ioScope,
        )
    }

    /**
     * The community feature's one Supabase client, or null on a build without credentials
     * (a contributor machine, CI): every community surface reads that null as "not
     * available on this install" and attendance never notices. See
     * [CommunityClient] for the identity-never-in-a-backup invariant this construction
     * is party to.
     */
    val communityClient: CommunityClient? by lazy {
        if (CommunitySupabaseConfig.available) {
            CommunityClient(
                supabaseUrl = com.attendo.BuildConfig.SUPABASE_URL,
                supabasePublishableKey = com.attendo.BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                context = appContext,
            )
        } else null
    }

    /**
     * The install's account, on the same session the community feature holds — see
     * [AccountManager] for why it wraps that client instead of owning one (short
     * version: a second client would mean a second session, and a second session would
     * eventually mean a second identity). Null on a build without credentials, which
     * Settings reads as "no Account section", the same way a null [communityClient]
     * reads as "no Community".
     */
    val accountManager: AccountManager? by lazy {
        communityClient?.let { client ->
            AccountManager(
                client = client,
                identityStore = CommunityIdentityStore(appContext),
                // The browser for the GitHub consent page, opened the way supabase-kt's
                // own Android code opens it (its openExternalUrl is internal): a plain
                // ACTION_VIEW intent on the application context. Injected here so the
                // manager stays platform-free and the one Android dependency stays in
                // the one place the app wires everything.
                openInBrowser = { url ->
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                // The sign-in path's reach into community storage, injected for the
                // same reason the browser is: the manager stays platform-free, and the
                // identity the rows belong to is the account layer's business only at
                // the moment it is about to be replaced. Both halves of the returning
                // sign-in's cleanup are wired here and nowhere else — the local rows
                // (discardLocalCommunityRows) and the server-side retirement
                // (retireOutgoingIdentity, migration 0021's RPC) — and the send gate
                // that makes them one uninterruptible sequence comes from the same
                // drain engine that runs the sends it blocks. See
                // CommunityRepository.retireOutgoingIdentity for what the RPC may and
                // may not delete, and AccountManager.importCallbackSession for the
                // ordering: retire, discard, import — all under the gate, and only
                // after the sign-in has positively succeeded.
                discardLocalCommunityRows = { community.discardLocalCommunityRows() },
                retireOutgoingIdentity = { community.retireOutgoingIdentity(it) },
                withSendsBlocked = { communitySync.withSendsBlocked(it) },
                // The deletion's one durable local consequence, and the reason the next
                // account to sign in is a new lifecycle rather than an inheritance. Wired
                // through the engine (which owns the pass gate and the sync store) and
                // deferred — read at call time, not at construction — so the manager and the
                // engine can each be built lazily without depending on the other's lazy
                // initialiser. The engine is never null while a deletion can be established:
                // both it and the manager need [communityClient], which is what a build
                // without credentials lacks.
                terminateLocalAttendanceClaim = { uid ->
                    attendanceSync?.terminateAttendanceClaim(uid)
                },
                scope = ioScope,
            )
        }
    }

    /**
     * The community outbox drain engine. Started from [warmUp] (not lazily on first use)
     * so reports queued before the app was killed still send themselves when the network
     * comes back, even if no community screen is ever opened.
     */
    val communitySync: CommunitySync by lazy {
        CommunitySync(
            context = appContext,
            database = database,
            client = communityClient,
            scope = ioScope,
            clientVersionCode = { version.code },
        )
    }

    /**
     * The single facade every community surface talks to — the only object outside
     * `data/community` that knows the feature exists.
     */
    val community: CommunityRepository by lazy {
        CommunityRepository(
            context = appContext,
            database = database,
            client = communityClient,
            sync = communitySync,
            scope = ioScope,
            clientVersionCode = { version.code },
        )
    }

    /**
     * Attendance Sync, on the same session and the same scope as everything else.
     *
     * Null on a build without credentials, like [communityClient], and for the same
     * reason — with no endpoint there is nothing to sync against, and the app is entirely
     * usable without it.
     *
     * It reads the account state rather than owning an identity of its own: the uid the
     * attendance rows belong to is the uid the session carries, and a second copy of that
     * would be a second answer to "whose data is this". See
     * [AttendanceSyncEngine]'s gate on `AccountState.Linked`.
     */
    val attendanceSync: AttendanceSyncEngine? by lazy {
        val client = communityClient ?: return@lazy null
        val accounts = accountManager ?: return@lazy null
        AttendanceSyncEngine(
            context = appContext,
            database = database,
            settings = settings,
            client = client,
            accountState = accounts.state,
            // The pass's own boundary check, on the one object that owns the session. See
            // AttendanceSyncEngine's `validateAccount` for why a pass carries its own
            // answer rather than reading a stored one.
            validateAccount = { accounts.validateAccount() },
            // What happens to a *deleted* account's local attendance when the phone is claimed
            // by the account that signs in next: set aside, then cleared. The copy is this
            // app's own backup format, written through the same encoder the export screen
            // uses, so what is kept is a file the restore path could read rather than a
            // second format invented for it. Written before anything is cleared — a failure
            // here leaves the phone untouched and the next pass tries again. See
            // AttendanceQuarantineStore and AttendanceSyncStore.releaseTerminatedClaim.
            preserveRemovedAccountAttendance = {
                attendanceQuarantine.write(backup.exportBackup())
            },
            scope = ioScope,
        )
    }

    /**
     * Asks the server whether this install's account still exists, off the main thread.
     *
     * The foreground-and-startup half of the account-lifecycle check. Its counterpart is
     * inside [AttendanceSyncEngine], which asks the same question at the top of every pass;
     * between them the check runs on exactly the boundaries that can change the answer — the
     * app opening, the app coming back to the foreground, and before authenticated work —
     * and on nothing else. There is deliberately no timer here: an app that polled the auth
     * server every few seconds would spend a student's battery and data asking a question
     * whose answer only ever changes when somebody else acts.
     *
     * Fire-and-forget by design. The verdict is not consumed here — [AccountManager] applies
     * it, ending the local session and raising the flag the Account screen reads, both of
     * which happen on the manager's own state rather than on this call's return. A failure
     * to even make the request is therefore not an error to report: it is the
     * network-unavailable reading, which preserves everything and tries again at the next
     * boundary.
     */
    fun revalidateAccount() {
        if (accountManager == null) return
        ioScope.launch { runCatching { accountManager?.validateAccount() } }
    }

    /**
     * Brings an install seeded from an older printed timetable up to the bundled edition.
     *
     * Held here rather than on a screen because it must run whether or not the student opens
     * the screen that would own it — a timetable revision is not something to be discovered,
     * and an install that never opens Courses again would keep July's slots forever. Started
     * from [warmUp].
     */
    val timetableMigration: TimetableMigrator by lazy {
        TimetableMigrator(
            context = appContext,
            attendance = attendance,
            timetables = timetable,
            settings = settings,
        )
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
            // After the database is open, and on the same background scope: the revision is
            // checked on every launch and does nothing at all once this edition has been
            // applied. Failure is swallowed like the read above — a migration that could not
            // run leaves its marker unwritten, so the next launch tries again.
            runCatching { timetableMigration.run() }
        }
        // Registers the connectivity listener only when there is an endpoint to talk to;
        // a build without credentials skips even the no-op callback. Attendance Sync is
        // started here for the same reason the community drain is: a pass needs no screen
        // to be open, and waiting for one would mean a student who records attendance and
        // never revisits Settings never gets a backup.
        if (communityClient != null) communitySync.start()
        attendanceSync?.start()
    }

    /**
     * Folds the write-ahead log back into `attendo.db`.
     *
     * The database runs in WAL mode, so a committed write lands in `attendo.db-wal` and
     * only moves into `attendo.db` when SQLite checkpoints — which it does on close, or
     * once the log passes about a thousand pages. This app writes a handful of rows a day
     * and is rarely closed, so weeks of attendance records can sit in the log.
     *
     * A checkpoint whenever the app leaves the foreground means the `.db` alone is
     * already complete, which is the state anything reading it from outside the app —
     * the student's own export, a restore, a bug report's copy — can rely on. Android's
     * automatic backup, for whoever turns it on in Settings, is another such reader:
     * it copies `attendo.db` and its log as files, and a checkpointed pair is one whose
     * restore needs no guessing. (The explicit backup file reads through the app rather
     * than the raw files, so it never needed this.)
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
        // The quiet update check: at most once a day, off the main thread, and silent
        // unless there is something worth showing. Never due to run? Then this does
        // nothing but restore what the last successful check found.
        container.updates.autoCheck()
    }
}
