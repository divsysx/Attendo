package com.attendo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attendo.core.update.ReleaseNotes
import com.attendo.core.update.UpdateManifest
import com.attendo.data.update.UpdateState
import java.util.Locale

/**
 * The update card, wherever an update is surfaced.
 *
 * Two screens show it — the dashboard, the moment the app opens, and the Updates section
 * in Settings — and both render this one composable from the one [UpdateState] the update
 * manager holds, so there is one card and one truth, never two copies that could
 * disagree. Everything it shows comes from the release's own metadata: its version, its
 * notes, its size. The actions are the student's, in order: look, download (or not),
 * install (or not). Installation is never a thing this app does by itself — every path
 * ends in Android's own installer asking the question.
 *
 * Idle renders nothing, so a screen can call this unconditionally and let the state
 * decide whether a card exists at all.
 */
@Composable
internal fun UpdateCard(
    state: UpdateState,
    onDownload: (UpdateManifest) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        UpdateState.Idle -> Unit

        is UpdateState.Available -> UpdateCardFrame(state.manifest, modifier) {
            AvailableBody(state.manifest, { onDownload(state.manifest) }, onDismiss)
        }

        is UpdateState.Downloading -> UpdateCardFrame(state.manifest, modifier) {
            DownloadingBody(state, onCancelDownload)
        }

        is UpdateState.DownloadFailed -> UpdateCardFrame(state.manifest, modifier) {
            DownloadFailedBody({ onDownload(state.manifest) }, onDismiss)
        }

        is UpdateState.Verifying -> UpdateCardFrame(state.manifest, modifier) {
            StatusBody("Checking the update…")
        }

        is UpdateState.VerificationFailed -> UpdateCardFrame(state.manifest, modifier) {
            VerificationFailedBody({ onDownload(state.manifest) }, onDismiss)
        }

        is UpdateState.ReadyToInstall -> UpdateCardFrame(state.manifest, modifier) {
            ReadyToInstallBody(onInstall)
        }

        is UpdateState.Installing -> UpdateCardFrame(state.manifest, modifier) {
            StatusBody("Android will take it from here — it will ask you to confirm the installation.")
        }
    }
}

/** The card's shared frame: a release is named at the top, its state below. */
@Composable
private fun UpdateCardFrame(manifest: UpdateManifest, modifier: Modifier, body: @Composable () -> Unit) {
    Column(modifier) {
        Text(
            text = "Attendo ${manifest.versionName}",
            style = MaterialTheme.typography.titleMedium,
        )
        body()
    }
}

@Composable
private fun AvailableBody(manifest: UpdateManifest, onDownload: () -> Unit, onDismiss: () -> Unit) {
    WhatsNew(manifest)
    ActionRow {
        Button(onClick = onDownload) { Text("Download") }
        TextButton(onClick = onDismiss) { Text("Not now") }
    }
}

@Composable
private fun DownloadingBody(state: UpdateState.Downloading, onCancel: () -> Unit) {
    val label = when (val total = state.totalBytes) {
        null -> "Downloading update…"
        else -> "Downloading update… ${sizeLabel(state.bytesSoFar)} of ${sizeLabel(total)}"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    val fraction = state.totalBytes
        ?.takeIf { it > 0 }
        ?.let { (state.bytesSoFar.toDouble() / it).coerceIn(0.0, 1.0) }
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    ActionRow { TextButton(onClick = onCancel) { Text("Cancel") } }
}

@Composable
private fun DownloadFailedBody(onRetry: () -> Unit, onDismiss: () -> Unit) {
    Text(
        text = "The download didn't finish. Check your connection and try again.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionRow {
        Button(onClick = onRetry) { Text("Try again") }
        TextButton(onClick = onDismiss) { Text("Not now") }
    }
}

@Composable
private fun VerificationFailedBody(onRetry: () -> Unit, onDismiss: () -> Unit) {
    Text(
        text = "We couldn't verify this update, so it wasn't installed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionRow {
        Button(onClick = onRetry) { Text("Try again") }
        TextButton(onClick = onDismiss) { Text("Not now") }
    }
}

@Composable
private fun ReadyToInstallBody(onInstall: () -> Unit) {
    Text(
        text = "The update is downloaded and ready. Android will ask you to confirm before anything is installed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionRow { Button(onClick = onInstall) { Text("Install") } }
}

@Composable
private fun StatusBody(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "What's new", as the release's own notes, in plain text. */
@Composable
private fun WhatsNew(manifest: UpdateManifest) {
    val notes = ReleaseNotes.plain(manifest.notes)
    if (notes.isBlank()) return
    Spacer(Modifier.height(4.dp))
    Text(
        text = "What's new",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = notes,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp),
    )
    manifest.apkSizeBytes?.let { size ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Update size: about ${sizeLabel(size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionRow(actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions()
    }
}

/**
 * "12.4 MB" / "820 KB" — a size a person can weigh, from the release's own asset size.
 * Round to one decimal; anything finer is false precision about a file still on a server.
 */
private fun sizeLabel(bytes: Long): String {
    val kib = bytes / 1024.0
    if (kib < 1024) return String.format(Locale.ENGLISH, "%.0f KB", kib)
    return String.format(Locale.ENGLISH, "%.1f MB", kib / 1024.0)
}
