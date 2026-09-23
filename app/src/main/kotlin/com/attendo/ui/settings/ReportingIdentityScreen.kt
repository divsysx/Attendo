package com.attendo.ui.settings

import android.net.Uri
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.data.community.AtidCodec
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.localLabel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * The `.atid` import picker's filter, and why it is this loose: file managers and
 * cloud drives hand an `.atid` file back as `application/octet-stream` or
 * `text/plain`, and greying out the student's own identity file — with no way to
 * explain why from inside the picker — is worse than admitting a stray text file.
 * The contents are validated on the way in; the filter is a convenience, never a check.
 */
private val IDENTITY_MIME_TYPES = arrayOf(
    AtidCodec.MIME_TYPE,
    "text/plain",
)

/** Export passphrases are refused below this length — a guessable one is no protection. */
private const val MIN_PASSPHRASE = 8

// ---- the confidential warning, pinned by test ---------------------------------
//
// One string, because the whole feature turns on it being said the same way every
// time: the file plus its passphrase moves an identity, Attendo will never back it
// up, and it must be treated as a secret. CommunityIdentityBackupGuardTest pins
// that it exists and reaches the screen.

internal val IDENTITY_FILE_WARNING: String =
    "Highly confidential. This file, together with its passphrase, is enough to move " +
        "everything you have reported, and the reputation it has earned, to another " +
        "phone. Attendo never includes it in any backup, never sends it anywhere " +
        "itself, and cannot recover it if you lose both the file and the passphrase. " +
        "Treat it like a password."

internal val IDENTITY_FILE_CONFIRM_EXPORT: String =
    "The file is encrypted with a passphrase you choose, and holds a single-use code " +
        "that expires in 3 days. No name, no email, nothing else.\n\n$IDENTITY_FILE_WARNING"

internal val IDENTITY_FILE_CONFIRM_IMPORT: String =
    "This moves the reporting identity from another phone to this one: its reports, " +
        "polls and reputation become this phone's. The old phone has 24 hours to cancel " +
        "the move after you start, and if it is used during that time, the move is " +
        "cancelled.\n\nAn import never merges two identities: if this phone already has " +
        "its own community history, the import is refused, and Attendo offers the " +
        "choice to replace this phone's identity instead. That is a permanent deletion " +
        "that is asked about separately, with its own warning."

/**
 * The destructive confirmation, pinned by test. Shown only after the server has
 * refused a claim with `identity_not_fresh` — this phone holds a community history
 * of its own, and the only way forward is deleting it. The copy must state the
 * deletion plainly (permanent, unrecoverable, everything the identity made) and
 * just as plainly when it happens: not now, only when the move completes.
 */
internal val IDENTITY_FILE_CONFIRM_REPLACE: String =
    "This phone already has its own reporting identity, and an identity file can " +
        "never merge two. To bring in the identity from the file, this phone's " +
        "identity must be deleted for good: every report, poll, vote and " +
        "verification it made, the reputation it earned, and its transfer history " +
        "are permanently erased from Attendo's servers. This cannot be undone and " +
        "cannot be recovered.\n\nNothing is deleted straight away. The 24-hour " +
        "window runs first: the old phone can still cancel the move, and this " +
        "phone's community actions are paused while it waits. Using this phone " +
        "doesn't cancel the move, and reports it makes are kept and sent once the " +
        "move is decided. The deletion happens only if and when the move completes. " +
        "If it is cancelled or runs out, this phone keeps its own identity and " +
        "everything in it, exactly as it was."

/**
 * Reporting identity backup — the transfer twin of [BackupScreen], and deliberately
 * not part of it.
 *
 * The attendance backup carries a semester's marks. This file carries *who filed
 * them* under community: the anonymous identity itself, which is why everything
 * about it is slower and louder here. Both directions get a full-screen warning
 * before anything happens; export asks for a passphrase twice; import never
 * touches the passphrase until a file is in hand; and every outcome — the 24-hour
 * veto window, the old phone's cancellation rights, the refusal to merge two
 * identities — is said in the words the server's state machine actually means.
 *
 * All file access is through Android's picker (SAF): the app holds no storage
 * permission and never sees a path, and the file never lives in app storage a
 * backup could scoop up.
 */
@Composable
fun ReportingIdentityScreen(
    onBack: () -> Unit,
    viewModel: ReportingIdentityViewModel = viewModel(factory = ReportingIdentityViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The status this screen shows is a moving target: the other phone can approve,
    // cancel, or let the 24-hour window pass at any moment, and none of it sends a
    // push. The banner must be true the moment the screen is looked at, so every
    // resume asks the server where things stand — the "navigate away and come back"
    // the two-device test needed just to see a banner update was this refresh missing.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshStatus()
        viewModel.startStatusPolling()
    }
    // ...and true *while* it is looked at: a claim can arrive, and a decision can
    // land, with this screen sitting open the whole time. The poll is the screen's
    // foreground span and nothing longer — paused means stopped, and the resume
    // above covers the return.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.stopStatusPolling()
    }

    // Where the picked file is waiting for its passphrase. Set by the picker
    // callbacks, consumed by the passphrase dialog's confirm — the passphrase is
    // turned into a CharArray at the moment of the call and never stored.
    var passphraseFor by remember { mutableStateOf<PassphraseRequest?>(null) }

    // The two warning dialogs. They sit *before* the pickers on purpose: the
    // warning is about what choosing a file means, so it is asked before the
    // system picker is on screen, not after the student has already committed to
    // a location.
    var confirmExport by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    // "Let it move now" is the one action here that gives the identity away
    // immediately, so it is asked about once more — the same patience the
    // automatic-backup toggle gets in BackupScreen.
    var confirmApprove by remember { mutableStateOf(false) }
    var confirmStartFresh by remember { mutableStateOf(false) }

    val saveAtid = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AtidCodec.MIME_TYPE),
    ) { target -> if (target != null) passphraseFor = PassphraseRequest.Export(target) }

    val openAtid = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source -> if (source != null) passphraseFor = PassphraseRequest.Import(source) }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = "Reporting identity",
            subtitle = "Move or restore your anonymous reporting ID",
            onBack = onBack,
        )

        IdentityContent(
            state = state,
            onRetryStatus = viewModel::refreshStatus,
            onExport = { confirmExport = true },
            onImport = { confirmImport = true },
            onKeep = viewModel::abort,
            onLetItMove = { confirmApprove = true },
            onStartFresh = { confirmStartFresh = true },
        )
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export an identity file?") },
            text = {
                Text(
                    IDENTITY_FILE_CONFIRM_EXPORT,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    saveAtid.launch(AtidCodec.suggestedFileName(LocalDate.now()))
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import an identity file?") },
            text = {
                Text(
                    IDENTITY_FILE_CONFIRM_IMPORT,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    openAtid.launch(IDENTITY_MIME_TYPES)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("Cancel") }
            },
        )
    }

    val request = passphraseFor
    if (request != null) {
        PassphraseDialog(
            exporting = request is PassphraseRequest.Export,
            onConfirm = { passphrase ->
                passphraseFor = null
                when (request) {
                    is PassphraseRequest.Export ->
                        viewModel.exportTo(request.target, passphrase)
                    is PassphraseRequest.Import ->
                        viewModel.importFrom(request.source, passphrase)
                    is PassphraseRequest.Replace ->
                        viewModel.confirmReplaceFrom(request.source, passphrase)
                }
            },
            onCancel = { passphraseFor = null },
        )
    }

    // The destructive confirmation. It appears only after the server refused a
    // claim with identity_not_fresh, and it is the one place on this screen where
    // "yes" deletes something permanently. Confirming re-asks for the file's
    // passphrase (the first one was wiped after the refused claim) — the file is
    // read and the replacement claim made only after both steps.
    state.pendingReplacement?.let { source ->
        AlertDialog(
            onDismissRequest = viewModel::cancelReplacement,
            title = { Text("Replace this phone's identity?") },
            text = {
                Text(
                    IDENTITY_FILE_CONFIRM_REPLACE,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    passphraseFor = PassphraseRequest.Replace(source)
                }) { Text("Delete and replace") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelReplacement) {
                    Text("Keep this phone's identity")
                }
            },
        )
    }

    if (confirmApprove) {
        AlertDialog(
            onDismissRequest = { confirmApprove = false },
            title = { Text("Let the identity move now?") },
            text = {
                Text(
                    "The move completes immediately. This phone stops being able to " +
                        "report, vote or ask polls as this identity, and everything it " +
                        "reported belongs to the other phone from that moment.\n\n" +
                        "Nothing else on this phone (attendance, courses, backups) is " +
                        "affected. A move cannot be undone, but it can be reversed the " +
                        "same way: export an identity file on the other phone and " +
                        "import it here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApprove = false
                    viewModel.approve()
                }) { Text("Move now") }
            },
            dismissButton = {
                TextButton(onClick = { confirmApprove = false }) { Text("Not yet") }
            },
        )
    }

    if (confirmStartFresh) {
        AlertDialog(
            onDismissRequest = { confirmStartFresh = false },
            title = { Text("Start a fresh reporting identity?") },
            text = {
                Text(
                    "This signs out the moved identity and creates a new, empty one on " +
                        "this phone. Reports you make from now on belong to the new one. " +
                        "The moved identity stays on the other phone, and your attendance " +
                        "and courses here are untouched.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartFresh = false
                    viewModel.startFresh()
                }) { Text("Start fresh") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartFresh = false }) { Text("Not now") }
            },
        )
    }

    state.working?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
        )
    }

    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissNotice,
            title = { Text(notice.title) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(notice.message, style = MaterialTheme.typography.bodyMedium)
                    notice.hint?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissNotice) { Text("OK") }
            },
        )
    }
}

/** A picked file waiting for the passphrase that will finish its journey. */
private sealed interface PassphraseRequest {
    /** An export target the student created — the passphrase is being chosen now. */
    data class Export(val target: Uri) : PassphraseRequest

    /** A picked identity file — the passphrase is the one set at its export. */
    data class Import(val source: Uri) : PassphraseRequest

    /**
     * The destructive path: the same picked file, read again after the student
     * confirmed the replacement. The passphrase the first attempt used was already
     * wiped, so it is asked for once more before the file is re-opened.
     */
    data class Replace(val source: Uri) : PassphraseRequest
}

@Composable
private fun IdentityContent(
    state: ReportingIdentityUiState,
    onRetryStatus: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onKeep: () -> Unit,
    onLetItMove: () -> Unit,
    onStartFresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The two states that outrank everything else this screen can say. Ordered
        // superseded first: an identity that has already moved makes the pending
        // banner's question moot.
        if (!state.available) {
            item {
                Text(
                    text = "Not available on this install. Moving a reporting identity " +
                        "needs the community service, which this build is not " +
                        "configured for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (state.superseded) {
                item { SectionLabel("This identity has moved") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Warning(
                            "Your reporting identity has moved to another phone. Every " +
                                "report and poll it made now belongs there, and this " +
                                "phone can no longer report, vote or ask polls as that " +
                                "identity.",
                        )
                        Text(
                            text = "There is no undo. Getting the identity back means " +
                                "moving it again, in the other direction: export an " +
                                "identity file on the phone that now holds it, and import " +
                                "that file here. The identity then leaves that phone, and " +
                                "anything reported there in the meantime comes with it. " +
                                "Or start over with a fresh identity below. Nothing else " +
                                "on this phone is affected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onStartFresh, modifier = Modifier.fillMaxWidth()) {
                            Text("Start fresh")
                        }
                    }
                }
                item { Divider() }
            } else if (state.transferPending) {
                item { SectionLabel("A move is waiting on you") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Warning(
                            "Another phone is trying to take over your reporting " +
                                "identity. If that wasn't you, keep it here. If it was " +
                                "you moving to a new phone, you can let it move now " +
                                "instead of waiting.",
                        )
                        state.transferClaimDeadline?.let { deadline ->
                            DeadlineLine(
                                lead = "Nothing moves without your answer until",
                                deadline = deadline,
                                tail = "After that, the other phone can finish the " +
                                    "move on its own.",
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onKeep, modifier = Modifier.weight(1f)) {
                                Text("Keep it on this phone")
                            }
                            OutlinedButton(onClick = onLetItMove, modifier = Modifier.weight(1f)) {
                                Text("Let it move now")
                            }
                        }
                    }
                }
                item { Divider() }
            } else if (state.loaded && !state.statusKnown) {
                item {
                    Text(
                        text = "Couldn't check where this identity stands. The server " +
                            "could not be reached.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onRetryStatus) { Text("Try again") }
                }
                item { Divider() }
            }

            // This phone as the claimant: it reached for an identity file, the claim
            // was accepted, and the 24-hour window is running on the old phone. It
            // can be true at the same time as a pending banner above only in the
            // sense that both describe different grants; normally it stands alone.
            // Frozen here means one-way-paused: unlike the owner side, using this
            // phone does NOT cancel the move.
            if (state.claimPending) {
                item { SectionLabel("A move is waiting on the old phone") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.claimReplacesIdentity) {
                            Warning(
                                "This phone is taking over the identity from an identity " +
                                    "file, replacing its own. The old phone can cancel " +
                                    "until the time below. Until the move is decided, this " +
                                    "phone's community actions are paused: anything it " +
                                    "files is kept and sent when the move is decided, and " +
                                    "using this phone doesn't cancel the move.\n\nIf the " +
                                    "move completes, this phone's own identity, everything " +
                                    "it reported and its reputation, is permanently " +
                                    "deleted. If the move is cancelled, this phone's " +
                                    "identity is exactly as it was.",
                            )
                        } else {
                            Text(
                                text = "This phone has claimed an identity from an identity " +
                                    "file and is waiting out the window. The old phone " +
                                    "can still cancel.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.claimDeadline?.let { deadline ->
                            DeadlineLine(
                                lead = "The old phone can cancel until",
                                deadline = deadline,
                                tail = "After that, importing the same file again here " +
                                    "finishes the move.",
                            )
                        }
                    }
                }
                item { Divider() }
            }

            item { SectionLabel("What this file is") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Your reports are filed under an anonymous ID created on " +
                            "this phone. An identity file can move that ID, and every " +
                            "report, poll and bit of reputation it has earned, to another " +
                            "phone. It is the only way to do it: the ID is never in a " +
                            "normal backup, never sent anywhere on its own, and never " +
                            "written down anywhere on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Warning(IDENTITY_FILE_WARNING)
                }
            }

            item { Divider() }

            item { SectionLabel("Export") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Text("Export identity file")
                    }
                    Text(
                        text = "You choose a passphrase, and Attendo writes an encrypted " +
                            ".atid file to a place you pick. The code inside works for 3 " +
                            "days and can move the identity once; exporting again makes a " +
                            "fresh one. Up to 3 files a day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Divider() }

            item { SectionLabel("Import") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text("Import identity file")
                    }
                    Text(
                        text = "Pick an identity file from the other phone and give its " +
                            "passphrase. The move starts a 24-hour window in which the " +
                            "old phone can cancel; after the window, importing the same " +
                            "file again finishes the move.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Divider() }

            item { SectionLabel("If this phone is lost") }
            item {
                Text(
                    text = "On a new phone: import the identity file, wait out the 24-hour " +
                        "window (the lost phone gets its chance to object), and import " +
                        "the same file again to finish. The lost phone's identity stops " +
                        "working the moment the move completes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A pending window's end, said as the moment it happens with a live countdown
 * beside it — the two-device test's "waiting out the 24 hours" made concrete.
 * The countdown ticks every second on its own, as hours:minutes:seconds (the
 * status poll only re-renders when something changes, so a static "23 hours
 * from now" would silently go stale on a screen left open — and a minute-level
 * "23 h 5 m" looked frozen for most of every minute, which read as broken);
 * the moment itself is the server's deadline, never a locally counted-up
 * guess, so a phone opened late still reads the truth.
 */
@Composable
private fun DeadlineLine(lead: String, deadline: Instant, tail: String) {
    var nowMs by remember(deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val remaining = Duration.between(Instant.ofEpochMilli(nowMs), deadline)
    val countdown = when {
        remaining.isNegative -> "that time has passed"
        else -> {
            @Suppress("NewApi")
            val h = remaining.toHours()
            @Suppress("NewApi")
            val m = remaining.toMinutesPart()
            @Suppress("NewApi")
            val s = remaining.toSecondsPart()
            String.format("%d:%02d:%02d left", h, m, s)
        }
    }
    Text(
        text = "$lead ${deadline.localLabel()} ($countdown). $tail",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The passphrase step. Export asks twice (a mistyped passphrase locks the identity
 * file forever — there is no recovery); import asks once, because the passphrase
 * already exists and this is only its check.
 *
 * The value lives in Compose state as a String for as long as the dialog is up, and
 * is turned into a CharArray at the moment of the call — the repository wipes that
 * array when it is done, and the dialog's state dies with the dialog.
 *
 * Each field carries an explicit Paste button. The keyboard's long-press paste works
 * too, but it is the one input path that varies by keyboard: some IMEs hide the
 * paste affordance in password-transformed fields, and a passphrase this dialog is
 * asking for usually exists somewhere copyable (a password manager, a note sent to
 * oneself). Paste *replaces* the field — a passphrase is pasted whole or not at all
 * — and trims the clipboard's text, because a copied note's trailing newline would
 * otherwise become part of the passphrase invisibly.
 */
@Composable
private fun PassphraseDialog(
    exporting: Boolean,
    onConfirm: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (exporting) "Choose a passphrase" else "The file's passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PassphraseField(
                    label = "Passphrase",
                    value = passphrase,
                    onChange = {
                        passphrase = it
                        error = null
                    },
                    onPasteNothing = { error = "Nothing to paste" },
                )
                if (exporting) {
                    PassphraseField(
                        label = "Repeat it",
                        value = repeat,
                        onChange = {
                            repeat = it
                            error = null
                        },
                        onPasteNothing = { error = "Nothing to paste" },
                    )
                }
                error?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = if (exporting) {
                        "At least $MIN_PASSPHRASE characters. Anyone with the file and " +
                            "this passphrase can start the move. The old phone keeps " +
                            "its 24-hour say, but pick something only you would guess.\n\n" +
                            "There is no recovery: lose the passphrase and the file is " +
                            "a sealed box."
                    } else {
                        "The passphrase that was set when this file was exported."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    exporting && passphrase.length < MIN_PASSPHRASE ->
                        error = "At least $MIN_PASSPHRASE characters"
                    exporting && passphrase != repeat ->
                        error = "The two don't match"
                    !exporting && passphrase.isEmpty() ->
                        error = "Enter the passphrase"
                    else -> {
                        val chars = passphrase.toCharArray()
                        // The dialog's own copy goes blank before the call: nothing in
                        // this screen outlives the confirm button's frame.
                        passphrase = ""
                        repeat = ""
                        onConfirm(chars)
                    }
                }
            }) { Text(if (exporting) "Encrypt and write" else "Open the file") }
        },
        dismissButton = {
            TextButton(onClick = {
                passphrase = ""
                repeat = ""
                onCancel()
            }) { Text("Cancel") }
        },
    )
}

/** One passphrase field: masked input, and a Paste button that always works. */
@Composable
private fun PassphraseField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onPasteNothing: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                val text = clipboard.getText()?.text?.trim()
                if (text.isNullOrEmpty()) onPasteNothing() else onChange(text)
            },
            modifier = Modifier.align(Alignment.End),
        ) { Text("Paste") }
    }
}

/** The loud sentence — error-toned on purpose, so it reads as a warning, not a note. */
@Composable
private fun Warning(text: String) {    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.fillMaxWidth())
}
