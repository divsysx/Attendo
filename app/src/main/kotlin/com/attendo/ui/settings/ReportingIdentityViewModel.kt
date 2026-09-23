package com.attendo.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.data.community.CommunityRepository
import com.attendo.data.community.PendingMoveMemory
import com.attendo.data.community.RemoteReportApi
import com.attendo.ui.container
import com.attendo.ui.localLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Something to tell the student once an operation has finished — the same shape as
 * [BackupNotice]: a line, whether it was good news, and what to do next when there
 * is something to do.
 */
data class IdentityNotice(
    val title: String,
    val message: String,
    val ok: Boolean,
    val hint: String? = null,
)

data class ReportingIdentityUiState(
    /** True once the opening status check has finished (or been skipped: no endpoint). */
    val loaded: Boolean = false,
    /** False on a build without credentials — nothing on this screen can run. */
    val available: Boolean = false,
    /** The status check answered. False means unreachable: no claim either way is made. */
    val statusKnown: Boolean = false,
    /** This identity has moved to another phone; every community write is refused. */
    val superseded: Boolean = false,
    /** Another phone has claimed this identity and the 24-hour window is running. */
    val transferPending: Boolean = false,
    /**
     * This phone has claimed ANOTHER identity and the window is running: it is
     * the claimant. When [claimReplacesIdentity] is true the claim is a
     * replacement — this phone's own community history will be hard-deleted if
     * the move completes, and its community writes are frozen server-side
     * until the window resolves.
     */
    val claimPending: Boolean = false,
    val claimReplacesIdentity: Boolean = false,
    /**
     * When the pending window ends, when there is one — [transferClaimDeadline]
     * for the owner (the other phone's claim on this identity),
     * [claimDeadline] for the claimant (this phone's claim on another). Null
     * when nothing is waiting or the server predates 0019; the screens fall
     * back to saying "24 hours" without pinning it to a moment.
     */
    val transferClaimDeadline: java.time.Instant? = null,
    val claimDeadline: java.time.Instant? = null,
    /** The one long operation in flight, if any — shown as a non-dismissible dialog. */
    val working: String? = null,
    val notice: IdentityNotice? = null,
    /**
     * The file whose identity this phone was refused (identity_not_fresh), parked
     * while the destructive confirmation is on screen. Non-null means "the replace
     * dialog is open" — it holds a content Uri (not a secret), and is cleared on
     * confirm or cancel. The passphrase is never kept: the dialog asks again.
     */
    val pendingReplacement: Uri? = null,
) {
    val busy: Boolean get() = working != null
}

/**
 * The Reporting identity screen's pace — the transfer twin of [BackupViewModel].
 *
 * Everything here goes through [CommunityRepository]; this class is only the order
 * things happen in and the words the outcomes get. The passphrase never enters
 * state: it travels from the dialog straight into the repository call as a
 * CharArray, which the repository wipes when it is done. No transfer code, token
 * or passphrase is ever held here, logged here, or put in a notice.
 */
class ReportingIdentityViewModel(
    private val repository: CommunityRepository,
    available: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportingIdentityUiState(available = available))
    val state: StateFlow<ReportingIdentityUiState> = _state.asStateFlow()

    init {
        refreshStatus()
    }

    /**
     * Asks the server where this identity stands. Null (unreachable) is kept distinct
     * from false: "unknown" must never render as "no move pending" — the pending claim
     * and the superseded state are exactly the things worth being unsure about out loud.
     *
     * A refresh can also *witness a move resolve*: the other phone decides the question
     * this screen is staring at, and without this the banner would simply vanish
     * (the gap the two-device test hit — "went out and came back, the waiting banner
     * was gone and nothing said what happened"). So when a remembered wait comes back
     * resolved, the refresh says which way it went. The memory is the repository's
     * [com.attendo.data.community.PendingMoveMemory], not this class's fields, on
     * purpose: this ViewModel dies with its screen, and a move that resolves while
     * nobody is looking has to still be news to the next one.
     */
    fun refreshStatus() = refreshStatus(reportTransitions = true)

    private fun refreshStatus(reportTransitions: Boolean) {
        if (!_state.value.available) {
            _state.update { it.copy(loaded = true) }
            return
        }
        // One check in flight at a time: the poll, a resume and a retry can land on
        // the same moment, and a second overlapping answer adds nothing but a race
        // over which state update lands last.
        if (statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            // Read before the status call: the call consumes the memory when the
            // wait is over, and the pair is what a resolution is made of.
            val remembered = repository.pendingMoveMemory()
            val status = runCatching { repository.identityStatus() }.getOrNull()
            _state.update { before ->
                val resolved = if (reportTransitions) {
                    resolvedMoveNotice(remembered, status)
                } else {
                    null
                }
                before.copy(
                    loaded = true,
                    statusKnown = status != null,
                    superseded = status?.superseded == true,
                    transferPending = status?.transferPending == true,
                    claimPending = status?.claimPending == true,
                    claimReplacesIdentity = status?.claimReplacesIdentity == true,
                    transferClaimDeadline = status?.transferClaimDeadline,
                    claimDeadline = status?.claimDeadline,
                    notice = resolved ?: before.notice,
                )
            }
        }
    }

    /**
     * The notice for a move that resolved between two status checks — the outcome the
     * other phone decided, arriving with nobody on this phone having pressed anything.
     *
     * The owner's side is unambiguous: `superseded` is the server's own record of
     * which way the grant went. The claimant's side has no flag for it, so the same
     * read's report ids carry the answer: a completed move re-parents the moved
     * identity's rows to this uid, a cancelled one leaves this uid's rows untouched
     * (this uid is frozen or kept fresh for exactly as long as the claim waits, so
     * its own set cannot move underneath the comparison).
     *
     * Only fires on a *remembered* wait resolving: a check that never saw a wait is
     * not news, and neither is a check whose outcome the student just decided on
     * this screen ([refreshStatus] is called quietly from those paths, because their
     * own notices own the moment).
     */
    private fun resolvedMoveNotice(
        remembered: PendingMoveMemory.Pending?,
        after: RemoteReportApi.IdentityStatus?,
    ): IdentityNotice? {
        if (after == null || remembered == null) return null

        if (!remembered.claimant && !after.transferPending) {
            return if (after.superseded) {
                IdentityNotice(
                    title = "The identity has moved",
                    message = "The other phone finished the move. Everything this " +
                        "identity reported now belongs to it, and this phone can no " +
                        "longer report as that identity. There is no undo. To get the " +
                        "identity back later, export an identity file on the other " +
                        "phone and import it here: that is a new move, and the identity " +
                        "leaves that phone when it completes. Or start fresh below.",
                    ok = true,
                )
            } else {
                IdentityNotice(
                    title = "The move is no longer waiting",
                    message = "It was cancelled or ran out. Your reporting identity " +
                        "stays on this phone exactly as it was, and the identity " +
                        "file that started the move no longer works.",
                    ok = true,
                )
            }
        }

        if (remembered.claimant && !after.claimPending) {
            return if (after.reportIds != remembered.reportIds) {
                IdentityNotice(
                    title = "The move completed",
                    message = "The old phone let the identity move, and it now " +
                        "belongs to this phone: every report and poll it made is " +
                        "here, and the phone it came from can no longer report as it.",
                    ok = true,
                )
            } else {
                IdentityNotice(
                    title = "The move was cancelled",
                    message = "The old phone cancelled it, or the claim ran out. " +
                        "This phone keeps its own identity exactly as it was.",
                    ok = true,
                )
            }
        }

        return null
    }

    // ---- the live screen ----------------------------------------------------

    /**
     * How often a foregrounded screen re-asks the server where the identity stands.
     * The move's other half is decided by a person on another phone, and nothing
     * pushes to this one — polling while the screen is actually looked at is the
     * only way "A move is waiting on you" appears without leaving and returning.
     * Ten seconds reads as instant next to a human on the other end, and costs one
     * lightweight read on a screen nobody leaves open for long.
     */
    private var statusJob: Job? = null
    private var pollingJob: Job? = null

    /** Begins the foreground poll. Paired with [stopStatusPolling] on every pause. */
    fun startStatusPolling() {
        stopStatusPolling()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(STATUS_POLL_INTERVAL_MS)
                refreshStatus()
            }
        }
    }

    /** Ends the foreground poll; the resume refresh covers the return. */
    fun stopStatusPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ---- export -------------------------------------------------------------

    /** Writes the identity file to the document the student created in the picker. */
    fun exportTo(target: Uri, passphrase: CharArray) {
        if (_state.value.busy) return
        _state.update { it.copy(working = WORKING_EXPORT, notice = null) }
        viewModelScope.launch {
            val notice = when (val result = repository.exportIdentityBackup(target, passphrase)) {
                is CommunityRepository.AtidExportResult.Exported -> IdentityNotice(
                    title = "Identity file written",
                    message = "This file, with its passphrase, can move your reporting " +
                        "identity, reports, polls and reputation, to another phone. The " +
                        "code inside works until ${result.expiresAt.localLabel()} and can " +
                        "be used once.",
                    ok = true,
                    hint = "Keep the file and the passphrase apart, and off this phone. " +
                        "If the code expires unused, exporting again makes a new one.",
                )

                is CommunityRepository.AtidExportResult.Refused -> IdentityNotice(
                    title = "Nothing was exported",
                    message = exportRefusalLine(result.code),
                    ok = false,
                )

                CommunityRepository.AtidExportResult.WriteFailed -> IdentityNotice(
                    title = "Nothing was exported",
                    message = "The file could not be written.",
                    ok = false,
                    hint = "Try a different location, or check the storage.",
                )

                CommunityRepository.AtidExportResult.Unreachable -> IdentityNotice(
                    title = "Nothing was exported",
                    message = "The server could not be reached. Nothing has changed.",
                    ok = false,
                    hint = "Check your connection and try again.",
                )
            }
            _state.update { it.copy(working = null, notice = notice) }
        }
    }

    // ---- import -------------------------------------------------------------

    /**
     * Claims the identity an identity file authorises. The passphrase is consumed
     * here — wrong or right, it never survives the call.
     *
     * When the server answers `identity_not_fresh` (this phone has its own
     * community history) the outcome is not a dead end: [pendingReplacement] is
     * set and the screen offers the destructive confirmation. The file's
     * passphrase was already wiped, so confirming asks for it again — the
     * replacement claim re-reads the same file.
     */
    fun importFrom(source: Uri, passphrase: CharArray) {
        if (_state.value.busy) return
        _state.update { it.copy(working = WORKING_IMPORT, notice = null) }
        viewModelScope.launch {
            val result = repository.importIdentityBackup(source, passphrase)
            handleImportResult(result, source)
        }
    }

    /**
     * The destructive path, confirmed. [source] is the same file the refused
     * claim came from; this call re-reads it and claims WITH replacement, which
     * the server will hard-delete this phone's identity for when the move
     * completes. Until then nothing is deleted: the 24-hour window still runs,
     * writes are frozen (server-side) but the outbox and history are preserved.
     */
    fun confirmReplaceFrom(source: Uri, passphrase: CharArray) {
        if (_state.value.busy) return
        _state.update {
            it.copy(working = WORKING_IMPORT, notice = null, pendingReplacement = null)
        }
        viewModelScope.launch {
            val result = repository.importIdentityBackup(source, passphrase, replaceClaimant = true)
            handleImportResult(result, source, confirmedReplacement = true)
        }
    }

    /** The student backed out of the destructive dialog — nothing moves. */
    fun cancelReplacement() {
        _state.update { it.copy(pendingReplacement = null) }
    }

    private fun handleImportResult(
        result: CommunityRepository.AtidImportResult,
        source: Uri,
        confirmedReplacement: Boolean = false,
    ) {
        val notice = when (result) {
            is CommunityRepository.AtidImportResult.Claimed -> {
                if (result.replaceClaimant) {
                    IdentityNotice(
                        title = "The replacement has started",
                        message = "This phone has claimed the identity in that file. Until " +
                            "${result.claimDeadline.localLabel()} the old phone can cancel " +
                            "the move. This phone's community actions are paused while it " +
                            "waits. Using it for anything doesn't cancel the move, and " +
                            "nothing is deleted until the move completes.",
                        ok = true,
                        hint = "Come back after that time and import the same file again to " +
                            "finish. If the move is cancelled, this phone's own history is " +
                            "exactly as it was.",
                    )
                } else {
                    IdentityNotice(
                        title = "The move has started",
                        message = "This phone has claimed the identity in that file. Until " +
                            "${result.claimDeadline.localLabel()} the old phone can cancel the " +
                            "move, and using it for anything cancels it too.",
                        ok = true,
                        hint = "Come back after that time and import the same file again to " +
                            "finish the move.",
                    )
                }
            }

            is CommunityRepository.AtidImportResult.Completed -> {
                if (result.replaced) {
                    IdentityNotice(
                        title = "Reporting identity replaced",
                        message = "The identity from the file, its reports, polls and " +
                            "reputation, now belongs to this phone. The identity this " +
                            "phone held before, and everything it reported, has been " +
                            "permanently deleted from Attendo's servers and cannot be " +
                            "recovered.",
                        ok = true,
                    )
                } else {
                    IdentityNotice(
                        title = "Reporting identity restored",
                        message = "Your reports, polls and reputation now belong to this " +
                            "phone. The phone they came from can no longer use them.",
                        ok = true,
                    )
                }
            }

            CommunityRepository.AtidImportResult.WrongPassphrase -> IdentityNotice(
                title = "That passphrase didn't open the file",
                message = "The file's own integrity check failed, which is what a " +
                    "wrong passphrase looks like. Nothing was changed.",
                ok = false,
                hint = "Try again, or re-export the file from the phone that made it.",
            )

            CommunityRepository.AtidImportResult.NotAnAtidFile -> IdentityNotice(
                title = "That is not an identity file",
                message = "The file was not written by Attendo's Reporting identity " +
                    "export.",
                ok = false,
                hint = "Identity files end in .atid and come from Settings → " +
                    "Reporting identity.",
            )

            is CommunityRepository.AtidImportResult.UnsupportedVersion -> IdentityNotice(
                title = "This file is from a newer Attendo",
                message = "It was written in format ${result.version}, which this " +
                    "Attendo cannot read.",
                ok = false,
                hint = "Update Attendo, or export again from a phone with the same " +
                    "version.",
            )

            CommunityRepository.AtidImportResult.Corrupt -> IdentityNotice(
                title = "This file is damaged",
                message = "It failed its own integrity check. Nothing was changed.",
                ok = false,
            )

            CommunityRepository.AtidImportResult.Unreadable -> IdentityNotice(
                title = "That file could not be opened",
                message = "Nothing was changed.",
                ok = false,
            )

            is CommunityRepository.AtidImportResult.Refused -> {
                // The not-fresh refusal is the destructive dialog's trigger, not a
                // dead end: this phone holds a history of its own, and the only way
                // forward is replacing it — which needs an explicit confirmation.
                // But only on a phone that still HOLDS that history. A superseded
                // phone's is already gone server-side, and the claim RPC refuses a
                // tombstoned uid even with the replace flag (0017: "a tombstoned
                // uid has nothing left to replace"), so the dialog offered there
                // can never succeed — its confirm just re-refuses and re-parks.
                // The same is true of a not-fresh arriving on the confirmed call
                // itself: with the replace flag set, that code can only mean
                // superseded. For both, the refusal is a direction, not an offer:
                // start fresh, then import this same file again.
                if (result.code == "identity_not_fresh" &&
                    !confirmedReplacement &&
                    !_state.value.superseded
                ) {
                    _state.update { it.copy(pendingReplacement = source) }
                    null
                } else if (result.code == "identity_not_fresh") {
                    IdentityNotice(
                        title = "This phone's identity already moved",
                        message = "There is nothing left here to replace. The identity " +
                            "this phone held lives on another phone now, and its claims " +
                            "are refused. Start fresh below, then import this same file " +
                            "again to bring the identity back.",
                        ok = false,
                        hint = "Starting fresh is safe here: this phone's old community " +
                            "history is already gone from the servers.",
                    )
                } else {
                    IdentityNotice(
                        title = "The move was refused",
                        message = claimRefusalLine(result.code),
                        ok = false,
                    )
                }
            }

            CommunityRepository.AtidImportResult.Unreachable -> IdentityNotice(
                title = "The server could not be reached",
                message = "Nothing was changed.",
                ok = false,
                hint = "Check your connection and try again.",
            )
        }
        _state.update { it.copy(working = null, notice = notice) }
        // Both claim outcomes change where this install stands — re-check so the
        // banner below the top bar is true as soon as the dialog closes. Quietly:
        // the notice above already tells this story, and the refresh's own
        // transition notice exists for outcomes nobody on this screen caused.
        refreshStatus(reportTransitions = false)
    }

    // ---- the pending window -------------------------------------------------

    /** The owner's "let it move now" — completes the pending transfer immediately. */
    fun approve() {
        if (_state.value.busy) return
        _state.update { it.copy(working = WORKING_DECIDING, notice = null) }
        repository.approveIdentityTransfer { result -> onDecided(result, approved = true) }
    }

    /** The owner's "keep it here" — kills the pending transfer. */
    fun abort() {
        if (_state.value.busy) return
        _state.update { it.copy(working = WORKING_DECIDING, notice = null) }
        repository.abortIdentityTransfer { result -> onDecided(result, approved = false) }
    }

    private fun onDecided(result: RemoteReportApi.TransferResult, approved: Boolean) {
        val notice = when (result) {
            is RemoteReportApi.TransferResult.Completed -> IdentityNotice(
                title = "The identity has moved",
                message = "Everything this identity reported now belongs to the other " +
                    "phone, and this phone can no longer report as that identity. " +
                    "There is no undo. To get the identity back later, export an " +
                    "identity file on the other phone and import it here: that is a " +
                    "new move, and the identity leaves that phone when it completes. " +
                    "Or start fresh here.",
                ok = true,
            )

            is RemoteReportApi.TransferResult.Aborted -> IdentityNotice(
                title = "The move was cancelled",
                message = "Your reporting identity stays on this phone. The file that " +
                    "started the move no longer works.",
                ok = true,
            )

            is RemoteReportApi.TransferResult.Rejected -> IdentityNotice(
                title = "That didn't work",
                message = transferDecisionFailureLine(result.code),
                ok = false,
            )

            RemoteReportApi.TransferResult.Unreachable -> IdentityNotice(
                title = "The server could not be reached",
                message = "Nothing was changed. The move is still waiting on your answer.",
                ok = false,
                hint = "Check your connection and try again.",
            )

            else -> IdentityNotice(
                title = "Unexpected answer",
                message = "Nothing was changed.",
                ok = false,
            )
        }
        _state.update { it.copy(working = null, notice = notice) }
        // An approve supersedes this install; an abort clears the pending flag. Either
        // way the banner must not survive on stale information. Quietly, for the same
        // reason as the import path: the notice above is the story of this decision.
        refreshStatus(reportTransitions = false)
    }

    // ---- superseded ---------------------------------------------------------

    /**
     * "Start fresh", confirmed. The old identity is dead server-side; this signs it
     * out, clears its local rows, and mints a new anonymous identity with no history.
     */
    fun startFresh() {
        if (_state.value.busy) return
        _state.update { it.copy(working = WORKING_FRESH, notice = null) }
        viewModelScope.launch {
            val ok = runCatching { repository.startFreshIdentity() }.getOrDefault(false)
            _state.update {
                it.copy(
                    working = null,
                    notice = if (ok) {
                        IdentityNotice(
                            title = "Starting fresh",
                            message = "This phone now has a new, empty reporting identity. " +
                                "Reports you make from now on belong to it.",
                            ok = true,
                        )
                    } else {
                        IdentityNotice(
                            title = "Couldn't start fresh",
                            message = "The new identity could not be created. The moved " +
                                "one is still signed in, and still cannot report.",
                            ok = false,
                            hint = "Check your connection and try again.",
                        )
                    },
                )
            }
            refreshStatus()
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    companion object {
        private const val WORKING_EXPORT = "Encrypting and writing your identity file…"
        private const val WORKING_IMPORT = "Opening the file and claiming the identity…"
        private const val WORKING_DECIDING = "Telling the server…"
        private const val WORKING_FRESH = "Starting a fresh identity…"

        /** See [startStatusPolling] for why this exists and why it is this long. */
        private const val STATUS_POLL_INTERVAL_MS = 10_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = container
                ReportingIdentityViewModel(
                    repository = app.community,
                    available = app.communityClient != null,
                )
            }
        }
    }
}

// ---- words -------------------------------------------------------------------

/**
 * The server's refusal of an export, said as a student would say it — the transfer
 * twin of [reportFailureLine]. An unknown code from a newer server still shows as
 * itself, exactly as the community lines do.
 */
internal fun exportRefusalLine(code: String): String = when (code) {
    "nothing_to_transfer" ->
        "This identity has no community history yet, so there is nothing to move. " +
            "Once you have reported something, the export will work."
    "rate_limited" ->
        "That's three identity files today. A fresh one can be exported tomorrow."
    "transfer_already_pending" ->
        "A move is already underway from this phone. Decide on it at the top of this " +
        "screen first."
    "identity_superseded" ->
        "This identity has already moved to another phone, so it has nothing to export."
    "not_authenticated" -> "You're signed out. Reopen the app and try again."
    else -> "The server refused ($code)."
}

/** The server's refusal of a claim — every state the transfer can be in that isn't a win. */
internal fun claimRefusalLine(code: String): String = when (code) {
    "invalid_code" ->
        "The code in this file wasn't recognised. It may be mistyped, or the file damaged."
    "transfer_unknown" ->
        "The code in this file doesn't match any transfer. It may have expired and been " +
            "cleared. Export a fresh file on the old phone."
    "transfer_used" ->
        "This file has already moved an identity. A code works once; export a fresh file " +
            "on the old phone."
    "transfer_aborted" ->
        "The move this file started was cancelled by the old phone. Export a fresh file " +
            "there if you still want to move."
    "transfer_expired" ->
        "The code in this file has expired (they last 3 days). Export a fresh file on " +
            "the old phone."
    "transfer_self" ->
        "This file was exported from this phone. An identity can't move to where it " +
            "already is."
    "transfer_in_progress" ->
        "Another phone has already claimed this identity. A code moves one identity to " +
            "one phone."
    "identity_not_fresh" ->
        "This phone already has its own community history, and a restore never merges " +
            "two identities. Start on a phone without community activity, or clear this " +
            "one's data first."
    "nothing_to_replace" ->
        "There is no longer anything to replace: this phone's community history is " +
            "gone. Import the file again without the replace step."
    "identity_superseded" ->
        "The identity this phone held has already been moved away. Start fresh from the " +
            "top of this screen."
    "transfer_not_ready" ->
        "There is no move waiting on this file right now. It may have completed or been " +
            "cancelled already."
    "not_authenticated" -> "You're signed out. Reopen the app and try again."
    else -> "The server refused ($code)."
}

/** The server's refusal of an approve/abort — both are owner decisions on a live grant. */
internal fun transferDecisionFailureLine(code: String): String = when (code) {
    "transfer_not_ready" ->
        "There is no move waiting any more. It may have completed, expired or been " +
            "cancelled already. The status above is the truth."
    "not_authenticated" -> "You're signed out. Reopen the app and try again."
    else -> "The server refused ($code)."
}
