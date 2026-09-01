package com.attendo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attendo.core.update.UpdateManifest
import com.attendo.data.update.UpdateState
import com.attendo.ui.components.UpdateCard

/**
 * The Updates section: a "Check for updates" row with its one-line answer, and — when
 * there is something to say — the update card underneath it.
 *
 * The card itself is [UpdateCard], shared with the dashboard: the same state renders the
 * same card in both places, so an update surfaced at app open is the same card the
 * student finds here, and acting on it in either place moves it in both.
 */
@Composable
internal fun UpdatesSection(
    panel: UpdatePanel,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Check for updates",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (panel.checking) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Checking…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onCheck) { Text("Check") }
            }
        }

        when (panel.checkResult) {
            // Shown after a *successful* check only, and said the same way whether the
            // installed build matches the latest release or is ahead of it (a local build
            // of an unpublished release, for instance). A check that could not complete
            // is the FAILED line below — never this one.
            UpdatePanel.CheckResult.UP_TO_DATE -> CheckNote("No update available.")
            UpdatePanel.CheckResult.FAILED -> CheckNote("Couldn't check for updates just now. Try again later.")
            null -> Unit
        }

        if (state !is UpdateState.Idle) {
            Spacer(Modifier.height(12.dp))
            UpdateCard(
                state = state,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onInstall = onInstall,
                onDismiss = onDismiss,
            )
        }
    }
}

/** The one-line answer under the check row, whatever the check concluded. */
@Composable
private fun CheckNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
