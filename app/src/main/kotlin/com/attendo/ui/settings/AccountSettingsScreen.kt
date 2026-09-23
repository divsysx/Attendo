package com.attendo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.sync.AttendanceSyncPolicy.ReturningReconciliation
import com.attendo.data.account.AccountState
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.SectionLabel

// ---- the copy, pinned by test ---------------------------------------------------
//
// The account's few sentences are the entire explanation of what an account is, so a
// promise that has drifted from the feature is worse than no sentence at all — in
// either direction. A wrong promise here does not merely fail to describe the feature;
// it misdescribes the student's own data. The constants below are pinned by
// AccountSettingsWordsTest for the same reason ReportingIdentityScreen pins its
// warnings: so the promise and the feature cannot drift apart silently.
//
// These paragraphs said "Community only" for as long as that was true — attendance was
// not the account's business. Attendance sync shipped after them, so they now say what
// the account carries: the Community identity, and a private copy of the attendance.
// The prose that used to reassure a student that their attendance never left the phone
// would now be describing the one thing the feature does.
//
// The two buttons are pinned hardest of all, because they are the two flows. "Sign in
// with GitHub" — the label this screen used to show — could mean either of two
// fundamentally different operations (attach GitHub to this phone's identity, or
// authenticate an identity this phone does not hold), and a student cannot tell which
// one a single button performs. The labels now say which is which, and the test keeps
// the ambiguous one from coming back.

/** The first-time path: attach GitHub to the identity this phone already holds. */
internal val CONNECT_LABEL: String = "Create or connect an account"

/** The returning path: authenticate an account that already exists. */
internal val SIGN_IN_LABEL: String = "Sign in to an existing account"

/**
 * The sign-in confirmation. This is the warning before the browser opens, and it says
 * what a returning sign-in does in the order a student needs to hear it: the account
 * and its reports come back, this phone's attendance and courses join the account, and
 * the Community activity made while signed out is discarded only after the sign-in
 * actually succeeds. That last timing is the promise: cancelling at GitHub's page, or
 * a sign-in that fails, deletes nothing. What the copy must never imply: that anything
 * is merged.
 */
internal val SIGN_IN_CONFIRM: String =
    "This signs this phone in to the Attendo account for that GitHub login. Your " +
        "existing Community identity and its reports are restored to this phone. " +
        "Attendance is part of the account: the copy held for the account is brought " +
        "down here — your courses, their targets, your timetable and your class " +
        "records — and what this phone records from then on is uploaded, so a second " +
        "phone stays in step.\n\n" +
        "If this phone already holds attendance belonging to a different account, " +
        "Attendo asks before anything on it is replaced. Community activity from while " +
        "you were signed out, such as reports, polls and votes, will not be carried " +
        "into the account. It is discarded after you sign in successfully. If you " +
        "cancel, or signing in does not complete, nothing is discarded.\n\n" +
        "If that GitHub login has never been used with Attendo, a new account will " +
        "be created for it."

/**
 * The sign-out confirmation. Sign-out is its own thing: not account deletion, not a
 * community identity reset, not Clear All. The copy says what leaves (the login), what
 * stays (everything), and what happens next, including the part that is easy to get
 * wrong: the fresh anonymous identity is temporary, and its activity is discarded when
 * the student signs back in, while the account's own identity and reports come back in
 * its place. Saying that here is what makes the loop from sign-out to sign-in
 * predictable instead of a surprise at the second confirmation.
 */
internal val SIGN_OUT_CONFIRM: String =
    "This removes the GitHub login from this phone. Nothing is deleted: your " +
        "attendance and courses stay on this phone, your Community data stays where " +
        "it is, and the account keeps its own copy of all of it on the server.\n\n" +
        "When you next use Community, Attendo will start a fresh anonymous identity. " +
        "Anything you do in Community while signed out belongs to that temporary " +
        "identity, and when you sign back in, it is discarded. Your account's " +
        "Community identity and its reports are restored instead.\n\n" +
        "To get your account back on this phone, sign in again with the same GitHub " +
        "login from this screen."

/** The standing paragraph: what an account here is, and what it carries. */
internal val WHAT_AN_ACCOUNT_IS: String =
    "An Attendo account is a GitHub login attached to this phone's identity, and it " +
        "carries two things. Your Community identity goes with it, so your reports, " +
        "polls and votes are waiting on any phone you sign in on. So does your " +
        "attendance — your courses and their targets, your timetable, your class " +
        "records, and the term dates, holidays, working Saturdays and name that go " +
        "with them — kept as a private copy for the account and brought down to " +
        "whichever phone you sign in on next.\n\n" +
        "None of it is shared with anyone else, and none of it happens without the " +
        "account: on a phone that has never signed in, your attendance stays on that " +
        "phone and local backups stay in the files you exported them to."

/**
 * Destructive warning when signing in with Account B on a phone owned by Account A.
 * Patterned directly after ReportingIdentityScreen's IDENTITY_FILE_CONFIRM_REPLACE.
 */
internal val ACCOUNT_SWITCH_CONFIRM: String =
    "This phone already holds attendance belonging to another account. Attendo cannot " +
        "merge attendance between two different accounts. To sync with this account, " +
        "the attendance and courses currently on this phone must be replaced: local classes, " +
        "timetable patterns, courses and unpushed history will be cleared before downloading " +
        "this account's attendance. This cannot be undone.\n\n" +
        "If you cancel, nothing is changed or deleted, and this phone keeps its existing attendance."

/**
 * Reconciliation prompt for returning account when local edits occurred while signed out.
 */
internal val RECONCILE_RETURNING_PROMPT: String =
    "Attendance was recorded or edited on this phone while signed out. Would you like to " +
        "merge these local changes into your account, or discard them and restore your " +
        "attendance from the cloud?"

/** Account Settings' Sync now row — a separate control, not a rewrite of standing copy. */
internal val SYNC_NOW_LABEL: String = "Sync now"

internal val SYNC_NOW_ACTION: String = "Sync"

internal val SYNC_NOW_IN_FLIGHT: String = "Syncing…"

/**
 * The success line, and not an attendance one.
 *
 * A Sync now pass carries the semesters, the courses, their patterns, the class records and
 * the account's settings row — attendance is what most of that adds up to, but it is not all
 * of it, and a student who has just watched their timetable move is owed a sentence that
 * covers what actually moved. It is also the only success line there is: the pass is one
 * operation, so a second string for part of it would be a second thing to keep true.
 *
 * The refusal below stays attendance-specific on purpose — the ownership check it reports is
 * about this phone's records, and saying "data" there would name the wrong thing.
 */
internal val SYNC_NOW_SYNCED: String = "All data is up to date."

internal val SYNC_NOW_UNREACHABLE: String =
    "Couldn't sync just now. Try again later."

internal val SYNC_NOW_REFUSED: String =
    "This phone's attendance belongs to a different account, so nothing was uploaded."

internal val SYNC_NOW_UNAVAILABLE: String =
    "Couldn't sync just now. Try again later."

/**
 * The account's own screen, reached from Settings' one compact Account row.
 *
 * Everything about the account lives here and only here: the state of the install's
 * one session, the two GitHub paths that change it (connect for a first account, sign
 * in for a returning one), the sign-out that ends it on this phone, and the words
 * that keep all of them from being mistaken for each other — or for Clear All.
 * Settings shows a single row that opens this screen; the account is the install's
 * identity, and a second place rendering it would be a second place to drift out of
 * sync with it.
 */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignIn by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = "Account",
            subtitle = "Manage your Attendo account",
            onBack = onBack,
        )

        if (!state.available) {
            // Same rule as every community surface: a build without an endpoint says
            // so and offers nothing, rather than showing controls that cannot work.
            Text(
                text = "Not available on this install. Accounts need the community " +
                    "service, which this build is not configured for.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            AccountContent(
                state = state,
                onLink = viewModel::linkWithGitHub,
                onRequestSignIn = { confirmSignIn = true },
                onSignOut = { confirmSignOut = true },
                onSyncNow = viewModel::syncNow,
            )
        }
    }

    if (confirmSignIn) {
        // The warning before the browser opens, because GitHub's page is the point of
        // no return for attention, not for data: everything the confirmation promises
        // (the discard happens only after a successful sign-in) is still true if the
        // student cancels there. The confirmation exists so the decision to sign in is
        // made with the whole picture, before the round-trip begins.
        AlertDialog(
            onDismissRequest = { confirmSignIn = false },
            title = { Text("Sign in to an existing account?") },
            text = {
                Text(
                    SIGN_IN_CONFIRM,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignIn = false
                    viewModel.signInToExistingAccount()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignIn = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    SIGN_OUT_CONFIRM,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    state.transitionPrompt?.let { prompt ->
        when (prompt) {
            is AccountTransitionPrompt.SwitchAccount -> {
                AlertDialog(
                    onDismissRequest = viewModel::cancelAccountSwitch,
                    title = { Text("Switch attendance account?") },
                    text = {
                        Text(
                            ACCOUNT_SWITCH_CONFIRM,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::confirmAccountSwitch) {
                            Text("Replace and switch")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::cancelAccountSwitch) {
                            Text("Cancel")
                        }
                    },
                )
            }
            is AccountTransitionPrompt.ReconcileReturning -> {
                AlertDialog(
                    onDismissRequest = {
                        // Dismiss defaults to non-destructive merge
                        viewModel.reconcileReturningAccount(ReturningReconciliation.KEEP_LOCAL_AND_MERGE)
                    },
                    title = { Text("Local changes detected") },
                    text = {
                        Text(
                            RECONCILE_RETURNING_PROMPT,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.reconcileReturningAccount(ReturningReconciliation.KEEP_LOCAL_AND_MERGE)
                        }) {
                            Text("Merge changes")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.reconcileReturningAccount(ReturningReconciliation.RESTORE_REMOTE)
                        }) {
                            Text("Restore synced attendance")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountContent(
    state: AccountUiState,
    onLink: () -> Unit,
    onRequestSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionLabel("Status") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = accountValueLabel(state.account),
                    style = MaterialTheme.typography.titleMedium,
                )
                // bodyMedium, not the bodySmall the old Settings row used: this is the
                // explanation the account exists for, and it is the reason the account
                // got its own screen.
                Text(
                    text = accountExplanation(state.account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // The one explanation this screen exists to be able to give: a sign-out the student
        // did not perform. Shown above the actions because it is what the actions now mean —
        // "Connect GitHub" is no longer "link this phone", it is "start again".
        if (state.accountRemoved) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = accountRemovedExplanation(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // And the half of the same story that outlives it. The sentence above is shown when
        // the account was removed; this one is shown afterwards, on the phone the next account
        // signed in to, where the removed account's attendance was set aside rather than
        // uploaded. Without it the "nothing on this phone was deleted" above would quietly
        // stop being true, and the student would have no way to know their term is still here.
        if (state.attendanceSetAside) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(
                        text = attendanceSetAsideExplanation(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (state.account is AccountState.Linked) {
            item { SyncNowRow(state = state, onSyncNow = onSyncNow) }
        }

        item {
            when {
                state.account is AccountState.Linked -> {
                    if (state.signingOut) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                    }
                }
                // One spinner for either handoff in flight: both end in the browser,
                // and neither button may be pressed while the other's press is still
                // doing its work — two browser round-trips interleaved is two
                // callbacks racing one import window.
                state.linking || state.signingIn -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                else -> {
                    // The two flows, named for what they do. NoSession and Anonymous
                    // get both: an install that has never touched Community can still
                    // be someone's returning phone, and an install with an anonymous
                    // identity can still be someone's first account. Which one a
                    // student needs is theirs to know; the labels' job is only to make
                    // the difference impossible to miss.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLink) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(CONNECT_LABEL)
                        }
                        OutlinedButton(onClick = onRequestSignIn) { Text(SIGN_IN_LABEL) }
                    }
                }
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    text = accountMessageLabel(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (message) {
                        AccountMessage.OPENING,
                        AccountMessage.CANCELLED,
                        AccountMessage.SIGNED_OUT,
                        -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        item { Divider() }

        item { SectionLabel("What an account is") }
        item {
            Text(
                text = WHAT_AN_ACCOUNT_IS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The word the headline shows for each account state. */
internal fun accountValueLabel(account: AccountState): String = when (account) {
    // NoSession and Anonymous are the same thing to the student: no GitHub login
    // attached yet. Whether a random ID exists underneath is our bookkeeping, not
    // their status.
    is AccountState.NoSession, is AccountState.Anonymous -> "Not signed in"
    is AccountState.Linked -> "Signed in with GitHub"
}

/** The paragraph under the headline: what this state means, and what it carries. */
internal fun accountExplanation(account: AccountState): String = when (account) {
    is AccountState.NoSession, is AccountState.Anonymous ->
        "Connect GitHub to carry this phone's Community identity and its reports to " +
            "any phone you sign in on, and to keep a private copy of your attendance " +
            "for the account, or sign in to an account you already have, from another " +
            "phone or after signing out. Until you do, nothing leaves this phone: your " +
            "attendance, courses and settings are held here alone."
    is AccountState.Linked ->
        "Your GitHub account holds this phone's Community identity and its reports, " +
            "and the same identity is waiting on any phone you sign in on. It also " +
            "holds a private copy of your attendance — courses and their targets, " +
            "timetable, class records and settings — which this phone keeps in step " +
            "whenever it is online."
}

/** The one-line answer to a press on this screen. */
/**
 * What the student is told when the server established that their account no longer
 * exists.
 *
 * Four things, in this order, because each answers the question the one before it raises:
 * what happened, why they are suddenly signed out, whether they lost anything, and what
 * signing in again will actually do. The last of those is the one that has to be said out
 * loud — the account a student signs in to next is a *different* account, and the app will
 * treat it as one — because it is the only part they could otherwise discover too late.
 *
 * The wording is chosen against three things it must not say:
 *
 *  * **Not "session expired", not "network error".** Both are different states with
 *    different sentences ([AccountMessage.NETWORK] and the sign-out answers), and using
 *    either here would send a student to debug a connection that is working.
 *  * **Not "something went wrong".** Nothing went wrong with the app: an account that
 *    existed was removed, which is a fact about the account and not a failure.
 *  * **Not "your data was deleted".** Nothing local was. The claim is retained on purpose
 *    (see [com.attendo.core.auth.AuthLifecyclePolicy.ownerAfterAccountDeletion]), and
 *    telling a student their attendance was thrown away when it is still on the phone
 *    would be a worse lie than saying nothing. What is true is stated instead: the account
 *    is gone, and the *next* sign-in starts fresh.
 *
 * Kept top-level and pure, beside [accountMessageLabel], so the sentence is pinned by
 * tests without a device.
 */
internal fun accountRemovedExplanation(): String =
    "Your Attendo account was removed.\n\n" +
        "This account no longer exists on Attendo's servers, so you've been signed out. " +
        "Nothing on this phone was deleted. If you sign in again, Attendo will treat it as " +
        "a new account — data from the removed account won't be restored or merged into it."

/**
 * What the student is told once the removed account's attendance has been set aside on the
 * phone the next account signed in to.
 *
 * The second half of [accountRemovedExplanation], and the one that has to exist for that
 * sentence to stay true. At the deletion nothing local was touched, and the app says so; the
 * moment a different account signs in, the rows *are* cleared — because a claim with rows
 * under it uploads them into that account, which is the leak this whole lifecycle is built to
 * stop. What makes that acceptable, and what this says, is that the rows were not discarded:
 * a copy was written first and is still on the phone.
 *
 * Three things it must not say:
 *
 *  * **Not that the attendance is in this account.** It is not, and it will never be
 *    uploaded — the copy is local and nothing reads it.
 *  * **Not that it can be brought back from within the app.** Nothing offers that, so it is
 *    not promised. What is stated is what is true: a copy is here.
 *  * **Not "deleted".** The file is what stands between an administrator's action and a lost
 *    term. Saying it was deleted, when a copy is two directories away, is the one sentence
 *    that would make a student stop looking.
 *
 * Kept top-level and pure, beside [accountRemovedExplanation], so it is pinned by tests
 * without a device.
 */
internal fun attendanceSetAsideExplanation(): String =
    "Attendance from the removed account is kept on this phone.\n\n" +
        "The attendance that had been backed up to the removed account was set aside here " +
        "when you signed in, so that it could not be uploaded as this account's. A copy of it " +
        "is still on this phone, and it is not part of this account. Clearing Attendo's data " +
        "removes it."

internal fun accountSyncResultLabel(result: AccountSyncResult): String = when (result) {
    AccountSyncResult.Synced -> SYNC_NOW_SYNCED
    AccountSyncResult.Unreachable -> SYNC_NOW_UNREACHABLE
    AccountSyncResult.Refused -> SYNC_NOW_REFUSED
    AccountSyncResult.Unavailable -> SYNC_NOW_UNAVAILABLE
}

@Composable
private fun SyncNowRow(
    state: AccountUiState,
    onSyncNow: () -> Unit,
) {
    val busy = state.syncing || state.signingOut || state.linking || state.signingIn
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = SYNC_NOW_LABEL,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.syncing) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = SYNC_NOW_IN_FLIGHT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(
                    onClick = onSyncNow,
                    enabled = !busy,
                ) { Text(SYNC_NOW_ACTION) }
            }
        }
        state.syncResult?.let { result ->
            Text(
                text = accountSyncResultLabel(result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun accountMessageLabel(message: AccountMessage): String = when (message) {
    AccountMessage.OPENING -> "Opening GitHub in your browser…"
    AccountMessage.CANCELLED -> "Sign-in was cancelled."
    AccountMessage.NETWORK ->
        "Couldn't reach the sign-in service. Check your internet connection and try again."
    AccountMessage.FAILED -> "Couldn't sign you in. Please try again."
    AccountMessage.IDENTITY_TAKEN ->
        "That GitHub account is already connected to another Attendo account — often " +
            "an earlier install of your own. If it's yours, sign in to it instead, " +
            "with 'Sign in to an existing account'."
    AccountMessage.IMPORT_FAILED -> "Couldn't finish signing in. Please try again."
    AccountMessage.SIGNED_OUT -> "Signed out. Nothing on this phone was deleted."
    AccountMessage.SIGNOUT_FAILED ->
        "Couldn't sign out. Check your connection and try again."
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier)
}
