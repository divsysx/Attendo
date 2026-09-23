package com.attendo.ui.rooms

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.data.Glossary
import com.attendo.core.engine.BookingGroup
import com.attendo.core.engine.RoomAvailability
import com.attendo.core.model.RoomBooking
import com.attendo.ui.community.CommunityStatusRow
import com.attendo.ui.community.CommunityUiState
import com.attendo.ui.community.CommunityViewModel
import com.attendo.ui.community.ObservationCard
import com.attendo.ui.community.PendingReportCard
import com.attendo.ui.community.PollCard
import com.attendo.ui.community.PollErrorLine
import com.attendo.ui.community.PollSheet
import com.attendo.ui.community.ReportContext
import com.attendo.ui.community.ReportSheet
import com.attendo.ui.community.rememberPollClock
import com.attendo.ui.community.rememberSnapshotClock
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.fullLabel
import com.attendo.ui.hours
import com.attendo.ui.shortLabel
import com.attendo.ui.theme.bands
import java.time.DayOfWeek

/**
 * One room's week: when it is free, and who has it when it is not.
 *
 * The list screen answers "where can I sit now"; this answers "when is 204 ever free",
 * which is the question behind booking a room for a group or finding somewhere to work
 * every Tuesday afternoon.
 */
@Composable
fun RoomDetailScreen(
    room: String,
    onBack: () -> Unit,
    viewModel: RoomDetailViewModel = viewModel(factory = RoomDetailViewModel.factory(room)),
    // The app-level shared instance: this screen updates live — Realtime's nudge
    // refreshes the shared state while the room is on top.
    communityViewModel: CommunityViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val community by communityViewModel.state.collectAsStateWithLifecycle()
    var reportOpen by remember { mutableStateOf(false) }
    var askOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { communityViewModel.onShown() }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = roomTitle(state.room),
            subtitle = nowLine(state),
            onBack = onBack,
        )

        if (!state.loaded) {
            LoadingPane()
        } else {
            RoomWeekList(
                state = state,
                community = community,
                onVerify = communityViewModel::verify,
                onVote = communityViewModel::vote,
                onCommunityRetry = communityViewModel::refresh,
                onUndoReport = { communityViewModel.undoReport(it) },
                onWithdrawReport = { communityViewModel.withdrawReport(it) },
                onUndoPoll = { communityViewModel.undoPoll(it) },
                onWithdrawPoll = { communityViewModel.withdrawPoll(it) },
                onRetryReport = communityViewModel::retryNow,
                onForgetReport = communityViewModel::forget,
                onReport = { reportOpen = true },
                onAsk = { askOpen = true },
            )
        }
    }

    if (reportOpen && community.available) {
        ReportSheet(
            context = ReportContext(room = room),
            onSubmit = { kind, payload, note, _, observationType, targetStartHour ->
                communityViewModel.submit(
                    kind = kind,
                    context = ReportContext(room = room),
                    payload = payload,
                    note = note,
                    observationType = observationType,
                    targetStartHour = targetStartHour,
                )
            },
            onDismiss = { reportOpen = false },
        )
    }

    // The poll composer shares the report's context — the room in the title bar — and
    // nothing else: a poll is not a report and never enters the outbox.
    if (askOpen && community.available) {
        PollSheet(
            context = ReportContext(room = room),
            onSubmit = { question, options, startHour, onResult ->
                communityViewModel.createPoll(
                    context = ReportContext(room = room),
                    question = question,
                    options = options,
                    startHour = startHour,
                    onDone = onResult,
                )
            },
            onDismiss = { askOpen = false },
        )
    }
}

@Composable
private fun RoomWeekList(
    state: RoomDetailUiState,
    community: CommunityUiState,
    onVerify: (String, Boolean) -> Unit,
    onVote: (String, Int) -> Unit,
    onCommunityRetry: () -> Unit,
    onUndoReport: (String) -> Unit,
    onWithdrawReport: (String) -> Unit,
    onUndoPoll: (String) -> Unit,
    onWithdrawPoll: (String) -> Unit,
    onRetryReport: (String) -> Unit,
    onForgetReport: (String) -> Unit,
    onReport: () -> Unit,
    onAsk: () -> Unit,
) {
    // The polls' clock, ticking while this list is on screen — see [rememberPollClock]
    // for why the snapshot's frozen clock would not do. Hoisted out of the
    // LazyColumn: the builder runs in a non-composable scope.
    val pollClock = rememberPollClock(community)
    // The observations' ticking twin: what the cards' "X min ago" labels and — the
    // reason it exists — the own-report Undo→Withdraw swap are judged by. A frozen
    // snapshot clock swaps only when some other change happens to trigger a refetch;
    // this one swaps on time, with no network involved.
    val snapshotClock = rememberSnapshotClock(community)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = if (state.isNeverBooked) {
                    "Nothing is timetabled in this room all week."
                } else {
                    "${hours(state.freeHoursThisWeek)} free across the week."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The community section: what other students say about this room right now — above
        // the week, because it is the answer to the question the screen title just asked.
        // The report button lives here rather than on the Rooms tab because this is the
        // screen with the room's name in its title bar: context already established,
        // nothing to re-enter.
        if (community.available) {
            if (community.status != CommunityUiState.Status.FRESH) {
                item(key = "community-status") {
                    CommunityStatusRow(
                        status = community.status,
                        onRetry = onCommunityRetry,
                    )
                }
            }
            if (community.forRoom(state.room).isNotEmpty()) {
                items(
                    community.forRoom(state.room),
                    key = { "community-${it.id}" },
                ) { observation ->
                    ObservationCard(
                        observation = observation,
                        serverNow = snapshotClock,
                        verifiable = community.isVerifiable(observation),
                        onVerify = { verdict -> onVerify(observation.id, verdict) },
                        own = community.ownReport(observation),
                        onUndo = onUndoReport,
                        onWithdraw = onWithdrawReport,
                    )
                }
            }

            // The student's own reports about this room that are not on the server yet —
            // queued, retrying, or frozen behind an identity move. They stand where the
            // observation they will become would stand, because a report that never
            // appears on the page it was filed from reads as if it vanished (the bug the
            // two-device test surfaced: an offline report left the room page unchanged).
            community.unsentForRoom(state.room).forEach { report ->
                item(key = "outbox-${report.idempotencyKey}") {
                    PendingReportCard(
                        report = report,
                        onRetry = { onRetryReport(report.idempotencyKey) },
                        onForget = { onForgetReport(report.idempotencyKey) },
                    )
                }
            }

            // The room's open polls, under the observations they belong with. Asking is
            // offered from the same context as reporting — the room is the subject either
            // way, and a poll with no context is just a survey.
            community.pollsForRoom(state.room, pollClock).forEach { poll ->
                item(key = "poll-${poll.id}") {
                    PollCard(
                        poll = poll,
                        pollClock = pollClock,
                        onVote = onVote,
                        own = community.ownPoll(poll),
                        onUndo = onUndoPoll,
                        onWithdraw = onWithdrawPoll,
                    )
                }
            }
            item(key = "poll-error") { PollErrorLine(community.pollError) }
            item(key = "report") {
                OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                    Text("Report something about this room")
                }
            }
            item(key = "ask") {
                TextButton(onClick = onAsk) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ask the room something")
                }
            }
        }

        state.days.forEach { day ->
            item {
                DayBlock(
                    day = day,
                    isToday = day == state.today,
                    freeLabel = freeLabel(state.freeOn(day).map { it.label }),
                    bookings = state.bookingsOn(day),
                    subjects = state.subjects,
                )
            }
        }

        item {
            Column {
                HorizontalDivider(Modifier.padding(bottom = 8.dp))
                Text(
                    text = "The timetable covers 9 AM to 6 PM, Monday to " +
                        "${state.days.lastOrNull()?.fullLabel() ?: "Friday"}. Outside those " +
                        "hours no room is booked. That is not the same as being open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayBlock(
    day: DayOfWeek,
    isToday: Boolean,
    freeLabel: String,
    bookings: List<RoomBooking>,
    subjects: Glossary,
) {
    // Joint classes are one class in a room even though the grid prints one row per
    // cohort, so they are folded before anything is drawn.
    val groups = remember(bookings) { RoomAvailability.groupBookings(bookings) }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(if (isToday) "${day.fullLabel()} · today" else day.fullLabel())
        Spacer(Modifier.height(4.dp))
        Text(
            text = freeLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (groups.isEmpty()) bands.onFull else MaterialTheme.colorScheme.onSurface,
        )
        groups.forEach { group ->
            Spacer(Modifier.height(8.dp))
            BookingRow(group = group, subjects = subjects)
        }
    }
}

@Composable
private fun BookingRow(
    group: BookingGroup,
    subjects: Glossary,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = group.booking.slotLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = group.booking.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subjects.nameOf(group.booking.subject),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Room 204", but "Basement Lab" — the timetable names some rooms and numbers others. */
private fun roomTitle(room: String): String =
    if (room.isBlank()) "Room" else if (room.toIntOrNull() != null) "Room $room" else room

/** The top bar's second line: the room's state right now, or where the week resumes. */
private fun nowLine(state: RoomDetailUiState): String? = when {
    !state.loaded -> null
    // The calendar's veto, said out loud: no "Now:" and no "Nothing until …" may follow
    // it, because both would be the weekly grid answering for a day it cannot see.
    state.isNonTeachingToday ->
        "Not a teaching day" +
            (state.nonTeachingReason.takeIf { it.isNotBlank() }?.let { ". ${it.lowercase()}" } ?: "")
    state.statusNow != null -> "Now: ${state.statusNow.summary.replaceFirstChar(Char::lowercase)}"
    state.nextSlot != null -> "Nothing until ${state.nextSlot.dayOfWeek.shortLabel()}, " +
        state.nextSlot.slotLabel
    // A teaching day with no teaching day after it: naming a "next" slot would point at
    // a date outside the term. Saying nothing is the honest line.
    else -> null
}

/** "Free 9–11 AM · 1–2 PM", or the honest answer when there is none. */
private fun freeLabel(runs: List<String>): String =
    if (runs.isEmpty()) "Booked all day" else "Free ${runs.joinToString(" · ")}"
