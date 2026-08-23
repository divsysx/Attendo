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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.engine.CourseStats
import com.attendo.core.engine.DayMark
import com.attendo.ui.AttendoIcons
import com.attendo.ui.components.AttendanceBar
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.EmptyState
import com.attendo.ui.components.KindTag
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.MarkSwatch
import com.attendo.ui.components.PercentHeadline
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.components.TargetAdviceLine
import com.attendo.ui.days
import com.attendo.ui.display
import com.attendo.ui.hours
import com.attendo.ui.longLabel
import com.attendo.ui.shortLabel
import com.attendo.ui.theme.bands
import java.time.LocalDate

/**
 * The Attendance tab: where you stand, what still needs marking, and today in one tap.
 *
 * The primary action is deliberately "Mark all present" rather than a per-class flow. The
 * common case is that you went to everything, and making that case free is the difference
 * between an app that gets used daily and one that accumulates a backlog. Anything less
 * ordinary — a half-attended block, a cancellation — is one tap further in, on the day
 * screen.
 */
@Composable
fun DashboardScreen(
    onOpenDay: (LocalDate) -> Unit,
    onOpenCourse: (Long) -> Unit,
    onOpenCourses: () -> Unit,
    onOpenSettings: () -> Unit,
    onSeed: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            // The greeting, where the screen's own name would be: the tab below already says
            // "Attendance", and this is the one screen the app opens on. "Hi" on its own until
            // a name is set in Settings — no prompt, no empty-looking placeholder.
            title = state.settings.greeting,
            subtitle = state.today.longLabel(),
            actions = {
                IconButton(onClick = onOpenCourses) {
                    Icon(AttendoIcons.School, contentDescription = "Courses")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )
        when {
            !state.loaded -> LoadingPane()

            !state.anyCourses -> EmptyState(
                icon = AttendoIcons.School,
                title = "No courses yet",
                body = "Seed them from the faculty timetable — pick your section and your " +
                    "lab batch — or add them one at a time.",
                action = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onSeed) { Text("Seed from timetable") }
                        OutlinedButton(onClick = onOpenCourses) { Text("Add by hand") }
                    }
                },
            )

            else -> DashboardContent(
                state = state,
                onApproveToday = viewModel::approveToday,
                onOpenDay = onOpenDay,
                onOpenCourse = onOpenCourse,
            )
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onApproveToday: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenCourse: (Long) -> Unit,
) {
    val overall = state.overall ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(16.dp)) {
                    PercentHeadline(
                        percent = overall.percent,
                        tally = overall.tally,
                        target = state.settings.overallTarget,
                        label = "Overall",
                    )
                    Spacer(Modifier.height(8.dp))
                    TargetAdviceLine(overall.target)
                    // Only ever set when the figure covers less than the whole semester — a
                    // late admission's window, or an install with no semester at all. Saying so
                    // here is the difference between a number and a number you can trust.
                    state.scopeLabel?.let { scope ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = scope,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.backlog.isNotEmpty()) {
            item {
                BacklogCard(
                    dates = state.backlog,
                    onOpen = { onOpenDay(state.backlog.first()) },
                )
            }
        }

        item {
            TodayCard(
                state = state,
                onApproveToday = onApproveToday,
                onOpenDay = { onOpenDay(state.today) },
            )
        }

        item {
            SectionLabel("Courses", modifier = Modifier.padding(top = 4.dp))
        }

        items(overall.perCourse, key = { it.course.id }) { stats ->
            CourseStatsRow(stats = stats, onClick = { onOpenCourse(stats.course.id) })
        }
    }
}

/**
 * The nudge for days that were never marked.
 *
 * Unreviewed sessions are invisible to the percentage, which is the kind decision but also
 * a silent one — without this card a student could sit at "100%" for a fortnight having
 * marked nothing at all.
 */
@Composable
private fun BacklogCard(
    dates: List<LocalDate>,
    onOpen: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = bands.pending,
            contentColor = bands.onPending,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "${days(dates.size)} awaiting review",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Oldest: ${dates.first().shortLabel()}. Unmarked classes count " +
                    "for nothing on either side of the fraction until you review them.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onOpen) {
                Text("Review ${dates.first().shortLabel()}")
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun TodayCard(
    state: DashboardUiState,
    onApproveToday: () -> Unit,
    onOpenDay: () -> Unit,
) {
    val plan = state.todayPlan
    // Merging the day's stored rows with its drafts allocates two lists, concatenates them
    // and sorts the result. Keyed on the plan so that happens when the day actually changes,
    // rather than on every recomposition of a card that sits on the screen a tab switch away
    // from the search box.
    val rows = remember(plan) { plan?.rows().orEmpty() }
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenDay) { Text("Review") }
            }

            when {
                plan == null || !plan.isTeachingDay -> Text(
                    text = "Not a teaching day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                !plan.hasAnything -> Text(
                    text = "Nothing timetabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    Spacer(Modifier.height(4.dp))
                    rows.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider()
                        TodayRow(
                            row = row,
                            code = state.courseById[row.courseId]?.code ?: "?",
                            onClick = onOpenDay,
                        )
                    }
                    if (state.unitsPendingToday > 0) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onApproveToday,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(AttendoIcons.DoneAll, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Mark all present · ${hours(state.unitsPendingToday)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayRow(
    row: DayRow,
    code: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MarkSwatch(row.mark())
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = code, style = MaterialTheme.typography.bodyLarge)
                KindTag(row.kind)
            }
            Text(
                text = listOfNotNull(row.slotLabel, row.room).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.statusLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = if (row.mark() == DayMark.AWAITING_REVIEW) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                bands.contentFor(row.mark())
            },
        )
    }
}

/** One subject in the dashboard list: where it stands, and how much slack is left. */
@Composable
private fun CourseStatsRow(
    stats: CourseStats,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stats.course.code,
                    style = MaterialTheme.typography.titleSmall,
                )
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
        }
        Spacer(Modifier.height(6.dp))
        AttendanceBar(
            tally = stats.tally,
            target = stats.course.targetPercent,
            height = 6.dp,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            TargetAdviceLine(stats.target, modifier = Modifier.weight(1f))
            if (stats.sessionsAwaitingReview > 0) {
                Text(
                    text = "${stats.sessionsAwaitingReview} to mark",
                    style = MaterialTheme.typography.bodySmall,
                    color = bands.onPending,
                )
            }
        }
    }
}
