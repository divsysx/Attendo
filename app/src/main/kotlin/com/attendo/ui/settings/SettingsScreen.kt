package com.attendo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.model.AttendanceBasis
import com.attendo.data.ThemePreference
import com.attendo.ui.components.AttendoDatePickerDialog
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ChipChoice
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.courses
import com.attendo.ui.days
import com.attendo.ui.fullLabel

/** As much of a name as the top bar can show next to the date. */
private const val MAX_NAME_LENGTH = 24

/**
 * The project's home, opened by the star row and by the "View source code" line under it.
 *
 * One constant rather than two literals: a footer that pointed at the repo from one row and
 * somewhere else from the next would be a footer nobody could trust.
 */
internal const val GITHUB_REPO_URL = "https://github.com/divsysx/Attendo"

/** Where a student who wants to say thank you can. The app never asks for it. */
internal const val SUPPORT_URL = "https://buymeacoffee.com/divsysx"

/**
 * The term's shape: when it runs, which days it does not, and what counts as a pass.
 *
 * This screen is a list of what the app can be asked to do, not a form. The two date-driven
 * editors that used to sit inline — the holiday list and the working-Saturday picker — and
 * the target controls are each on their own screen now, and each row here says in one line
 * what is currently configured and opens it. What is left inline is the handful of things
 * that genuinely are a single choice: your name, the term's two dates, your section, the
 * theme.
 *
 * Every control that remains changes what the timetable implies for days that have already
 * been generated, so the ViewModel corrects the sessions table as each one is used.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSeed: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMyCommunity: () -> Unit,
    onOpenReportingIdentity: () -> Unit,
    onOpenTargets: () -> Unit,
    onOpenHolidays: () -> Unit,
    onOpenWorkingSaturdays: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var feedbackOpen by remember { mutableStateOf(false) }

    if (feedbackOpen) {
        FeedbackSection(
            appVersion = state.version.name.ifBlank { "unknown" },
            onDismiss = { feedbackOpen = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(title = "Settings", onBack = onBack)

        if (!state.loaded) {
            LoadingPane()
        } else {
            SettingsContent(
                state = state,
                viewModel = viewModel,
                accountViewModel = accountViewModel,
                onSeed = onSeed,
                onOpenBackup = onOpenBackup,
                onOpenAccount = onOpenAccount,
                onOpenMyCommunity = onOpenMyCommunity,
                onOpenReportingIdentity = onOpenReportingIdentity,
                onOpenTargets = onOpenTargets,
                onOpenHolidays = onOpenHolidays,
                onOpenWorkingSaturdays = onOpenWorkingSaturdays,
                onFeedback = { feedbackOpen = true },
                onDialog = { dialog = it },
            )
        }
    }

    when (dialog) {
        SettingsDialog.TermStart -> AttendoDatePickerDialog(
            initial = state.calendar.termStart,
            latest = state.calendar.termEnd,
            onPick = viewModel::setTermStart,
            onDismiss = { dialog = null },
        )

        SettingsDialog.TermEnd -> AttendoDatePickerDialog(
            initial = state.calendar.termEnd,
            earliest = state.calendar.termStart,
            onPick = viewModel::setTermEnd,
            onDismiss = { dialog = null },
        )

        SettingsDialog.JoiningDate -> AttendoDatePickerDialog(
            initial = state.attendanceStart.joinedOn
                ?: state.today.coerceIn(state.calendar.termStart, state.calendar.termEnd),
            // Bounded by the term because a joining date outside it says nothing: earlier is
            // ignored in favour of the semester start, later would count no classes at all.
            earliest = state.calendar.termStart,
            latest = state.calendar.termEnd,
            onPick = viewModel::setJoiningDate,
            onDismiss = { dialog = null },
        )

        SettingsDialog.DisplayName -> NameDialog(
            initial = state.nameFieldValue,
            onConfirm = { name ->
                viewModel.setDisplayName(name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    accountViewModel: AccountViewModel,
    onSeed: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMyCommunity: () -> Unit,
    onOpenReportingIdentity: () -> Unit,
    onOpenTargets: () -> Unit,
    onOpenHolidays: () -> Unit,
    onOpenWorkingSaturdays: () -> Unit,
    onFeedback: () -> Unit,
    onDialog: (SettingsDialog) -> Unit,
) {
    val calendar = state.calendar
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionLabel("You") }
        item {
            Column {
                SettingRow(
                    label = "Your name",
                    value = state.displayNameLabel,
                    onClick = { onDialog(SettingsDialog.DisplayName) },
                )
                Text(
                    text = "Used to greet you on the Attendance tab, and to say whose file it " +
                        "is when you import a backup. It is a label rather than a login: no " +
                        "row is keyed by it. It travels with your account's copy of your " +
                        "settings if you sign in, and stays on this phone if you do not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        // The install's identity, compact on purpose: the account itself — its state,
        // its GitHub link, its sign-out — lives on its own screen, and Settings shows
        // only the way in. Right under "You" because it is the other half of who this
        // install is. Label and row both hide on builds without an endpoint.
        if (accountState.available) {
            item { SectionLabel("Account") }
            item {
                Column {
                    SettingRow(
                        label = "Manage your Attendo account",
                        value = "Open",
                        onClick = onOpenAccount,
                    )
                    Text(
                        text = "Your GitHub sign-in, and the account your Community " +
                            "reports are associated with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Divider() }

        item { SectionLabel("Targets") }
        item {
            Column {
                SettingRow(
                    label = "Attendance Targets",
                    value = state.targetsSummary,
                    onClick = onOpenTargets,
                )
                Text(
                    text = "The threshold your overall figure is judged against, what a new " +
                        "course starts at, and the one place a single percentage can be " +
                        "applied to the courses you already have.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Semester") }
        item {
            Column {
                SettingRow(
                    label = "Starts",
                    value = calendar.termStart.fullLabel(),
                    onClick = { onDialog(SettingsDialog.TermStart) },
                )
                SettingRow(
                    label = "Ends",
                    value = calendar.termEnd.fullLabel(),
                    onClick = { onDialog(SettingsDialog.TermEnd) },
                )
                SettingRow(
                    label = "Holidays",
                    value = state.holidaysSummary,
                    onClick = onOpenHolidays,
                )

                // Hidden outright for a section the timetable teaches on Saturday every
                // week: its Saturdays come from its weekly pattern, so this would be a
                // control the app ignores. See RecurringSaturdays for which sections those
                // are. Nothing else on this screen or below it moves when it goes.
                if (!state.saturdayIsRecurring) {
                    SettingRow(
                        label = "Working Saturdays",
                        value = state.workingSaturdaysSummary,
                        onClick = onOpenWorkingSaturdays,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${days(state.teachingDays)} of teaching, " +
                        "${state.teachingDaysSoFar} of them gone. A class on a holiday is " +
                        "cancelled rather than missed, so it counts for neither side of your " +
                        "percentage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Your section") }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.sectionLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.settings.section != null) {
                        TextButton(onClick = viewModel::clearSection) { Text("Clear") }
                    }
                    TextButton(onClick = onSeed) { Text("Seed") }
                }
                Text(
                    text = "Tracking ${courses(state.courseCount)}, " +
                        "${state.markedSessions} classes marked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Attendance counted from") }
        item {
            val start = state.attendanceStart
            Column {
                ChipChoice(
                    options = AttendanceBasis.entries,
                    selected = start.basis,
                    label = { it.label },
                    onSelect = viewModel::setAttendanceBasis,
                )
                Spacer(Modifier.height(4.dp))
                SettingRow(
                    label = "Your joining date",
                    value = start.joinedOn?.fullLabel() ?: "Not set",
                    onClick = { onDialog(SettingsDialog.JoiningDate) },
                )
                Text(
                    text = start.basis.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (start.isIncomplete) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Set the date, or this reads the same as the university's figure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "For a spot admission or a late transfer. Nothing is deleted either " +
                        "way: the classes held before you arrived stay in your history and can " +
                        "still be opened, and this only decides which of them count towards " +
                        "your percentage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Appearance") }
        item {
            Column {
                Text(text = "Theme", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                ChipChoice(
                    options = ThemePreference.entries,
                    selected = appearance.theme,
                    label = ::themeLabel,
                    onSelect = viewModel::setTheme,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "System default follows this phone's dark mode setting, and " +
                        "changes when it changes. Light and Dark hold Attendo to one of them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Use dynamic colours",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = appearance.dynamicColors,
                        onCheckedChange = viewModel::setDynamicColors,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Use colours from your device when available. Off, Attendo always " +
                        "uses its own colours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Data & backup") }
        item {
            Column {
                SettingRow(
                    label = "Export or import a backup",
                    value = "Open",
                    onClick = onOpenBackup,
                )
                Text(
                    text = "A semester's attendance cannot be reconstructed from anything " +
                        "else, not from the timetable, and not from memory. Export a file, " +
                        "keep it off this phone, and import it on the next one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Community") }
        item {
            Column {
                SettingRow(
                    label = "My reports and polls",
                    value = "Open",
                    onClick = onOpenMyCommunity,
                )
                Text(
                    text = "Everything you have reported or asked, in one place, with the " +
                        "undo and withdraw actions that belong to their owner. All of it is " +
                        "filed anonymously under a random ID created on this phone: no name, " +
                        "no email, nothing that identifies you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        // Its own section, deliberately not part of "Data & backup": the file this
        // leads to can move who the reports belong to, so it gets its own screen,
        // its own passphrase and its own warnings.
        item { SectionLabel("Reporting identity") }
        item {
            Column {
                SettingRow(
                    label = "Move or restore my reporting identity",
                    value = "Open",
                    onClick = onOpenReportingIdentity,
                )
                Text(
                    text = "A separate, encrypted file that can move the anonymous ID " +
                        "your reports are filed under, and everything it has reported, " +
                        "to a new phone. It is never part of the backup above, and the " +
                        "old phone keeps a 24-hour say in any move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Feedback") }
        item {
            Column {
                SettingRow(
                    label = "Report a bug or suggest an improvement",
                    value = "Send",
                    onClick = onFeedback,
                )
                Text(
                    text = "Opens your email app with the message started for you. Nothing is " +
                        "sent from inside Attendo — the message leaves through your mail app, " +
                        "not through a server of ours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("How the percentage is worked out") }
        item {
            Text(
                text = "Attendance is counted in hours, not in classes. Sitting " +
                    "through one hour of a two-hour lab is 50% of that lab, and the course " +
                    "figure is every hour attended divided by every hour held, never an " +
                    "average of averages. Cancelled classes count for neither side, and a " +
                    "class stays out of the maths entirely until it is marked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.problems.isNotEmpty()) {
            item { Divider() }
            item { SectionLabel("Timetable problems") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "These lines of the bundled timetable could not be read, so " +
                            "whatever they described is missing from the Rooms tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.problems.forEach { problem ->
                        Text(
                            text = problem.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // Only builds with an update source get the section. A Play-installed build has
        // nothing to check — Play owns its updates — and a section that always said
        // "couldn't check" would be worse than no section.
        if (state.update.supported) {
            item { Divider() }
            item { SectionLabel("Updates") }
            item {
                UpdatesSection(
                    panel = state.update,
                    state = updateState,
                    onCheck = viewModel::checkForUpdates,
                    onDownload = viewModel::downloadUpdate,
                    onCancelDownload = viewModel::cancelUpdateDownload,
                    onInstall = viewModel::installUpdate,
                    onDismiss = viewModel::dismissUpdate,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("About") }
        item {
            // Two lines, deliberately. The product is called Attendo; the version is a fact
            // about this build, and joining the two into "Attendo 1.0" would turn a release
            // number into part of the name.
            Column {
                Text(text = "Attendo", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = state.versionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The project footer. Nothing here changes how Attendo behaves, which is why
                // it sits under About rather than growing a heading of its own — the brief
                // asked for a footer, and a fourth settings category would be a different
                // thing wearing the same words. Both rows leave the app through the same
                // ACTION_VIEW intent the feedback section uses; nothing is rendered in-app.
                Spacer(Modifier.height(16.dp))
                SettingRow(
                    label = "⭐ Enjoying Attendo? Star the project on GitHub",
                    value = "GitHub",
                    onClick = { context.openUrl(GITHUB_REPO_URL) },
                )
                Text(
                    text = "View source code — Attendo is open source under the MIT licence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SettingRow(
                    label = "☕ Support development",
                    value = "Buy me a coffee",
                    onClick = { context.openUrl(SUPPORT_URL) },
                )
                Text(
                    text = "Attendo is free, with no ads and no paid tier. A coffee is a " +
                        "thank-you rather than a subscription, and nothing in the app " +
                        "depends on it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The words on the three theme chips — a preference, so "System default" not "Auto". */
internal fun themeLabel(theme: ThemePreference): String = when (theme) {
    ThemePreference.SYSTEM -> "System default"
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
}

/** A label with a tappable value on the right — the shape of every date row here. */@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * What Attendo should call you — one text field, stored on this phone.
 *
 * Empty is a real answer rather than a missing one, so there is no validation and no error
 * state: clearing the box is how you go back to being greeted with a plain "Hi".
 */
@Composable
private fun NameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    // Capped rather than rejected: a long name would push the date out of the
                    // top bar, and there is nothing to say to somebody about the 41st character.
                    onValueChange = { typed -> if (typed.length <= MAX_NAME_LENGTH) text = typed },
                    // No placeholder. A greyed-out example name in an empty field reads as a
                    // value that is already there, and an example of a first name teaches
                    // nobody anything the label does not already say.
                    label = { Text("First name is plenty") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Leave it empty for a plain \"Hi\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

private sealed interface SettingsDialog {
    data object TermStart : SettingsDialog
    data object TermEnd : SettingsDialog
    data object JoiningDate : SettingsDialog
    data object DisplayName : SettingsDialog
}
