package com.attendo.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The title of the sentence the app shows when the server establishes that this install's
 * account has been removed.
 *
 * The same words the Account screen's explanation opens with, because it is the same
 * statement in two places — one the moment it becomes true, one for as long as it stays true.
 */
internal const val REMOVED_ACCOUNT_DIALOG_TITLE: String = "Your Attendo account was removed"

/** The single button on that sentence. It says "understood", and nothing else. */
internal const val REMOVED_ACCOUNT_DIALOG_DISMISS: String = "OK"

/**
 * Says "your Attendo account was removed" once, where the student will actually see it.
 *
 * Rendered from [com.attendo.ui.AttendoApp]'s root, over the nav graph, exactly as
 * `RolloverGate` is — the app's one existing pattern for a sentence that does not belong to
 * any screen. It renders nothing at all while there is no deletion, so the graph underneath
 * stays fully interactive and this costs one collected boolean.
 *
 * ### Why a dialog and not a banner
 *
 * The deletion is carried out by a background check at app start or on the app returning to
 * the foreground, which is not a moment the student is looking at any particular screen. A
 * banner would have to pick a screen to live on and would be missed by anyone who never opens
 * it; the deletion is a fact about the whole install (the session is gone, nothing will sync
 * until they sign in again), so it is said over the whole install. It is non-destructive and
 * dismissible — the app stays usable, and everything it says stays available in Account
 * Settings, which is where the buttons that do something about it live.
 *
 * The body is [accountRemovedExplanation] — the same text the Account screen's card shows,
 * from one function, so the two can never drift into saying different things about the same
 * deletion.
 */
@Composable
fun RemovedAccountNotice(
    viewModel: RemovedAccountNoticeViewModel,
    modifier: Modifier = Modifier,
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        modifier = modifier,
        title = { Text(REMOVED_ACCOUNT_DIALOG_TITLE) },
        text = {
            Text(
                text = accountRemovedExplanation(),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismiss) {
                Text(REMOVED_ACCOUNT_DIALOG_DISMISS)
            }
        },
    )
}
