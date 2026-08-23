package com.attendo.ui.rollover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendo.core.backup.BackupCodec

/**
 * The blocking overlay that asks what to do when the semester on the device no longer matches the
 * one this build of the app carries.
 *
 * Rendered as a full-screen [Surface] in a [Box] over the nav graph (see `AttendoApp`), not as a
 * conditional start destination: the [androidx.navigation.compose.NavHost] stays stable, and the
 * gate simply covers it while it is shown. The gate only appears for the states that need the
 * student's attention or block while work is in flight — [RolloverUiState.Idle] renders nothing.
 *
 * The three actions map one-to-one to the feature: export a Semester Record PDF (a human-readable
 * summary of the previous term), export a full JSON backup (the existing, unchanged path), and
 * start the new semester (the destructive clear). "Remind me later" is the one non-blocking exit:
 * it hides the gate for this session only, and detection re-prompting on the next launch is the
 * safety against deferring it indefinitely.
 */
@Composable
fun RolloverGate(
    viewModel: RolloverViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state is RolloverUiState.Idle) return

    // Launchers are created unconditionally so they survive recompositions without losing the
    // registration — the same pattern the Backup screen follows.
    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SemesterRecordRendererMime),
    ) { target -> if (target != null) viewModel.exportPdf(target) }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE),
    ) { target -> if (target != null) viewModel.exportBackup(target) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when (val current = state) {
                is RolloverUiState.Required -> RolloverChoice(
                    state = current,
                    onExportPdf = { savePdf.launch(viewModel.suggestedPdfFileName()) },
                    onExportBackup = { saveBackup.launch(viewModel.suggestedBackupFileName()) },
                    onRemindLater = viewModel::remindLater,
                    onConfirmedStart = viewModel::rollOver,
                )

                RolloverUiState.Rolling -> WorkingDialog("Starting the new semester. Do not close Attendo…")
                RolloverUiState.Exporting -> WorkingDialog("Writing the file…")
                is RolloverUiState.Notice -> NoticeDialog(
                    notice = current,
                    onDismiss = viewModel::dismissNotice,
                )

                RolloverUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RolloverChoice(
    state: RolloverUiState.Required,
    onExportPdf: () -> Unit,
    onExportBackup: () -> Unit,
    onRemindLater: () -> Unit,
    onConfirmedStart: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        StartConfirmation(
            exportedThisSession = state.exportedThisSession,
            onConfirm = {
                confirming = false
                onConfirmedStart()
            },
            onDismiss = { confirming = false },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "A new semester is available",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This device still holds ${state.current.label}, but this version of Attendo " +
                "carries ${state.bundled.label}. Start the new semester to switch over — the " +
                "previous term's data is removed from this device, so save a copy first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))

        Button(onClick = onExportPdf, modifier = Modifier.fillMaxWidth()) {
            Text("Export Semester Record")
        }
        OutlinedButton(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) {
            Text("Export full backup")
        }
        Button(
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start new semester")
        }

        Spacer(Modifier.height(4.dp))

        TextButton(onClick = onRemindLater) {
            Text("Remind me later")
        }
    }
}

@Composable
private fun StartConfirmation(
    exportedThisSession: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start new semester?") },
        text = {
            Text(
                text = if (exportedThisSession) {
                    "Your previous semester's courses, timetable and attendance will be removed " +
                        "from this device. The new semester, with its term dates and targets, " +
                        "takes their place."
                } else {
                    "Your previous semester's attendance will be removed from this device and " +
                        "cannot be recovered from Attendo after this. Export a Semester Record or " +
                        "a full backup first if you have not already."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Start new semester", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Go back") }
        },
    )
}

@Composable
private fun NoticeDialog(
    notice: RolloverUiState.Notice,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (notice.ok) "Done" else "That did not work") },
        text = {
            Text(text = notice.message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
private fun WorkingDialog(label: String) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}

/** Re-exported so the gate's launcher picks up the MIME type without importing :core's renderer. */
private const val SemesterRecordRendererMime: String =
    com.attendo.core.rollover.SemesterRecordRenderer.MIME_TYPE
