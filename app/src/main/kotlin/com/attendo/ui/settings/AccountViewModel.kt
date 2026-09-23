package com.attendo.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.sync.AttendanceSyncPolicy
import com.attendo.core.sync.AttendanceSyncPolicy.AccountTransition
import com.attendo.core.sync.AttendanceSyncPolicy.ReturningReconciliation
import com.attendo.data.AttendanceQuarantineStore
import com.attendo.data.account.AccountManager
import com.attendo.data.account.AccountState
import com.attendo.data.account.HandoffFailure
import com.attendo.data.account.HandoffNotice
import com.attendo.data.account.HandoffOutcome
import com.attendo.data.account.SignOutcome
import com.attendo.data.sync.AttendanceSyncEngine
import com.attendo.data.sync.SyncPassResult
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Account transitions requiring explicit student choice/confirmation.
 */
sealed interface AccountTransitionPrompt {
    /**
     * Account B attempting to use a device whose local attendance belongs to Account A.
     * Must ask for destructive replacement approval before wiping Account A's local rows
     * and claiming ownership for Account B.
     */
    data class SwitchAccount(val incomingUserId: String, val ownerUserId: String) : AccountTransitionPrompt

    /**
     * The same account returning after sign-out, with unpushed attendance edits or
     * tombstones made while signed out. Asks whether to keep/merge or discard/restore.
     */
    data class ReconcileReturning(val userId: String) : AccountTransitionPrompt
}

private data class AccountDetails(
    val linking: Boolean,
    val signingIn: Boolean,
    val signingOut: Boolean,
    val message: AccountMessage?,
    val prompt: AccountTransitionPrompt?,
)

/**
 * The Account screen: what the install's one session is, and the three actions that
 * can change it — connect GitHub (make this phone's identity an account), sign in to
 * an existing account, sign out.
 *
 * The account itself is not here — [AccountManager] owns it, off the community session —
 * so this ViewModel is deliberately a thin window: state straight from the manager, one
 * forwarding method per action, and the sentence that answers each press. A null
 * manager (a build without an endpoint) means the screen says so and offers nothing,
 * exactly the way a build without an update source shows no update section.
 *
 * Both collectors live in [init], which runs once per ViewModel: an Activity recreation
 * recomposes the screen but reuses this object, so there is no second subscription to
 * the notices or the state, and no duplicated sentence.
 */
class AccountViewModel(
    private val manager: AccountManager?,
    private val syncEngine: AttendanceSyncEngine? = null,
    /**
     * The copy of a removed account's attendance, or null on a build that cannot have one.
     *
     * Read only for [AccountUiState.attendanceSetAside], which is the sentence that keeps the
     * deletion explanation honest *after* the phone has been claimed by the next account: at
     * the moment of the deletion nothing was deleted and the message says so, and once the
     * rows have been set aside and cleared the same sentence would be false. Only the file
     * knows which of the two is true, so the file is what is read — no second flag mirroring
     * it, because two records of one fact is how they come to disagree.
     */
    private val quarantine: AttendanceQuarantineStore? = null,
    private val drainCommunity: () -> Unit = {},
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val linking = MutableStateFlow(false)

    private val signingIn = MutableStateFlow(false)

    private val signingOut = MutableStateFlow(false)

    /** The one-line answer to the last press, so the button is never a mystery. */
    private val message = MutableStateFlow<AccountMessage?>(null)

    private val transitionPrompt = MutableStateFlow<AccountTransitionPrompt?>(null)

    private val syncing = MutableStateFlow(false)

    private val syncResult = MutableStateFlow<AccountSyncResult?>(null)

    init {
        // The browser round-trip ends without a second press: the callback either
        // carried a session (state turns Linked on its own — see the state collector
        // below) or came back without one, and the without-one verdicts arrive as
        // notices here. One flow for both paths, because the deep link is one: the
        // callback does not say which button opened the browser, and neither verdict
        // depends on which did. Collected for as long as this ViewModel lives, so the
        // sentence is waiting whenever the Account screen is next looked at.
        manager?.notices?.let { notices ->
            viewModelScope.launch {
                notices.collect { notice ->
                    linking.value = false
                    signingIn.value = false
                    message.value = accountMessageOf(notice)
                }
            }
        }
        // The session changing underneath the screen is the other half of the story:
        // the callback's import turns the state Linked on its own — for a link, this
        // install's own user with its new identity; for a sign-in, the account's user
        // — and the message that described the browser handoff ("Opening GitHub…")
        // must not outlive the browser's return. Nothing here polls and nothing
        // refreshes the screen — the manager's state flow is the one truth, and this
        // only retires the sentence once the truth has answered it.
        manager?.state?.let { states ->
            viewModelScope.launch {
                states.collect { account ->
                    message.value = messageAfterHandoff(account, message.value)
                    checkAccountTransition(account)
                }
            }
        }
    }

    private suspend fun checkAccountTransition(account: AccountState) {
        val sync = syncEngine ?: return
        if (account !is AccountState.Linked) {
            transitionPrompt.value = null
            return
        }

        val linkedUserId = account.uid
        val owner = sync.attendanceOwner()
        when (AttendanceSyncPolicy.accountTransition(owner, linkedUserId)) {
            AccountTransition.FIRST_CLAIM -> {
                transitionPrompt.value = null
            }
            AccountTransition.SAME_ACCOUNT_RETURN -> {
                if (sync.hasLoggedOutEdits(linkedUserId)) {
                    transitionPrompt.value = AccountTransitionPrompt.ReconcileReturning(linkedUserId)
                } else {
                    transitionPrompt.value = null
                }
            }
            AccountTransition.DIFFERENT_ACCOUNT_REFUSE -> {
                transitionPrompt.value = AccountTransitionPrompt.SwitchAccount(
                    incomingUserId = linkedUserId,
                    ownerUserId = owner?.userId ?: "",
                )
            }
            AccountTransition.SWITCH_APPROVED -> {
                transitionPrompt.value = null
            }
            // A new lifecycle, not a switch, and deliberately silent. The claim this account
            // is arriving over names an account the server established is gone, so there is
            // nothing to ask: the outgoing account has no owner left to want its data kept,
            // and the sentence already shown for the deletion said the next sign-in would be
            // treated as a new account. Asking anyway would put "replace the other account's
            // data?" in front of a student whose other account no longer exists — and put the
            // only route out of a stuck sync behind a confirmation about a ghost.
            //
            // Nothing is cleared here either. The pass that follows this sign-in carries it
            // out — set the outgoing body aside, then claim — so the screen stays a view of
            // the lifecycle rather than a second executor of it. See
            // [com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE].
            AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION -> {
                transitionPrompt.value = null
            }
        }
    }

    private val flowsCombined = combine(
        linking,
        signingIn,
        signingOut,
        message,
        transitionPrompt,
    ) { linking, signingIn, signingOut, message, prompt ->
        AccountDetails(linking, signingIn, signingOut, message, prompt)
    }

    val state: StateFlow<AccountUiState> = combine(
        manager?.state ?: MutableStateFlow(AccountState.NoSession),
        // Read straight from the manager rather than folded into a message: the deletion is
        // a *situation*, not the answer to a press, and it has to still be on screen when
        // the student next opens this page — possibly days later, having wondered why they
        // were signed out. See AccountManager.accountRemoved.
        manager?.accountRemoved ?: MutableStateFlow(false),
        flowsCombined,
        syncing,
        syncResult,
    ) { account, accountRemoved, details, syncingNow, syncResultNow ->
        AccountUiState(
            available = manager != null,
            account = account,
            accountRemoved = accountRemoved,
            // Two `stat` calls on an app-private file, on the emissions that already happen
            // (a session changing, a press, a pass finishing) — not a per-frame read. The
            // pass that writes the copy runs from the application scope while this screen may
            // be open, so the value is re-read on the next emission rather than watched; the
            // copy appearing a beat late is not a state the student can act on.
            attendanceSetAside = quarantine?.exists() == true,
            linking = details.linking,
            signingIn = details.signingIn,
            signingOut = details.signingOut,
            message = details.message,
            transitionPrompt = details.prompt,
            syncing = syncingNow,
            syncResult = syncResultNow,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUiState())

    /**
     * "Connect GitHub" — the first-time path — pressed. The browser takes over from
     * here; the session comes back as the login-callback deep link with the *same* UID
     * it left with, and [AccountUiState.account] turns [AccountState.Linked] on its
     * own; there is nothing to poll and no second code path.
     */
    fun linkWithGitHub() {
        val account = manager ?: return
        if (linking.value || signingIn.value) return
        viewModelScope.launch {
            linking.value = true
            message.value = null
            message.value = when (val outcome = account.linkWithGitHub()) {
                HandoffOutcome.Started -> AccountMessage.OPENING
                is HandoffOutcome.Failed -> accountMessageOf(outcome.reason)
            }
            linking.value = false
        }
    }

    /**
     * "Sign in to an existing account" — the returning path — pressed, after the
     * screen's confirmation. The manager discards the outgoing identity's local
     * community rows and opens the browser; the account's session comes back as the
     * same login-callback deep link, and [AccountUiState.account] turns
     * [AccountState.Linked] with the *account's* UID on its own. The one answer this
     * press can get before the browser takes over is that the browser would not open.
     */
    fun signInToExistingAccount() {
        val account = manager ?: return
        if (signingIn.value || linking.value) return
        viewModelScope.launch {
            signingIn.value = true
            message.value = null
            message.value = when (val outcome = account.signInToExistingAccount()) {
                HandoffOutcome.Started -> AccountMessage.OPENING
                // The sign-in press makes no request, so nothing can be unreachable
                // today; the fold keeps the mapping honest should that ever change.
                is HandoffOutcome.Failed -> accountMessageOf(outcome.reason)
            }
            signingIn.value = false
        }
    }

    /**
     * "Sign out", pressed (after the screen's confirmation). One call, one answer: the
     * session ends on the existing client and [AccountUiState.account] turns
     * [AccountState.NoSession] on its own — nothing else is touched, because sign-out
     * is not account deletion, not a community identity reset, and not Clear All. The
     * way back is the sign-in button, which is what makes this safe to offer.
     */
    fun signOut() {
        val account = manager ?: return
        if (signingOut.value) return
        viewModelScope.launch {
            signingOut.value = true
            message.value = when (account.signOut()) {
                SignOutcome.SignedOut -> AccountMessage.SIGNED_OUT
                SignOutcome.Failed -> AccountMessage.SIGNOUT_FAILED
            }
            signingOut.value = false
        }
    }

    /**
     * Account Settings' Sync now control.
     *
     * Community drain is fire-and-forget through [drainCommunity] — the engine
     * already retries. The waitable success / failure the student sees is the
     * attendance pass. A second press, or a press while sign-in / sign-out /
     * linking is in flight, is a no-op: the UI flag stops a second launch, and
     * the engine's mutex serializes if one slips through.
     *
     * [SyncPassResult.AccountGone] is not a generic failure. The existing
     * lifecycle ([AccountUiState.accountRemoved]) owns that sentence.
     */
    fun syncNow() {
        if (syncing.value || signingOut.value || linking.value || signingIn.value) return
        viewModelScope.launch {
            syncing.value = true
            syncResult.value = null
            try {
                drainCommunity()
                val engine = syncEngine
                if (engine == null) {
                    syncResult.value = AccountSyncResult.Unavailable
                    return@launch
                }
                val passResult = engine.syncNow()
                Log.d(TAG, "operation: AccountViewModel.syncNow(), result: $passResult")
                syncResult.value = when (passResult) {
                    SyncPassResult.Completed -> AccountSyncResult.Synced
                    SyncPassResult.Unreachable -> AccountSyncResult.Unreachable
                    SyncPassResult.Refused -> AccountSyncResult.Refused
                    SyncPassResult.NotLinked -> AccountSyncResult.Unavailable
                    SyncPassResult.AccountGone -> null
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "operation: AccountViewModel.syncNow() catch, exception class: ${e::class.qualifiedName}, exception message: ${e.message}",
                    e
                )
                syncResult.value = AccountSyncResult.Unreachable
            } finally {
                syncing.value = false
            }
        }
    }

    /**
     * Approves switching attendance ownership to the currently linked account.
     *
     * ### The press is re-derived from the claim, not from the prompt
     *
     * A [AccountTransitionPrompt.SwitchAccount] was built from the claim as it stood when the
     * prompt was *shown*, and the one thing that can change underneath it is the outgoing
     * account being deleted. That changes which operation this press is: an approval to
     * replace a live account's attendance, or the start of a new lifecycle on a phone whose
     * attendance belongs to an account nobody can ask any more.
     *
     * So the claim is read again here and the transition asked again, with the approval the
     * press carries — the same [AttendanceSyncPolicy.accountTransition] the prompt was built
     * from, not a second rule. Two answers matter:
     *
     *  * [AccountTransition.SWITCH_APPROVED] — the outgoing account is still live, so this is
     *    the ordinary approved switch, unchanged.
     *  * [AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION] — the outgoing account was
     *    deleted while the prompt was on screen. There is nobody left to have approved
     *    anything, so the approval is not consulted and the new-lifecycle path is taken
     *    instead: the terminated claim's body is set aside, and only then is the phone claimed.
     *
     * Nothing else can arrive. The incoming uid is the linked account the prompt was built
     * for, so a claim that now names it is a return rather than a switch, and a claim that has
     * vanished is a first claim — both of which the pass that follows this press handles on
     * its own terms, with nothing to clear.
     *
     * This routing is what the screen does; it is not what makes the switch safe. Both calls
     * end at [com.attendo.data.sync.AttendanceSyncStore.switchAttendanceOwner], which reads
     * the claim once more immediately before it clears anything and takes the copy itself if
     * the claim is terminated by then — so a press that lost this race still cannot empty a
     * removed account's phone without setting its body aside first.
     */
    fun confirmAccountSwitch() {
        val sync = syncEngine ?: return
        val prompt = transitionPrompt.value as? AccountTransitionPrompt.SwitchAccount ?: return
        viewModelScope.launch {
            when (AttendanceSyncPolicy.accountTransition(
                owner = sync.attendanceOwner(),
                linkedUserId = prompt.incomingUserId,
                isExplicitSwitchApproved = true,
            )) {
                AccountTransition.SWITCH_APPROVED ->
                    sync.switchAttendanceOwner(prompt.incomingUserId, now())

                AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION ->
                    sync.releaseTerminatedClaim(prompt.incomingUserId, now())

                else -> Unit
            }
            transitionPrompt.value = null
            sync.wake()
        }
    }

    /**
     * Cancels switching attendance ownership.
     * Retains the existing owner and keeps sync refused for the incoming account.
     */
    fun cancelAccountSwitch() {
        transitionPrompt.value = null
    }

    /**
     * Handles returning account reconciliation between local logged-out edits and cloud attendance.
     *
     * [ReturningReconciliation.RESTORE_REMOTE] is destructive — it discards the local edits the
     * prompt described — so it re-reads the claim first, the same way
     * [confirmAccountSwitch] does and for the same reason: the
     * [AccountTransitionPrompt.ReconcileReturning] was built from a claim read when it was
     * *shown*, and the account can have been deleted while the student was deciding.
     *
     * If it has, the restore is not performed, and the decision not to perform it is the
     * point rather than an omission:
     *
     *  * There is nothing to restore. The account is gone, so the cloud copy the student asked
     *    for does not exist, and the local edits are the only remaining copy of that work.
     *  * Clearing them anyway would not merely lose them. The copy that is supposed to outlive
     *    a removed account is written by the quarantine path, which sets the body aside and
     *    clears it in the same breath as claiming the phone for the next account. Clearing
     *    here first would leave *that* path to preserve an already-empty database over the
     *    only good copy — "restore my backup" would end as the deletion of the thing it was
     *    protecting.
     *
     * So the press writes nothing and the lifecycle keeps the body. The prompt still clears:
     * the student's answer has been taken, and what it means has changed underneath them.
     *
     * [com.attendo.data.sync.AttendanceSyncStore.restoreRemote] refuses the same case on its
     * own as well, so this is a legible decision rather than the only thing standing in the
     * way of one.
     */
    fun reconcileReturningAccount(decision: ReturningReconciliation) {
        val sync = syncEngine ?: return
        val prompt = transitionPrompt.value as? AccountTransitionPrompt.ReconcileReturning ?: return
        viewModelScope.launch {
            when (decision) {
                ReturningReconciliation.KEEP_LOCAL_AND_MERGE -> {
                    // Local edits take precedence / merge via LWW on push
                    transitionPrompt.value = null
                    sync.wake()
                }
                ReturningReconciliation.RESTORE_REMOTE -> {
                    // Discard local edits and restore from cloud backup — unless the account
                    // this backup belongs to has been deleted since the prompt was shown, in
                    // which case there is no cloud copy to restore from and the local rows are
                    // the last copy of that work. See this method's doc.
                    val owner = sync.attendanceOwner()
                    if (owner?.terminated != true) {
                        sync.restoreRemote(prompt.userId)
                    }
                    transitionPrompt.value = null
                    sync.wake()
                }
            }
        }
    }

    companion object {
        private const val TAG = "AttendanceSyncDiag"
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AccountViewModel(
                    manager = container.accountManager,
                    syncEngine = container.attendanceSync,
                    quarantine = container.attendanceQuarantine,
                    drainCommunity = { container.community.requestDrain() },
                )
            }
        }
    }
}

/**
 * The message that survives the account changing on its own. A successful handoff
 * answers itself — the headline turns "Signed in with GitHub" the same instant, with
 * whichever UID the flow resolved to — and a leftover "Opening GitHub…" underneath
 * would describe a browser that already came back. Every other state leaves the last
 * message be: a refusal's sentence is still true until something changes it. Kept
 * top-level and pure so the transition is pinned by tests without a device.
 */
internal fun messageAfterHandoff(account: AccountState, current: AccountMessage?): AccountMessage? =
    if (account is AccountState.Linked) null else current

/**
 * The sentence a verdict from the browser round-trip earns. Top-level and pure, beside
 * [messageAfterHandoff], so the mapping from each verdict to the words on the screen is
 * pinned by tests without a device — and so the whole chain a failure travels
 * ([com.attendo.data.account.handoffNoticeOfFailure] → here → [accountMessageLabel])
 * can be asserted as a single expression.
 *
 * That chain is the point of this function existing. Classifying a transport failure
 * correctly buys nothing on its own: what the student actually receives is the sentence
 * at the end of it, and a mapping that quietly sent [HandoffNotice.NETWORK] to the
 * generic answer would undo the classification without failing any test that only
 * looked at the classification.
 */
internal fun accountMessageOf(notice: HandoffNotice): AccountMessage = when (notice) {
    HandoffNotice.CANCELLED -> AccountMessage.CANCELLED
    HandoffNotice.REFUSED -> AccountMessage.FAILED
    HandoffNotice.IDENTITY_TAKEN -> AccountMessage.IDENTITY_TAKEN
    HandoffNotice.IMPORT_FAILED -> AccountMessage.IMPORT_FAILED
    HandoffNotice.NETWORK -> AccountMessage.NETWORK
}

/**
 * The sentence a handoff that never started earns. The other half of the same chain,
 * reached from the exception the press threw via
 * [com.attendo.data.account.handoffFailureOf].
 */
internal fun accountMessageOf(failure: HandoffFailure): AccountMessage = when (failure) {
    HandoffFailure.NETWORK -> AccountMessage.NETWORK
    HandoffFailure.UNKNOWN -> AccountMessage.FAILED
}

/** What the Account screen renders. */
data class AccountUiState(
    /** False on a build without an endpoint: the screen says so, the app works. */
    val available: Boolean = false,
    val account: AccountState = AccountState.NoSession,
    /**
     * True once the server established that this install's account no longer exists.
     *
     * The screen's own explanation of a sign-out the student did not perform, and the one
     * thing that makes "if you sign in again, this is a new account" true rather than a
     * guess. Set once per deleted account and cleared by a new sign-in.
     */
    val accountRemoved: Boolean = false,
    /**
     * True while a copy of a removed account's attendance is kept on this phone.
     *
     * The other half of [accountRemoved], and the one that outlives it. A deletion keeps the
     * rows; the *next* account to sign in sets them aside and clears them, because a claim
     * with rows under it would upload them. What the student was told at the deletion — that
     * nothing on the phone was deleted — stops being the whole story at that moment, so this
     * is what the screen says instead. False on an install where nothing was ever set aside,
     * which is the permanent state of almost every phone.
     */
    val attendanceSetAside: Boolean = false,
    /** True between the connect press and the browser opening — one network call, not a state. */
    val linking: Boolean = false,
    /** True between the sign-in press and the browser opening — the discard is part of that span. */
    val signingIn: Boolean = false,
    /** True while the sign-out request is in flight. */
    val signingOut: Boolean = false,
    val message: AccountMessage? = null,
    val transitionPrompt: AccountTransitionPrompt? = null,
    /** True while Account Settings' Sync now pass is in flight. */
    val syncing: Boolean = false,
    /**
     * The one-line answer to the last Sync now press. Independent of
     * [message], which answers sign-in / sign-out.
     */
    val syncResult: AccountSyncResult? = null,
)

/** The one-line answers a Sync now press can get. Not an [AccountMessage]. */
sealed interface AccountSyncResult {
    /** Attendance pass completed. */
    data object Synced : AccountSyncResult

    /** Network / 5xx. Not deletion. */
    data object Unreachable : AccountSyncResult

    /** Local attendance belongs to a different account. Nothing was uploaded. */
    data object Refused : AccountSyncResult

    /** No engine, or the session is not Linked. */
    data object Unavailable : AccountSyncResult
}

/** The one-line answers a press on this screen can get. */
enum class AccountMessage {
    /** The browser is opening on GitHub's consent page. */
    OPENING,

    /** The student said "no" on GitHub's consent screen. */
    CANCELLED,

    /**
     * The request could not travel — the phone has no usable connection, or the
     * sign-in service was unreachable. Reached only from a verdict the account layer
     * classified as a genuine transport failure ([HandoffNotice.NETWORK],
     * [HandoffFailure.NETWORK]); a server's refusal, a GitHub refusal, a cancellation
     * and a browser that simply came back are all answered elsewhere.
     */
    NETWORK,

    /** The browser could not be opened, or anything else unexpected. The safe answer. */
    FAILED,

    /**
     * The server refused the link: that GitHub account is already connected to
     * another user. Its own sentence because the student can recognise the state —
     * most often their own earlier install, whose link outlives any local reset — and
     * because the way back into that account is the other button on this screen.
     */
    IDENTITY_TAKEN,

    /** The callback came back with a session, but it could not be imported. */
    IMPORT_FAILED,

    /** The session ended. Nothing was deleted. */
    SIGNED_OUT,

    /** The sign-out could not complete; the session is still signed in. */
    SIGNOUT_FAILED,
}
