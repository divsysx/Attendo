package com.attendo.data.community

import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityPoll
import com.attendo.core.community.CommunityPollStatus
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.ReportPayload
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

/**
 * The community backend's API surface: every RPC and read the app makes, in one class.
 *
 * Contract notes pinned by Phase 4's adversarial validation, mirrored here:
 *
 *  * **Every non-DEFAULT parameter must be present in the RPC body** — PostgREST 16
 *    hard-rejects a partial parameter set (PGRST202), so the builders below always send
 *    the full set, nulls included. Extra/forged fields are rejected server-side the same
 *    way, which is why nothing here ever sends a field the RPC does not own.
 *  * **All timestamps are the server's.** `server_now` comes back with every snapshot
 *    and poll result; the app never trusts the device clock for expiry.
 *  * **Responses are `{ok, code, ...}` envelopes.** `ok: true` with a `code` is a
 *    friendly outcome (e.g. `already_verified`), not an error; only `ok: false`
 *    routes to the failure UI.
 *  * **Reads are the sanitized shapes only**: the view, poll_results' counts,
 *    my_pending_reports' rows. No path in this class can fetch a reporter identity,
 *    because the server's column grants make those columns physically unreadable.
 */
class RemoteReportApi(private val client: CommunityClient) {

    private val postgrest get() = client.client.postgrest

    // ------------------------------------------------------------- submissions

    /** A submission's outcome, mapped from the server's envelope. */
    sealed interface SubmitResult {
        /** Accepted (or already accepted — same id, no double effect). */
        data class Ok(val observationId: String?): SubmitResult
        data class Rejected(val code: String): SubmitResult
        /** Network/transport failure — retryable, never terminal. */
        data object Unreachable : SubmitResult
    }

    /**
     * Submits a report or a future claim.
     *
     * The claim parameters are sent **only for a claim**, deliberately. Migration 0007's
     * `submit_report` gave the three new parameters defaults, so an old server still has
     * its ten-parameter signature and PostgREST accepts exactly the old body for a
     * present or historical report; sending `p_observation_type` to it would be PGRST202
     * (unknown parameter) and break every non-claim report on an unmigrated backend. A
     * claim against an old server fails — honestly, with a rejection the UI shows —
     * which is the right trade: claims are new, reports are not.
     */
    suspend fun submitReport(
        kind: CommunityReportKind,
        room: String?,
        section: String?,
        subject: String?,
        classDate: LocalDate?,
        startHour: Int?,
        payload: ReportPayload,
        note: String?,
        idempotencyKey: String,
        clientVersionCode: Long,
        observationType: CommunityObservationType = CommunityObservationType.PRESENT,
        eventDate: LocalDate? = null,
        targetStartHour: Int? = null,
    ): SubmitResult = rpc(
        "submit_report",
        buildSubmitReportBody(
            kind = kind,
            room = room,
            section = section,
            subject = subject,
            classDate = classDate,
            startHour = startHour,
            payload = payload,
            note = note,
            idempotencyKey = idempotencyKey,
            clientVersionCode = clientVersionCode,
            observationType = observationType,
            eventDate = eventDate,
            targetStartHour = targetStartHour,
        ),
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    suspend fun createPoll(
        room: String?,
        section: String?,
        subject: String?,
        classDate: LocalDate?,
        startHour: Int?,
        question: String,
        options: List<String>,
        idempotencyKey: String,
        clientVersionCode: Long,
    ): SubmitResult = rpc(
        "create_poll",
        buildCreatePollBody(
            room = room,
            section = section,
            subject = subject,
            classDate = classDate,
            startHour = startHour,
            question = question,
            options = options,
            idempotencyKey = idempotencyKey,
            clientVersionCode = clientVersionCode,
        ),
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    /** One verdict per user per observation; the server's answer is final. */
    suspend fun verify(observationId: String, verdict: Boolean): SubmitResult = rpc(
        "verify",
        buildJsonObject {
            put("p_observation_id", observationId)
            put("p_verdict", verdict)
        }
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    suspend fun castVote(pollId: String, optionIndex: Int): SubmitResult = rpc(
        "cast_vote",
        buildJsonObject {
            put("p_poll_id", pollId)
            put("p_option_index", optionIndex)
        }
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    // ------------------------------------------------------- undo / withdrawal

    /**
     * Takes a report back inside the fresh window. Server-enforced owner-only,
     * atomic, idempotent, zero reputation change — the client's job here is
     * transport and honest reporting of the answer, nothing else. See migration
     * 0009 for the guarantees the server side carries.
     */
    suspend fun undoReport(observationId: String): SubmitResult = rpc(
        "undo_report",
        buildJsonObject { put("p_observation_id", observationId) },
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    /** Withdraws a report after the window: one reputation decrease, ever. */
    suspend fun withdrawReport(observationId: String): SubmitResult = rpc(
        "withdraw_report",
        buildJsonObject { put("p_observation_id", observationId) },
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    /** The polls' twins — identical guarantees, `withdraw_poll`'s engine. */
    suspend fun undoPoll(pollId: String): SubmitResult = rpc(
        "undo_poll",
        buildJsonObject { put("p_poll_id", pollId) },
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    suspend fun withdrawPoll(pollId: String): SubmitResult = rpc(
        "withdraw_poll",
        buildJsonObject { put("p_poll_id", pollId) },
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(body.str("id"))
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    // ------------------------------------------- reporting identity transfer

    /**
     * What an identity-transfer RPC decided, mapped from the server's envelope.
     *
     * The state machine is the server's (migration 0015): `armed` (a code exists,
     * nobody has claimed it) → `pending_claim` (a new device holds the claim, the
     * 24-hour veto window is running) → `completed` (the identity moved, atomically)
     * or `aborted` (owner vetoed, expired, self-claimed, or the claimant turned out
     * not fresh). The client maps each answer to exactly one of these; every refusal
     * keeps the server's own code (`transfer_in_progress`, `identity_not_fresh`,
     * `rate_limited`, …) because those words are the honest UI copy.
     *
     * `identity_superseded` arrives here only as a raised exception (the tombstone
     * check fires before any envelope); the `rpc` transport maps it to
     * [TransferResult.Rejected] like every other raise.
     */
    sealed interface TransferResult {
        /** begin succeeded: the one-time code, for the owner's eyes and export only. */
        data class CodeMinted(val code: String, val expiresAt: Instant) : TransferResult

        /** claim accepted; the veto window runs until [claimDeadline] (the server's clock). */
        data class ClaimPending(val claimDeadline: Instant, val replaceClaimant: Boolean = false) : TransferResult

        /**
         * The transfer finished: the identity, history and reputation are now this
         * device's. [replaced] is true when this device's own former identity was
         * hard-deleted server-side to make room (0017's replacement path).
         */
        data class Completed(val serverNow: Instant, val replaced: Boolean = false) : TransferResult

        /** The owner aborted; no identity moved. */
        data class Aborted(val serverNow: Instant) : TransferResult

        /** The server said no, in its own words. */
        data class Rejected(val code: String) : TransferResult

        /** Network/transport failure — retryable, never terminal. */
        data object Unreachable : TransferResult
    }

    /**
     * Asks the server for a one-time transfer code. Owner-only server-side; the code
     * is the *only* thing this call returns that matters, and the only place it ever
     * goes is the user's encrypted `.atid` file (or the owner's own screen, for the
     * "write this down" path — never a log, never Room, never analytics).
     */
    suspend fun beginIdentityTransfer(): TransferResult = transferRpc(
        "begin_identity_transfer",
        buildJsonObject { },
    ) { body ->
        when {
            body.bool("ok") && body.str("code") != null -> TransferResult.CodeMinted(
                code = body.str("code")!!,
                expiresAt = body.str("expires_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: Instant.EPOCH,
            )
            else -> TransferResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    /**
     * Redeems a transfer code from a `.atid` file. On this device's identity,
     * which must be fresh (the server refuses a claimant with history — identities
     * never merge) *unless* [replaceClaimant] is set, which the UI passes only
     * after the destructive confirmation dialog: then the server will hard-delete
     * this device's own identity when the transfer completes. Reaching
     * [TransferResult.ClaimPending] means the old device now has 24 hours to
     * veto; [TransferResult.Completed] means it didn't (or approved outright)
     * and the identity has already moved.
     */
    suspend fun claimReportingIdentity(
        transferCode: String,
        replaceClaimant: Boolean = false,
    ): TransferResult = transferRpc(
        "claim_reporting_identity",
        buildClaimIdentityBody(transferCode, replaceClaimant),
    ) { body ->
        when (body.str("state")) {
            "pending_claim" -> TransferResult.ClaimPending(
                claimDeadline = body.str("claim_deadline")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
                replaceClaimant = body.bool("replace_claimant"),
            )
            "completed" -> TransferResult.Completed(
                serverNow = body.str("server_now")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
                replaced = body.bool("replaced"),
            )
            else -> when {
                body.bool("ok") && body.str("code") == null ->
                    // An ok envelope with no state and no code: a shape this build does
                    // not know. Safer to surface than to guess.
                    TransferResult.Rejected("unexpected_answer")
                else -> TransferResult.Rejected(body.str("code") ?: "rejected")
            }
        }
    }

    /** The owner's explicit consent — completes the pending transfer immediately. */
    suspend fun approveIdentityTransfer(): TransferResult = transferRpc(
        "approve_identity_transfer",
        buildJsonObject { },
    ) { body ->
        when {
            body.str("state") == "completed" -> TransferResult.Completed(
                serverNow = body.str("server_now")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
                replaced = body.bool("replaced"),
            )
            else -> TransferResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    /** The owner's Keep-my-identity button — kills the live grant, armed or pending. */
    suspend fun abortIdentityTransfer(): TransferResult = transferRpc(
        "abort_identity_transfer",
        buildJsonObject { },
    ) { body ->
        when {
            body.str("state") == "aborted" -> TransferResult.Aborted(
                serverNow = body.str("server_now")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
            )
            else -> TransferResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    /**
     * This identity's standing, from the owner-facing read the app already makes.
     * Null means unreachable — which must never read as "everything is fine", so the
     * caller treats null as "unknown" rather than "no".
     */
    data class IdentityStatus(
        /** Someone holds a pending claim on this identity: show the veto warning. */
        val transferPending: Boolean,
        /** This identity was moved elsewhere: writes are dead, offer Start fresh. */
        val superseded: Boolean,
        /** This device holds a pending claim on another identity (it is the claimant). */
        val claimPending: Boolean = false,
        /**
         * That claim is a replacement: this device's own identity will be
         * hard-deleted when the move completes, and its community writes are
         * frozen server-side until then (0017).
         */
        val claimReplacesIdentity: Boolean = false,
        /**
         * The ids of this identity's pending reports, from the same read. They are
         * how a *claimant* learns a pending claim's outcome from flags alone: a
         * completed move re-parents the moved identity's rows to this uid (a
         * different set arrives), while a cancelled one changes nothing — this uid
         * is frozen or kept fresh for exactly as long as the claim waits, so its
         * own set cannot move.
         */
        val reportIds: Set<String> = emptySet(),
        /**
         * The owner's pending window, from the same grant the transferPending
         * flag reads (0019): until this moment nothing moves without this
         * phone's answer; past it the claimant can finalize alone. Null when no
         * claim is waiting (or a server predating 0019).
         */
        val transferClaimDeadline: Instant? = null,
        /** The claimant's twin of [transferClaimDeadline]: when the old phone's veto ends. */
        val claimDeadline: Instant? = null,
    )

    suspend fun identityStatus(): IdentityStatus? = runCatching {
        val body = postgrest.rpc("my_pending_reports").data
        val obj = json.parseToJsonElement(body).jsonObject
        if (!obj.bool("ok")) return@runCatching null
        IdentityStatus(
            transferPending = obj.bool("transfer_pending"),
            superseded = obj.bool("superseded"),
            claimPending = obj.bool("claim_pending"),
            claimReplacesIdentity = obj.bool("claim_replaces_identity"),
            reportIds = obj["reports"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.str("id") }
                ?.toSet() ?: emptySet(),
            transferClaimDeadline = obj.str("transfer_claim_deadline")?.let(::instantOrNull),
            claimDeadline = obj.str("claim_deadline")?.let(::instantOrNull),
        )
    }.getOrNull()

    // ---------------------------------------- temporary identity retirement

    /**
     * Retires this phone's anonymous reporting identity server-side — the returning-
     * sign-in cleanup, migration 0021's `retire_reporting_identity`.
     *
     * The call rides the session it is retiring: every deletion the RPC performs is
     * scoped to `auth.uid()`, so the caller's own live session is the proof of
     * ownership, and [destinationUserId] is a precondition only (the established
     * account being returned to), never a deletion target. Called from exactly one
     * place — the OAuth callback's import window, after the account's user has been
     * fetched and before its session is imported — which is the only moment the client
     * holds both the outgoing identity's live session and the incoming account's
     * validated session. A cancelled or failed OAuth never reaches this call.
     *
     * The envelope's answers, all 200 `{ok, code}`:
     *  * `ok: true`, `retired` / `already_retired` — [SubmitResult.Ok]. The identity's
     *    rows are gone server-side (grants, profile and everything the cascade
     *    removes) and a tombstone refuses any straggler write; `already_retired` is
     *    the idempotent answer a retried or duplicated callback gets.
     *  * `ok: false`, `destination_not_established` — the account being signed into
     *    has no community identity yet (a GitHub login's first ever use): there is
     *    nothing to return to, so nothing was deleted, and the caller proceeds with
     *    its local cleanup only, leaving the outgoing identity's server rows
     *    orphaned — a disclosed limitation, not a silent one.
     *  * `ok: false`, `not_anonymous` — the outgoing session is an account, and
     *    account identities are permanent: nothing deleted, local cleanup proceeds.
     *  * `ok: false`, `not_authenticated` / `invalid_destination` — the session or
     *    the destination could not authorize anything: nothing deleted, local cleanup
     *    proceeds (retrying cannot change a definite answer). A server that does not
     *    have migration 0021 yet answers HTTP 404 (PGRST202) here, which this
     *    transport maps to [SubmitResult.Rejected] and the caller treats the same
     *    way: the sign-in still completes, with the outgoing identity's server rows
     *    orphaned — the migration should precede or accompany the client release.
     *  * [SubmitResult.Unreachable] — no answer came back. The caller must treat
     *    this as "unknown", never "done": the retirement is idempotent, so aborting
     *    and retrying is always safe, while proceeding would abandon the outgoing
     *    identity's server rows with no way to reach them again.
     */
    suspend fun retireReportingIdentity(destinationUserId: String): SubmitResult = rpc(
        "retire_reporting_identity",
        buildJsonObject { put("p_destination_user_id", destinationUserId) },
    ) { body ->
        when {
            body.bool("ok") -> SubmitResult.Ok(null)
            else -> SubmitResult.Rejected(body.str("code") ?: "rejected")
        }
    }

    // ------------------------------------------------------------------ reads

    /** A snapshot: rows plus the server's now, which is the clock the UI renders by. */
    data class Snapshot(
        val observations: List<CommunityObservation>,
        val serverNow: Instant,
    )

    /**
     * The authoritative read: the `active_observations` view (live rows, counts, no
     * identity) plus the server's now from my_pending_reports.
     */
    suspend fun snapshot(): Snapshot? = runCatching {
        val rows = postgrest["active_observations"].select().decodeList<ObservationDto>()
        // The caller's own verdicts — the one per-user fact the read policy allows on
        // this table, exactly as `poll_votes` allows the vote. Without it a card
        // cannot know the buttons it shows would be refused with `already_verified`.
        val myVerdicts = postgrest["verifications"].select().decodeList<VerificationRow>()
            .associate { it.observation_id to it.verdict }
        Snapshot(
            observations = rows.mapNotNull { dto ->
                dto.toModel()?.copy(myVerdict = myVerdicts[dto.id])
            },
            serverNow = fetchServerNow(),
        )
    }.getOrNull()

    private suspend fun fetchServerNow(): Instant = runCatching {
        val body = postgrest.rpc(
            "my_pending_reports",
        ).data
        val obj = json.parseToJsonElement(body).jsonObject
        Instant.parse(obj["server_now"]?.jsonPrimitive?.contentOrNull ?: return@runCatching Instant.now())
    }.getOrDefault(Instant.now())

    /**
     * The reporter's own rows at any status, sanitized (no identity columns).
     *
     * This is the reconciliation truth for "My Reports": a sent outbox row whose id is
     * absent from this list is a row the server no longer has — an admin wipe, a manual
     * delete — and the phone lets it go. The server's lookback (7 days past expiry, 30
     * for withdrawn) outlives the outbox's own 24-hour life by design, so absence is
     * never just age; it is always the row actually being gone.
     *
     * Null means the call failed or was refused — which must never read as "none of
     * your reports exist", or a network blip would erase the outbox. An empty list is
     * a real answer ("the server knows none of them") and is exactly right after a wipe.
     */
    suspend fun myPendingReports(): List<CommunityObservation>? = runCatching {
        val body = postgrest.rpc("my_pending_reports").data
        val obj = json.parseToJsonElement(body).jsonObject
        if (!obj.bool("ok")) return@runCatching null
        val reports = obj["reports"]?.jsonArray ?: return@runCatching null
        reports.mapNotNull { element ->
            val row = element.jsonObject
            ObservationDto(
                id = row.str("id") ?: return@mapNotNull null,
                kind = row.str("kind") ?: return@mapNotNull null,
                status = row.str("status") ?: "reported",
                room = row.str("room"),
                section = row.str("section"),
                subject = row.str("subject"),
                class_date = row.str("class_date"),
                start_hour = row.intOrNull("start_hour"),
                payload = row["payload"]?.jsonObject,
                note = row.str("note"),
                created_at = row.str("created_at") ?: return@mapNotNull null,
                expires_at = row.str("expires_at") ?: return@mapNotNull null,
                observation_type = row.str("observation_type"),
                event_date = row.str("event_date"),
                target_start_hour = row.intOrNull("target_start_hour"),
                target_end_hour = row.intOrNull("target_end_hour"),
                withdrawn_at = row.str("withdrawn_at"),
                withdrawal_kind = row.str("withdrawal_kind"),
                verification_count = row.intOrNull("verification_count"),
                dispute_count = row.intOrNull("dispute_count"),
            ).toModel()
        }
    }.getOrNull()

    /**
     * The creator's own polls, sanitized — the owner-facing read migration 0009 added.
     * Counts only (no voter rows, no creator identity); carries the withdrawal/undo
     * facts "My Polls" shows. Null means unreachable, no client, or a refused call
     * (a dead session's `not_authenticated` envelope lands here too) — anything the
     * caller should answer by keeping what it already had, never by claiming the
     * student has no polls.
     */
    suspend fun myPolls(): List<CommunityPoll>? = runCatching {
        val body = postgrest.rpc("my_polls").data
        val obj = json.parseToJsonElement(body).jsonObject
        if (!obj.bool("ok")) return null
        val polls = obj["polls"]?.jsonArray ?: return emptyList()
        polls.mapNotNull { element ->
            val row = element.jsonObject
            val closesAt = row.str("closes_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            CommunityPoll(
                id = row.str("id") ?: return@mapNotNull null,
                room = row.str("room"),
                section = row.str("section"),
                subject = row.str("subject"),
                classDate = row.str("class_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                startHour = row.intOrNull("start_hour"),
                question = row.str("question") ?: "",
                status = CommunityPollStatus.fromWire(row.str("status") ?: "open"),
                options = emptyList(),
                totalVotes = row.intOrNull("total_votes") ?: 0,
                closesAt = closesAt,
                withdrawnAt = row.str("withdrawn_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                withdrawalKind = row.str("withdrawal_kind"),
                createdAt = row.str("created_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
    }.getOrNull()

    /** A poll's tallies: counts only, no voter information of any kind. */
    suspend fun pollResults(pollId: String): CommunityPoll? = runCatching {
        val body = postgrest.rpc(
            "poll_results",
            buildJsonObject { put("p_poll_id", pollId) },
        ).data
        val obj = json.parseToJsonElement(body).jsonObject
        if (!obj.bool("ok")) return null
        CommunityPoll(
            id = pollId,
            room = null, section = null, subject = null,
            classDate = null, startHour = null,
            question = obj.str("question") ?: "",
            status = com.attendo.core.community.CommunityPollStatus.fromWire(
                obj.str("status") ?: "open"
            ),
            options = (obj["options"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
                .mapIndexed { index, element ->
                    val o = element.jsonObject
                    com.attendo.core.community.PollOption(
                        optionIndex = o.intOrNull("option_index") ?: index,
                        label = o.str("label") ?: "—",
                        count = o.intOrNull("count") ?: 0,
                    )
                },
            totalVotes = obj.intOrNull("total_votes") ?: 0,
            closesAt = obj.str("closes_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.EPOCH,
        )
    }.getOrNull()

    /** The open polls with their tallies, plus the server's now — the polls' snapshot. */
    data class PollsSnapshot(
        val polls: List<CommunityPoll>,
        val serverNow: Instant,
    )

    /**
     * Every poll the server will let this student see (RLS: open, or their own), with
     * tallies filled in from [pollResults].
     *
     * Three table reads carry the parts the RPC cannot: the question and context from
     * `polls` (the RPC returns counts but not the question), the option labels from
     * `poll_options`, and the student's own vote from `poll_votes` — the one per-user
     * row the read policy allows, exactly so the card can show "your vote". One
     * `poll_results` call per open poll then supplies everyone else's verdicts as
     * counts; a context holds a couple of open polls at most, so the round-trips are
     * few, and a single tally failing degrades that poll to label-only rather than
     * dropping the snapshot.
     *
     * Rows the server still lists as open but whose `closes_at` has passed (the cron
     * sweep runs every ten minutes) are filtered here by the server's own clock.
     *
     * The `polls` read names its columns — see [POLL_SELECT_COLUMNS] — because a bare
     * select would ask for all of them, and the column grant does not allow all of
     * them. (`poll_options` and `poll_votes` kept their table-level grants, so their
     * reads need no list.)
     */
    suspend fun openPolls(): PollsSnapshot? = runCatching {
        val rows = postgrest["polls"].select(Columns.list(POLL_SELECT_COLUMNS)).decodeList<PollRow>()
            .sortedByDescending { it.created_at }
        val options = postgrest["poll_options"].select().decodeList<OptionRow>()
            .groupBy { it.poll_id }
        val myVotes = postgrest["poll_votes"].select().decodeList<VoteRow>()
            .associate { it.poll_id to it.option_index }
        val serverNow = fetchServerNow()

        val polls = rows.mapNotNull { row ->
            val closesAt = runCatching { Instant.parse(row.closes_at) }.getOrNull()
                ?: return@mapNotNull null
            if (row.status != "open" || closesAt <= serverNow) return@mapNotNull null

            val tallies = pollResults(row.id)
            CommunityPoll(
                id = row.id,
                room = row.room,
                section = row.section,
                subject = row.subject,
                classDate = row.class_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                startHour = row.start_hour,
                question = row.question,
                status = tallies?.status ?: CommunityPollStatus.fromWire(row.status),
                options = tallies?.options
                    ?: options[row.id].orEmpty()
                        .sortedBy { it.option_index }
                        .map { com.attendo.core.community.PollOption(it.option_index, it.label, 0) },
                totalVotes = tallies?.totalVotes ?: 0,
                closesAt = tallies?.closesAt?.takeIf { it != Instant.EPOCH } ?: closesAt,
                myVote = myVotes[row.id],
            )
        }
        PollsSnapshot(polls = polls, serverNow = serverNow)
    }.getOrNull()

    // ------------------------------------------------------------- transport

    private suspend fun rpc(
        name: String,
        parameters: JsonObject,
        map: (JsonObject) -> SubmitResult,
    ): SubmitResult = runCatching {
        val body = postgrest.rpc(name, parameters).data
        val obj = json.parseToJsonElement(body).jsonObject
        map(obj)
    }.recoverCatching { error ->
        // A RestException is the server answering "no": rate-limited, restricted,
        // invalid — recover its reason as the rejection code. Anything else is transport.
        if (error is RestException) {
            SubmitResult.Rejected(rejectionCode(error.message))
        } else {
            throw error
        }
    }.getOrElse { SubmitResult.Unreachable }

    /**
     * The transfer RPCs' transport — [rpc] with the transfer result type. Same rules:
     * envelopes are mapped by the caller, raised exceptions (`identity_superseded`
     * above all) surface as [TransferResult.Rejected] carrying the raised reason,
     * and everything else is [TransferResult.Unreachable].
     */
    private suspend fun transferRpc(
        name: String,
        parameters: JsonObject,
        map: (JsonObject) -> TransferResult,
    ): TransferResult = runCatching {
        val body = postgrest.rpc(name, parameters).data
        val obj = json.parseToJsonElement(body).jsonObject
        map(obj)
    }.recoverCatching { error ->
        if (error is RestException) {
            TransferResult.Rejected(rejectionCode(error.message))
        } else {
            throw error
        }
    }.getOrElse { TransferResult.Unreachable }

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull == "true"

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /** A server timestamp, or null when it is absent or unparseable. */
    private fun instantOrNull(text: String): Instant? =
        runCatching { Instant.parse(text) }.getOrNull()

    private fun JsonObject.intOrNull(key: String): Int? =
        str(key)?.toIntOrNull()

    private companion object {
        /**
         * The columns the `polls` read asks for: exactly what [PollRow] decodes, and
         * exactly what migration `0006_column_security.sql` grants `authenticated`.
         *
         * The grant deliberately excludes `creator_id` and `idempotency_key`, and
         * Postgres column privileges are all-or-explicit — `select=*` requires every
         * column of the table, so a bare `select()` on polls fails with permission
         * denied, this method's `runCatching` turns that into null, and polls go dark
         * on every screen with no error shown anywhere. The column list is the whole
         * difference between polls working against the real backend and polls
         * silently not existing. Pinned by `CommunityReadContractTest`.
         */
        const val POLL_SELECT_COLUMNS = "id,room,section,subject,class_date,start_hour,question,status,created_at,closes_at"

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The code a server refusal travels as, recovered from whatever shape it arrived in.
 *
 * The RPCs' own refusals never reach here — they return HTTP 200 `{ok, code}` envelopes
 * and are mapped by the caller's own `map`. This is for errors that arrive as HTTP
 * failures, chiefly a raised SQL exception such as `not_authenticated`: supabase-kt
 * parses the PostgREST body into a [RestException] whose message is the server's own
 * message first, followed by code/hint/URL lines — so the first line is the reason. Some
 * layers (proxies, other server versions) hand the JSON envelope over whole, so a first
 * line that parses as a JSON object carrying a "message" member is unwrapped too.
 * Anything else passes through as itself: a code this build has never met is shown raw,
 * never hidden — the same honesty rule the failure-line helpers apply.
 */
internal fun rejectionCode(raw: String?): String {
    val firstLine = raw?.substringBefore("\n")?.trim().orEmpty()
    if (firstLine.isEmpty()) return "rejected"
    val fromJson = runCatching {
        Json.parseToJsonElement(firstLine).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return fromJson ?: firstLine
}

// ------------------------------------------------------------- RPC bodies

/**
 * PostgREST 16 resolves an RPC only when the request body carries **every non-DEFAULT
 * parameter**; an absent one is PGRST202 — the HTTP 404 production logged while every
 * basic room report (no section, subject, class date or start hour) silently omitted
 * four of them. Null context fields must therefore ride the body as JSON null, never
 * as missing keys — which is what [putNullable] below writes, and what
 * `RpcBodyContractTest` pins against the serialized bodies themselves.
 */
internal fun buildSubmitReportBody(
    kind: CommunityReportKind,
    room: String?,
    section: String?,
    subject: String?,
    classDate: LocalDate?,
    startHour: Int?,
    payload: ReportPayload,
    note: String?,
    idempotencyKey: String,
    clientVersionCode: Long,
    observationType: CommunityObservationType,
    eventDate: LocalDate?,
    targetStartHour: Int?,
): JsonObject = buildJsonObject {
    put("p_kind", kind.wireName)
    putNullable("p_room", room)
    putNullable("p_section", section)
    putNullable("p_subject", subject)
    putNullable("p_class_date", classDate?.toString())
    putNullable("p_start_hour", startHour)
    put("p_payload", payload.toJson())
    putNullable("p_note", note)
    put("p_idempotency_key", idempotencyKey)
    put("p_client_version_code", clientVersionCode)
    if (observationType == CommunityObservationType.FUTURE) {
        put("p_observation_type", observationType.wireName)
        putNullable("p_event_date", eventDate?.toString())
        putNullable("p_target_start_hour", targetStartHour)
    }
}

    /**
     * The `claim_reporting_identity` body. The code must never ride anything but
     * `p_transfer_code` (a code in a header or a URL is a code in a log); the
     * replacement flag rides `p_replace_claimant` — and *only on the confirmed
     * path*, so the body is exactly one key until the user has said the
     * destructive yes. Kept as a named builder for the same reason as
     * [buildSubmitReportBody]: the wire contract is pinned against the
     * serialized body itself.
     */
    internal fun buildClaimIdentityBody(transferCode: String, replaceClaimant: Boolean): JsonObject =
        buildJsonObject {
            put("p_transfer_code", transferCode)
            if (replaceClaimant) put("p_replace_claimant", true)
        }

/** The `create_poll` body — same non-default rule as [buildSubmitReportBody]. */
internal fun buildCreatePollBody(
    room: String?,
    section: String?,
    subject: String?,
    classDate: LocalDate?,
    startHour: Int?,
    question: String,
    options: List<String>,
    idempotencyKey: String,
    clientVersionCode: Long,
): JsonObject = buildJsonObject {
    putNullable("p_room", room)
    putNullable("p_section", section)
    putNullable("p_subject", subject)
    putNullable("p_class_date", classDate?.toString())
    putNullable("p_start_hour", startHour)
    put("p_question", question)
    put("p_options", kotlinx.serialization.json.JsonArray(options.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    put("p_idempotency_key", idempotencyKey)
    put("p_client_version_code", clientVersionCode)
}

/**
 * A parameter the RPC owns, present whether or not the report filled it: null travels
 * as JSON null. An omitted key is not "null" to PostgREST — it is a missing parameter,
 * and a non-DEFAULT missing parameter is a PGRST202 the student reads as a decline.
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Int?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}

// ---------------------------------------------------------------- wire DTOs

@kotlinx.serialization.Serializable
private data class ObservationDto(
    val id: String,
    val kind: String,
    val status: String,
    val room: String? = null,
    val section: String? = null,
    val subject: String? = null,
    val class_date: String? = null,
    val start_hour: Int? = null,
    val payload: JsonObject? = null,
    val note: String? = null,
    val created_at: String,
    val expires_at: String,
    // Claim fields (migration 0007). Nullable and defaulted: rows from a server older
    // than the migration carry none of them, and decode as present observations.
    val observation_type: String? = null,
    val event_date: String? = null,
    val target_start_hour: Int? = null,
    val target_end_hour: Int? = null,
    // Withdrawal fields (migration 0009) — on the owner-facing RPC's rows only.
    val withdrawn_at: String? = null,
    val withdrawal_kind: String? = null,
    val verification_count: Int? = null,
    val dispute_count: Int? = null,
)

private fun ObservationDto.toModel(): CommunityObservation? = runCatching {
    CommunityObservation(
        id = id,
        kind = CommunityReportKind.fromWire(kind),
        status = com.attendo.core.community.CommunityObservationStatus.fromWire(status),
        room = room,
        section = section,
        subject = subject,
        classDate = class_date?.let { LocalDate.parse(it) },
        startHour = start_hour,
        payload = ReportPayload.fromJson(payload),
        note = note,
        createdAt = Instant.parse(created_at),
        expiresAt = Instant.parse(expires_at),
        observationType = CommunityObservationType.fromWire(observation_type),
        eventDate = event_date?.let { LocalDate.parse(it) },
        targetStartHour = target_start_hour,
        targetEndHour = target_end_hour,
        withdrawnAt = withdrawn_at?.let { runCatching { Instant.parse(it) }.getOrNull() },
        withdrawalKind = withdrawal_kind,
        verificationCount = verification_count ?: 0,
        disputeCount = dispute_count ?: 0,
    )
}.getOrNull()

// Poll reads use their own DTOs for the same reason: the tables carry columns the client
// must never render (creator_id) and simply not decoding them is the cleanest way to be
// sure they never reach a card.
@kotlinx.serialization.Serializable
private data class PollRow(
    val id: String,
    val room: String? = null,
    val section: String? = null,
    val subject: String? = null,
    val class_date: String? = null,
    val start_hour: Int? = null,
    val question: String,
    val status: String,
    val closes_at: String,
    val created_at: String,
)

@kotlinx.serialization.Serializable
private data class OptionRow(
    val poll_id: String,
    val option_index: Int,
    val label: String,
)

@kotlinx.serialization.Serializable
private data class VoteRow(
    val poll_id: String,
    val option_index: Int,
)

@kotlinx.serialization.Serializable
private data class VerificationRow(
    val observation_id: String,
    val verdict: Boolean,
)
