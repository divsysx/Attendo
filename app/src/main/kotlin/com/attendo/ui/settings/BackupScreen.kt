package com.attendo.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.backup.AttendanceCsv
import com.attendo.core.backup.BackupCodec
import com.attendo.core.backup.BackupSummary
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.courses
import com.attendo.ui.display
import com.attendo.ui.fullLabel
import com.attendo.ui.localLabel

/**
 * What the picker will let the student choose.
 *
 * JSON first, because that is what a backup is. The rest are here because plenty of file
 * managers and cloud drives hand a `.json` file back as `application/octet-stream` or
 * `text/plain`, and a filter that greys out the student's own backup — with no way to explain
 * why from inside the picker — is worse than one that also admits a stray text file. Narrowing
 * this is a convenience, never a check: the contents are validated on the way in regardless of
 * what the file claims to be, and the extension is not trusted on its own.
 */
private val IMPORT_MIME_TYPES = arrayOf(
    BackupCodec.MIME_TYPE,
    "text/json",
    "text/plain",
    "application/octet-stream",
)

/**
 * Export, import, and the honest paragraph about what Android's own backup does not promise.
 *
 * The screen is deliberately unhurried about importing. A restore replaces the whole term, so
 * the file is read and validated first, its contents are shown next to what is already on the
 * phone, and only then is there a button that does anything. The three buttons that touch the
 * file system all go through Android's picker — the app holds no storage permission and never
 * sees a path.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE),
    ) { target -> if (target != null) viewModel.exportBackup(target) }

    val saveCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AttendanceCsv.MIME_TYPE),
    ) { target -> if (target != null) viewModel.exportCsv(target) }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source -> if (source != null) viewModel.preview(source) }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(title = "Data & backup", onBack = onBack)

        if (!state.loaded) {
            LoadingPane()
        } else {
            BackupContent(
                state = state,
                onExportBackup = { saveBackup.launch(viewModel.suggestedBackupFileName()) },
                onImportBackup = { openBackup.launch(IMPORT_MIME_TYPES) },
                onExportCsv = { saveCsv.launch(viewModel.suggestedCsvFileName()) },
                onUndo = viewModel::undoLastRestore,
            )
        }
    }

    val task = state.task
    val pending = state.pending
    val notice = state.notice
    when {
        // Order matters. The confirmation stays in the state while the restore runs, so the
        // working dialog has to win — and it takes no dismissal, because a half-interrupted
        // restore is the one outcome worth designing against.
        task != null -> WorkingDialog(task)

        pending != null -> RestorePreviewDialog(
            pending = pending,
            current = state.current,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::cancelPending,
        )

        notice != null -> NoticeDialog(notice = notice, onDismiss = viewModel::dismissNotice)
    }
}

@Composable
private fun BackupContent(
    state: BackupUiState,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportCsv: () -> Unit,
    onUndo: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionLabel("On this phone") }
        item {
            val current = state.current
            if (current == null) {
                Note(
                    text = "Attendo could not read its own data just now. Try leaving the screen " +
                        "and coming back before exporting anything.",
                    error = true,
                )
            } else {
                Column {
                    Fact("Courses", courseCount(current))
                    Fact("Classes", classCount(current))
                    Fact("Attendance", current.overallPercent.display())
                    Fact("History", historyRange(current))
                }
            }
        }

        item { Divider() }

        item { SectionLabel("Backup file") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) {
                    Text("Export backup")
                }
                OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) {
                    Text("Import backup")
                }
                Note(
                    "One file with everything Attendo knows: every class and how much of it you " +
                        "attended, every cancellation and what caused it, the classes that moved " +
                        "and where they landed, timetable slots you have since retired, the term " +
                        "dates, your holidays and your targets. Not just what today's screen shows.",
                )
                Note(
                    "Keep it somewhere that is not this phone — a drive, or sent to yourself. A " +
                        "backup that only exists on the phone you lose is not a backup.",
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Spreadsheet") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                    Text("Export attendance CSV")
                }
                Note(
                    "One row per class — date, course, kind, hours planned, hours attended, " +
                        "status — for a spreadsheet, or for anyone who wants to check the record. " +
                        "It cannot be imported back: flattening a term into rows loses which slot " +
                        "generated a class and which cancellation a makeup replaced, and a restore " +
                        "that guesses at those is worse than no restore.",
                )
            }
        }

        if (state.canUndo) {
            item { Divider() }
            item { SectionLabel("Undo") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore previous data")
                    }
                    Note(
                        "A copy of what the last import replaced is still on this phone. Putting " +
                            "it back uses the copy up, so this is one step backwards rather than a " +
                            "switch between two sets of data.",
                    )
                }
            }
        }

        item { Divider() }

        item { SectionLabel("Moving to a new phone") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Step(1, "On the old phone: Export backup, and save the file somewhere the new one can reach.")
                Step(2, "Install Attendo on the new phone and open it once.")
                Step(3, "Import backup, check the preview against the old phone, then replace.")
                Step(4, "Compare the attendance figure on both phones before you wipe the old one.")
            }
        }

        item { Divider() }

        item { SectionLabel("Android's own backup") }
        item {
            Note(
                "Attendo allows Android's automatic backup as well, which can carry your data " +
                    "across during the setup of a new phone. Treat it as luck rather than a plan: " +
                    "Android runs it roughly once a day and only while the phone is idle, " +
                    "charging and on Wi-Fi, keeps one copy, discards it if you uninstall the app " +
                    "or leave the phone unused for a couple of months, does nothing for a phone " +
                    "signed in to a different account, and gives no way to ask for one or to " +
                    "check that it worked. The export above is the copy you control.",
            )
        }
    }
}

// ---- import preview ---------------------------------------------------------

/**
 * What is in the file, next to what it would replace.
 *
 * Everything here comes from a backup that has already been read, checksummed and validated,
 * so the numbers are the ones that would actually land — not a guess from the file's name.
 */
@Composable
private fun RestorePreviewDialog(
    pending: PendingImport,
    current: BackupSummary?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val incoming = pending.summary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace everything with this backup?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pending.fileName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Fact("Exported", incoming.exportedAt.localLabel())
                Fact("Written by", incoming.writtenBy)
                Fact("Courses", courseCount(incoming))
                Fact("Classes", classCount(incoming))
                Fact("Attendance", incoming.overallPercent.display())
                Fact("History", historyRange(incoming))
                Fact("Term", "${incoming.termStart.fullLabel()} – ${incoming.termEnd.fullLabel()}")
                Fact("Holidays", incoming.holidays.toString())
                Fact("Working Saturdays", incoming.workingSaturdays.toString())
                if (incoming.cancelledSessions > 0) {
                    Fact("Cancelled", incoming.cancelledSessions.toString())
                }
                if (incoming.rescheduledSessions > 0) {
                    Fact("Moved", incoming.rescheduledSessions.toString())
                }
                if (incoming.adhocSessions > 0) {
                    Fact("Added by hand", incoming.adhocSessions.toString())
                }
                incoming.section?.let { section ->
                    Fact("Section", listOfNotNull(section, incoming.batch).joinToString(" · "))
                }

                Spacer(Modifier.height(8.dp))

                if (incoming.isEmpty) {
                    Note(
                        "This backup has no courses and no classes in it. Restoring it would " +
                            "leave Attendo empty.",
                        error = true,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (incoming.formatVersion < BackupCodec.FORMAT_VERSION) {
                    Note(
                        "Written in backup format ${incoming.formatVersion} by an older Attendo, " +
                            "and read forward to format ${BackupCodec.FORMAT_VERSION}.",
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Note(
                    text = if (current == null || current.isEmpty) {
                        "There is nothing on this phone to lose."
                    } else {
                        "Everything on this phone now — ${courseCount(current)}, " +
                            "${current.sessions} classes — is deleted and replaced. Attendo saves " +
                            "a copy of it first, so you can undo this from this screen straight " +
                            "afterwards."
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Replace", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---- outcome ----------------------------------------------------------------

@Composable
private fun NoticeDialog(
    notice: BackupNotice,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (notice.ok) "Done" else "That did not work") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = notice.message, style = MaterialTheme.typography.bodyMedium)

                // The second line: what to do about it. Its own paragraph rather than a longer
                // first sentence, because the two say different kinds of thing.
                notice.hint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
private fun WorkingDialog(task: BackupTask) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text = task.label, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}

// ---- pieces -----------------------------------------------------------------

/** A label with its value on the right — the shape of every line of both summaries. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f),
        )
    }
}

/** Explanatory text. [error] is for the sentences that are a warning rather than a note. */
@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

// ---- labels -----------------------------------------------------------------

private val BackupTask.label: String
    get() = when (this) {
        BackupTask.EXPORTING -> "Writing your backup…"
        BackupTask.WRITING_CSV -> "Writing the spreadsheet…"
        BackupTask.READING -> "Checking the file…"
        BackupTask.RESTORING -> "Restoring. Do not close Attendo…"
        BackupTask.UNDOING -> "Putting your previous data back…"
    }

/** "6 courses", with the archived ones called out — they still carry their history. */
private fun courseCount(summary: BackupSummary): String =
    courses(summary.courses) +
        if (summary.archivedCourses > 0) " (${summary.archivedCourses} archived)" else ""

/** "148, 96 marked" */
private fun classCount(summary: BackupSummary): String =
    if (summary.sessions == 0) "None" else "${summary.sessions}, ${summary.reviewedSessions} marked"

/** "3 Aug 2026 – 18 Aug 2026" — the span the file actually covers, not the term's. */
private fun historyRange(summary: BackupSummary): String {
    val first = summary.firstSession
    val last = summary.lastSession
    return when {
        first == null || last == null -> "Nothing yet"
        first == last -> first.fullLabel()
        else -> "${first.fullLabel()} – ${last.fullLabel()}"
    }
}
