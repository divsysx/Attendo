package com.attendo.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityPoll
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.CommunityRules
import com.attendo.core.community.CommunitySummaries
import com.attendo.core.community.ReportPayload
import com.attendo.data.community.CommunityRepository
import com.attendo.data.community.RemoteReportApi
import com.attendo.data.community.RealtimeObservations
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * What a community surface renders, in one shape.
 *
 * [observations] is the last snapshot's live rows (already filtered by the server's own
 * definition of live, then re-filtered here by the *server's* now — never the device
 * clock). Every derived view below is a fold over that one list, so the Rooms tab, a
 * room's detail screen and a day's review never disagree about what was reported.
 */
data class CommunityUiState(
    /** False on a build without credentials — the honest "not on this install" state. */
    val available: Boolean = false,
    /** True once the cache has been read, so screens can distinguish empty from loading. */
    val loaded: Boolean = false,
    /** A snapshot fetch is in flight. */
    val refreshing: Boolean = false,
    /** The last fetch failed; what is on screen is the cache, and saying so is honest. */
    val stale: Boolean = false,
    /** The server's own clock, as of the last successful snapshot. */
    val serverNow: Instant? = null,
    /**
     * The wall clock at the moment [serverNow] was captured — the observations' twin of
     * [pollsFetchedAt]. Together the pair gives the undo window a clock that keeps
     * moving between fetches, which is what lets an own-report card's Undo button swap
     * to Withdraw *on time* (the two-device test's "swap only after going back" was a
     * frozen snapshot clock: nothing recomputed, so nothing flipped). Null until the
     * first successful fetch, and unmoved by a failed one — the pair stays consistent.
     */
    val snapshotFetchedAt: Long? = null,
    val observations: List<CommunityObservation> = emptyList(),
    /**
     * The last poll fetch's open polls. Unlike observations these are never cached on
     * disk (see [CommunityRepository.openPolls]) — a poll that outlived its closes_at
     * in a cache table would be a question about a moment already gone.
     */
    val polls: List<CommunityPoll> = emptyList(),
    /**
     * The polls' clock anchor: the server's own now at the last successful poll fetch,
     * paired with [pollsFetchedAt], the device's clock at the same moment. Together
     * they give the polls a clock that starts at the server's truth and keeps moving
     * on the device's ticks — because a failed refresh keeps the last polls on screen,
     * and a poll judged by the *fetch's* frozen clock would stay votable for as long
     * as the network is down. [pollClock] is how the two are read.
     */
    val pollsServerNow: Instant? = null,
    val pollsFetchedAt: Long? = null,
    /** A vote or poll creation the server refused — one line, cleared by the next refresh. */
    val pollError: String? = null,
    val myReports: List<CommunityRepository.OutboxUiState> = emptyList(),
    /**
     * The student's own polls, from the owner-facing RPC (null while unfetched). A
     * failed fetch keeps the last good list — the polls have no disk cache by design,
     * so memory is the only "My Reports"-style fallback they get.
     */
    val myPolls: List<CommunityPoll>? = null,
    /**
     * True once the first [myPolls] fetch has answered, one way or the other. Null
     * polls before that mean *waiting*, not *failed*: My Reports paints instantly
     * from the outbox cache, and a "check your connection" line shown while the
     * first poll fetch was still in flight was a lie told about a running request —
     * the bug that made "My Polls" look broken on perfectly good connections.
     */
    val myPollsSettled: Boolean = false,
    /**
     * The server ids of this student's own polls — the vote chips hide themselves for
     * these, the poll twin of [mySentIds]: the server refuses a creator's vote in their
     * own poll (`own_poll`), so the card does not offer an action the server has
     * already promised to refuse.
     */
    val myPollIds: Set<String> = emptySet(),
    /**
     * The server ids of this student's own sent reports — the set the verify buttons
     * hide themselves for. Own reports are never verifiable (the server refuses with
     * `own_report`), so a card whose id is here shows no verdict buttons at all: UI
     * hiding is courtesy, server enforcement is the wall, and the card does not offer
     * an action the server has already promised to refuse.
     */
    val mySentIds: Set<String> = emptySet(),
    /** Rooms this student swiped away — client-only, never sent anywhere. */
    val dismissed: Set<String> = emptySet(),
) {
    /** The clock expiries are judged by: the server's when there is one, else none show. */
    private val judgedBy: Instant get() = serverNow ?: Instant.now()

    val live: List<CommunityObservation>
        get() = observations.filter { CommunityRules.isObservationVisible(it, judgedBy) }

    /** The Rooms tab's list: one card per room, newest signal first. */
    val roomSummaries: List<CommunitySummaries.RoomSummary>
        get() = CommunitySummaries.byRoom(live).filter { it.room !in dismissed }

    /**
     * Whether any of a room's live reports is this student's own — what the Rooms tab's
     * card needs to say "You report" instead of "Students report" on the reporter's own
     * phone, the list-card twin of the observation card's "Your report" marker. The fold
     * itself stays pure (see [CommunitySummaries.byRoom]); ownership is a join against
     * [mySentIds], and it lives here, beside the set it reads. It reads `live`, not
     * [observations], so a report that has expired or been withdrawn stops counting the
     * moment it leaves the tab.
     */
    fun hasOwnReport(summary: CommunitySummaries.RoomSummary): Boolean =
        live.any { it.room == summary.room && it.id in mySentIds }

    /**
     * How many of the student's own reports have not been sent or have failed. Frozen
     * rows count too: they are mid-identity-move, not sent — the count is "things this
     * phone still owes the server", and a frozen row is exactly that.
     */
    val pendingCount: Int
        get() = myReports.count {
            it.status == CommunityRepository.OutboxUiState.Status.PENDING ||
                it.status == CommunityRepository.OutboxUiState.Status.FAILED ||
                it.status == CommunityRepository.OutboxUiState.Status.FROZEN
        }

    /**
     * Whether a report card should offer verdict buttons: never for the student's own
     * reports ([mySentIds] — the server refuses `own_report`), never once they have
     * already given a verdict ([CommunityObservation.myVerdict] — the server refuses
     * `already_verified`), and only while the observation is still actionable
     * (reported/corroborated/disputed and unexpired).
     */
    fun isVerifiable(observation: CommunityObservation): Boolean =
        observation.id !in mySentIds && observation.status.isActive &&
            observation.myVerdict == null

    /**
     * The outbox row behind an observation the student is looking at, when it is their
     * own — what an observation card needs to offer Undo / Withdraw in place, without
     * a trip to the My community screen. Null for everyone else's reports.
     */
    fun ownReport(observation: CommunityObservation): CommunityRepository.OutboxUiState? =
        myReports.firstOrNull { it.serverObservationId == observation.id }

    /**
     * The owner-facing row behind a poll the student is looking at, when it is their
     * own — the poll twin of [ownReport], and the only place the card can learn the
     * poll's creation time (public reads carry no created_at). Null for everyone
     * else's polls, and until the first my_polls fetch lands.
     */
    fun ownPoll(poll: CommunityPoll): CommunityPoll? =
        myPolls?.firstOrNull { it.id == poll.id }

    /** Whether a poll card should offer vote chips, given the same own-row rule. */
    fun isVotable(poll: CommunityPoll, clock: Instant?): Boolean =
        poll.id !in myPollIds && poll.myVote == null &&
            (clock == null || CommunityRules.isPollOpen(poll, clock))

    /**
     * The one honest word about what is on screen. Supabase being down never takes a
     * community section away — it changes which word the status row says.
     */
    val status: Status
        get() = when {
            stale && live.isEmpty() -> Status.UNAVAILABLE
            stale -> Status.STALE
            else -> Status.FRESH
        }

    /** What the status row can say: fresh data, stale data, or no data at all. */
    enum class Status { FRESH, STALE, UNAVAILABLE }

    fun forRoom(room: String): List<CommunityObservation> =
        live.filter { it.room.equals(room, ignoreCase = true) }

    /**
     * The student's own reports about this room the server has not accepted yet — queued,
     * waiting out a backoff, frozen behind an identity move, or failed. The room page
     * renders these beside the observations they will become, because a report that never
     * appears on the page it was filed from reads as if it vanished. Sent rows are absent:
     * they are already among the observations (the own-row join), and declined ones never
     * become anything — "My Reports" is where those live.
     */
    fun unsentForRoom(room: String): List<CommunityRepository.OutboxUiState> =
        myReports.filter { report ->
            report.serverObservationId == null &&
                report.room?.equals(room, ignoreCase = true) == true &&
                (
                    report.status == CommunityRepository.OutboxUiState.Status.PENDING ||
                        report.status == CommunityRepository.OutboxUiState.Status.FAILED ||
                        report.status == CommunityRepository.OutboxUiState.Status.FROZEN
                    )
        }

    /**
     * The polls' clock, advanced from the anchor by the device's ticks since the fetch.
     * Null means there has been no fetch to anchor to — in which case there are no
     * polls to judge either.
     */
    fun pollClock(wallNow: Long): Instant? {
        val server = pollsServerNow ?: return null
        val fetchedAt = pollsFetchedAt ?: return server
        return server.plusMillis(wallNow - fetchedAt)
    }

    /** Polls still worth answering — judged by the anchored clock, which keeps running between fetches. */
    fun livePolls(clock: Instant?): List<CommunityPoll> =
        polls.filter { clock == null || CommunityRules.isPollOpen(it, clock) }

    /** The polls a room's detail screen shows. */
    fun pollsForRoom(room: String, clock: Instant?): List<CommunityPoll> =
        livePolls(clock).filter { it.room.equals(room, ignoreCase = true) }
}

/**
 * The community feature's one ViewModel.
 *
 * The repository is the facade; this class is the *pace*. A snapshot fetch runs when a
 * screen appears, when Realtime says something changed, and when the channel comes
 * alive — never on a timer, because a poll that runs while nobody is looking is the
 * connection budget spent on nobody. The 2-second floor between Realtime-triggered
 * refetches is what turns a burst of reports into one fetch, not a burst of fetches.
 */
class CommunityViewModel(
    private val repository: CommunityRepository,
    /** Present only on builds with credentials; null degrades every surface to read-only. */
    private val realtime: RealtimeObservations? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(CommunityUiState(available = realtime != null))
    val state: StateFlow<CommunityUiState> = _state.asStateFlow()

    /** Set by the first [onShown]; the engine starts on the first community *view*. */
    private var started = false

    /**
     * A refresh nudge that arrived while one was running — the coalescing flag
     * [refresh] answers with one more full pass. Volatile in spirit: only the
     * main dispatcher touches it.
     */
    private var refreshAgain = false

    /**
     * The engine starter — called by every community surface when it appears.
     *
     * Construction alone mints nothing: the identity belongs to the first community
     * *view* (the D1-A invariant), and the ViewModel is constructed once at the app
     * level so every surface shares one state and one Realtime channel. The first
     * call starts everything (local reload, snapshot, outbox observation, the
     * channel); every later call is just a refresh — a screen reappearing should show
     * the world as it is now, not as the last visit left it.
     */
    fun onShown() {
        if (!started) {
            started = true
            // Local first: the cache and the outbox are read before anything touches the
            // network, so a screen shows yesterday's answer immediately and today's when it
            // lands. Rejected/failed rows are part of the honest picture — a student who saw
            // "sent" needs to still see it, and one whose report failed needs to see that more.
            viewModelScope.launch { reloadLocal() }
            refresh()

            // The outbox is observed, not polled: every state change the sync engine makes
            // (queued → sent, failed attempt N, refusal code, withdrawal) reaches every
            // community surface as it happens. This is what makes "Waiting to send" a live
            // sentence rather than a state that only changes when the student reopens the
            // screen — the pull-only reads it replaces were why a report sat at "Waiting"
            // on screen after the send had already succeeded.
            viewModelScope.launch {
                repository.outboxChanges().collect {
                    reloadLocal()
                }
            }

            // Realtime is a freshness nudge, nothing more: an event means "the snapshot you
            // have is out of date", and the refetch — not the event — is what updates the
            // screen. One shared channel for every surface: the app-level instance holds
            // it, so a room's detail screen updates while it is the one on top.
            // A feed that dies is not the end: the flow ends itself quietly, and the loop
            // below rebuilds it on a slow drumbeat — the app-level channel has no screen
            // entry to rebuild it anymore. The runCatching is the belt under the flow's
            // own catch — an engine that throws while building the channel must not take
            // the app with it.
            if (realtime != null) {
                viewModelScope.launch {
                    runCatching {
                        var lastFetch = 0L
                        while (isActive) {
                            realtime.changes().collect {
                                if (wallClock() - lastFetch >= REFETCH_FLOOR_MS) {
                                    lastFetch = wallClock()
                                    // A nudge that arrives while a fetch is running is
                                    // coalesced into one more pass (see refresh) — never
                                    // dropped — so marking the floor here is safe: the
                                    // pulse's answer may trail by one fetch, but it always
                                    // comes.
                                    refresh()
                                }
                            }
                            // The collect above ended: the feed failed quietly (most often
                            // no network at join time) and tore its channel down. Wait one
                            // drumbeat and subscribe again — the ACTIVATED nudge inside the
                            // new feed fetches whatever happened meanwhile.
                            delay(REALTIME_RESUBSCRIBE_MS)
                        }
                    }
                }
            }
        } else {
            refresh()
        }
    }

    /**
     * Re-fetches the authoritative snapshot. Cheap to call often; runs one at a
     * time.
     *
     * A call that lands while another is still running is never dropped — it
     * folds into one more fetch at the end. The old drop-guard was why realtime
     * went stale in exactly the busiest moments: a pulse arriving while the
     * vote's own callback fetch was in flight was lost, and with it every
     * change since (a pulse carries no content; only the fetch can carry any).
     * Coalescing keeps "one fetch at a time" while making the last nudge the
     * one that always gets answered.
     */
    fun refresh() {
        if (_state.value.refreshing) {
            refreshAgain = true
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            do {
                refreshAgain = false
                refreshOnce()
            } while (refreshAgain)
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    /**
     * One full snapshot pass. Split out of [refresh] so the coalescing loop can
     * run it as many times as nudges demanded; everything it writes is
     * idempotent, so an extra pass costs one fetch and nothing else.
     */
    private suspend fun refreshOnce() {
        val ok = repository.refresh()
        // Captured the instant the snapshot landed — before the polls and My Polls
        // fetches below add their latency — so the (serverNow, snapshotFetchedAt)
        // pair stays tight. The undo window is 30 seconds; a loose pair would offer
        // Undo past the moment the server starts refusing it.
        val snapshotFetchedAt = if (ok) wallClock() else null
        // Polls have no cache to fall back on, so an unreachable fetch keeps what
        // is on screen — same bargain the observations' cache gets, minus the disk.
        // The clock anchor moves only on success, and keeps ticking from wherever
        // it last landed: that is what stops a poll held through a network outage
        // from outliving its own close on screen.
        val pollsSnapshot = repository.openPolls()
        // My Polls rides the same refresh: the owner-facing RPC carries the
        // withdrawal facts "My Polls" shows, and its clock is the server's own.
        val mine = repository.myPolls()
        reloadLocal()
        _state.value = _state.value.copy(
            stale = !ok && _state.value.loaded,
            // serverNow itself is set by reloadLocal from the cache rows; this is
            // only its wall-clock partner, moved on success alone so the pair never
            // claims a failed fetch's clock was fresh.
            snapshotFetchedAt = snapshotFetchedAt ?: _state.value.snapshotFetchedAt,
            polls = pollsSnapshot?.polls ?: _state.value.polls,
            pollsServerNow = pollsSnapshot?.serverNow ?: _state.value.pollsServerNow,
            pollsFetchedAt = if (pollsSnapshot != null) wallClock() else _state.value.pollsFetchedAt,
            myPolls = mine ?: _state.value.myPolls,
            myPollIds = (mine ?: _state.value.myPolls)?.mapTo(mutableSetOf()) { it.id } ?: emptySet(),
            // Settled, not successful: the fetch has answered, and only after it has
            // may the section call a null list a failure instead of a wait.
            myPollsSettled = true,
            pollError = null,
        )
    }

    /**
     * Enqueues a report. Returns the validation error when there is one (the sheet shows
     * it); null means accepted — locally queued, on its way when the network allows.
     *
     * A future claim arrives with its target hour; the claim's own validation (later slot
     * today, not yet started, room kind) runs in the repository alongside the report
     * rules, and the claim's event date is the day it was filed — decided there, at queue
     * time, so a row that waits out an offline morning still claims the day the student
     * meant it for.
     */
    fun submit(
        kind: CommunityReportKind,
        context: ReportContext,
        payload: ReportPayload,
        note: String?,
        observationType: CommunityObservationType = CommunityObservationType.PRESENT,
        targetStartHour: Int? = null,
    ): String? {
        // The date preview runs before anything is queued, exactly as the poll path does:
        // the server's clamp (today ± 1) is the authority, but a report it will refuse
        // should never reach the outbox to be refused there — "Declined — date_out_of_range"
        // a day later is not an answer the student can act on.
        contextDateError(context)?.let { return it }
        val error = repository.submitReport(
            kind = kind,
            room = context.room,
            section = context.section,
            subject = context.subject,
            classDate = context.classDate,
            startHour = context.startHour,
            payload = payload,
            note = note,
            observationType = observationType,
            targetStartHour = targetStartHour,
        )
        if (error == null) viewModelScope.launch { reloadLocal() }
        return error
    }

    /**
     * "Is this accurate?" — one verdict per student per observation. The result rides
     * back: a refusal the buttons should not have offered (`own_report`, a second
     * verdict the snapshot had not caught up with, a frozen or superseded identity)
     * says so in one line, exactly as a refused vote does — a tap that does nothing
     * is a bug, not a courtesy.
     */
    fun verify(observationId: String, verdict: Boolean) {
        repository.verify(observationId, verdict) { result ->
            when (result) {
                RemoteReportApi.SubmitResult.Unreachable ->
                    _state.value = _state.value.copy(pollError = "Couldn't reach the server")
                is RemoteReportApi.SubmitResult.Rejected ->
                    _state.value = _state.value.copy(pollError = verdictFailureLine(result.code))
                is RemoteReportApi.SubmitResult.Ok -> refresh()
            }
        }
    }

    /**
     * Asks a poll. Validation runs locally first (the same rules the server enforces);
     * [onDone] then receives null for "it went out — the sheet may close" or the line to
     * show instead. Unlike a report this is not queued: a poll nobody can see yet is a
     * question about now, so it is asked now or not at all.
     */
    fun createPoll(
        context: ReportContext,
        question: String,
        options: List<String>,
        startHour: Int?,
        onDone: (String?) -> Unit,
    ) {
        val error = CommunityRules.pollValidationError(question, options)
            ?: contextHourError(context, startHour)
            ?: contextDateError(context)
        if (error != null) {
            onDone(error)
            return
        }
        repository.createPoll(
            room = context.room,
            section = context.section,
            subject = context.subject,
            classDate = context.classDate,
            startHour = startHour,
            question = question,
            options = options,
        ) { result ->
            when (result) {
                RemoteReportApi.SubmitResult.Unreachable ->
                    onDone("Couldn't reach the server. Try again")
                is RemoteReportApi.SubmitResult.Rejected ->
                    onDone(pollFailureLine(result.code))
                is RemoteReportApi.SubmitResult.Ok -> {
                    refresh()
                    onDone(null)
                }
            }
        }
    }

    /** One vote, one tap. A refusal stays on screen as one line until the next refresh. */
    fun vote(pollId: String, optionIndex: Int) {
        // The last line of defense for closes_at: whatever the card shows, a vote past
        // the close is refused here, before the network — the server would refuse it
        // too, but the student should not have to spend a round-trip to be told. The
        // clock is the anchored one, so this holds even when the list on screen is the
        // last good fetch and the fetches since have all failed.
        val state = _state.value
        val poll = state.polls.firstOrNull { it.id == pollId }
        val clock = state.pollClock(wallClock())
        if (poll != null && clock != null && !CommunityRules.isPollOpen(poll, clock)) {
            _state.value = state.copy(pollError = pollFailureLine("poll_closed"))
            return
        }
        repository.castVote(pollId, optionIndex) { result ->
            when (result) {
                RemoteReportApi.SubmitResult.Unreachable ->
                    _state.value = _state.value.copy(pollError = "Couldn't reach the server")
                is RemoteReportApi.SubmitResult.Rejected ->
                    _state.value = _state.value.copy(pollError = pollFailureLine(result.code))
                is RemoteReportApi.SubmitResult.Ok -> refresh()
            }
        }
    }

    /** A class-bound poll needs the hour, exactly as `create_poll` requires it. */
    private fun contextHourError(context: ReportContext, startHour: Int?): String? =
        if (context.room == null && startHour == null) {
            "a poll about a class needs an hour"
        } else null

    /**
     * The server clamps context dates to its today ± 1 day; better to say so up front —
     * in the report sheet and the poll sheet alike, before anything is queued or asked.
     */
    private fun contextDateError(context: ReportContext): String? {
        val date = context.classDate ?: return null
        return if (!CommunityRules.isReportDateInRange(LocalDate.now(), date)) {
            "reports and polls can only be about today, yesterday or tomorrow"
        } else null
    }

    /** Hides a room's community card on this phone. Local only — the server never learns. */
    fun dismissRoom(room: String) {
        repository.dismissRoom(room)
        viewModelScope.launch { reloadLocal() }
    }

    fun forget(key: String) {
        repository.forget(key)
        viewModelScope.launch { reloadLocal() }
    }

    /** A failed row's "try now" — bypasses the backoff schedule once. */
    fun retryNow(key: String) {
        repository.retryNow(key)
        viewModelScope.launch { reloadLocal() }
    }

    // -------------------------------------------------------- undo / withdraw

    /**
     * Takes a just-submitted report back, inside the fresh window. The sheet shows
     * undo right after a submission; this call is what the tap does. The local row's
     * state is the server's answer only — and the outbox Flow observer reloads every
     * surface the moment it lands, so "My Reports" flips to "Taken back" by itself.
     */
    fun undoReport(key: String, onDone: (Boolean) -> Unit = {}) {
        repository.undoReport(key) { outcome ->
            if (outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY
            ) {
                refresh()
            }
            onDone(outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY)
        }
    }

    /**
     * Withdraws a report after the window. Confirmation wording lives where it is
     * asked for (the UI), because the words are the product's, not the state's —
     * see the caller for why they say "reputation decreases" and nothing more.
     */
    fun withdrawReport(key: String, onDone: (Boolean) -> Unit = {}) {
        repository.withdrawReport(key) { outcome ->
            if (outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY
            ) {
                refresh()
            }
            onDone(outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY)
        }
    }

    /** The polls' twins — same shape, no local row to update, same refresh on done. */
    fun undoPoll(pollId: String, onDone: (Boolean) -> Unit = {}) {
        repository.undoPoll(pollId) { outcome ->
            if (outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY
            ) {
                refresh()
            }
            onDone(outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY)
        }
    }

    fun withdrawPoll(pollId: String, onDone: (Boolean) -> Unit = {}) {
        repository.withdrawPoll(pollId) { outcome ->
            if (outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY
            ) {
                refresh()
            }
            onDone(outcome == CommunityRepository.WithdrawOutcome.DONE ||
                outcome == CommunityRepository.WithdrawOutcome.ALREADY)
        }
    }

    private suspend fun reloadLocal() {
        val observations = repository.cachedObservations()
        val reports = repository.myReports()
        val dismissed = repository.dismissedRooms().toSet()
        // serverNow comes from the snapshot, which the repository carries in the cache
        // rows; a cache written by an older snapshot is still a better clock than none.
        val snapshotClock = repository.cacheServerNow()
        _state.value = _state.value.copy(
            loaded = true,
            observations = observations,
            myReports = reports,
            // The ids of rows the server accepted for this student: the verify buttons'
            // own-report rule. Derived here so a single source (the outbox) feeds it.
            mySentIds = reports.mapNotNull { it.serverObservationId }.toSet(),
            dismissed = dismissed,
            serverNow = snapshotClock,
        )
    }

    companion object {
        /**
         * The floor between Realtime-triggered refetches. Two seconds, not ten: the
         * point of the live channel is that a screen watching a room shows its new
         * state almost at once, and two seconds still turns any burst of events into
         * at most one fetch per two seconds.
         */
        private const val REFETCH_FLOOR_MS = 2_000L

        /**
         * The drumbeat a dead Realtime feed is rebuilt on. The channel is app-level, so
         * nothing else will resubscribe it; one attempt per 30 seconds — the same order
         * as Realtime's own heartbeat — is nothing next to the snapshot fetches, and
         * keeps a transient failure at app start from muting the feed for a session.
         */
        private const val REALTIME_RESUBSCRIBE_MS = 30_000L

        /**
         * The app-level instance — every community surface shares it, so there is one
         * state and one Realtime channel for the whole app. It mints nothing on its
         * own: the engine starts on the first community screen's [onShown].
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = container
                CommunityViewModel(
                    repository = container.community,
                    realtime = container.communityClient?.let { RealtimeObservations(it) },
                )
            }
        }
    }
}
