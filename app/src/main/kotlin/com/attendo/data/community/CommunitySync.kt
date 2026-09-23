package com.attendo.data.community

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.OutboxPolicy
import com.attendo.core.community.ReportPayload
import com.attendo.data.db.AttendoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Drains the community outbox: no WorkManager, one coroutine.
 *
 * The engine is woken by the events that actually change the answer — a submission was
 * enqueued, the app came to the foreground, connectivity returned — and otherwise sleeps.
 * A drain pass walks every pending row, sends what is due, and schedules its own next
 * wake-up from [OutboxPolicy] when something is waiting but not yet due. The app being
 * killed mid-drain is safe: the outbox row survives in Room, and the next drain starts
 * over — the idempotency key makes the re-send free of double effects.
 *
 * Failure discipline:
 *  * transport failure → attempts++, backoff (retryable);
 *  * server rejection → the row is marked with the code and never retried (the server
 *    will say the same thing again);
 *  * acceptance → `sentAt` is stamped and the row is kept briefly as the "sent" state.
 *
 * The [ConnectivityManager] callback is registered once and held for the process
 * lifetime: it costs nothing when the app is idle in the background, and it is the
 * difference between "the report sends itself when the train comes out of the tunnel"
 * and "the student has to reopen the app".
 */
class CommunitySync(
    private val context: Context,
    private val database: AttendoDatabase,
    private val client: CommunityClient?,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val clientVersionCode: () -> Long = { 0L },
) {
    private val dao get() = database.communityDao()

    /** One drain at a time; a second trigger joins by cancelling the wait, not the send. */
    private var drainJob: Job? = null
    private val wakingUp = MutableStateFlow(false)

    /**
     * The send gate: no outbox send runs, starts, or decides anything unless it holds
     * this lock — and the account layer holds it across both identity changes that
     * replace the session: the sign-in swap (retire the outgoing identity, discard its
     * rows, import the account's session) and a server-authoritative deletion (discard
     * the gone identity's local rows, then clear the session).
     *
     * Without the gate there is a window no amount of re-checking can close: a pass
     * could re-ask the table (row still pending), then suspend on its HTTP call, and
     * the swap could complete underneath it — the row's send then flies under whatever
     * session landed, which is exactly the misfile (a pending report filed under the
     * account) the discard machinery exists to prevent. The gate makes the swap and
     * every send mutually exclusive instead of merely double-checked, and it also
     * covers the pass's identity step: [drainOnce] takes the lock before it loads or
     * mints a session, so no drain can mint a throwaway identity while a swap is
     * replacing the session.
     */
    private val sendGate = Mutex()

    /**
     * Runs [block] with outbox sends blocked: no drain pass may send, re-check, or
     * mint an identity for its duration. The two callers are the sign-in swap (see
     * [AccountManager.importCallbackSession][com.attendo.data.account.AccountManager.importCallbackSession])
     * and a server-authoritative deletion (see
     * [AccountManager.validateAccount][com.attendo.data.account.AccountManager.validateAccount])
     * — both replace the session, and both must keep a drain from sending under
     * whoever would be minted after it.
     */
    suspend fun <T> withSendsBlocked(block: suspend () -> T): T = sendGate.withLock { block() }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Registers the connectivity listener. Call once from the Application scope.
     */
    fun start() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                drain()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
        networkCallback = callback
        // A restart with rows pending and the network already up gets no onAvailable —
        // the network never went away — so the first drain after a restart is kicked
        // here, not left to the next connectivity change.
        drain()
    }

    fun stop() {
        networkCallback?.let {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { manager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        drainJob?.cancel()
    }

    /**
     * Wakes the engine: starts a drain pass now (or, if one is already sending, makes
     * sure a fresh pass follows it).
     */
    fun drain() {
        drainJob?.cancel()
        drainJob = scope.launch {
            drainOnce()
            // Then sleep-walk: while not-yet-due rows exist, wake up when the earliest
            // becomes due. A row with no failures is never "not yet due" (it is always
            // sendable — see sendRows), so only backoff schedules keep the loop alive;
            // a pass that sends everything simply does not reschedule.
            while (true) {
                val pending = pendingRows().filter { it.failedAttempts > 0 }
                val due = pending
                    .mapNotNull { OutboxPolicy.nextDueAt(it.lastFailureAt ?: it.createdAt, it.failedAttempts) }
                    .minOrNull() ?: break
                val wait = java.time.Duration.between(now(), due).toMillis().coerceAtLeast(1000)
                delay(wait)
                drainOnce()
            }
        }
    }

    private suspend fun drainOnce() {
        // Rows first, identity second — and the sync engine mints nothing on its own:
        // the identity belongs to the first community *contact* (a view of community
        // data or a contribution), and the read path owns that mint. If this drain
        // minted too, an app start with an empty outbox would create an identity
        // before the student ever saw a community card.
        //
        // The whole pass — identity step included — runs under the send gate, so a
        // sign-in swap (retire → discard → import, in withSendsBlocked) can never
        // interleave with it: no row is sent under a session that is being replaced,
        // and no session is minted while a replacement is in flight. The row list was
        // read before the lock; the per-row re-check inside sendRows is what makes a
        // list read that early still trustworthy.
        val rows = pendingRows()
        if (rows.isNotEmpty() && client != null) {
            val api = RemoteReportApi(client)
            sendGate.withLock {
                // No identity yet (first report on a fresh install that never opened a
                // community surface): ensure one before sending. Null means Supabase is
                // unreachable — the rows keep their schedule.
                if (CommunityIdentityStore(context).ensureSession(client) != null) {
                    sendRows(api, rows)
                }
            }
        }
        // Sent/rejected history older than a day goes.
        dao.pruneOutbox(now().minusSeconds(24 * 3600))
    }

    private suspend fun sendRows(api: RemoteReportApi, rows: List<com.attendo.data.db.CommunityOutboxEntity>) {
        for (row in rows) {
            if (row.rejectionCode != null || row.sentAt != null) continue
            // The list was read before the pass took the gate, and the table can have
            // changed under it since. The sign-in swap cannot (it holds the gate too),
            // but the identity resets that clear the outbox without the gate can —
            // startFreshIdentity, a superseded-identity restart — and a row cleared
            // that way must not leave the phone under whatever session follows.
            // Re-asking the table is the check: the clear emptied it in one
            // transaction, so the count is zero and this row is skipped, whatever the
            // in-memory list still holds.
            if (dao.outboxPendingCount(row.idempotencyKey) == 0) continue
            // The first attempt is due now — a report the student just filed sends
            // immediately, online or not. Backoff is for *retries*: it begins after
            // the first failure, judged from that failure's timestamp, never from
            // createdAt (the old behavior: even attempt zero waited 30 seconds,
            // which read on screen as a stuck "Waiting to send").
            if (row.failedAttempts > 0) {
                val dueAt = OutboxPolicy.nextDueAt(row.lastFailureAt ?: row.createdAt, row.failedAttempts)
                if (dueAt != null && dueAt > now()) continue
            }

            val payload = runCatching {
                ReportPayload.fromJson(Json.parseToJsonElement(row.payloadJson).let { it as? kotlinx.serialization.json.JsonObject })
            }.getOrNull() ?: ReportPayload()

            val result = api.submitReport(
                kind = row.kind,
                room = row.room,
                section = row.section,
                subject = row.subject,
                classDate = row.classDate,
                startHour = row.startHour,
                payload = payload,
                note = row.note,
                idempotencyKey = row.idempotencyKey,
                clientVersionCode = clientVersionCode(),
                observationType = row.observationType,
                eventDate = row.eventDate,
                targetStartHour = row.targetStartHour,
            )
            when (result) {
                is RemoteReportApi.SubmitResult.Ok -> {
                    dao.markOutboxSent(row.idempotencyKey, now())
                    // The server's id for the row: what undo and withdrawal target
                    // later. Stamp it the moment the server names it, so a fresh undo
                    // does not race a second acceptance.
                    result.observationId?.let { dao.markOutboxServerId(row.idempotencyKey, it) }
                }
                is RemoteReportApi.SubmitResult.Rejected ->
                    when {
                        result.code == "identity_frozen" -> {
                            // This install holds a pending replacement claim: the server
                            // refuses its writes for the window, and the refusal is a
                            // *state*, not a verdict on the report. The row keeps its
                            // schedule: it sends when the claim completes (and is then
                            // the new identity's) or when the claim dies and the freeze
                            // lifts — never marked rejected, never lost. The frozen
                            // stamp is what "My Reports" renders as the identity-move
                            // sentence, instead of letting the attempt cap age the row
                            // into a network failure it never was.
                            dao.markOutboxFrozen(row.idempotencyKey, row.failedAttempts + 1, now())
                        }

                        // A duplicate means the report is already on the server under
                        // this identity — filed from the other phone mid-move, or
                        // re-filed by hand within the hour. The local row adopts the
                        // server's row instead of declining: what the student filed is
                        // live, and a decline would hide it and strand undo/withdraw.
                        // The same idempotency key never lands here (the server replays
                        // its original acceptance), so the collider is always a row
                        // this phone does not know the id of.
                        result.code == "duplicate_report" && reconcileDuplicate(row, api) -> Unit

                        else -> dao.markOutboxRejected(row.idempotencyKey, result.code)
                    }
                RemoteReportApi.SubmitResult.Unreachable ->
                    dao.markOutboxFailure(row.idempotencyKey, row.failedAttempts + 1, now())
            }
        }
        // Sent/rejected history older than a day goes.
        dao.pruneOutbox(now().minusSeconds(24 * 3600))
    }

    /**
     * A duplicate_report refusal's other half: find the colliding server row and make
     * the local outbox row point at it.
     *
     * The server refused because this identity already has a row in the same dedup
     * bucket — the same key its unique index refused on: kind, room, section, subject,
     * class date, start hour, payload's new room, claim target, filing hour. The
     * candidates come from the owner-facing read (this identity's rows only, withdrawn
     * ones excluded exactly as the partial index excludes them), matched on everything
     * but the hour — the collider is always the newest, because our send would have
     * been the newest had it landed. No device-clock arithmetic anywhere: the clock on
     * this phone can be wrong by hours, and the server's refusal already proved the
     * hours agree.
     *
     * True when the local row was stamped sent with the server's id; false lets the
     * refusal speak for itself (no match found, or the read failed).
     */
    private suspend fun reconcileDuplicate(
        row: com.attendo.data.db.CommunityOutboxEntity,
        api: RemoteReportApi,
    ): Boolean {
        val mine = api.myPendingReports() ?: return false
        val payload = runCatching {
            ReportPayload.fromJson(
                Json.parseToJsonElement(row.payloadJson).let { it as? kotlinx.serialization.json.JsonObject },
            )
        }.getOrNull() ?: ReportPayload()
        val collider = mine
            .filter { it.status != CommunityObservationStatus.WITHDRAWN }
            .filter { o ->
                o.kind == row.kind &&
                    o.room == row.room &&
                    o.section == row.section &&
                    o.subject == row.subject &&
                    o.classDate == row.classDate &&
                    o.startHour == row.startHour &&
                    o.payload.newRoom == payload.newRoom &&
                    o.targetStartHour == row.targetStartHour
            }
            .maxByOrNull { it.createdAt }
            ?: return false
        dao.markOutboxSent(row.idempotencyKey, now())
        dao.markOutboxServerId(row.idempotencyKey, collider.id)
        return true
    }

    private suspend fun pendingRows() = dao.pendingOutbox()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
