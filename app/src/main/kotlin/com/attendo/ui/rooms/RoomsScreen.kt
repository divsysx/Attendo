package com.attendo.ui.rooms

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.data.Glossary
import com.attendo.core.engine.RoomStatus
import com.attendo.core.engine.SlotQuery
import com.attendo.core.model.TimeGrid
import com.attendo.ui.AttendoIcons
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ChipChoice
import com.attendo.ui.components.EmptyState
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.components.rememberEditableText
import com.attendo.ui.community.CommunityStatusRow
import com.attendo.ui.community.CommunityUiState
import com.attendo.ui.community.CommunityViewModel
import com.attendo.ui.community.RoomCommunityCard
import com.attendo.ui.fullLabel
import com.attendo.ui.shortLabel
import com.attendo.ui.theme.bands
import java.time.DayOfWeek

/**
 * The second tab: which rooms are free.
 *
 * Opens on the hour it currently is, because that is the question being asked when the tab
 * is opened at all — "somewhere to sit for the next hour". The day and hour chips are there
 * for the other question, planning ahead to a free slot tomorrow.
 */
@Composable
fun RoomsScreen(
    onOpenRoom: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RoomsViewModel = viewModel(factory = RoomsViewModel.Factory),
    // The app-level shared instance, passed in: one state and one Realtime channel
    // for every community surface.
    communityViewModel: CommunityViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val community by communityViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { communityViewModel.onShown() }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = "Rooms",
            subtitle = state.query?.let { slotHeading(it, state.isLive) },
            // The same gear the Attendance tab has: Settings is where the term shape,
            // backups and "My community" live, and a student who lives on the Rooms
            // tab should not have to change tabs to reach any of it.
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        when {
            !state.loaded -> LoadingPane()

            !state.hasTimetable -> EmptyState(
                icon = AttendoIcons.MeetingRoom,
                title = "No timetable",
                body = "The bundled timetable holds no bookings, so there is nothing to say " +
                    "about rooms.",
            )

            else -> RoomsContent(
                state = state,
                community = community,
                onCommunityRetry = communityViewModel::refresh,
                onDay = viewModel::setDay,
                onHour = viewModel::setHour,
                onNow = viewModel::now,
                onFilter = viewModel::setFilter,
                onOpenRoom = onOpenRoom,
            )
        }
    }
}

@Composable
private fun RoomsContent(
    state: RoomsUiState,
    community: CommunityUiState,
    onCommunityRetry: () -> Unit,
    onDay: (DayOfWeek) -> Unit,
    onHour: (Int) -> Unit,
    onNow: () -> Unit,
    onFilter: (String) -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    // The search box's text and caret, held here rather than read back out of
    // [RoomsUiState.filter] — see [rememberEditableText] for why every field in the app is
    // written this way. Hoisted above the LazyColumn as well as out of the ViewModel: the
    // list disposes items that scroll out of view, and the caret would go with the row that
    // draws it.
    //
    // [RoomsUiState.filter] is still the source of truth for the *results*, and still what
    // restores the box across a configuration change; it is simply read on the way in rather
    // than on every emission.
    var search by rememberEditableText(state.filter)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Every item is keyed. Without keys a `LazyColumn` identifies its items by index, so
        // the conditional rows below shifted the index of everything after them as they came
        // and went — recreating the search field, and losing its focus, on an edit that had
        // nothing to do with it.
        item(key = "days") {
            ChipChoice(
                options = state.days,
                selected = state.query?.dayOfWeek,
                label = { it.shortLabel() },
                onSelect = onDay,
            )
        }
        item(key = "hours") {
            ChipChoice(
                options = TimeGrid.startHours,
                selected = state.query?.startHour,
                label = ::hourChip,
                onSelect = onHour,
            )
        }

        // The button belongs to a view the student navigated to; the default view — even
        // opened outside teaching hours, when there is no live slot for it to equal — is
        // already "now" and offering a way back to where they are would be noise.
        if (!state.isRightNow) {
            item(key = "now") {
                TextButton(onClick = onNow) {
                    Icon(AttendoIcons.Schedule, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back to now")
                }
            }
        }

        item(key = "search") {
            OutlinedTextField(
                value = search,
                onValueChange = { edit ->
                    // Applied here first, so the field is never waiting on anything.
                    search = edit
                    onFilter(edit.text)
                },
                label = { Text("Find a room") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.isFiltered && state.matchCount == 0 && !state.isNonTeachingDay) {
            item(key = "no-match") {
                Text(
                    text = "No room called \"${state.filter}\". The timetable names " +
                        "${state.roomCount} rooms.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.isNonTeachingDay) {
            item(key = "non-teaching") {
                // "today" on the right-now view (live or not — a Sunday afternoon is still
                // today), the picked date otherwise — the state is about whichever day the
                // query names, and the copy says which.
                val whenLine = if (state.isRightNow) "today"
                else state.queryDate?.let { "on ${it.shortLabel()}" } ?: "then"
                Text(
                    text = "Not a teaching day. ${state.nonTeachingReason.lowercase()}. " +
                        "No rooms are in use $whenLine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (state.free.isNotEmpty()) {
                item(key = "free-label") { SectionLabel("Free: ${state.free.size}") }
                items(state.free, key = { "free-${it.room}" }) { status ->
                    FreeRoomRow(status = status, onClick = { onOpenRoom(status.room) })
                }
            } else if (!state.isFiltered) {
                item(key = "all-busy") {
                    Text(
                        text = "Every room is in use this hour.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Busy rooms are inside this branch on purpose: on a non-teaching day the
            // weekly grid still holds that day-of-week's bookings, and rendering them
            // under a "not a teaching day" line would say classes run on a day the
            // calendar says they do not.
            if (state.busy.isNotEmpty()) {
                item(key = "busy-label") {
                    SectionLabel("In use: ${state.busy.size}", Modifier.padding(top = 8.dp))
                }
                items(state.busy, key = { "busy-${it.room}" }) { status ->
                    BusyRoomRow(
                        status = status,
                        subjects = state.subjects,
                        onClick = { onOpenRoom(status.room) },
                    )
                }
            }
        }

        // The community section: what other students say about these rooms right now.
        // Only on a view that *is* now — the right-now view in any form (a live slot, an
        // after-hours hour, a holiday with an extra class running in it) or a picked slot
        // that happens to be the live one. A report about "now" next to a Tuesday being
        // browsed on a Thursday would be a lie sitting next to a true list. Unreachable
        // Supabase never removes the section: the status row says which honest thing is
        // true (stale data, or none) and offers the retry; the timetable above is
        // untouched either way, because the community feature is a guest on this tab, not
        // a tenant.
        if ((state.isLive || state.isRightNow) && community.available) {
            if (community.status != CommunityUiState.Status.FRESH) {
                item(key = "community-status") {
                    CommunityStatusRow(status = community.status, onRetry = onCommunityRetry)
                }
            }
            if (community.roomSummaries.isNotEmpty()) {
                item(key = "community-label") {
                    SectionLabel(
                        "Students report",
                        Modifier.padding(top = 8.dp),
                    )
                }
                items(community.roomSummaries, key = { "community-${it.room}" }) { summary ->
                    RoomCommunityCard(
                        summary = summary,
                        serverNow = community.serverNow,
                        own = community.hasOwnReport(summary),
                        onClick = { onOpenRoom(summary.room) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FreeRoomRow(
    status: RoomStatus,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = status.room,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = status.summary,
            style = MaterialTheme.typography.bodyMedium,
            // Green means free here, the same way it means attended on the other tab.
            color = bands.onFull,
        )
    }
}

@Composable
private fun BusyRoomRow(
    status: RoomStatus,
    subjects: Glossary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status.room,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Two groups here is a genuine clash in the printed grid, not a joint class —
        // those have already been folded into one line.
        status.groups.forEach { group ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${group.booking.label} · ${subjects.nameOf(group.booking.subject)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Thursday, 1–2 PM" — or the same with "now" said out loud. */
private fun slotHeading(query: SlotQuery, isLive: Boolean): String {
    val slot = "${query.dayOfWeek.fullLabel()}, ${query.slotLabel}"
    return if (isLive) "$slot · now" else slot
}

/**
 * "9", "12", "1" — the chip row reads like the printed timetable's header, where the
 * meridiem is never repeated either.
 */
private fun hourChip(hour: Int): String = ((hour % 12).takeIf { it != 0 } ?: 12).toString()
