package com.attendo.ui.attendance

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.engine.CourseStats
import com.attendo.core.model.Course
import com.attendo.ui.AttendoIcons
import com.attendo.ui.components.AttendanceBar
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ConfirmDialog
import com.attendo.ui.components.EmptyState
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.display
import com.attendo.ui.hours
import com.attendo.ui.theme.bands

/**
 * What is being tracked.
 *
 * Reached from the dashboard rather than living in the bottom bar: it is a setup screen, and
 * after the first week of term a student has no reason to open it.
 */
@Composable
fun CoursesScreen(
    onBack: () -> Unit,
    onOpenCourse: (Long) -> Unit,
    onEditCourse: (Long) -> Unit,
    onAddCourse: () -> Unit,
    onSeed: () -> Unit,
    viewModel: CoursesViewModel = viewModel(factory = CoursesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Course?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AttendoTopBar(
                title = "Courses",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onSeed) {
                        Icon(AttendoIcons.AutoAwesome, contentDescription = "Seed from timetable")
                    }
                },
            )

            if (state.isEmpty) {
                EmptyState(
                    icon = AttendoIcons.School,
                    title = "Nothing tracked yet",
                    body = "Pull your subjects out of the faculty timetable, or add them one " +
                        "at a time.",
                    action = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = onSeed) { Text("Seed from timetable") }
                            OutlinedButton(onClick = onAddCourse) { Text("Add by hand") }
                        }
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        // Clears the FAB.
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.current?.let { current ->
                        if (current.semester != null) {
                            item(key = "current-semester") { SemesterHeader(current) }
                        }
                    }

                    items(state.active, key = { it.course.id }) { stats ->
                        CourseListRow(
                            stats = stats,
                            onClick = { onOpenCourse(stats.course.id) },
                            onEdit = { onEditCourse(stats.course.id) },
                            onArchive = { viewModel.setArchived(stats.course, true) },
                            onDelete = { confirmDelete = stats.course },
                        )
                    }

                    if (state.archived.isNotEmpty()) {
                        item {
                            SectionLabel("Archived", modifier = Modifier.padding(top = 16.dp))
                        }
                        items(state.archived, key = { it.id }) { course ->
                            ArchivedRow(
                                course = course,
                                onOpen = { onOpenCourse(course.id) },
                                onUnarchive = { viewModel.setArchived(course, false) },
                                onDelete = { confirmDelete = course },
                            )
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddCourse,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Course")
        }
    }

    confirmDelete?.let { course ->
        ConfirmDialog(
            title = "Delete ${course.code}?",
            body = "Every class and every mark for this course goes with it. Archiving keeps " +
                "the record and stops it counting.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(course) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun CourseListRow(
    stats: CourseStats,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = stats.course.code, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stats.course.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stats.percent.display(),
                style = MaterialTheme.typography.titleMedium,
                color = if (stats.isBelowTarget && stats.percent != null) {
                    bands.onAbsent
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        onClick = {
                            menuOpen = false
                            onArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        AttendanceBar(
            tally = stats.tally,
            target = stats.course.targetPercent,
            height = 6.dp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("target ${stats.course.targetPercent.format(0)}%")
                if (!stats.tally.isEmpty) {
                    append(" · ${stats.tally.unitsAttended} of ${hours(stats.tally.unitsHeld)}")
                }
                if (stats.sessionsAwaitingReview > 0) {
                    append(" · ${stats.sessionsAwaitingReview} to mark")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One semester's name, dates and figure.
 *
 * The percentage belongs to the header rather than to a total at the bottom of the screen,
 * because there is no total at the bottom of the screen: the semester is its own tally.
 */
@Composable
private fun SemesterHeader(group: SemesterGroup) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (group.isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                group.subtitle?.let { range ->
                    Text(
                        text = range,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = group.overall.display(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArchivedRow(
    course: Course,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = course.code,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = course.name,
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
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Unarchive") },
                    onClick = {
                        menuOpen = false
                        onUnarchive()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
