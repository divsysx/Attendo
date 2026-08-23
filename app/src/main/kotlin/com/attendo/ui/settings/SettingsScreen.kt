package com.attendo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.Percent
import com.attendo.ui.components.AttendoDatePickerDialog
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ChipChoice
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.courses
import com.attendo.ui.dayMonth
import com.attendo.ui.days
import com.attendo.ui.fullLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The targets worth offering, matching the course editor's list. */
private val TARGETS: List<Percent> =
    listOf(50.0, 60.0, 65.0, 70.0, 75.0, 80.0, 85.0, 90.0).map { Percent.ofPercent(it) }

/** As much of a name as the top bar can show next to the date. */
private const val MAX_NAME_LENGTH = 24

/**
 * The term's shape: what counts as a pass, when the term runs, and which days it does not.
 *
 * Every control here changes what the timetable implies for days that have already been
 * generated, so the ViewModel corrects the sessions table as each one is used.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSeed: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(title = "Settings", onBack = onBack)

        if (!state.loaded) {
            LoadingPane()
        } else {
            SettingsContent(
                state = state,
                viewModel = viewModel,
                onSeed = onSeed,
                onOpenBackup = onOpenBackup,
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

        SettingsDialog.AddHoliday -> AttendoDatePickerDialog(
            initial = state.today.coerceIn(state.calendar.termStart, state.calendar.termEnd),
            earliest = state.calendar.termStart,
            latest = state.calendar.termEnd,
            onPick = viewModel::addHoliday,
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
    onSeed: () -> Unit,
    onOpenBackup: () -> Unit,
    onDialog: (SettingsDialog) -> Unit,
) {
    val calendar = state.calendar
    val saturdays = remember(calendar.termStart, calendar.termEnd) {
        saturdaysBetween(calendar.termStart, calendar.termEnd)
    }

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
                        "is when you import a backup. It stays on this phone: there is no " +
                        "account, nothing is sent anywhere, and none of your data is filed " +
                        "under it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Overall target") }
        item {
            ChipChoice(
                options = TARGETS,
                selected = state.settings.overallTarget,
                label = { "${it.format(0)}%" },
                onSelect = viewModel::setOverallTarget,
            )
        }

        item { SectionLabel("Target for new courses") }
        item {
            Column {
                ChipChoice(
                    options = TARGETS,
                    selected = state.settings.courseTarget,
                    label = { "${it.format(0)}%" },
                    onSelect = viewModel::setCourseTarget,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Courses already added keep the target they have.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Term") }
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
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${days(state.teachingDays)} of teaching, " +
                        "${state.teachingDaysSoFar} of them gone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item {
            SectionLabel("Holidays") {
                TextButton(onClick = { onDialog(SettingsDialog.AddHoliday) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add")
                }
            }
        }
        item {
            if (state.holidays.isEmpty()) {
                Text(
                    text = "None. A class on a day you mark a holiday is cancelled rather than " +
                        "missed, so it counts for neither side.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DateChips(
                    dates = state.holidays,
                    isOn = { true },
                    onToggle = viewModel::removeHoliday,
                    removable = true,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("Working Saturdays") }
        item {
            Column {
                Text(
                    text = "Saturday is a holiday unless you say otherwise, so a Saturday slot " +
                        "only generates classes on the dates ticked here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DateChips(
                    dates = saturdays,
                    isOn = { it in calendar.workingSaturdays },
                    onToggle = { date ->
                        if (date in calendar.workingSaturdays) {
                            viewModel.removeWorkingSaturday(date)
                        } else {
                            viewModel.addWorkingSaturday(date)
                        }
                    },
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
                        "way — the classes held before you arrived stay in your history and can " +
                        "still be opened; this only decides which of them count towards your " +
                        "percentage.",
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
                    text = "A term of marks cannot be reconstructed from anything else — not " +
                        "from the timetable, and not from memory. Export a file, keep it off " +
                        "this phone, and import it on the next one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Divider() }

        item { SectionLabel("How the percentage is worked out") }
        item {
            Text(
                text = "Attendance is counted in one-hour units, not in classes. Sitting " +
                    "through one hour of a two-hour lab is 50% of that lab, and the course " +
                    "figure is every hour attended divided by every hour held — never an " +
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
            }
        }
    }
}

/** A label with a tappable value on the right — the shape of every date row here. */
@Composable
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

/**
 * A wrapping row of dates that can be turned on and off.
 *
 * [removable] gives each chip a cross, for the lists where "off" means "gone" — a holiday
 * is either in the list or not, while a Saturday is one of a fixed set and stays visible
 * when unticked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateChips(
    dates: List<LocalDate>,
    isOn: (LocalDate) -> Boolean,
    onToggle: (LocalDate) -> Unit,
    removable: Boolean = false,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dates.forEach { date ->
            FilterChip(
                selected = isOn(date),
                onClick = { onToggle(date) },
                label = { Text(date.dayMonth()) },
                trailingIcon = if (removable) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

/** Every Saturday the term contains, for the working-Saturday picker. */
private fun saturdaysBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
    val first = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    return generateSequence(first) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(to) }
        .toList()
}

private sealed interface SettingsDialog {
    data object TermStart : SettingsDialog
    data object TermEnd : SettingsDialog
    data object AddHoliday : SettingsDialog
    data object JoiningDate : SettingsDialog
    data object DisplayName : SettingsDialog
}
