package com.attendo.ui.attendance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.core.model.TimeGrid
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ChipChoice
import com.attendo.ui.components.KindTag
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.components.rememberEditableText
import com.attendo.ui.hours
import com.attendo.ui.label
import com.attendo.ui.shortLabel
import java.time.DayOfWeek

/** The targets worth offering. 75% is the one most attendance rules actually use. */
private val TARGET_OPTIONS: List<Percent> =
    listOf(50.0, 60.0, 65.0, 70.0, 75.0, 80.0, 85.0, 90.0).map { Percent.ofPercent(it) }

/** Sunday never teaches, so it is never offered. */
private val SLOT_DAYS: List<DayOfWeek> = DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

/**
 * Add or correct one course, along with the weekly slots that generate its classes.
 *
 * The slots are on this screen rather than behind another tap because a course with no slot
 * produces no class and therefore no attendance — the two belong in the same act of setup.
 */
@Composable
fun CourseEditorScreen(
    courseId: Long,
    onDone: () -> Unit,
    viewModel: CourseEditorViewModel = viewModel(
        factory = CourseEditorViewModel.factory(courseId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SlotDraft?>(null) }
    var addingSlot by remember { mutableStateOf(false) }

    // Seeded once the course has been read — an existing one arrives a moment after the first
    // frame — and owned here afterwards. Typing a code used to go out through the editor's
    // state, get combined with a Room flow of the course's patterns, and come back a frame
    // later as this field's value, which is how a caret ends up behind the word. The
    // capitalising keyboard on the code field made it worse, since the IME had its own idea of
    // the composing region.
    var code by rememberEditableText(state.code, state.loaded)
    var name by rememberEditableText(state.name, state.loaded)

    // The write is the screen's whole purpose, so it leaves the moment the write lands.
    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = if (state.isNew) "New course" else state.code.ifBlank { "Course" },
            subtitle = if (state.isNew) null else state.name.ifBlank { null },
            onBack = onDone,
            actions = {
                TextButton(onClick = viewModel::save, enabled = state.canSave) {
                    Text("Save")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { edit ->
                    code = edit
                    viewModel.setCode(edit.text)
                },
                label = { Text("Code") },
                placeholder = { Text("CS201") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                supportingText = { Text("How it appears in the timetable.") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { edit ->
                    name = edit
                    viewModel.setName(edit.text)
                },
                label = { Text("Name") },
                placeholder = { Text("Data Structures") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Target")
            // A target the seeder or an older build set — 72%, say — is folded into the
            // options, so the chip row always shows what the course is actually aiming at.
            val targets = remember(state.target) {
                (TARGET_OPTIONS + state.target).distinct().sorted()
            }
            ChipChoice(
                options = targets,
                selected = state.target,
                label = { "${it.format(0)}%" },
                onSelect = viewModel::setTarget,
            )

            if (!state.isNew) {
                HorizontalDivider(Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Archived", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Keeps the record, stops it counting and stops generating " +
                                "classes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.archived, onCheckedChange = viewModel::setArchived)
                }
            }

            HorizontalDivider(Modifier.padding(top = 4.dp))

            SectionLabel("Weekly slots") {
                IconButton(onClick = { addingSlot = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a slot")
                }
            }

            if (state.slots.isEmpty()) {
                Text(
                    text = "No slots yet. Without one, this course never produces a class to " +
                        "mark.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.slots.forEach { slot ->
                    SlotRow(
                        slot = slot,
                        onClick = { editing = slot },
                        onRemove = { viewModel.removeSlot(slot) },
                    )
                }
                Text(
                    text = "${hours(state.unitsPerWeek)} a week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isNew && state.slots.isNotEmpty()) {
                Text(
                    text = "Slots are written when you save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (addingSlot) {
        SlotDialog(
            initial = SlotDraft(),
            occupied = { day, id -> state.slots.occupiedHours(day, id) },
            onConfirm = {
                viewModel.addSlot(it)
                addingSlot = false
            },
            onDismiss = { addingSlot = false },
        )
    }

    editing?.let { slot ->
        SlotDialog(
            initial = slot,
            occupied = { day, id -> state.slots.occupiedHours(day, id) },
            onConfirm = {
                viewModel.updateSlot(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SlotRow(
    slot: SlotDraft,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = slot.dayOfWeek.shortLabel(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = slot.slotLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        KindTag(slot.kind)
        if (slot.room.isNotBlank()) {
            Text(
                text = slot.room,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove this slot")
        }
    }
}

/**
 * One weekly slot: day, hour, length, kind, room.
 *
 * The length chips and start-hour chips police each other through [TimeGrid.fits], so a
 * three-hour lab cannot be placed at 5pm when the teaching day ends at 6.
 */
@Composable
private fun SlotDialog(
    initial: SlotDraft,
    occupied: (DayOfWeek, Long) -> Set<Int>,
    onConfirm: (SlotDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val taken = occupied(draft.dayOfWeek, draft.patternId)
    val clashes = (draft.startHour until draft.startHour + draft.units).any { it in taken }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isNew) "Add a slot" else "Change this slot") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Day", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = SLOT_DAYS,
                    selected = draft.dayOfWeek,
                    label = { it.shortLabel() },
                    onSelect = { draft = draft.copy(dayOfWeek = it) },
                )

                Spacer(Modifier.height(12.dp))
                Text("Starts at", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = TimeGrid.startHours,
                    selected = draft.startHour,
                    label = { TimeGrid.slotLabel(it) },
                    onSelect = { draft = draft.copy(startHour = it) },
                    enabled = { TimeGrid.fits(it, draft.units) },
                )

                Spacer(Modifier.height(12.dp))
                Text("Length", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = (1..TimeGrid.MAX_UNITS_PER_SESSION).toList(),
                    selected = draft.units,
                    label = { hours(it) },
                    onSelect = { draft = draft.copy(units = it) },
                    enabled = { TimeGrid.fits(draft.startHour, it) },
                )

                Spacer(Modifier.height(12.dp))
                Text("Kind", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = SessionKind.entries.toList(),
                    selected = draft.kind,
                    label = { it.label() },
                    onSelect = { draft = draft.copy(kind = it) },
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.room,
                    onValueChange = { draft = draft.copy(room = it) },
                    label = { Text("Room (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (clashes) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Another slot on this course already covers that hour.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                enabled = draft.fits && !clashes,
            ) {
                Text(if (initial.isNew) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The hours a course's other slots already cover on one weekday, so two of its own classes
 * are not booked into the same hour.
 *
 * [except] is the slot being edited, which does not clash with itself.
 */
private fun List<SlotDraft>.occupiedHours(day: DayOfWeek, except: Long): Set<Int> =
    filter { it.dayOfWeek == day && it.patternId != except }
        .flatMap { slot -> slot.startHour until slot.startHour + slot.units }
        .toSet()
