package com.attendo.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.engine.DayMark
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.Course
import com.attendo.core.model.SessionKind
import com.attendo.core.model.TimeGrid
import com.attendo.ui.AttendoIcons
import com.attendo.ui.components.AttendoDatePickerDialog
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ChipChoice
import com.attendo.ui.components.EmptyState
import com.attendo.ui.components.KindTag
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.MarkSwatch
import com.attendo.ui.components.UnitToggles
import com.attendo.ui.classes
import com.attendo.ui.hours
import com.attendo.ui.label
import com.attendo.ui.longLabel
import com.attendo.ui.notTeachingReason
import com.attendo.ui.originLabel
import com.attendo.ui.relativeLabel
import com.attendo.ui.shortLabel
import com.attendo.ui.theme.bands
import java.time.LocalDate

/**
 * One day, marked.
 *
 * This is where the app's central idea lives: every class is a strip of one-hour chips, and
 * a two-hour lab you left halfway through is two taps away from being recorded as exactly
 * that. The dashboard's one-tap path handles the common day; this screen handles every
 * other one — a class cancelled, a lab shortened, a lecture moved to Saturday.
 *
 * Toggling a chip does not commit the day. Attendance and status are separate in
 * [com.attendo.core.engine.SessionOps] precisely so a student can set the whole day up and
 * then approve it, rather than each tap being a decision that counts immediately.
 */
@Composable
fun DayReviewScreen(
    date: LocalDate,
    onBack: () -> Unit,
    viewModel: DayReviewViewModel = viewModel(
        factory = DayReviewViewModel.factory(date),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DayDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = state.date.relativeLabel(state.today),
            subtitle = state.date.longLabel(),
            onBack = onBack,
            actions = {
                if (state.date != state.today) {
                    IconButton(onClick = viewModel::goToToday) {
                        Icon(AttendoIcons.Today, contentDescription = "Jump to today")
                    }
                }
                IconButton(onClick = { dialog = DayDialog.PickDate }) {
                    Icon(AttendoIcons.CalendarMonth, contentDescription = "Pick a date")
                }
            },
        )

        DayStepper(
            onPrevious = viewModel::previousDay,
            onNext = viewModel::nextDay,
            label = state.date.relativeLabel(state.today),
        )

        if (!state.loaded) {
            LoadingPane()
        } else {
            DayContent(
                state = state,
                viewModel = viewModel,
                onDialog = { dialog = it },
            )
        }
    }

    DayDialogHost(
        dialog = dialog,
        state = state,
        viewModel = viewModel,
        onDismiss = { dialog = null },
    )
}

@Composable
private fun DayContent(
    state: DayReviewUiState,
    viewModel: DayReviewViewModel,
    onDialog: (DayDialog) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.rows.isNotEmpty()) {
            item {
                DaySummaryCard(
                    state = state,
                    onApproveAll = viewModel::approveAll,
                    onMarkAllAbsent = { onDialog(DayDialog.MissedAll) },
                    onCancelAll = { onDialog(DayDialog.CancelAll) },
                )
            }
        }

        // Possible timetable changes and day polls are gone by design: the community
        // feature lives in the Rooms tab (rooms and their questions), and the day
        // view stays purely the student's own record. A community report was advice
        // about the world; it never marked, cancelled or moved anything here.

        items(state.rows, key = { it.key }) { row ->
            SessionCard(
                row = row,
                code = state.codeOf(row.courseId),
                name = state.nameOf(row.courseId),
                onToggleUnit = { index -> viewModel.toggleUnit(row, index) },
                onApprove = { viewModel.approve(row) },
                onAbsent = { viewModel.markAbsent(row) },
                onReopen = { viewModel.reopen(row) },
                onDialog = onDialog,
                onDelete = { viewModel.delete(row) },
            )
        }

        if (state.rows.isEmpty()) {
            item {
                EmptyState(
                    icon = AttendoIcons.CalendarMonth,
                    title = if (state.isTeachingDay) "Nothing timetabled" else "Not a teaching day",
                    body = if (state.isTeachingDay) {
                        "No classes on this day. You can still add an extra one."
                    } else {
                        state.calendar.notTeachingReason(state.date) +
                            ". The timetable is not applied, so nothing counts here."
                    },
                )
            }
        }

        item {
            OutlinedButton(
                onClick = { onDialog(DayDialog.AddExtra) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.addableCourses.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Add an extra class")
            }
        }
    }
}

/** Yesterday / tomorrow without a trip through the back stack. */
@Composable
private fun DayStepper(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
        }
    }
}

/**
 * The day in one line, plus the two bulk actions.
 *
 * "Mark all present" is the same action the dashboard offers, repeated here because this is
 * where a student lands from the backlog card and the whole point is that a normal day
 * takes one tap even when it is a week old.
 */
@Composable
private fun DaySummaryCard(
    state: DayReviewUiState,
    onApproveAll: () -> Unit,
    onMarkAllAbsent: () -> Unit,
    onCancelAll: () -> Unit,
) {
    val day = state.day
    val summary = when {
        day != null && !day.tally.isEmpty ->
            "${day.tally.unitsAttended} of ${hours(day.tally.unitsHeld)} attended"
        !state.isFuture && state.unitsPending > 0 -> "${hours(state.unitsPending)} to mark"
        else -> "Nothing counted"
    }
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MarkSwatch(day?.mark ?: DayMark.NO_CLASS, size = 12.dp)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (day != null && day.unitsCancelled > 0) {
                    Text(
                        text = "${hours(day.unitsCancelled)} cancelled",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // A future day has nothing to confirm yet — its classes have not happened, and
            // approving them would put attendance on the record before the fact.
            if (state.isFuture) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Still to come. Mark it once the day is over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (state.unitsPending > 0) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onApproveAll, modifier = Modifier.fillMaxWidth()) {
                        Icon(AttendoIcons.DoneAll, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Mark all present · ${hours(state.unitsPending)}")
                    }
                    TextButton(onClick = onMarkAllAbsent, modifier = Modifier.fillMaxWidth()) {
                        Text("I missed this whole day")
                    }
                }
                TextButton(onClick = onCancelAll) { Text("Cancel the whole day") }
            }
        }
    }
}

/**
 * One class: its hours as chips, its status, and everything unusual behind the overflow.
 *
 * A cancelled class keeps its chips visible but disabled — seeing that it *was* a two-hour
 * lab is the difference between "cancelled" and "vanished".
 */
@Composable
private fun SessionCard(
    row: DayRow,
    code: String,
    name: String,
    onToggleUnit: (Int) -> Unit,
    onApprove: () -> Unit,
    onAbsent: () -> Unit,
    onReopen: () -> Unit,
    onDialog: (DayDialog) -> Unit,
    onDelete: () -> Unit,
) {
    val mark = row.mark()
    val stored = (row as? DayRow.Stored)?.session
    val cancelled = stored?.isCancelled == true
    val awaiting = mark == DayMark.AWAITING_REVIEW
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        colors = if (cancelled) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarkSwatch(mark, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = code, style = MaterialTheme.typography.titleSmall)
                        KindTag(row.kind)
                    }
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    SessionMenu(
                        expanded = menuOpen,
                        row = row,
                        cancelled = cancelled,
                        onDismiss = { menuOpen = false },
                        onDialog = onDialog,
                        onReopen = onReopen,
                        onDelete = onDelete,
                    )
                }
            }

            Text(
                text = listOfNotNull(
                    row.slotLabel,
                    row.room,
                    stored?.originLabel(),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
            )

            stored?.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            UnitToggles(
                labels = row.unitLabels,
                isAttended = { index -> row.isUnitAttended(index) },
                onToggle = onToggleUnit,
                enabled = !cancelled,
                modifier = Modifier.padding(end = 12.dp),
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.statusLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (awaiting) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        bands.contentFor(mark)
                    },
                    modifier = Modifier.weight(1f),
                )
                when {
                    cancelled -> TextButton(onClick = onReopen) { Text("Reopen") }
                    awaiting -> {
                        TextButton(onClick = onAbsent) { Text("Missed it") }
                        Button(onClick = onApprove) { Text("Confirm") }
                    }
                    else -> TextButton(onClick = onReopen) { Text("Unmark") }
                }
            }
        }
    }
}

@Composable
private fun SessionMenu(
    expanded: Boolean,
    row: DayRow,
    cancelled: Boolean,
    onDismiss: () -> Unit,
    onDialog: (DayDialog) -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit,
) {
    val isAdhoc = (row as? DayRow.Stored)?.session?.patternId == null

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!cancelled) {
            MenuItem("Cancel this class…", onDismiss) { onDialog(DayDialog.Cancel(row)) }
        } else {
            MenuItem("Reopen", onDismiss, onReopen)
        }
        MenuItem("Change length…", onDismiss) { onDialog(DayDialog.Resize(row)) }
        MenuItem("Move to another hour…", onDismiss) { onDialog(DayDialog.Move(row)) }
        MenuItem("Move to another day…", onDismiss) { onDialog(DayDialog.Reschedule(row)) }
        MenuItem("Note…", onDismiss) { onDialog(DayDialog.Note(row)) }
        if (isAdhoc) {
            MenuItem("Delete", onDismiss, onDelete)
        }
    }
}

/** A menu row that closes the menu before acting, so the dialog it opens is not covered. */
@Composable
private fun MenuItem(
    label: String,
    onDismiss: () -> Unit,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            onDismiss()
            action()
        },
    )
}

// ---- dialogs ---------------------------------------------------------------

/**
 * Which dialog is open.
 *
 * The row is captured by value rather than looked up again by key. A dialog is modal and
 * short-lived, and every action it can take goes through
 * [DayReviewViewModel.materialised], which re-reads the stored row before writing — so a
 * snapshot here cannot commit a stale mask.
 */
private sealed interface DayDialog {
    data object PickDate : DayDialog
    data object AddExtra : DayDialog
    data object CancelAll : DayDialog
    data object MissedAll : DayDialog
    data class Cancel(val row: DayRow) : DayDialog
    data class Resize(val row: DayRow) : DayDialog
    data class Move(val row: DayRow) : DayDialog
    data class Reschedule(val row: DayRow) : DayDialog
    data class Note(val row: DayRow) : DayDialog
}

@Composable
private fun DayDialogHost(
    dialog: DayDialog?,
    state: DayReviewUiState,
    viewModel: DayReviewViewModel,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit

        DayDialog.PickDate -> AttendoDatePickerDialog(
            initial = state.date,
            onPick = viewModel::goTo,
            onDismiss = onDismiss,
        )

        DayDialog.CancelAll -> CancelReasonDialog(
            title = "Cancel every class",
            body = "All ${classes(state.rows.size)} on ${state.date.shortLabel()} stop " +
                "counting, on both sides of the fraction.",
            onPick = { reason ->
                viewModel.cancelAll(reason)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        DayDialog.MissedAll -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Miss the whole day?") },
            text = {
                Text(
                    "Every class still to be marked on ${state.date.shortLabel()} will be " +
                        "recorded as missed. That's ${hours(state.unitsPending)} in all. They still " +
                        "count as held, so they lower your attendance.\n\n" +
                        "Classes you have already marked are left alone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markAllAbsent()
                    onDismiss()
                }) {
                    Text("I missed them all")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )

        is DayDialog.Cancel -> CancelReasonDialog(
            title = "Cancel this class",
            body = "It stops counting on both sides of the fraction. Neither attended nor missed.",
            onPick = { reason ->
                viewModel.cancel(dialog.row, reason)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is DayDialog.Resize -> ResizeDialog(
            row = dialog.row,
            onPick = { units ->
                viewModel.resize(dialog.row, units)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is DayDialog.Move -> MoveHourDialog(
            row = dialog.row,
            onPick = { hour ->
                viewModel.moveSlot(dialog.row, hour)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is DayDialog.Reschedule -> RescheduleDialog(
            row = dialog.row,
            from = state.date,
            calendar = state.calendar,
            onConfirm = { newDate, hour, units ->
                viewModel.reschedule(dialog.row, newDate, hour, units)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is DayDialog.Note -> NoteDialog(
            initial = (dialog.row as? DayRow.Stored)?.session?.note.orEmpty(),
            onConfirm = { note ->
                viewModel.setNote(dialog.row, note)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        DayDialog.AddExtra -> AddExtraDialog(
            courses = state.addableCourses,
            onConfirm = { courseId, hour, units, kind, room ->
                viewModel.addExtraClass(courseId, hour, units, kind, room)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Why a class did not happen.
 *
 * The reason is asked for rather than assumed because it is the only thing that
 * distinguishes a holiday from a lecturer who did not turn up, and a student comparing
 * their record against the department's will want to know which.
 */
@Composable
private fun CancelReasonDialog(
    title: String,
    body: String,
    onPick: (CancellationReason) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                listOf(
                    CancellationReason.FACULTY_CANCELLED to "Faculty cancelled it",
                    CancellationReason.HOLIDAY to "Holiday",
                    CancellationReason.OTHER to "Something else",
                ).forEach { (reason, label) ->
                    TextButton(
                        onClick = { onPick(reason) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}/** A two-hour lab that ran for one. Re-clamps the mask in `:core`, so hours cannot be lost. */
@Composable
private fun ResizeDialog(
    row: DayRow,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var units by rememberSaveable { mutableStateOf(row.unitsPlanned) }
    val options = (1..TimeGrid.MAX_UNITS_PER_SESSION).filter { TimeGrid.fits(row.startHour, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How long did it run?") },
        text = {
            Column {
                ChipChoice(
                    options = options,
                    selected = units,
                    label = { hours(it) },
                    onSelect = { units = it },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = TimeGrid.rangeLabel(row.startHour, units),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(units) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Same day, different hour — the "it moved to the afternoon" case. */
@Composable
private fun MoveHourDialog(
    row: DayRow,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by rememberSaveable { mutableStateOf(row.startHour) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to another hour") },
        text = {
            Column {
                ChipChoice(
                    options = TimeGrid.startHours,
                    selected = hour,
                    label = { TimeGrid.slotLabel(it) },
                    onSelect = { hour = it },
                    enabled = { TimeGrid.fits(it, row.unitsPlanned) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = TimeGrid.rangeLabel(hour, row.unitsPlanned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(hour) },
                enabled = TimeGrid.fits(hour, row.unitsPlanned),
            ) {
                Text("Move")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A class moved to another day.
 *
 * This leaves two rows behind — the original cancelled as `RESCHEDULED` and a replacement
 * on the new date — because a class that moved is two facts about the term, not an edit to
 * one. The dialog says so, since "move" otherwise suggests the original disappears.
 */
@Composable
private fun RescheduleDialog(
    row: DayRow,
    from: LocalDate,
    calendar: AcademicCalendar,
    onConfirm: (LocalDate, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var newDate by rememberSaveable { mutableStateOf(from.plusDays(1)) }
    var hour by rememberSaveable { mutableStateOf(row.startHour) }
    var units by rememberSaveable { mutableStateOf(row.unitsPlanned) }
    var pickingDate by remember { mutableStateOf(false) }

    if (pickingDate) {
        AttendoDatePickerDialog(
            initial = newDate,
            onPick = { newDate = it },
            onDismiss = { pickingDate = false },
            earliest = calendar.termStart,
            latest = calendar.termEnd,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to another day") },
        text = {
            Column {
                OutlinedButton(
                    onClick = { pickingDate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AttendoIcons.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(newDate.longLabel())
                }
                Spacer(Modifier.height(12.dp))
                Text("Starts at", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = TimeGrid.startHours,
                    selected = hour,
                    label = { TimeGrid.slotLabel(it) },
                    onSelect = { hour = it },
                    enabled = { TimeGrid.fits(it, units) },
                )
                Spacer(Modifier.height(12.dp))
                Text("Length", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = (1..TimeGrid.MAX_UNITS_PER_SESSION).toList(),
                    selected = units,
                    label = { hours(it) },
                    onSelect = { units = it },
                    enabled = { TimeGrid.fits(hour, it) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The original stays on record as moved, so the week it was " +
                        "timetabled for still reads correctly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newDate, hour, units) },
                enabled = TimeGrid.fits(hour, units) && newDate != from,
            ) {
                Text("Move")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoteDialog(
    initial: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Why this class was unusual") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.ifBlank { null }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A class nobody timetabled: a makeup lecture, an extra lab.
 *
 * Ad-hoc rows carry no `patternId`, so they are never regenerated and are the only rows the
 * review screen offers to delete.
 */
@Composable
private fun AddExtraDialog(
    courses: List<Course>,
    onConfirm: (Long, Int, Int, SessionKind, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var courseId by rememberSaveable { mutableStateOf(courses.firstOrNull()?.id ?: 0L) }
    var hour by rememberSaveable { mutableStateOf(TimeGrid.FIRST_START_HOUR) }
    var units by rememberSaveable { mutableStateOf(1) }
    var kind by rememberSaveable { mutableStateOf(SessionKind.LECTURE) }
    var room by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an extra class") },
        text = {
            Column {
                Text("Course", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = courses,
                    selected = courses.firstOrNull { it.id == courseId },
                    label = { it.code },
                    onSelect = { courseId = it.id },
                )
                Spacer(Modifier.height(12.dp))
                Text("Starts at", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = TimeGrid.startHours,
                    selected = hour,
                    label = { TimeGrid.slotLabel(it) },
                    onSelect = { hour = it },
                    enabled = { TimeGrid.fits(it, units) },
                )
                Spacer(Modifier.height(12.dp))
                Text("Length", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = (1..TimeGrid.MAX_UNITS_PER_SESSION).toList(),
                    selected = units,
                    label = { hours(it) },
                    onSelect = { units = it },
                    enabled = { TimeGrid.fits(hour, it) },
                )
                Spacer(Modifier.height(12.dp))
                Text("Kind", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = SessionKind.entries.toList(),
                    selected = kind,
                    label = { it.label() },
                    onSelect = { kind = it },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Room (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(courseId, hour, units, kind, room) },
                enabled = courseId != 0L && TimeGrid.fits(hour, units),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
