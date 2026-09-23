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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.engine.HistoryMonth
import com.attendo.core.engine.HistoryRow
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionPattern
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.BandLegend
import com.attendo.ui.components.KindTag
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.MarkSwatch
import com.attendo.ui.components.MonthCalendar
import com.attendo.ui.components.PercentHeadline
import com.attendo.ui.components.PercentPicker
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.components.TargetAdviceLine
import com.attendo.ui.classes
import com.attendo.ui.dayMonth
import com.attendo.ui.display
import com.attendo.ui.hours
import com.attendo.ui.label
import com.attendo.ui.mark
import com.attendo.ui.shortLabel
import com.attendo.ui.statusLabel
import com.attendo.ui.theme.bands
import java.time.LocalDate

/**
 * Everything about one subject: the number, the month, and the log behind it.
 *
 * The log is the part that earns trust. A percentage a student cannot audit is a percentage
 * they will not believe, so every row shows what it contributed and what the running total
 * was after it.
 */
@Composable
fun CourseDetailScreen(
    courseId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    viewModel: CourseDetailViewModel = viewModel(
        factory = CourseDetailViewModel.factory(courseId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf(false) }

    // The course can vanish under this screen — deleted from the course list while it sits
    // on the back stack. Popping is better than showing an empty shell.
    if (state.missing) {
        onBack()
        return
    }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = state.course?.code ?: "",
            subtitle = state.course?.name,
            onBack = onBack,
            actions = {
                IconButton(onClick = { onEdit(courseId) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit course")
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val archived = state.course?.archived == true
                    DropdownMenuItem(
                        text = { Text(if (archived) "Unarchive" else "Archive") },
                        onClick = {
                            menuOpen = false
                            viewModel.setArchived(!archived)
                        },
                    )
                }
            },
        )

        if (!state.loaded || state.stats == null) {
            LoadingPane()
        } else {
            CourseDetailContent(
                state = state,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onOpenDay = onOpenDay,
                onEditTarget = { editingTarget = true },
            )
        }
    }

    if (editingTarget) {
        val current = state.course?.targetPercent ?: Percent.DEFAULT_TARGET
        TargetDialog(
            current = current,
            onConfirm = { chosen ->
                viewModel.setTarget(chosen)
                editingTarget = false
            },
            onDismiss = { editingTarget = false },
        )
    }
}

@Composable
private fun CourseDetailContent(
    state: CourseDetailUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onEditTarget: () -> Unit,
) {
    val stats = state.stats ?: return
    val course = state.course ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    PercentHeadline(
                        percent = stats.percent,
                        tally = stats.tally,
                        target = course.targetPercent,
                    )
                    Spacer(Modifier.height(8.dp))
                    TargetAdviceLine(stats.target)
                    // Set only when the figure is not simply "the whole of this course" —
                    // a finished semester, or a term joined partway through. The row that
                    // led here shows the same number, and this says what it covers.
                    state.scopeLabel?.let { scope ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = scope,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stats.sessionsAwaitingReview > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${classes(stats.sessionsAwaitingReview)} still to mark. " +
                                "They count for nothing until you do.",
                            style = MaterialTheme.typography.bodySmall,
                            color = bands.onPending,
                        )
                    }
                }
            }
        }

        item {
            TargetRow(target = course.targetPercent, onEdit = onEditTarget)
        }

        if (state.remainingUnits > 0) {
            item { ProjectionCard(state) }
        }

        if (state.patterns.isNotEmpty()) {
            item { SectionLabel("Weekly slots") }
            item { WeeklySlots(state.patterns) }
        }

        item {
            SectionLabel(state.month.label()) {
                Row {
                    IconButton(onClick = onPreviousMonth) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous month",
                        )
                    }
                    IconButton(onClick = onNextMonth) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next month",
                        )
                    }
                }
            }
        }

        state.grid?.let { grid ->
            item {
                Column {
                    MonthCalendar(
                        grid = grid,
                        today = state.today,
                        onSelect = onOpenDay,
                    )
                    Spacer(Modifier.height(8.dp))
                    BandLegend(grid)
                    if (!grid.tally.isEmpty) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "This month: ${grid.tally.unitsAttended} of " +
                                "${hours(grid.tally.unitsHeld)} · ${grid.percent.display()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.history.isEmpty()) {
            item {
                Text(
                    text = "Nothing logged yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        state.history.forEach { historyMonth ->
            item(key = "header-${historyMonth.month}") {
                HistoryMonthHeader(historyMonth)
            }
            items(
                count = historyMonth.rows.size,
                key = { index -> historyMonth.rows[index].session.id },
            ) { index ->
                HistoryRowItem(
                    row = historyMonth.rows[index],
                    onClick = { onOpenDay(historyMonth.rows[index].session.date) },
                )
            }
        }
    }
}

/**
 * This course's own target, and the way to change it.
 *
 * A course is judged against its own target rather than the app-wide default — a lab and a
 * theory paper can reasonably want different numbers, and the figure on the Attendance tab
 * is a third thing again. Printing the value here rather than only inside the dialog means
 * the screen always answers "what is this measured against?" without a tap.
 */
@Composable
private fun TargetRow(
    target: Percent,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "Target", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "What this course is judged against. Not the default for new courses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${target.format(0)}%",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Any whole percentage from 0 to 100, chosen with the same control the rest of the app uses.
 *
 * The dialog holds the choice until "Set" rather than writing on each drag, so a student who
 * opens it, moves the slider to see what a number would mean, and backs out has changed
 * nothing. [PercentPicker] is what keeps an existing fractional target readable in the
 * meantime: it shows the exact stored figure and still writes nothing until a whole
 * percentage is actually picked.
 */
@Composable
private fun TargetDialog(
    current: Percent,
    onConfirm: (Percent) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by rememberSaveable { mutableIntStateOf(current.basisPoints) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target for this course") },
        text = {
            Column {
                Text(
                    text = "Any whole percentage from 0 to 100. This changes this course " +
                        "alone — every other course keeps its own target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PercentPicker(
                    value = Percent.ofBasisPoints(chosen),
                    onSelect = { picked -> chosen = picked.basisPoints },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(Percent.ofBasisPoints(chosen)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The two numbers a student actually plans against.
 *
 * Both are computed from the weekly patterns rather than from stored rows, because the
 * future has no rows — sessions are generated only up to today.
 */
@Composable
private fun ProjectionCard(state: CourseDetailUiState) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "${hours(state.remainingUnits)} left this semester",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Projection(
                    label = "Attend everything",
                    percent = state.ifAllAttended,
                    color = bands.onFull,
                )
                Projection(
                    label = "Miss everything",
                    percent = state.ifAllMissed,
                    color = bands.onAbsent,
                )
            }
        }
    }
}

@Composable
private fun Projection(
    label: String,
    percent: Percent?,
    color: Color,
) {
    Column {
        Text(
            text = percent.display(),
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The timetable behind the course, so a wrong number can be traced to a wrong slot. */
@Composable
private fun WeeklySlots(patterns: List<SessionPattern>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        patterns.forEach { pattern ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = pattern.dayOfWeek.shortLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    text = pattern.slotLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                KindTag(pattern.kind)
                pattern.room?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!pattern.isOpenEnded) {
                    Text(
                        text = "until ${pattern.effectiveTo?.dayMonth()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryMonthHeader(month: HistoryMonth) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = month.month.label(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (month.tally.isEmpty) {
                    "nothing held"
                } else {
                    "${month.tally.unitsAttended}/${month.tally.unitsHeld} · " +
                        month.tally.percent.display(0)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One logged class: what it was, what it contributed, and where the total stood after it.
 *
 * The running percentage is the column that makes a drop explicable — a student can see
 * the exact week the number started sliding.
 */
@Composable
private fun HistoryRowItem(
    row: HistoryRow,
    onClick: () -> Unit,
) {
    val session = row.session
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MarkSwatch(session.mark())
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = session.date.shortLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                KindTag(session.kind)
            }
            Text(
                text = session.slotLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (session.countsTowardAttendance) row.unitsLabel else session.statusLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = bands.contentFor(session.mark()),
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = row.runningPercent.display(0),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}
