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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.data.Glossary
import com.attendo.core.engine.BookingGroup
import com.attendo.core.engine.RoomAvailability
import com.attendo.core.model.RoomBooking
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = roomTitle(state.room),
            subtitle = nowLine(state),
            onBack = onBack,
        )

        if (!state.loaded) {
            LoadingPane()
        } else {
            RoomWeekList(state)
        }
    }
}

@Composable
private fun RoomWeekList(state: RoomDetailUiState) {
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
                        "hours no room is booked — which is not the same as being open.",
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
    state.statusNow != null -> "Now: ${state.statusNow.summary.replaceFirstChar(Char::lowercase)}"
    state.nextSlot != null -> "Nothing until ${state.nextSlot.dayOfWeek.shortLabel()}, " +
        state.nextSlot.slotLabel
    else -> null
}

/** "Free 9–11 AM · 1–2 PM", or the honest answer when there is none. */
private fun freeLabel(runs: List<String>): String =
    if (runs.isEmpty()) "Booked all day" else "Free ${runs.joinToString(" · ")}"
