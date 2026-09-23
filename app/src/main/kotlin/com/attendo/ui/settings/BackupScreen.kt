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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.backup.AttendanceCsv
import com.attendo.core.backup.BackupCodec
import com.attendo.core.backup.BackupSummary
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.classes
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

// ---- Android's own backup: what the toggle says ---------------------------------
//
// Every sentence here is checked by AutomaticBackupWordingTest, because each one is a
// promise about a system Attendo does not control. "May", never "will"; "Android
// decides", never a claim that Attendo uploads anything; and the one hard limit said
// plainly — turning the toggle off cannot delete a backup Android has already stored.

internal val AUTOMATIC_BACKUP_TITLE: String = "Automatic backup"

internal val AUTOMATIC_BACKUP_OFF_NOTE: String =
    "Off. Uninstalling Attendo removes its data, and reinstalling starts fresh. Keep an " +
        "export if you want the semester back."

internal val AUTOMATIC_BACKUP_ON_NOTE: String =
    "On. Android may copy your courses and attendance to your Google account, and may put " +
        "them back if you reinstall Attendo or set up a new phone. Whether it does is up to " +
        "Android and the phone's backup settings. It runs about once a day, while the phone " +
        "is idle, charging and on Wi-Fi, and there is no way to check from here that a copy " +
        "exists. Turning this off later stops new copies but cannot delete one Android has " +
        "already stored."

internal val AUTOMATIC_BACKUP_WHOSE_NOTE: String =
    "This is Android's own backup, not something Attendo uploads. The export above is the " +
        "copy you control."

internal val AUTOMATIC_BACKUP_CONFIRM_TITLE: String = "Turn on Android's automatic backup?"

internal val AUTOMATIC_BACKUP_CONFIRM_BODY: String =
    "Allow Android to back up your Attendo data, so it may be restored when you reinstall " +
        "the app or move to a new device. Android decides when to back up and whether to " +
        "restore. Attendo cannot check either, and turning this off later cannot delete a " +
        "copy Android has already made. For a copy you control, use Export backup."

// ---- Clear all Attendo data -----------------------------------------------------
//
// The words of the one destructive action in the app, pinned by the same test as the
// backup wording: the confirmation must say what is lost and that it is permanent, the
// hint must never promise that clearing deletes Android's copy of anything, and neither
// may suggest Attendo decides what Android does with its backups.

internal val CLEAR_ALL_BUTTON: String = "Clear all Attendo data"

internal val CLEAR_ALL_CONFIRM_TITLE: String = "Clear all Attendo data?"

internal val CLEAR_ALL_CONFIRM_BODY: String =
    "This permanently removes all Attendo data stored on this device, including your " +
        "attendance and settings. This cannot be undone.\n\nYour Automatic backup setting " +
        "is not changed, and this does not delete any backup Android may already hold."

internal val CLEAR_ALL_NOTE: String =
    "Removes every course, class and setting Attendo keeps on this phone, and the app " +
        "comes back as a fresh install. Export a backup first if there is anything you " +
        "want to keep."

internal val ANDROID_RESTORE_HINT: String =
    "Reinstalled Attendo and found old data waiting? Android may restore one of " +
        "its own backups during installation. If that happens, Clear all Attendo data " +
        "below starts fresh. So does Android's App info → Storage → Clear storage. Neither " +
        "deletes the backup Android or Google may already be keeping, and Attendo does not " +
        "control whether Android backs up or restores anything. A normal app update is " +
        "different: it keeps your data exactly as it is."

/**
 * Export, import, and the "Automatic backup" toggle for Android's own backup.
 *
 * The screen is deliberately unhurried about importing. A restore replaces the whole term, so
 * the file is read and validated first, its contents are shown next to what is already on the
 * phone, and only then is there a button that does anything. The three buttons that touch the
 * file system all go through Android's picker — the app holds no storage permission and never
 * sees a path.
 *
 * The toggle gets the same patience in the other direction: turning it on is the one act here
 * that changes what an uninstall means, so it is asked about before it is done. Its words are
 * constants, pinned by a test, because "may" and "will" are the difference between describing
 * Android's backup and promising it.
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

    // Set when the student flips the toggle on, cleared by either button of the dialog
    // that asks about it. Turning it off needs no such step.
    var confirmAutomaticBackup by remember { mutableStateOf(false) }

    // The clear-all confirmation is a second, deliberate step on top: the button is not
    // the action, the button asks. Cleared by Cancel, or by Clear all data — which is the
    // only path that ever calls into the ViewModel for this.
    var confirmClearAll by remember { mutableStateOf(false) }

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
                onAndroidBackupChecked = { checked ->
                    if (checked) confirmAutomaticBackup = true
                    else viewModel.setAndroidBackupEnabled(false)
                },
                onClearAllData = { confirmClearAll = true },
            )
        }
    }

    if (confirmAutomaticBackup) {
        AlertDialog(
            onDismissRequest = { confirmAutomaticBackup = false },
            title = { Text(AUTOMATIC_BACKUP_CONFIRM_TITLE) },
            text = { Text(AUTOMATIC_BACKUP_CONFIRM_BODY, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAutomaticBackup = false
                    viewModel.setAndroidBackupEnabled(true)
                }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAutomaticBackup = false }) { Text("Not now") }
            },
        )
    }

    // Ordered after the working dialog by the `when` below: once Clear is pressed the
    // confirmation is replaced by the undialogurable "Clearing everything…" — there is no
    // moment where the screen looks finished before the process actually restarts.
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(CLEAR_ALL_CONFIRM_TITLE) },
            text = { Text(CLEAR_ALL_CONFIRM_BODY, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    viewModel.clearAllData()
                }) {
                    Text("Clear all data", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            },
        )
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
    onAndroidBackupChecked: (Boolean) -> Unit,
    onClearAllData: () -> Unit,
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
                        "and where they landed, timetable slots you have since stopped, the " +
                        "semester dates, your holidays and your targets. Not just what today's " +
                        "screen shows.",
                )
                Note(
                    "Keep it somewhere that is not this phone, like a drive, or send it to " +
                        "yourself. A backup that only exists on the phone you lose is not a " +
                        "backup.",
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
                    "Each class is one row: date, course, kind, hours planned, hours " +
                        "attended, status, and more. Use it in a spreadsheet, or hand it to " +
                        "anyone who wants to check the record. It cannot be imported back: " +
                        "flattening a semester into " +
                        "rows loses which slot generated a class and which cancelled class a " +
                        "moved one replaced, and a restore that guesses at those is worse " +
                        "than no restore.",
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
                        "A copy of what the last import replaced is still on this phone. " +
                            "Putting it back consumes that copy, so this is one step backwards " +
                            "rather than a switch between two sets of data.",
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = AUTOMATIC_BACKUP_TITLE,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.androidBackupEnabled,
                        onCheckedChange = onAndroidBackupChecked,
                    )
                }
                Note(if (state.androidBackupEnabled) AUTOMATIC_BACKUP_ON_NOTE else AUTOMATIC_BACKUP_OFF_NOTE)
                Note(AUTOMATIC_BACKUP_WHOSE_NOTE)
                Note(ANDROID_RESTORE_HINT)
            }
        }

        item { Divider() }

        item { SectionLabel("Clear all data") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearAllData,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(CLEAR_ALL_BUTTON)
                }
                Note(CLEAR_ALL_NOTE)
            }
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
                Fact("Semester dates", "${incoming.termStart.fullLabel()} – ${incoming.termEnd.fullLabel()}")
                Fact("Holidays", incoming.holidays.toString())
                Fact("Working Saturdays", incoming.workingSaturdays.toString())
                if (incoming.cancelledSessions > 0) {
                    Fact("Cancelled", incoming.cancelledSessions.toString())
                }
                if (incoming.rescheduledSessions > 0) {
                    Fact("Moved", incoming.rescheduledSessions.toString())
                }
                if (incoming.adhocSessions > 0) {
                    Fact("Extra classes", incoming.adhocSessions.toString())
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
                            "and upgraded to format ${BackupCodec.FORMAT_VERSION} as it was read.",
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Note(
                    text = if (current == null || current.isEmpty) {
                        "There is nothing on this phone to lose."
                    } else {
                        "Everything on this phone now (${courseCount(current)}, " +
                            "${classes(current.sessions)}) is deleted and replaced. Attendo " +
                            "saves a copy of it first, so you can undo this from this screen " +
                            "straight afterwards."
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
        BackupTask.RESTORING -> "Restoring… Do not close Attendo."
        BackupTask.UNDOING -> "Putting your previous data back…"
        BackupTask.CLEARING -> "Clearing everything… Do not close Attendo."
    }

/** "6 courses", with the archived ones called out — they still carry their history. */
private fun courseCount(summary: BackupSummary): String =
    courses(summary.courses) +
        if (summary.archivedCourses > 0) " (${summary.archivedCourses} archived)" else ""

/** "148 (96 marked)" */
private fun classCount(summary: BackupSummary): String =
    if (summary.sessions == 0) "Nothing yet"
    else "${summary.sessions} (${summary.reviewedSessions} marked)"

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
