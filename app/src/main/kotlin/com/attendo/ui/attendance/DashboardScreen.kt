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
import androidx.compose.material3.AlertDialog
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
import com.attendo.core.engine.DayMark
import com.attendo.core.model.CancellationReason
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
import com.attendo.ui.components.UpdateCard
import com.attendo.ui.days
import com.attendo.ui.display
import com.attendo.ui.hours
import com.attendo.ui.longLabel
import com.attendo.ui.shortLabel
import com.attendo.ui.theme.bands
import com.attendo.data.update.UpdateState
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
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DashboardDialog?>(null) }

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

        // The update card, on the screen the app opens on. When the launch-time check —
        // cached or fresh — has something to say, it is said here, not held until the
        // student happens to visit Settings. It is the same card and the same state as
        // the Updates section renders, and it disappears the same way too: dismissed, or
        // installed.
        if (updateState !is UpdateState.Idle) {
            UpdateCard(
                state = updateState,
                onDownload = viewModel::downloadUpdate,
                onCancelDownload = viewModel::cancelUpdateDownload,
                onInstall = viewModel::installUpdate,
                onDismiss = viewModel::dismissUpdate,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }

        when {
            !state.loaded -> LoadingPane()

            !state.anyCourses -> EmptyState(
                icon = AttendoIcons.School,
                title = "No courses yet",
                body = "Seed them from the faculty timetable. Pick your section and your " +
                    "lab batch, or add them one at a time.",
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
                onMarkBacklogAbsent = {
                    dialog = DashboardDialog.MissedBacklog(
                        dates = state.backlog,
                    )
                },
                onOpenBulkCancel = {
                    dialog = DashboardDialog.CancelBacklog(
                        dates = state.backlog,
                    )
                },
            )
        }
    }

    DashboardDialogHost(
        dialog = dialog,
        today = state.today,
        onDismiss = { dialog = null },
        onMarkBacklogAbsent = viewModel::markBacklogAbsent,
        onCancelBacklog = viewModel::cancelBacklog,
    )
}

/** Which backlog dialog is open on the dashboard. */
private sealed interface DashboardDialog {
    data class MissedBacklog(val dates: List<LocalDate>) : DashboardDialog
    data class CancelBacklog(val dates: List<LocalDate>) : DashboardDialog
}

@Composable
private fun DashboardDialogHost(
    dialog: DashboardDialog?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onMarkBacklogAbsent: () -> Unit,
    onCancelBacklog: (CancellationReason) -> Unit,
) {
    when (dialog) {
        null -> Unit

        is DashboardDialog.MissedBacklog -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Miss all of them?") },
            text = {
                Text(
                    "Every unmarked class ${backlogRangeDialogPhrase(dialog.dates, today)} " +
                        "will be recorded as missed. They still count as held, so they lower " +
                        "your attendance.\n\n" +
                        "Classes you have already marked are left alone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onMarkBacklogAbsent()
                    onDismiss()
                }) {
                    Text("I missed all of them")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )

        is DashboardDialog.CancelBacklog -> CancelReasonDialog(
            body = "Every unmarked class ${backlogRangeDialogPhrase(dialog.dates, today)} " +
                "stops counting, on both sides of the fraction.\n\n" +
                "Classes you have already marked are left alone.",
            onPick = { reason ->
                onCancelBacklog(reason)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Why a stretch of classes did not happen. Reused from the day review screen — the
 * reason is what separates a holiday from a stretch the faculty did not turn up for.
 */
@Composable
private fun CancelReasonDialog(
    body: String,
    onPick: (CancellationReason) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancel every unmarked class?") },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                listOf(
                    CancellationReason.FACULTY_CANCELLED to "Faculty cancelled them",
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
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onApproveToday: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenCourse: (Long) -> Unit,
    onMarkBacklogAbsent: () -> Unit,
    onOpenBulkCancel: () -> Unit,
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
                    today = state.today,
                    // The same date the button's label names — see backlogReviewTarget.
                    onOpen = { backlogReviewTarget(state.backlog)?.let(onOpenDay) },
                    onMarkAllAbsent = onMarkBacklogAbsent,
                    onOpenBulkCancel = onOpenBulkCancel,
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
 *
 * Beyond the review button there are two bulk ways out, both shown only when the backlog
 * spans more than one day (a single day is what the review button itself handles):
 * mark the lot as missed, or cancel the lot. Deliberately absent is any way to *ignore*
 * the backlog — unmarked hours are not a rounding problem the app can quietly dispose of.
 */
@Composable
private fun BacklogCard(
    dates: List<LocalDate>,
    today: LocalDate,
    onOpen: () -> Unit,
    onMarkAllAbsent: () -> Unit,
    onOpenBulkCancel: () -> Unit,
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
                text = backlogDescription(dates, today),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onOpen) {
                    Text(backlogActionLabel(dates, today))
                    Spacer(Modifier.size(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
                if (dates.size > 1) {
                    TextButton(onClick = onOpenBulkCancel) { Text("More…") }
                }
            }
            if (dates.size > 1) {
                TextButton(onClick = onMarkAllAbsent) {
                    Text("I missed all of them")
                }
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

/**
 * Pure presentation formatting for the backlog card's headline description.
 *
 * Case 1: [today] alone -> "Today's classes are awaiting review."
 * Case 2: exactly one past date -> "Classes from Fri 18 Sep are awaiting review."
 * Case 3: multiple past dates -> "Classes from Mon 14 Sep to Fri 18 Sep are awaiting review."
 * Case 4: past dates through today -> "Classes from Fri 18 Sep to today are awaiting review."
 * Case 5: empty -> empty string (card is not shown).
 */
internal fun backlogDescription(dates: List<LocalDate>, today: LocalDate): String {
    if (dates.isEmpty()) return ""

    val phrase = when {
        dates.size == 1 && dates.first() == today -> "Today's classes are"
        dates.size == 1 -> "Classes from ${dates.first().shortLabel()} are"
        dates.last() == today -> "Classes from ${dates.first().shortLabel()} to today are"
        else -> "Classes from ${dates.first().shortLabel()} to ${dates.last().shortLabel()} are"
    }

    return "$phrase awaiting review. Unmarked classes count for nothing on either " +
        "side of the fraction until you review them."
}

/**
 * The date the backlog card's button opens: the oldest day still waiting for review.
 *
 * Its own function because two places need the same answer — the button's *label* names a
 * date and the button's *navigation* opens one, and a label promising one day while the tap
 * opens another is a bug that reads correctly in both places. Both now ask this.
 */
internal fun backlogReviewTarget(dates: List<LocalDate>): LocalDate? = dates.firstOrNull()

/**
 * Action button label on the backlog card.
 *
 * Names the date [backlogReviewTarget] returns, clearly distinguishing "Review today" from
 * "Review <Date>".
 */
internal fun backlogActionLabel(dates: List<LocalDate>, today: LocalDate): String {
    val target = backlogReviewTarget(dates) ?: return ""
    return if (target == today) "Review today" else "Review ${target.shortLabel()}"
}

/**
 * Phrasing helper for dialogs describing the range of backlog classes being acted upon.
 */
internal fun backlogRangeDialogPhrase(dates: List<LocalDate>, today: LocalDate): String {
    if (dates.isEmpty()) return ""
    return when {
        dates.size == 1 && dates.first() == today -> "from today"
        dates.size == 1 -> "from ${dates.first().shortLabel()}"
        dates.last() == today -> "from ${dates.first().shortLabel()} to today"
        else -> "from ${dates.first().shortLabel()} to ${dates.last().shortLabel()}"
    }
}

