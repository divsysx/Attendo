package com.attendo.ui.attendance

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.data.SeedClash
import com.attendo.core.data.SeedProposal
import com.attendo.ui.AttendoIcons
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ChipChoice
import com.attendo.ui.components.EmptyState
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.courses
import com.attendo.ui.hours

/** The chip that stands for "I want both batches' classes", where batch is null. */
private const val ALL_BATCHES = "All"

/**
 * Creates a term's courses from one section's page of the printed timetable.
 *
 * Nothing is written until the button at the bottom: the grid knows the section's classes
 * but not which lab batch the student is in, nor which of the courses on the page they
 * actually take, so the screen proposes and the student confirms.
 */
@Composable
fun SeedScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: SeedViewModel = viewModel(factory = SeedViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.created) {
        if (state.created != null) onDone()
    }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(title = "Seed from timetable", onBack = onBack)

        when {
            !state.loaded -> LoadingPane()

            state.sections.isEmpty() -> EmptyState(
                icon = AttendoIcons.AutoAwesome,
                title = "No timetable to read",
                body = "The bundled timetable has no sections in it. Courses can still be " +
                    "added by hand.",
            )

            else -> SeedContent(
                state = state,
                onSection = viewModel::setSection,
                onBatch = viewModel::setBatch,
                onToggle = viewModel::toggle,
                onAll = viewModel::selectAll,
                onNone = viewModel::selectNone,
                onApply = viewModel::apply,
            )
        }
    }
}

@Composable
private fun SeedContent(
    state: SeedUiState,
    onSection: (String) -> Unit,
    onBatch: (String?) -> Unit,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onApply: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionLabel("Your section")
        }
        item {
            ChipChoice(
                options = state.sections,
                selected = state.section,
                label = { it },
                onSelect = onSection,
            )
        }

        if (state.batchOptions.isNotEmpty()) {
            item { SectionLabel("Your lab batch") }
            item {
                Column {
                    ChipChoice(
                        options = listOf(ALL_BATCHES) + state.batchOptions,
                        selected = state.batch ?: ALL_BATCHES,
                        label = { it },
                        onSelect = { picked -> onBatch(picked.takeUnless { it == ALL_BATCHES }) },
                    )
                    if (state.batch == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Labs are split by batch. Until you pick one, every batch's " +
                                "classes are proposed and they will look like clashes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.section == null) {
            item {
                Text(
                    text = "Pick your section to see what it is timetabled for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.proposals.isEmpty()) {
            item {
                Text(
                    text = "Nothing timetabled for ${state.section}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                SectionLabel("Courses found") {
                    Row {
                        TextButton(onClick = onAll) { Text("All") }
                        TextButton(onClick = onNone) { Text("None") }
                    }
                }
            }

            items(state.proposals, key = { it.course.code }) { proposal ->
                ProposalRow(
                    proposal = proposal,
                    checked = proposal.course.code in state.selected,
                    tracked = state.isTracked(proposal),
                    // A batch was chosen and this subject is still split: it is numbered on
                    // a scheme the choice said nothing about, so the student has to look.
                    note = proposal.batches
                        .takeIf { state.batch != null && proposal.needsBatchCheck }
                        ?.let { "Split ${it.joinToString(" / ")} — check which one is yours" },
                    onToggle = { onToggle(proposal.course.code) },
                )
            }

            state.plan?.clashes?.takeIf { it.isNotEmpty() }?.let { clashes ->
                item {
                    Column {
                        SectionLabel("Two classes at once", Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "The grid puts ${state.section} in two places at these hours. " +
                                "Usually that means you still have a batch to pick — the classes " +
                                "are created either way, and the ones you do not attend can be " +
                                "cancelled or the course archived.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(clashes, key = { "${it.dayOfWeek}-${it.startHour}" }) { clash ->
                    ClashRow(clash)
                }
            }

            item {
                Column {
                    HorizontalDivider(Modifier.padding(bottom = 12.dp))
                    Button(
                        onClick = onApply,
                        enabled = state.canApply,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.canApply) {
                                "Create ${courses(state.chosen.size)}"
                            } else {
                                "Nothing ticked"
                            },
                        )
                    }
                    if (state.canApply) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${hours(state.unitsChosen)} a week, and the classes since " +
                                "the semester started will be waiting to mark.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.problems > 0) {
            item {
                Text(
                    text = "${state.problems} line${if (state.problems == 1) "" else "s"} of the " +
                        "timetable could not be read, so whatever ${if (state.problems == 1) "it" else "they"} " +
                        "listed is missing here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: SeedProposal,
    checked: Boolean,
    tracked: Boolean,
    note: String?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tracked) Modifier else Modifier.clickable(onClick = onToggle))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked && !tracked,
            onCheckedChange = if (tracked) null else { _ -> onToggle() },
            enabled = !tracked,
        )
        Column(Modifier.weight(1f)) {
            Text(text = proposal.course.code, style = MaterialTheme.typography.titleSmall)
            if (proposal.course.name != proposal.course.code) {
                Text(
                    text = proposal.course.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = proposal.scheduleLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = if (tracked) "tracked" else "${proposal.unitsPerWeek}h",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClashRow(clash: SeedClash) {
    Text(
        text = clash.label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
