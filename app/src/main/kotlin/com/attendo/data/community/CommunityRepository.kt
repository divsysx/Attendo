package com.attendo.data.community

import android.content.Context
import androidx.room.withTransaction
import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityPoll
import com.attendo.core.community.CommunityRules
import com.attendo.core.community.OutboxPolicy
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.CommunityObservationCacheEntity
import com.attendo.data.db.CommunityOutboxEntity
import com.attendo.data.db.toModel
import com.attendo.core.community.ReportPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The single facade every community surface talks to.
 *
 * Local-first, exactly as the plan locked it:
 *
 *  * **Reports enqueue, then send.** [submitReport] validates with
 *    [CommunityRules] (same rules the server enforces), writes an outbox row with a
 *    fresh idempotency key, and hands off to [CommunitySync]. A submission the student
 *    saw confirmed is then either delivered exactly once (the key makes retries safe)
 *    or surfaced as failed after [OutboxPolicy.MAX_ATTEMPTS] attempts — never silently
 *    dropped.
 *  * **Reads come from the cache, refreshed by snapshot.** The Rooms tab renders the
 *    cache immediately and a refresh replaces it wholesale — the snapshot is the
 *    authority, Realtime is freshness only.
 *
 * Everything the app needs is here so no ViewModel ever touches Supabase, Room, or
 * the sync engine directly.
 */
class CommunityRepository(
    private val context: Context,
    private val database: AttendoDatabase,
    private val client: CommunityClient?,
    private val sync: CommunitySync,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val clientVersionCode: () -> Long = { 0L },
) {
    private val dao get() = database.communityDao()

    private val _identityReady = MutableStateFlow(false)

    private companion object {
        /** The pause before a one-shot RPC's single transient retry — see [rpcWithTransientRetry]. */
        const val TRANSIENT_RETRY_DELAY_MS = 1_500L
    }

    // ------------------------------------------------------------- submissions

    /**
     * Enqueues a report and tries to send it immediately.
     *
     * Client-side validation failure returns the reason without touching the outbox:
     * the sheet shows it and the student fixes it. A server rejection (rate limited,
     * restricted, duplicate window) is recorded on the row and surfaced in "your
     * reports" — the report is not retried, because the server will give the same
     * answer tomorrow.
     *
     * A future claim is the same flow with one more check: [CommunityRules.futureClaimValidationError]
     * previews the server's claim block (room kind, later slot today, not yet started) before
     * the row is written, and the claim's target slot travels on the outbox row so a retry
     * months of app-versions later still sends what the student meant.
     */
    fun submitReport(
        kind: com.attendo.core.community.CommunityReportKind,
        room: String?,
        section: String?,
        subject: String?,
        classDate: LocalDate?,
        startHour: Int?,
        payload: ReportPayload,
        note: String?,
        observationType: CommunityObservationType = CommunityObservationType.PRESENT,
        targetStartHour: Int? = null,
    ): String? {
        val error = CommunityRules.reportValidationError(kind, room, classDate, startHour, payload, note)
        if (error != null) return error

        val today = LocalDate.now()
        if (observationType == CommunityObservationType.FUTURE) {
            val claimError = CommunityRules.futureClaimValidationError(
                kind = kind,
                room = room,
                today = today,
                now = java.time.LocalDateTime.now(),
                targetStartHour = targetStartHour,
            )
            if (claimError != null) return claimError
        }

        val key = UUID.randomUUID().toString()
        val row = CommunityOutboxEntity(
            idempotencyKey = key,
            kind = kind,
            room = room,
            section = section,
            subject = subject,
            classDate = classDate,
            startHour = startHour,
            payloadJson = payload.toJson().toString(),
            note = note,
            observationType = observationType,
            eventDate = if (observationType == CommunityObservationType.FUTURE) today else null,
            targetStartHour = targetStartHour,
            createdAt = now(),
        )
        scope.launch {
            dao.upsertOutbox(row)
            sync.drain()
        }
        return null
    }

    /**
     * The student's own reports: pending, sent, rejected, and — once the server
     * confirms an undo or withdrawal — taken back, with which way. What "My Reports"
     * shows. The states a row can be in, and their meanings:
     *
     *  * PENDING (queued)  — waiting for its first send: sent immediately when the
     *    network is up, held when it is not.
     *  * PENDING (backoff) — a send failed; retrying on the [OutboxPolicy] schedule.
     *  * SENT               — the server accepted it.
     *  * FROZEN             — the server refused it because this identity is waiting out
     *    a pending replacement claim; a state, not a verdict — the row keeps its retry
     *    schedule and sends when the claim resolves either way.
     *  * FAILED             — [OutboxPolicy.MAX_ATTEMPTS] sends failed; the row is the
     *    student's to retry or remove.
     *  * REJECTED           — the server refused it (rate limited, duplicate window);
     *    not retried, because the server will say the same thing again.
     *  * WITHDRAWN          — the server confirmed an undo or withdrawal; terminal
     *    for the client, kept visible until the day's history is pruned.
     */
    suspend fun myReports(): List<OutboxUiState> = database.withTransaction {
        dao.allOutbox().map { row ->
            OutboxUiState(
                idempotencyKey = row.idempotencyKey,
                kind = row.kind,
                room = row.room,
                createdAt = row.createdAt,
                status = when {
                    row.withdrawalKind != null -> OutboxUiState.Status.WITHDRAWN
                    row.rejectionCode != null -> OutboxUiState.Status.REJECTED
                    row.sentAt != null -> OutboxUiState.Status.SENT
                    // Before the attempt cap: a frozen row is frozen no matter how many
                    // times the refusal has repeated, and must never read as a network
                    // failure — the server is fine, the identity is mid-move.
                    row.frozenAt != null -> OutboxUiState.Status.FROZEN
                    row.failedAttempts >= OutboxPolicy.MAX_ATTEMPTS -> OutboxUiState.Status.FAILED
                    else -> OutboxUiState.Status.PENDING
                },
                failureCode = row.rejectionCode,
                observationType = row.observationType,
                targetStartHour = row.targetStartHour,
                withdrawalKind = row.withdrawalKind,
                serverObservationId = row.serverObservationId,
                sentAt = row.sentAt,
            )
        }
    }

    /** Delete a failed/rejected outbox row (the "dismiss" affordance). */
    fun forget(key: String) {
        scope.launch { dao.deleteOutbox(key) }
    }

    /**
     * The outbox as a stream — every row change (enqueue, send, failure, refusal,
     * withdrawal) emits. "My Reports" and the verdict buttons' own-report rule both
     * live downstream of this: the screen reflects the submission state machine as
     * it moves, instead of on the next pull.
     */
    fun outboxChanges(): Flow<List<com.attendo.data.db.CommunityOutboxEntity>> =
        dao.allOutboxFlow()

    /**
     * Retry a failed or declined row now, bypassing the backoff schedule.
     *
     * The search is [CommunityDao.allOutbox], not the pending set: a declined row carries
     * its refusal code and so is invisible to `pendingOutbox()`, yet the Retry its card
     * offers must genuinely revive it — some refusals clear with time (a dead session
     * re-mints on the next contact; the PGRST202 shape mismatch that broke reports before
     * the null-parameter fix cleared with this app update). The reset clears the refusal
     * and the counters but keeps the row and its idempotency key, so a re-send is free of
     * double effects. Sent rows are left alone: their button never exists.
     */
    fun retryNow(key: String) {
        scope.launch {
            dao.allOutbox().firstOrNull { it.idempotencyKey == key }
                ?.takeIf { it.sentAt == null }
                ?.let { dao.resetOutboxForRetry(key) }
                ?.let { sync.drain() }
        }
    }

    /**
     * Wakes the community drain engine. Fire-and-forget: the engine already
     * retries, and the waitable success/failure a student sees from Account
     * Settings is the attendance pass.
     *
     * A thin wrapper so no ViewModel ever holds [CommunitySync] or calls
     * [CommunitySync.drainOnce].
     */
    fun requestDrain() {
        sync.drain()
    }

    // ---------------------------------------------------------- undo / withdraw

    /**
     * The server's answer about a taken-back item, mapped for the caller's UI:
     * DONE (this call confirmed it), ALREADY (idempotent success — it was already
     * done, nothing changed), REFUSED (the server said no: not yours, window gone,
     * poll closed), OFFLINE (never reached the server — retryable).
     */
    enum class WithdrawOutcome { DONE, ALREADY, REFUSED, OFFLINE }

    /**
     * Takes a just-submitted report back. The server enforces everything that matters
     * — owner-only, fresh window, atomicity, idempotency, zero reputation change — and
     * this method's job is to record what the server actually confirmed, nothing more:
     * the row's withdrawalKind is stamped only from an `ok` envelope, so a refused undo
     * never reads on screen as one that happened. The undo targets the row's *server*
     * id, so it only exists once the submission has been accepted; before that the row
     * is still local-only and the honest action is deleting the outbox row, which the
     * caller decides.
     */
    fun undoReport(key: String, onDone: (WithdrawOutcome) -> Unit) {
        scope.launch {
            val outcome = withdrawReportInternal(key, undo = true)
            onDone(outcome)
        }
    }

    /** [undoReport] after the window: one reputation decrease, ever (server-enforced). */
    fun withdrawReport(key: String, onDone: (WithdrawOutcome) -> Unit) {
        scope.launch {
            val outcome = withdrawReportInternal(key, undo = false)
            onDone(outcome)
        }
    }

    private suspend fun withdrawReportInternal(key: String, undo: Boolean): WithdrawOutcome {
        val api = authenticatedApi() ?: return WithdrawOutcome.OFFLINE
        val row = dao.allOutbox().firstOrNull { it.idempotencyKey == key } ?: return WithdrawOutcome.REFUSED
        val observationId = row.serverObservationId ?: return WithdrawOutcome.REFUSED
        if (row.withdrawalKind != null) return WithdrawOutcome.ALREADY

        val result = rpcWithTransientRetry {
            if (undo) api.undoReport(observationId) else api.withdrawReport(observationId)
        }
        return when (result) {
            is RemoteReportApi.SubmitResult.Ok -> {
                // The kind recorded is what was asked *and* confirmed: an undo outside
                // the window is refused server-side (never lands here), and a withdrawal
                // inside it is the server's own 'withdraw' answer. The envelope's id is
                // the observation's, not a kind — the kind is ours to name.
                dao.markOutboxWithdrawn(key, if (undo) "undo" else "withdraw")
                WithdrawOutcome.DONE
            }
            is RemoteReportApi.SubmitResult.Rejected ->
                when (result.code) {
                    "already_withdrawn" -> WithdrawOutcome.ALREADY
                    else -> WithdrawOutcome.REFUSED
                }
            RemoteReportApi.SubmitResult.Unreachable -> WithdrawOutcome.OFFLINE
        }
    }

    // ------------------------------------------------------------------ reads

    /**
     * The API behind an authenticated session — the community read path's gate.
     *
     * Every community read is granted to `authenticated` only (anon gets nothing, by
     * design), so a fresh install cannot read until it holds an anonymous identity.
     * Reading *is* a community contact: the first time a community surface appears,
     * the session is minted here — a student who never reports anything still sees
     * the rooms and polls, not "unavailable". The mint is idempotent (an existing
     * session is loaded, not replaced), so calling this on every refresh re-uses one
     * identity for the install's whole life. Null means no client configured or
     * Supabase unreachable — the honest "can't read right now", never an exception.
     */
    private suspend fun authenticatedApi(): RemoteReportApi? {
        val client = client ?: return null
        if (CommunityIdentityStore(context).ensureSession(client) == null) return null
        return RemoteReportApi(client)
    }

    /**
     * The cached snapshot — what the Rooms tab shows before the first fetch lands and
     * whenever the network is gone. The cache is judged by the snapshot's own
     * `server_now`, never the device clock.
     */
    suspend fun cachedObservations(): List<CommunityObservation> =
        dao.cachedObservations().mapNotNull { it.toModel() }

    /** Refreshes the cache from the authoritative snapshot. Returns false when unreachable. */
    suspend fun refresh(): Boolean {
        val api = authenticatedApi() ?: return false
        val snapshot = api.snapshot() ?: return false
        database.withTransaction {
            dao.clearCachedObservations()
            dao.upsertCachedObservations(
                snapshot.observations.map { o ->
                    CommunityObservationCacheEntity(
                        id = o.id,
                        kind = o.kind,
                        status = o.status,
                        room = o.room,
                        section = o.section,
                        subject = o.subject,
                        classDate = o.classDate,
                        startHour = o.startHour,
                        payloadJson = o.payload.toJson().toString(),
                        note = o.note,
                        createdAt = o.createdAt,
                        expiresAt = o.expiresAt,
                        observationType = o.observationType,
                        eventDate = o.eventDate,
                        targetStartHour = o.targetStartHour,
                        targetEndHour = o.targetEndHour,
                        // The verdict tallies: the view computes them, the cache keeps
                        // them, the card shows them. Without these two lines a "Not
                        // right" tap refetched a snapshot whose counts were dropped at
                        // this exact boundary — the two-device test's "no update at all".
                        verificationCount = o.verificationCount,
                        disputeCount = o.disputeCount,
                        myVerdict = o.myVerdict,
                        snapshotServerNow = snapshot.serverNow,
                    )
                }
            )
        }
        reconcileSentReportsWith(api)
        return true
    }

    /**
     * The wipe rule and the move rule, in one pass: the server's memory of this
     * identity's reports is the truth "My Reports" keeps, in both directions.
     *
     * **Deletion half.** When the backend is wiped (`delete from
     * community.observations` — retention's deletes included, once 0014 pulses
     * them), the observations cache empties on this very refresh and the polls are
     * re-fetched live, but the outbox rows behind "My Reports" are device-local and
     * would linger until the 24-hour prune. Every server-confirmed row (it carries
     * a `serverObservationId`) that the server's own owner-facing answer no longer
     * lists is a row the server no longer has, and the phone lets it go the moment
     * it learns that.
     *
     * **Addition half — the rehydration.** The reverse gap is the identity-move bug:
     * when a transfer or replacement completes on *another* phone (the owner's
     * approval, or a window that simply ran out), this phone's import call returned
     * `ClaimPending` long ago and never ran its own completion path — the outbox is
     * empty of the moved identity's reports, so "My Reports" shows nothing and the
     * verdict buttons' own-report rule (`mySentIds`, derived from the outbox) offers
     * buttons on reports this identity owns. The server would refuse every tap with
     * `own_report` — the two-device test's "tappable options that do nothing". So a
     * server row the outbox does not know is added, as a sent row carrying its
     * server id: "Your report", no buttons, undo/withdraw working.
     *
     * Only *live* rows are rehydrated (expires_at still ahead): the outbox's own
     * 24-hour prune is the honest horizon for dead history, and re-adding rows the
     * prune just removed would make it useless. A live row is exactly the one a
     * card can still render and the own-report rule still needs.
     *
     * The added rows are synthesized, not recovered: the original idempotency keys
     * died with the identity that made them (or live on in another install's
     * outbox), so each row gets a deterministic `rehydrated-<server id>` key. That
     * is safe for one reason — the row is stamped sent, and the send path only ever
     * reads unsent rows. A key on a sent row is a label, not a replay risk.
     *
     * Two safety rules for the deletion half, both load-bearing:
     *
     *  * **Candidates are captured *before* the fetch.** A report accepted while the
     *    fetch is in flight stamps its server id after this list was taken, and must
     *    never be judged against an answer that predates it — that race would delete a
     *    live report and take its undo button with it. Rows without a server id yet
     *    (pending, failed, declined) are not the server's to confirm or deny and stay
     *    untouched — they are the student's local record, cleared only by the prune or
     *    their own dismiss.
     *  * **A null answer reconciles nothing.** [RemoteReportApi.myPendingReports]
     *    returns null when the call failed, precisely so a network blip cannot read as
     *    "the server has none of your reports" and erase the outbox. Only a real answer
     *    — an empty one included — carries the truth.
     */
    private suspend fun reconcileSentReportsWith(api: RemoteReportApi) {
        val candidates = dao.allOutbox().filter { it.serverObservationId != null }
        val serverTruth = api.myPendingReports() ?: return
        val known = serverTruth.mapNotNull { it.id }.toSet()
        candidates
            .filter { it.serverObservationId !in known }
            .forEach { dao.deleteOutbox(it.idempotencyKey) }

        val knownLocal = candidates.map { it.serverObservationId }.toSet()
        val at = now()
        serverTruth
            .filter { report ->
                val id = report.id ?: return@filter false
                id !in knownLocal && report.expiresAt.isAfter(at)
            }
            .forEach { report ->
                dao.upsertOutbox(
                    CommunityOutboxEntity(
                        idempotencyKey = "rehydrated-${report.id}",
                        kind = report.kind,
                        room = report.room,
                        section = report.section,
                        subject = report.subject,
                        classDate = report.classDate,
                        startHour = report.startHour,
                        payloadJson = report.payload.toJson().toString(),
                        note = report.note,
                        observationType = report.observationType,
                        eventDate = report.eventDate,
                        targetStartHour = report.targetStartHour,
                        createdAt = report.createdAt,
                        sentAt = report.createdAt,
                        serverObservationId = report.id,
                        withdrawalKind = report.withdrawalKind,
                    ),
                )
            }
    }

    /**
     * The snapshot's server clock, recovered from the cache rows. Every cache row carries
     * the same `snapshotServerNow`, so one row is enough — and no rows means null, which
     * the UI reads as "judge expiries by nothing" rather than guessing with the device
     * clock.
     */
    suspend fun cacheServerNow(): Instant? = dao.cachedObservations().firstOrNull()?.snapshotServerNow

    /** Rooms this student swiped away. Client-only state, never sent to the server. */
    suspend fun dismissedRooms(): List<String> = dao.dismissed().map { it.key }

    fun dismissRoom(room: String) {
        scope.launch {
            dao.dismiss(com.attendo.data.db.CommunityDismissedEntity(room, now()))
        }
    }

    // ---------------------------------------------------------- verify / vote

    /**
     * One silent retry for a one-shot RPC that got no answer.
     *
     * `Unreachable` means the request never came back — DNS blip, a dropped connection,
     * a token refresh that raced the call — not that the server refused anything. The
     * server may or may not have applied the first attempt, and every one-shot here is
     * idempotent server-side (`already_voted`, `already_verified`, `already_withdrawn`,
     * and the poll idempotency key makes a replay return the original answer), so
     * asking once more after a pause cannot double-apply. The retry is silent because
     * the student only needs to hear about it if both attempts fail. One retry, not a
     * loop: a network that is genuinely down is the outbox's / status row's story to
     * tell, not a spinner's.
     */
    private suspend fun rpcWithTransientRetry(
        call: suspend () -> RemoteReportApi.SubmitResult,
    ): RemoteReportApi.SubmitResult {
        val first = call()
        if (first != RemoteReportApi.SubmitResult.Unreachable) return first
        delay(TRANSIENT_RETRY_DELAY_MS)
        return call()
    }

    /** "Is this accurate?" — one verdict per user per observation. */
    fun verify(observationId: String, verdict: Boolean, onDone: (RemoteReportApi.SubmitResult) -> Unit) {
        scope.launch {
            val result = client?.let {
                rpcWithTransientRetry { RemoteReportApi(it).verify(observationId, verdict) }
            } ?: RemoteReportApi.SubmitResult.Unreachable
            onDone(result)
        }
    }

    /**
     * The open polls with their tallies — fetched live, kept nowhere.
     *
     * Unlike observations, polls are deliberately not cached in Room: a poll is a
     * question about *now* whose closes_at is hours away at best, so a cached poll is
     * either still open (and the fetch that failed would have refreshed it) or noise
     * from a yesterday the student is not asking about. The screen keeps what it had
     * in memory across a failed fetch; a restart offline simply shows no polls, which
     * is the honest answer. The snapshot's server-now travels with the rows so the
     * caller can keep judging closes_at on the server's clock while the list ages —
     * that clock is the whole difference between "the last fetch's data" and "the last
     * fetch's truth". Null means unreachable (or no client) — the caller keeps
     * whatever it already had.
     */
    suspend fun openPolls(): RemoteReportApi.PollsSnapshot? {
        val api = authenticatedApi() ?: return null
        return api.openPolls()
    }

    /**
     * Asks the room a question. Online-only by design: polls are spontaneity, not
     * record-keeping, so there is no outbox — a failed creation says so on the spot
     * and the student can tap Ask again.
     *
     * The sheet disables its button while a request is in flight; the server's dedup
     * window is the backstop for anything that still slips through.
     */
    fun createPoll(
        room: String?,
        section: String?,
        subject: String?,
        classDate: LocalDate?,
        startHour: Int?,
        question: String,
        options: List<String>,
        onDone: (RemoteReportApi.SubmitResult) -> Unit,
    ) {
        scope.launch {
            val api = authenticatedApi()
            // Minted before the call and reused across the transient retry: a replay
            // must answer "here is the poll you already made", never make a second one.
            val idempotencyKey = UUID.randomUUID().toString()
            val result = api?.let {
                rpcWithTransientRetry {
                    it.createPoll(
                        room = room,
                        section = section,
                        subject = subject,
                        classDate = classDate,
                        startHour = startHour,
                        question = question,
                        options = options,
                        idempotencyKey = idempotencyKey,
                        clientVersionCode = clientVersionCode(),
                    )
                }
            } ?: RemoteReportApi.SubmitResult.Unreachable
            onDone(result)
        }
    }

    /**
     * One vote per student per poll — the PK makes a double vote structurally
     * impossible, so a double-tap is harmless. Votes, like every community write,
     * ride the same authenticated session the read path mints.
     */
    fun castVote(pollId: String, optionIndex: Int, onDone: (RemoteReportApi.SubmitResult) -> Unit) {
        scope.launch {
            val api = authenticatedApi()
            val result = api?.let { rpcWithTransientRetry { it.castVote(pollId, optionIndex) } }
                ?: RemoteReportApi.SubmitResult.Unreachable
            onDone(result)
        }
    }

    // --------------------------------------------------------------- my polls

    /**
     * The student's own polls, straight from the owner-facing RPC — not cached,
     * for the same reason [openPolls] is not (a poll is about *now*), but unlike the
     * public list these rows include the withdrawn ones for their audit window.
     * Null means unreachable or no client; the caller keeps whatever it had.
     */
    suspend fun myPolls(): List<CommunityPoll>? {
        val api = authenticatedApi() ?: return null
        return api.myPolls()
    }

    /**
     * Takes a just-asked poll back inside the fresh window — the polls' twin of
     * [undoReport]. The server enforces ownership, the window, and the zero-penalty
     * rule; unlike a report there is no outbox row, so there is nothing to record
     * locally — the next [myPolls] fetch carries the truth.
     */
    fun undoPoll(pollId: String, onDone: (WithdrawOutcome) -> Unit) {
        scope.launch {
            onDone(pollWithdrawInternal(pollId, undo = true))
        }
    }

    /** [undoPoll] after the window: one reputation decrease, ever (server-enforced). */
    fun withdrawPoll(pollId: String, onDone: (WithdrawOutcome) -> Unit) {
        scope.launch {
            onDone(pollWithdrawInternal(pollId, undo = false))
        }
    }

    private suspend fun pollWithdrawInternal(pollId: String, undo: Boolean): WithdrawOutcome {
        val api = authenticatedApi() ?: return WithdrawOutcome.OFFLINE
        val result = rpcWithTransientRetry {
            if (undo) api.undoPoll(pollId) else api.withdrawPoll(pollId)
        }
        return when (result) {
            is RemoteReportApi.SubmitResult.Ok -> WithdrawOutcome.DONE
            is RemoteReportApi.SubmitResult.Rejected ->
                when (result.code) {
                    "already_withdrawn" -> WithdrawOutcome.ALREADY
                    else -> WithdrawOutcome.REFUSED
                }
            RemoteReportApi.SubmitResult.Unreachable -> WithdrawOutcome.OFFLINE
        }
    }

    // ------------------------------------------- reporting identity transfer

    /**
     * What exporting the `.atid` file did. Every refusal keeps the server's own
     * words: `nothing_to_transfer` (no community history to move), `rate_limited`
     * (3 exports a day), `transfer_already_pending` (a live grant exists — abort it
     * first), `identity_superseded` (this identity already moved away).
     */
    sealed interface AtidExportResult {
        /** The file is written, encrypted, and the code inside is single-use. */
        data class Exported(val expiresAt: Instant) : AtidExportResult
        data class Refused(val code: String) : AtidExportResult
        data object Unreachable : AtidExportResult
        /** The server minted the code but the file could not be written — nothing was exported. */
        data object WriteFailed : AtidExportResult
    }

    /**
     * What importing a `.atid` file did. The passphrase and file failures are the
     * codec's typed answers verbatim; the claim outcomes are the server's state
     * machine. A [AtidImportResult.Claimed] means the 24-hour veto window is now
     * running on the old device; a [AtidImportResult.Completed] means the identity,
     * history and reputation are this install's now — and, on a replacement, that
     * the identity this install held before has been hard-deleted server-side.
     */
    sealed interface AtidImportResult {
        data class Claimed(val claimDeadline: Instant, val replaceClaimant: Boolean = false) : AtidImportResult
        data class Completed(val serverNow: Instant, val replaced: Boolean = false) : AtidImportResult
        data object WrongPassphrase : AtidImportResult
        data object NotAnAtidFile : AtidImportResult
        data class UnsupportedVersion(val version: Int) : AtidImportResult
        data object Corrupt : AtidImportResult
        /** The file could not be read at all — wrong pick, revoked permission. */
        data object Unreadable : AtidImportResult
        /** The server refused the claim — `identity_not_fresh`, `transfer_in_progress`, … */
        data class Refused(val code: String) : AtidImportResult
        data object Unreachable : AtidImportResult
    }

    /**
     * Exports the reporting identity as an encrypted `.atid` file to a SAF location
     * the user chose.
     *
     * The transfer code exists in clear text for exactly the span of this call: the
     * server mints it, the codec encrypts it, the file store writes it, the array is
     * wiped. It is never in Room, never in preferences, never logged, and never in
     * any backup — the include-lists do not know this flow exists, and the guard
     * test pins that.
     */
    suspend fun exportIdentityBackup(uri: android.net.Uri, passphrase: CharArray): AtidExportResult {
        val api = authenticatedApi() ?: return AtidExportResult.Unreachable
        var code: String? = null
        var expiresAt = Instant.EPOCH
        try {
            when (val began = api.beginIdentityTransfer()) {
                is RemoteReportApi.TransferResult.CodeMinted -> {
                    code = began.code
                    expiresAt = began.expiresAt
                }
                is RemoteReportApi.TransferResult.Rejected -> return AtidExportResult.Refused(began.code)
                else -> return AtidExportResult.Unreachable
            }
            val content = AtidCodec.encode(code!!, passphrase, now())
            val written = AtidFileStore(context).writeTo(uri, content)
            return if (written) {
                // The code dies with this frame; what the UI shows afterwards comes
                // from expiresAt, which carries no secret.
                AtidExportResult.Exported(expiresAt)
            } else {
                AtidExportResult.WriteFailed
            }
        } finally {
            // The passphrase array is wiped; the code itself is a String (immutable
            // in Kotlin), so the honest guarantee is scope: it dies with this frame.
            passphrase.fill(' ')
        }
    }

    /**
     * Imports a `.atid` file: decrypt it with the passphrase, then claim the
     * identity it authorizes — no more. Possession of the file alone never moves
     * anything; the claim is what starts the server's transfer, and the old device
     * keeps its veto.
     *
     * [replaceClaimant] is the destructive path: it is passed only after the
     * user's explicit confirmation that this phone's own community history may
     * be permanently deleted. It changes nothing about *when* the deletion
     * happens — the server still waits out the 24-hour window, during which
     * this phone's writes are frozen server-side and its unsent reports are
     * preserved locally. The outbox is cleared only on completion, when the
     * identity it belongs to is genuinely gone: if the move is aborted or
     * expires, this phone's data — local and server — is exactly as it was.
     *
     * On a completed transfer the cache is refreshed immediately so the moved
     * history is what the student sees next, not the empty cache of the identity
     * that just received it.
     */
    suspend fun importIdentityBackup(
        uri: android.net.Uri,
        passphrase: CharArray,
        replaceClaimant: Boolean = false,
    ): AtidImportResult {
        val text = AtidFileStore(context).readFrom(uri) ?: return AtidImportResult.Unreadable
        try {
            return when (val decoded = AtidCodec.decode(text, passphrase)) {
                is AtidCodec.DecodeResult.Decoded -> when (
                    val claimed = claimIdentityInternal(decoded.transferCode, replaceClaimant)
                ) {
                is RemoteReportApi.TransferResult.ClaimPending -> {
                    // The wait exists from this instant, so the memory of it is
                    // written HERE, in the claim's own call — every later status
                    // read is optional (the screen can be left, the process
                    // killed, a refresh in flight when the claim landed can be
                    // the one that never re-runs) and the two-device test lost a
                    // "move completed" notice to exactly that. This read happens
                    // after the claim committed, so it is guaranteed to see the
                    // pending window and record the claimant's frozen report set.
                    identityStatus()
                    AtidImportResult.Claimed(claimed.claimDeadline, claimed.replaceClaimant)
                }
                    is RemoteReportApi.TransferResult.Completed -> {
                        // The replacement is done: the outbox rows belong to an
                        // identity that no longer exists, and the cached community
                        // rows are the replaced identity's. Clear both, then pull
                        // the moved history so it is what renders. The refresh's
                        // reconcile rehydrates the moved identity's own reports
                        // into the outbox, so they read as "Your report" instead
                        // of strangers' reports with buttons the server refuses.
                        if (claimed.replaced) {
                            database.withTransaction {
                                dao.clearOutbox()
                                dao.clearCachedObservations()
                                dao.clearDismissed()
                            }
                        }
                        refresh()
                        AtidImportResult.Completed(claimed.serverNow, claimed.replaced)
                    }
                    is RemoteReportApi.TransferResult.Rejected ->
                        AtidImportResult.Refused(claimed.code)
                    else -> AtidImportResult.Unreachable
                }
                AtidCodec.DecodeResult.NotAnAtidFile -> AtidImportResult.NotAnAtidFile
                is AtidCodec.DecodeResult.UnsupportedVersion ->
                    AtidImportResult.UnsupportedVersion(decoded.version)
                AtidCodec.DecodeResult.WrongPassphrase -> AtidImportResult.WrongPassphrase
                AtidCodec.DecodeResult.Corrupt -> AtidImportResult.Corrupt
            }
        } finally {
            passphrase.fill(' ')
        }
    }

    /** The owner's explicit consent during the veto window — completes the transfer now. */
    fun approveIdentityTransfer(onDone: (RemoteReportApi.TransferResult) -> Unit) {
        scope.launch {
            val api = authenticatedApi()
            onDone(api?.approveIdentityTransfer() ?: RemoteReportApi.TransferResult.Unreachable)
        }
    }

    /** The owner's Keep-my-identity button — kills the live grant (armed or pending). */
    fun abortIdentityTransfer(onDone: (RemoteReportApi.TransferResult) -> Unit) {
        scope.launch {
            val api = authenticatedApi()
            onDone(api?.abortIdentityTransfer() ?: RemoteReportApi.TransferResult.Unreachable)
        }
    }

    private suspend fun claimIdentityInternal(
        transferCode: String,
        replaceClaimant: Boolean = false,
    ): RemoteReportApi.TransferResult {
        val api = authenticatedApi() ?: return RemoteReportApi.TransferResult.Unreachable
        return api.claimReportingIdentity(transferCode, replaceClaimant)
    }

    /**
     * This identity's standing — the pending-claim warning and the superseded
     * detector both read here. Null means unknown (unreachable / no client), which
     * the caller must treat as "do not claim either way", never as "fine".
     *
     * Every read also maintains [PendingMoveMemory]: a wait seen here is
     * remembered, and a wait gone clears it. The move's outcome arrives on this
     * phone as the difference between two of these reads, and the memory is what
     * lets the second one happen in a different ViewModel than the first.
     */
    suspend fun identityStatus(): RemoteReportApi.IdentityStatus? {
        val api = authenticatedApi() ?: return null
        val status = api.identityStatus() ?: return null
        withContext(Dispatchers.IO) {
            val memory = PendingMoveMemory(context)
            when {
                status.claimPending -> memory.write(
                    PendingMoveMemory.Pending(claimant = true, reportIds = status.reportIds),
                )
                status.transferPending -> memory.write(
                    PendingMoveMemory.Pending(claimant = false, reportIds = status.reportIds),
                )
                else -> memory.clear()
            }
        }
        return status
    }

    /**
     * The move this phone last saw waiting on it, if any. Read this *before*
     * [identityStatus]: the status call consumes the memory when the wait is
     * over, and the pair — remembered wait, live answer — is what a resolution
     * is made of.
     */
    suspend fun pendingMoveMemory(): PendingMoveMemory.Pending? = withContext(Dispatchers.IO) {
        PendingMoveMemory(context).read()
    }

    /**
     * Starts over after this identity was superseded (moved to another device).
     *
     * The old identity is dead server-side — tombstoned, every write refused — so
     * the only honest paths are "restore that identity here" (a transfer back, via
     * the other device) or "start fresh". This is start fresh: the anonymous
     * session is signed out and wiped, the local community rows that belonged to
     * it are cleared (their undo buttons are pointless against a server that will
     * refuse every one of their writes), and a new anonymous identity is minted
     * with zero history. Reports made from now on belong to the new identity.
     *
     * Returns false when no client is configured or the new session could not be
     * minted — the caller keeps its superseded warning up either way, because in
     * that state the install still cannot write.
     */
    suspend fun startFreshIdentity(): Boolean {
        val c = client ?: return false
        CommunityIdentityStore(context).signOutAndWipe(c)
        database.withTransaction {
            dao.clearCachedObservations()
            dao.clearOutbox()
            dao.clearDismissed()
        }
        return CommunityIdentityStore(context).ensureSession(c) != null
    }

    /**
     * What retiring the outgoing identity server-side concluded. The distinction that
     * matters to the caller is one bit: did the server *answer*?
     *
     * A server that answered — [Retired] or [Declined] — has positively decided what
     * happens to the outgoing identity's rows (deleted, or definitively not deletable:
     * the account being signed into has no community identity yet, or the outgoing
     * session is itself an account, which is permanent). Either way the sign-in may
     * proceed to its local cleanup and the session import; retrying a definite answer
     * can only ever return the same one.
     *
     * [Unreachable] is not an answer. Nothing is known — the retirement may or may
     * not have run — so the only safe move is to abort the sign-in entirely and let
     * the student retry: the RPC is idempotent, and the outgoing identity is untouched
     * either way.
     */
    sealed interface RetirementOutcome {
        /** The outgoing identity's server rows are gone (or were already gone — idempotent). */
        data object Retired : RetirementOutcome

        /** The server definitively deleted nothing; its own code says why. */
        data class Declined(val code: String) : RetirementOutcome

        /** No answer: the outcome is unknown, and nothing may proceed on unknown. */
        data object Unreachable : RetirementOutcome
    }

    /**
     * Retires the identity this phone currently holds — the outgoing half of a
     * returning sign-in — server-side, in favour of [destinationUserId]: the
     * established account the student is signing back into.
     *
     * One RPC, migration 0021's `retire_reporting_identity`, riding the session it
     * retires: the call itself is the proof of ownership (every deletion is scoped to
     * `auth.uid()`), the destination is a precondition rather than a target, and the
     * whole deletion is one server-side transaction — grants, profile, and every
     * identity-owned row the schema's cascade removes — followed by the tombstone
     * that refuses any straggler write under the outgoing identity's still-valid
     * access token. Never a merge: the destination's rows are never touched, and no
     * count is ever moved or summed.
     *
     * This is the server half only. The local half — the outgoing identity's outbox,
     * cache and dismissals — is [discardLocalCommunityRows], and the two run in one
     * gated sequence in the account layer's import window, in that order, so a
     * retirement that cannot be confirmed never discards anything.
     */
    suspend fun retireOutgoingIdentity(destinationUserId: String): RetirementOutcome {
        val c = client ?: return RetirementOutcome.Declined("no_client")
        return when (val result = RemoteReportApi(c).retireReportingIdentity(destinationUserId)) {
            is RemoteReportApi.SubmitResult.Ok -> RetirementOutcome.Retired
            is RemoteReportApi.SubmitResult.Rejected -> RetirementOutcome.Declined(result.code)
            RemoteReportApi.SubmitResult.Unreachable -> RetirementOutcome.Unreachable
        }
    }

    /**
     * Discards every local community row tied to the identity this phone currently
     * holds — the same three clears as [startFreshIdentity], minus the identity
     * rotation around them.
     *
     * Called by the account layer's sign-in import, and only there: the moment the
     * callback's user proves to be a different user than the one this phone holds.
     * The rows written under the outgoing identity cannot follow it — outbox rows
     * carry no reporter of their own (the server stamps the reporter from the session
     * that sends them), so a row still unsent when the account's session lands would
     * be filed under the *account* by the next drain — a silent merge of one
     * identity's reports into another's. The discard runs inside the same send gate
     * as the retirement that precedes it and the session import that follows, so no
     * drain can interleave with it from either side.
     *
     * The cache and the dismissals go with the outbox for the same reason they go in
     * [startFreshIdentity]: they are the outgoing identity's view of the community,
     * and the next refresh re-fills them as the account's. Already-sent reports are
     * not touched anywhere by this — they live on the server under the identity that
     * sent them, which is exactly where signing in leaves them (the outgoing
     * identity's *server* rows are the retirement RPC's business, not this method's).
     */
    suspend fun discardLocalCommunityRows() {
        database.withTransaction {
            dao.clearCachedObservations()
            dao.clearOutbox()
            dao.clearDismissed()
        }
    }

    // ------------------------------------------------------------------ reset

    /**
     * Clears all community state: cache, outbox, dismissals, and the identity itself.
     * Called by AppReset — see [CommunityClient] for why the identity must die with a
     * reset. Room's clearAllTables (which AppReset already runs) empties the tables;
     * this wipes the identity file and signs the session out first.
     */
    suspend fun clearForReset() {
        client?.let { CommunityIdentityStore(context).signOutAndWipe(it) }
    }

    /** [clearForReset] against an explicit client — the container's reset wiring. */
    suspend fun clearForResetWith(client: CommunityClient) {
        CommunityIdentityStore(context).signOutAndWipe(client)
    }

    /** The one UI-visible shape of an outbox row. */
    data class OutboxUiState(
        val idempotencyKey: String,
        val kind: com.attendo.core.community.CommunityReportKind,
        val room: String?,
        val createdAt: Instant,
        val status: Status,
        val failureCode: String?,
        /** The claim fields, so "your reports" can say *what* a claim was about. */
        val observationType: CommunityObservationType = CommunityObservationType.PRESENT,
        val targetStartHour: Int? = null,
        /** 'undo' or 'withdraw' — set only from the server's confirmation. */
        val withdrawalKind: String? = null,
        /** The server's id for the row, once accepted — what undo/withdraw targets. */
        val serverObservationId: String? = null,
        /** When the server accepted it; the undo window is judged against this. */
        val sentAt: Instant? = null,
    ) {
        enum class Status { PENDING, SENT, FROZEN, FAILED, REJECTED, WITHDRAWN }
    }
}
