package com.attendo.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.attendo.core.auth.AuthLifecycleState
import com.attendo.core.sync.AttendanceSyncPolicy
import com.attendo.core.sync.AttendanceSyncPolicy.LocalAttendanceOwner
import com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict
import com.attendo.core.sync.SyncTable
import com.attendo.data.SettingsStore
import com.attendo.data.account.AccountState
import com.attendo.data.community.CommunityClient
import com.attendo.data.db.AttendoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.time.Instant

/**
 * Runs Attendance Sync: when a pass happens, in what order, and what is done with the
 * answers. No WorkManager, one collector, and every decision delegated to
 * [AttendanceSyncPolicy] and [AttendanceSyncStore].
 *
 * ### When it runs
 *
 * Only on the events that can actually change the answer — connectivity came back, a
 * session was linked — plus once at start-up. There is no polling and no timer: a student
 * who never opens the app while online does not have their phone waking up to ask a server
 * whether anything changed. This mirrors [com.attendo.data.community.CommunitySync]
 * deliberately, down to the conflated wake-up flow, because the app has exactly one
 * background machinery and a second, differently-shaped one would be a second thing to
 * reason about.
 *
 * ### What it is allowed to assume
 *
 * Nothing about the network, and nothing about the clock beyond its own monotonicity. Every
 * pass is written so that being interrupted at any point, or running twice in a row, leaves
 * the local database exactly as correct as running once would — the rows are already
 * committed when the cursor moves, and a re-applied row is rejected by the same comparison
 * that let it in the first time.
 *
 * ### What it will not do
 *
 * It will not touch attendance to make a sync work. There is no path from here to deleting
 * a course, resetting a counter or re-seeding anything: the two writes it can make to
 * attendance are the ones [AttendanceSyncStore] describes, and both require the cloud to
 * have explicitly asked. A sync that cannot complete leaves the app completely functional
 * and simply tries again later.
 */
class AttendanceSyncEngine(
    context: Context,
    private val database: AttendoDatabase,
    private val settings: SettingsStore,
    private val client: CommunityClient?,
    private val accountState: StateFlow<AccountState>,
    /**
     * The server-authoritative account check, injected rather than performed here.
     *
     * Attendance Sync is the one place in the app that acts on the account without a
     * screen open, so it is the place where "the account was deleted an hour ago" would
     * otherwise go unnoticed: every pass would present a token the server still verifies
     * and quietly keep a deleted account's backup alive. Asking at the top of a pass makes
     * this the boundary the check actually runs on, without a poll and without a second
     * timer — passes are already event-driven, so the check inherits that shape.
     *
     * Injected as a lambda rather than as a second state flow so a pass cannot act on a
     * stale answer: the value consulted is the one just established, and the object that
     * owns the session remains the only thing that decides what it means.
     */
    private val validateAccount: suspend () -> AuthLifecycleState,
    /**
     * Keeps a copy of the local attendance before a **terminated** claim's rows are cleared
     * away for a new account — see
     * [AttendanceSyncStore.releaseTerminatedClaim], which calls this before it clears
     * anything.
     *
     * Injected rather than built here for the reason the browser and the community discard
     * are injected into [com.attendo.data.account.AccountManager]: the copy is a backup-file
     * concern, and the object that can write one (`BackupRepository`, with the app version
     * and the settings that belong in the file) lives outside this package. This engine
     * decides *when* a body has to be set aside; it does not decide what a copy of it looks
     * like.
     *
     * Required, not defaulted. A no-op default would compile, run, and quietly turn "the
     * deleted account's attendance was kept" into "it was deleted" — the exact silent
     * data-loss this path exists to avoid, with nothing in the code to show for it. The one
     * production construction site wires the real writer.
     */
    private val preserveRemovedAccountAttendance: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {

    private val appContext = context.applicationContext

    private val store by lazy { AttendanceSyncStore(database, now) }

    private val api by lazy { client?.let { AttendanceSyncApi(it) } }

    /**
     * One pass at a time.
     *
     * A second trigger arriving mid-pass queues on this rather than starting a competing
     * pass: two passes interleaved would each be correct on its own and pointless together,
     * and the conflation below usually means the queued one is skipped anyway.
     */
    private val passGate = Mutex()

    /** Bumped by every trigger; conflated, so a storm of them is one extra pass. */
    private val wakeups = MutableStateFlow(0L)

    private var job: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Registers the triggers and starts collecting. Call once, from the application scope.
     *
     * A build with no credentials does nothing at all — [client] is null and there is no
     * endpoint to talk to, which is the same reading every other community surface gives it.
     */
    fun start() {
        if (client == null) return

        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wake()
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { manager.registerNetworkCallback(request, callback) }
            networkCallback = callback
        }

        job = scope.launch {
            // Linking an account is the other event that changes the answer, and it is not a
            // network event: the student signs in on a phone that has been online all along.
            // Watching the account state for it means the first sync of a new install happens
            // when they sign in, not whenever the app is next restarted.
            launch {
                accountState.collect { state ->
                    if (state is AccountState.Linked) wake()
                }
            }
            // StateFlow conflates, so a trigger during a pass leaves exactly one pass to
            // follow it rather than a queue of them.
            wakeups.collect {
                runCatching { pass() }
            }
        }
    }

    fun stop() {
        networkCallback?.let { callback ->
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { manager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        job?.cancel()
        job = null
    }

    /** Ask for a pass. Safe to call from anywhere, including before [start]. */
    fun wake() {
        wakeups.update { it + 1 }
    }

    /**
     * The account this device's attendance belongs to, or null when none has claimed it.
     *
     * The claim *and* whether the account it names has been established gone — the state
     * [pass] refuses or quarantines on, exposed because both are otherwise invisible: the
     * student gets a sync that never finishes and no sentence explaining why, which is
     * indistinguishable from a phone with no network. [attendanceOwner]'s refusal is what
     * Account Settings' switch prompt is built from, and what the app should *offer* a
     * student whose linked account is not the one holding their attendance is still a
     * product decision (see the Stage C audit) — except for the terminated case, which is
     * not a question at all and is answered by the lifecycle. This exists so that decision
     * has something to render rather than a silent no-op to go and debug.
     */
    suspend fun attendanceOwner(): LocalAttendanceOwner? = store.attendanceOwner()

    suspend fun hasLoggedOutEdits(userId: String): Boolean = store.hasLoggedOutEdits(userId)

    /**
     * Marks this device's attendance claim as naming an account the server has established is
     * gone — the persisted half of the deletion lifecycle, called from [AccountManager]'s
     * deletion branch through the lambda the container wires.
     *
     * It goes through the engine rather than reaching into [AttendanceSyncStore] from the
     * account layer for two reasons: the store is this package's, and the engine is the
     * object that holds the pass gate — the one thing that can be in flight for the account
     * being deleted. A pass already running when the deletion is established is not stopped
     * by this (it validated before the deletion was known, and what it could still upload is
     * the gone account's own rows, addressed to the gone account's own session), but every
     * pass that starts afterwards reads the terminated claim and cannot proceed as a normal
     * sync for that account.
     *
     * Returns whether this call was the one that marked it; idempotent, so a second
     * announcement after a process death is a no-op rather than a second write.
     */
    suspend fun terminateAttendanceClaim(goneUserId: String, at: Instant = now()): Boolean =
        store.terminateAttendanceClaim(goneUserId, at)

    /**
     * Replaces local attendance and ownership with [newUserId], from Account Settings'
     * approved-switch press.
     *
     * The engine is where [preserveRemovedAccountAttendance] lives, so this is where it is
     * supplied — and supplying it is not optional, because the store decides for itself
     * whether the outgoing body has to be set aside first. A press whose approval was given
     * while the outgoing account was still live can arrive after that account has been
     * deleted; the store reads the claim at the transition and takes the copy itself. See
     * [AttendanceSyncStore.switchAttendanceOwner].
     */
    suspend fun switchAttendanceOwner(newUserId: String, at: Instant) =
        store.switchAttendanceOwner(newUserId, at, preserveRemovedAccountAttendance)

    /**
     * The same transition, for the lifecycle that reaches it when the claim's account has
     * been established gone — the same call, because the store reads the claim rather than
     * trusting the branch that arrived here.
     */
    suspend fun releaseTerminatedClaim(newUserId: String, at: Instant) =
        store.releaseTerminatedClaim(newUserId, at, preserveRemovedAccountAttendance)

    suspend fun restoreRemote(userId: String) = store.restoreRemote(userId)

    /**
     * One automatic pass. Same body and same mutex as [syncNow]; the wake collector
     * still swallows the result.
     *
     * The scope is [AttendanceSyncPolicy.PullScope.INCREMENTAL]: a background pass exists to
     * pick up what changed since the last one, and the cursor is exactly the right answer to
     * "since when". See [syncNow] for why the manual control does not get to use it.
     */
    private suspend fun pass() = passGate.withLock {
        val lifecycle = validateAccount()
        Log.d(TAG, "operation: pass, lifecycle verdict: $lifecycle (forbidsAuthenticatedWork: ${lifecycle.forbidsAuthenticatedWork})")
        if (lifecycle.forbidsAuthenticatedWork) return@withLock SyncPassResult.AccountGone
        runPass(AttendanceSyncPolicy.PullScope.INCREMENTAL)
    }

    /**
     * A waitable pass for Account Settings' Sync now control.
     *
     * Same body and same mutex as [pass]: a second press while one is in flight queues
     * on the gate rather than starting a competing pull-push. What differs is the scope.
     *
     * This is the control a student presses when they want the two devices to agree *now*, so
     * it is answered about the account rather than about this device's cursor. Under
     * [AttendanceSyncPolicy.PullScope.RECONCILE] every table is re-read from the beginning of
     * the account's history, and the reads carry on until one of them comes back with nothing
     * left in it, so a press that finds nothing to change has established that by reading the
     * whole account and rejecting it — not by reading the cursor, which only ever says what
     * this phone has already seen, and not by reading a prefix of it and stopping. "All data
     * is up to date." is then a statement about the account, which is what the student was
     * asking.
     */
    suspend fun syncNow(): SyncPassResult = passGate.withLock {
        val lifecycle = validateAccount()
        Log.d(TAG, "operation: syncNow, lifecycle verdict: $lifecycle (forbidsAuthenticatedWork: ${lifecycle.forbidsAuthenticatedWork})")
        if (lifecycle.forbidsAuthenticatedWork) return@withLock SyncPassResult.AccountGone
        val result = runPass(AttendanceSyncPolicy.PullScope.RECONCILE)
        Log.d(TAG, "operation: syncNow, runPass returned: $result")
        result
    }

    /**
     * One complete sync: read what the cloud has, then send what this device has.
     *
     * Pull first, always. A device that takes on what the cloud holds before it uploads has
     * already converged when it writes, so the push cannot present a stale row as current —
     * the server's `lww_touch` would refuse that anyway, but arriving already correct is
     * better than being corrected. It also means the read path is never looking at rows this
     * pass just wrote.
     *
     * The cursor is moved as soon as the reads are committed and *before* the push, so a
     * failure to upload cannot lose the progress of having caught up.
     *
     * ### [scope] is the whole difference between the two callers
     *
     * [AttendanceSyncPolicy.PullScope.INCREMENTAL] reads from behind the cursor and is what a
     * background pass wants. [AttendanceSyncPolicy.PullScope.RECONCILE] reads from the
     * beginning of the account's history and is what a student pressing Sync now is owed: the
     * same pass either way, with the local shortcut taken out of the answer. See
     * [AttendanceSyncPolicy.pullFloor].
     *
     * ### The account question is already answered here
     *
     * Both callers — [pass] and [syncNow] — ask the server and refuse before reaching this
     * method, inside the same [passGate] this method runs under, so the answer is at most a
     * few milliseconds old and cannot have been overtaken by the only thing that changes it:
     * a concurrent pass. Asking again here would be a *second* `GET /auth/v1/user` per pass
     * for every student, on every wake-up, forever, to re-learn what the caller established
     * and to fail exactly the same way — which is why it is asked once, at the call site that
     * owns the gate, and the requirement to reach this method through one is stated here
     * rather than enforced by a second network round trip.
     */
    private suspend fun runPass(scope: AttendanceSyncPolicy.PullScope): SyncPassResult {
        val api = api ?: run {
            Log.d(TAG, "operation: runPass, api is null -> NotLinked")
            return SyncPassResult.NotLinked
        }
        // Attendance is owned by an account, and only a linked one. An anonymous identity is
        // retired and discarded on sign-in and dies with an app-data clear, so attendance
        // pushed to one would be a backup the student can never reach — worse than no backup,
        // because it looks like one. See AccountState.
        val userId = (accountState.value as? AccountState.Linked)?.uid ?: run {
            Log.d(TAG, "operation: runPass, accountState is not Linked -> NotLinked")
            return SyncPassResult.NotLinked
        }

        Log.d(TAG, "operation: runPass, started for userId: $userId, accountState: ${accountState.value::class.simpleName}")

        // The account question has already been asked — see the doc on this method for why it
        // is not asked a second time in here. Only a server-authoritative "gone" (or the
        // absence of a session altogether) stops a pass, and both callers refuse on that
        // verdict before reaching this point. A network failure or a recoverable auth failure
        // leaves the verdict alone, and the pass proceeds to fail or succeed on its own terms
        // — which is the whole reason those states are kept apart from SERVER_ACCOUNT_GONE.
        // Turning an unreachable server into a refused sync would strand a student offline
        // with a backup that never resumes. See AccountManager.validateAccount.

        // Whose attendance is this? Attendance is written before any account exists and
        // belongs to the first account that syncs it, so a pass for a *different* account
        // would upload the first one's rows into the second — all of them, because a uid this
        // device has never synced reads as an install that has never synced, which is exactly
        // the condition that makes an initial push take every row.
        //
        // Both directions stop, not just the push. Pulling the second account's rows into a
        // database still holding the first's would produce the same mixture from the other
        // side — two students' timetables in one percentage — and the settings row would be
        // overwritten as well. See [AttendanceSyncPolicy.ownershipVerdict] for why a mismatch
        // is refused outright rather than resolved one way or the other.
        val owner = try {
            store.attendanceOwner()
        } catch (t: Throwable) {
            Log.e(TAG, "operation: runPass, attendanceOwner() exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }
        Log.d(TAG, "operation: runPass, attendanceOwner: $owner, current userId: $userId")
        when (AttendanceSyncPolicy.ownershipVerdict(owner, userId)) {
            // No account has claimed this phone's attendance. This pass is the claim, and it
            // is written down before anything is uploaded rather than after: see
            // [AttendanceSyncStore.claimAttendance].
            OwnershipVerdict.CLAIM -> {
                Log.d(TAG, "operation: runPass, claiming attendance for userId: $userId")
                try {
                    store.claimAttendance(userId, now())
                } catch (t: Throwable) {
                    Log.e(TAG, "operation: runPass, claimAttendance exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
                    throw t
                }
            }
            // The claim names an account the server has established is gone, and this pass is
            // for a different one. A lifecycle ended and a new one is beginning, so the
            // outgoing body is set aside and the phone is claimed fresh — no prompt, because
            // there is nobody left to ask and the deletion was already announced.
            //
            // This is the one place both directions of the leak are closed at once: without
            // it, the ownership record that stops A's rows being uploaded as B's would also
            // leave B permanently unable to sync anything, and the alternative — clearing the
            // claim — is the initial push that hands A's whole history to B. See
            // [AttendanceSyncPolicy.ownershipVerdict] and
            // [AttendanceSyncStore.releaseTerminatedClaim].
            OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE -> {
                Log.d(TAG, "operation: runPass, ownershipVerdict is QUARANTINE_NEW_LIFECYCLE -> releasing for userId: $userId")
                try {
                    store.releaseTerminatedClaim(userId, now(), preserveRemovedAccountAttendance)
                } catch (t: Throwable) {
                    Log.e(TAG, "operation: runPass, releaseTerminatedClaim exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
                    throw t
                }
            }
            OwnershipVerdict.PROCEED -> Unit
            OwnershipVerdict.REFUSE -> {
                Log.d(TAG, "operation: runPass, ownershipVerdict is REFUSE -> Refused")
                return SyncPassResult.Refused
            }
        }

        val at = now()
        val state = try {
            store.ensureState(userId)
        } catch (t: Throwable) {
            Log.e(TAG, "operation: runPass, ensureState exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }

        Log.d(TAG, "operation: runPass, calling pullAll (scope: $scope)")
        val read = try {
            pullAll(api, userId, scope, state.cursor, at)
        } catch (t: Throwable) {
            Log.e(TAG, "operation: runPass, pullAll exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }
        // No network. Everything already applied is committed and safe; the rest of the pass
        // has nothing to say, and the next trigger starts over from the same cursor.
        if (read == null) {
            Log.e(TAG, "operation: runPass, pullAll refused a table -> Unreachable")
            return SyncPassResult.Unreachable
        }

        Log.d(TAG, "operation: runPass, calling pullAccounts")
        try {
            if (!pullAccounts(api, userId)) {
                Log.e(TAG, "operation: runPass, pullAccounts could not read the accounts row -> Unreachable")
                return SyncPassResult.Unreachable
            }
            Log.d(TAG, "operation: runPass, pullAccounts completed successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "operation: runPass, pullAccounts exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }

        Log.d(TAG, "operation: runPass, calling push")
        val pushed = try {
            val sent = push(api, userId, at)
            Log.d(TAG, "operation: runPass, push completed successfully, changed the cloud: $sent")
            sent
        } catch (t: Throwable) {
            Log.e(TAG, "operation: runPass, push exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }

        // Read again — for the one scope that promised to, and until a read has nothing left
        // in it.
        //
        // Two things bring the pass back here. A push is not a no-op on the server: it bumps
        // the revision of every row it accepts, and a row this device pushed under an identity
        // it had just been assigned comes back as a row it has to reconcile. And a table
        // longer than one read's page allowance stops with rows still below it; a manual sync
        // that reported "up to date" over an account it had read only the first few thousand
        // rows of would be answering a question it had not finished asking. Reading until a
        // round is finished is what makes the report true of the whole account rather than of
        // the part of it that fitted in one pass.
        //
        // Each round is incremental — the cursor has absorbed every earlier round — so the cost
        // is the pages this pass itself disturbed, not the account's history over again. The
        // round bound is therefore a bound on a pathological table, not on the common case: a
        // read that is finished on the first round costs no second request at all.
        if (scope == AttendanceSyncPolicy.PullScope.RECONCILE) {
            var cursor = read.cursor
            var more = !read.finished || pushed
            var round = 0
            while (more && round < MAX_RECONCILE_ROUNDS) {
                round++
                Log.d(TAG, "operation: runPass, reconcile: read again from cursor $cursor (round $round)")
                val next = try {
                    pullAll(api, userId, AttendanceSyncPolicy.PullScope.INCREMENTAL, cursor, at)
                } catch (t: Throwable) {
                    Log.e(TAG, "operation: runPass, reconcile read exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
                    throw t
                }
                if (next == null) {
                    Log.e(TAG, "operation: runPass, reconcile read refused a table -> Unreachable")
                    return SyncPassResult.Unreachable
                }
                more = !next.finished
                cursor = next.cursor
            }
            if (!pullAccounts(api, userId)) {
                Log.e(TAG, "operation: runPass, second pullAccounts could not read the accounts row -> Unreachable")
                return SyncPassResult.Unreachable
            }
        }

        try {
            store.stampSync(userId, at)
        } catch (t: Throwable) {
            Log.e(TAG, "operation: runPass, stampSync exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }
        Log.d(TAG, "operation: runPass, completed successfully -> Completed")
        return SyncPassResult.Completed
    }

    /**
     * One read of every table: where the cursor now stands, and whether the read has
     * anything left in it.
     */
    private data class Read(
        /**
         * The cursor after this read, committed. Every table's pages are durable by the time
         * this is returned — the cursor only moves after a page's rows are applied.
         */
        val cursor: Long,
        /**
         * True when reading again within this pass would hand back nothing new: every table
         * either read to its end, or stopped at a row it is waiting on a parent for.
         *
         * False is the narrower statement — *some* table stopped with rows still below it,
         * because the pass ran out of pages rather than out of rows. It carries no judgement
         * about the data; it is what the caller needs to decide whether one read was enough.
         */
        val finished: Boolean,
    )

    /**
     * The read half of a pass: every table from [scope]'s floor, applied and committed, then
     * the cursor. Returns null if the network refused a table.
     *
     * A null is not a rollback: the pages that did commit are durable and the cursor stays
     * where it was, so the tables read after the refused one are simply read again next pass.
     */
    private suspend fun pullAll(
        api: AttendanceSyncApi,
        userId: String,
        scope: AttendanceSyncPolicy.PullScope,
        cursor: Long,
        at: Instant,
    ): Read? {
        val floor = AttendanceSyncPolicy.pullFloor(cursor, scope)
        Log.d(TAG, "operation: pullAll, scope: $scope, cursor: $cursor, floor: $floor")

        val pulls = SyncTable.entries.map { table ->
            try {
                pullTable(api, table, floor)
            } catch (t: Throwable) {
                Log.e(TAG, "operation: pullAll, pullTable(${table.wireName}) exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
                throw t
            }
        }
        Log.d(
            TAG,
            "operation: pullAll, per-table pull results: " + pulls.mapIndexed { index, pull ->
                "${SyncTable.entries[index].wireName}(reachable: ${pull.reachable}, " +
                    "highestApplied: ${pull.progress.highestApplied}, firstDeferred: ${pull.progress.firstDeferred}, " +
                    "exhausted: ${pull.progress.exhausted})"
            }.joinToString(", ")
        )
        if (pulls.any { !it.reachable }) return null

        val next = AttendanceSyncPolicy.cursorAfterPass(cursor, pulls.map { it.progress })
        Log.d(TAG, "operation: pullAll, all pulls reachable. Advancing cursor: $cursor -> $next")
        try {
            store.advanceCursor(userId = userId, cursor = next, at = at)
        } catch (t: Throwable) {
            Log.e(TAG, "operation: pullAll, advanceCursor exception class: ${t::class.qualifiedName}, message: ${t.message}", t)
            throw t
        }
        // A deferral counts as finished for the caller's purposes: the row it stopped at is a
        // child whose parent this device does not hold, and nothing the rest of *this* pass
        // does can conjure that parent — reading the table again would return the same rows and
        // stop at the same one. The next pass, after the parent has been read or pushed, picks
        // it up. What must not be counted as finished is the other reason a read stops: the page
        // allowance running out with rows still below it. See
        // [AttendanceSyncPolicy.readIsFinished].
        return Read(next, AttendanceSyncPolicy.readIsFinished(pulls.map { it.progress }))
    }

    // ------------------------------------------------------------------- pull

    /** One table's read, and whether the network let it finish. */
    private data class TablePull(
        val progress: AttendanceSyncPolicy.TableProgress,
        val reachable: Boolean,
    )

    /** The four tables, each read through the same loop with its own row type and apply step. */
    private suspend fun pullTable(api: AttendanceSyncApi, table: SyncTable, floor: Long): TablePull =
        when (table) {
            SyncTable.SEMESTERS -> pullPages(api, table, floor, SemesterRow.serializer(), { it.revision }) {
                store.applySemesters(it)
            }

            SyncTable.COURSES -> pullPages(api, table, floor, CourseRow.serializer(), { it.revision }) {
                store.applyCourses(it)
            }

            SyncTable.PATTERNS -> pullPages(api, table, floor, PatternRow.serializer(), { it.revision }) {
                store.applyPatterns(it)
            }

            SyncTable.SESSIONS -> pullPages(api, table, floor, SessionRow.serializer(), { it.revision }) {
                store.applySessions(it)
            }
        }

    /**
     * Pages one table upward from [floor] until it runs out, is deferred, or gives up.
     *
     * The read starts at [floor] — behind the stored cursor, not at it — because
     * `attendance.change_seq` is a plain sequence and `nextval` is not transactional: a row
     * numbered below the cursor can commit after one numbered above it. Re-reading is free,
     * since a row this device already applied is not strictly newer than the copy it applied
     * and is rejected without a write.
     *
     * The loop stops early on a deferral rather than paging on: the cursor cannot pass that
     * row, so everything above it would be read again next pass regardless.
     */
    private suspend fun <T> pullPages(
        api: AttendanceSyncApi,
        table: SyncTable,
        floor: Long,
        deserializer: KSerializer<T>,
        revisionOf: (T) -> Long,
        apply: suspend (List<T>) -> AttendanceSyncStore.Applied,
    ): TablePull {
        var from = floor
        var highest: Long? = null
        var deferred: Long? = null
        var exhausted = false
        var pages = 0

        while (pages < MAX_PAGES_PER_TABLE) {
            pages++
            val page = when (val result = api.pull(table, from, AttendanceSyncPolicy.PULL_PAGE_SIZE, deserializer)) {
                is AttendanceSyncApi.PullResult.Unreachable ->
                    return TablePull(
                        // Not exhausted: the pass stopped for a reason that says nothing about
                        // whether the table has more rows, and the cursor must not read it as
                        // "there was nothing left".
                        AttendanceSyncPolicy.TableProgress(highest, deferred, exhausted = false),
                        reachable = false,
                    )

                is AttendanceSyncApi.PullResult.Ok -> result
            }

            val applied = apply(page.rows)
            Log.d(
                TAG,
                "operation: pullPages, table: ${table.wireName}, page: $pages, from: $from, rowsReturned: ${page.rows.size}, " +
                    "full: ${page.full}, appliedCount: ${applied.revisions.size}, firstDeferred: ${applied.firstDeferred}"
            )
            applied.revisions.maxOrNull()?.let { highest = maxOf(highest ?: it, it) }
            if (applied.firstDeferred != null) {
                deferred = applied.firstDeferred
                break
            }
            if (!page.full) {
                exhausted = true
                break
            }
            // The page was full, so there is more to read. The next read starts above the
            // last revision in it — taken from the rows themselves, not from what was
            // applied, so a page that somehow applied nothing still moves the read forward
            // instead of asking the same question forever.
            from = page.rows.maxOf(revisionOf)
        }

        return TablePull(
            AttendanceSyncPolicy.TableProgress(highest, deferred, exhausted),
            reachable = true,
        )
    }

    /**
     * The settings row, which is not a revision-cursored table — there is one row per
     * account, so it is simply read and compared.
     *
     * Returns whether the question was actually answered. `false` means the request never
     * landed, which is the one case a pass must not paper over: an account row this device
     * could not read is indistinguishable, in the local database, from an account whose
     * settings already match — and a Sync now that reports "up to date" after failing to read
     * the account's settings has answered a question nobody asked. Everything else — a row
     * this device declines to apply, or no row at all because this account has never pushed —
     * is a real answer and returns `true`.
     */
    private suspend fun pullAccounts(api: AttendanceSyncApi, userId: String): Boolean {
        val row = when (val result = api.pullAccounts()) {
            is AttendanceSyncApi.PullResult.Unreachable -> {
                Log.e(TAG, "operation: pullAccounts, result: Unreachable -> the accounts row was NOT read")
                return false
            }

            is AttendanceSyncApi.PullResult.Ok -> {
                Log.d(TAG, "operation: pullAccounts, result: Ok (rowCount: ${result.rows.size})")
                result.rows.firstOrNull() ?: run {
                    Log.d(TAG, "operation: pullAccounts, no accounts row on the server yet -> read, nothing to apply")
                    return true
                }
            }
        }
        val applied = store.settingsFromAccount(userId, row, settings.current) ?: run {
            Log.d(TAG, "operation: pullAccounts, settingsFromAccount declined the row (unreadable, not newer than what was agreed, or local settings changed since) -> read, nothing to apply")
            return true
        }
        settings.replaceAll(applied)
        // Recorded after the settings are in place, never before. A crash between the two
        // leaves the row to be re-decided — the local settings now match it, so it is a
        // no-op next pass. The other order would leave this device believing it had agreed
        // to settings it never took, and then pushing its own stale copy over the newer one.
        store.recordAccountsApplied(userId, row)
        Log.d(TAG, "operation: pullAccounts, applied the accounts row (clientUpdatedAt: ${row.clientUpdatedAt})")
        return true
    }

    // ------------------------------------------------------------------- push

    /**
     * Sends what this device owes, in foreign-key order, and records only what landed.
     *
     * Each request is independently accepted or refused, so a chunk that fails leaves
     * exactly its own rows dirty. The one exception is [AttendanceSyncStore.markPushed]'s
     * `initialDone`, which is deliberately all-or-nothing: the flag says "this install's
     * pre-sync history is in the cloud", and setting it after a partial upload would leave
     * the rows that failed out of the backup permanently — they are `dirty = false` and were
     * never uploaded, so no later pass would pick them up.
     *
     * Returns whether the server accepted *anything* — a row, a tombstone, the settings row.
     * A pass that changed the cloud has new revisions to read back; one that changed nothing
     * has nothing of its own to reconcile, which is what lets a manual sync skip a read it
     * would learn nothing from.
     */
    private suspend fun push(api: AttendanceSyncApi, userId: String, at: Instant): Boolean {
        val batch = store.preparePush(userId, settings.current, at)
        Log.d(
            TAG,
            "operation: push, batch prepared: initial: ${batch.initial}, " +
                "rows per table: " + batch.bodies.entries.joinToString(", ") { "${it.key.wireName}: ${it.value.size}" } +
                ", tombstones: ${batch.tombstones.size}, accountsDue: ${batch.accounts != null}"
        )

        val accepted = mutableMapOf<SyncTable, MutableList<AttendanceSyncStore.Outgoing>>()
        var tablesOk = true
        for ((table, rows) in batch.bodies) {
            var tableOk = true
            for (chunk in rows.chunked(AttendanceSyncPolicy.PUSH_CHUNK_SIZE)) {
                Log.d(TAG, "operation: push, table: ${table.wireName}, sending chunk size: ${chunk.size}")
                when (val res = api.push(table, chunk.map { it.body })) {
                    AttendanceSyncApi.PushResult.Ok ->
                        accepted.getOrPut(table) { mutableListOf() } += chunk

                    // Refused is not retryable in this pass — the server will say the same
                    // thing again — but it is not fatal either: the rows keep their `dirty`
                    // flag and the pass carries on with the tables that still might land.
                    is AttendanceSyncApi.PushResult.Refused -> {
                        Log.e(TAG, "operation: push, table: ${table.wireName}, Refused: ${res.code}")
                        tableOk = false
                    }

                    AttendanceSyncApi.PushResult.Unreachable -> {
                        // The network went away mid-push. Stop asking.
                        Log.e(TAG, "operation: push, table: ${table.wireName}, Unreachable")
                        tableOk = false
                        break
                    }
                }
            }
            if (!tableOk) tablesOk = false
        }

        val acceptedTombstones = mutableListOf<String>()
        var tombstonesOk = true
        for ((table, group) in batch.tombstones.groupBy { it.table }) {
            for (chunk in group.chunked(AttendanceSyncPolicy.PUSH_CHUNK_SIZE)) {
                when (api.push(table, chunk.map { it.body })) {
                    AttendanceSyncApi.PushResult.Ok -> acceptedTombstones += chunk.map { it.key }
                    else -> {
                        tombstonesOk = false
                        break
                    }
                }
            }
        }

        var accountsAgreed: AttendanceSyncStore.AccountsAgreed? = null
        batch.accounts?.let { body ->
            if (api.pushAccounts(body) == AttendanceSyncApi.PushResult.Ok) {
                accountsAgreed = AttendanceSyncStore.AccountsAgreed(
                    clientUpdatedAt = batch.accountsClientUpdatedAt ?: at.toEpochMilli(),
                    fingerprint = batch.accountsFingerprint.orEmpty(),
                )
            }
        }
        Log.d(
            TAG,
            "operation: push, push concluded: tablesOk: $tablesOk, tombstonesOk: $tombstonesOk, " +
                "accountsAccepted: ${accountsAgreed != null}, initialPushDone will be set: ${batch.initial && tablesOk && tombstonesOk}"
        )

        store.markPushed(
            userId = userId,
            accepted = accepted,
            acceptedTombstones = acceptedTombstones,
            accountsAgreed = accountsAgreed,
            initialDone = batch.initial && tablesOk && tombstonesOk,
            at = at,
        )

        return accepted.isNotEmpty() || acceptedTombstones.isNotEmpty() || accountsAgreed != null
    }

    private companion object {
        private const val TAG = "AttendanceSyncDiag"

        /**
         * How many pages one table may be read through in a single pass.
         *
         * Not a limit on how much a device will sync — the next pass continues from the
         * cursor — but a bound on how long one pass can run. A first sync on an install with
         * a term of history is thousands of rows, and reading them all in one uninterrupted
         * pass would hold the pass gate for a long time while every later trigger queued
         * behind it. At 200 rows a page this is 10,000 rows a table before the pass yields.
         */
        const val MAX_PAGES_PER_TABLE = 50

        /**
         * How many further reads a manual sync may make beyond its first, when the first did
         * not finish.
         *
         * A read that is finished on the first round — every account this app has ever held —
         * costs no second round at all; this only bounds an account large enough to need
         * several. At 10,000 rows a table per round it is a ceiling far above any student's
         * history, and it exists so a table that somehow never reports itself finished cannot
         * hold a manual sync open indefinitely. Reaching it is not a failure: the cursor has
         * moved by everything that *was* read, so the next pass — background or manual —
         * continues from there.
         */
        const val MAX_RECONCILE_ROUNDS = 20
    }
}

/**
 * What one attendance pass concluded, for a caller that has to wait on it.
 *
 * The automatic [AttendanceSyncEngine.wake] path still swallows this; Account
 * Settings' Sync now is the caller that surfaces it. Network / 5xx are
 * [Unreachable], never deletion.
 */
sealed interface SyncPassResult {
    /** Pull committed, push ran, cursor stamped. */
    data object Completed : SyncPassResult

    /** No API, or the session is not Linked. */
    data object NotLinked : SyncPassResult

    /**
     * [AuthLifecycleState.forbidsAuthenticatedWork] — the account is gone, or
     * there is no session. Not a generic failure; the existing lifecycle owns it.
     */
    data object AccountGone : SyncPassResult

    /** Local attendance belongs to a different account. Nothing was uploaded. */
    data object Refused : SyncPassResult

    /** A pull could not reach the server. Network / 5xx, not deletion. */
    data object Unreachable : SyncPassResult
}
