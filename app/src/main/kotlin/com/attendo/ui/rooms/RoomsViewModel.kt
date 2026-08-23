package com.attendo.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.data.Glossary
import com.attendo.core.engine.RoomAvailability
import com.attendo.core.engine.RoomStatus
import com.attendo.core.engine.SlotQuery
import com.attendo.core.engine.currentSlot
import com.attendo.data.SettingsStore
import com.attendo.data.Timetable
import com.attendo.data.TimetableRepository
import com.attendo.ui.container
import com.attendo.ui.notTeachingReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

data class RoomsUiState(
    val loaded: Boolean = false,
    /** False when the bundled timetable holds no bookings at all. */
    val hasTimetable: Boolean = false,
    /** Weekdays the timetable uses — Saturday only if somebody teaches then. */
    val days: List<DayOfWeek> = emptyList(),
    val query: SlotQuery? = null,
    /** True when [query] is the slot the clock is in, so the lists mean "right now". */
    val isLive: Boolean = false,
    val free: List<RoomStatus> = emptyList(),
    val busy: List<RoomStatus> = emptyList(),
    val filter: String = "",
    /** Rooms in the timetable, before [filter] — the denominator in "8 of 41". */
    val roomCount: Int = 0,
    val subjects: Glossary = Glossary.EMPTY,
    /**
     * True only on the right-now view, when today is not a teaching day — a Sunday, a holiday,
     * or a Saturday that is not a working one. The grid still shows (the day/hour chips let a
     * student plan ahead), but the free/busy lists are replaced by an empty state naming why.
     * Never set when the student has manually picked a slot: that is them asking about a day,
     * not the app telling them about today.
     */
    val isNonTeachingToday: Boolean = false,
    /** Why today is not a teaching day, when [isNonTeachingToday] holds; empty otherwise. */
    val notTodayReason: String = "",
) {
    val matchCount: Int get() = free.size + busy.size

    val isFiltered: Boolean get() = filter.isNotBlank()
}

/**
 * One slot's answer, before the search box narrows it.
 *
 * Kept as its own value because it is expensive and the needle is not. Working out which
 * rooms are free walks every booking in the timetable, groups them by room, merges each
 * room's bookings into runs and sorts the result — and it depends on the timetable and the
 * chosen slot alone. Neither of those changes while somebody is typing, so neither should be
 * recomputed while they do.
 *
 * [free] and [busy] are already in display order, so narrowing them is a single pass with
 * no re-sort.
 */
private data class Availability(
    val hasTimetable: Boolean,
    val days: List<DayOfWeek>,
    val query: SlotQuery,
    val isLive: Boolean,
    /** True when no slot was picked, so [query] is the right-now view rather than a chosen one. */
    val isRightNow: Boolean,
    /** Free rooms, longest free stretch first. */
    val free: List<RoomStatus>,
    /** Rooms in use, in reading order. */
    val busy: List<RoomStatus>,
    val roomCount: Int,
    val subjects: Glossary,
)

/**
 * What is free, and when.
 *
 * The timetable is a fixed weekly grid, so this asks one question — "which rooms are busy
 * in this day-and-hour?" — and answers it either for the current slot or for one the
 * student picked. Opening the tab lands on the live slot, which is the answer wanted often
 * enough that reaching it should take no taps at all.
 *
 * Nothing here ticks. The clock is read whenever the timetable loads or the student picks a
 * slot, which is what a student returning to the tab does; a class runs for an hour, so a
 * slot that goes stale while the screen is open is not worth a timer. Reading it on any
 * narrower occasion than that would be worse than useless — see [availability].
 */
class RoomsViewModel(
    private val timetables: TimetableRepository,
    private val settings: SettingsStore,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    /** Null until the CSV has been parsed off the main thread. */
    private val timetable = MutableStateFlow<Timetable?>(null)

    /** Null means "whatever slot it is now", which is where the tab opens. */
    private val picked = MutableStateFlow<SlotQuery?>(null)
    private val filter = MutableStateFlow("")

    init {
        viewModelScope.launch { timetable.value = timetables.timetable() }
    }

    /**
     * The slot's rooms, recomputed only when the slot or the timetable changes.
     *
     * Deliberately not combined with [filter]. Doing so put the whole derivation below on
     * the critical path of every keystroke, and it also re-read [clock] on each one: a
     * character typed as the hour turned would move `live`, take the "back to now" row with
     * it, and reshuffle the list under the text field mid-word.
     */
    private val availability: Flow<Availability?> =
        combine(timetable, picked) { loadedTimetable, chosen ->
            val bookings = loadedTimetable?.bookings ?: return@combine null

            val days = RoomAvailability.weekdaysFor(bookings)
            val now = clock()
            val live = RoomAvailability.slotAt(now)?.takeIf { it.dayOfWeek in days }
            // Anchored to today's day-of-week, never rolling forward to the next teaching day:
            // opening the tab on a Sunday shows Sunday, not Monday. A slot the student picked is
            // honoured first — see [currentSlot]. The old fallback was RoomAvailability.nextSlot,
            // which advanced to Monday whenever there was no live slot.
            val query = currentSlot(now, days, chosen)
            // Derived once and handed to statusesAt, which would otherwise derive it again
            // for its default argument — the same six hundred bookings walked twice.
            val allRooms = RoomAvailability.rooms(bookings)
            val statuses = RoomAvailability.statusesAt(bookings, query, allRooms)

            Availability(
                hasTimetable = bookings.isNotEmpty(),
                days = days,
                query = query,
                isLive = query == live,
                isRightNow = chosen == null,
                // Longest stretch first: a room free for three hours beats one free for one.
                free = statuses.filter { it.isFree }.sortedByDescending { it.freeHours },
                busy = statuses.filterNot { it.isFree },
                roomCount = allRooms.size,
                subjects = loadedTimetable.subjects,
            )
        }

    val state: StateFlow<RoomsUiState> =
        combine(availability, filter, settings.settings) { slot, needle, appSettings ->
            if (slot == null) return@combine RoomsUiState()

            // Trimmed once rather than inside the match, which ran it per room.
            val trimmed = needle.trim()
            // The right-now view only: when no slot was picked, today's grid is what is on
            // screen, so a non-teaching today is worth saying out loud. A student who tapped a
            // day chip is asking about that day, not today — the empty state stays out of the way.
            val now = clock().toLocalDate()
            val calendar = appSettings.calendar
            val nonTeaching = slot.isRightNow && !calendar.isTeachingDay(now)
            RoomsUiState(
                loaded = true,
                hasTimetable = slot.hasTimetable,
                days = slot.days,
                query = slot.query,
                isLive = slot.isLive,
                free = slot.free.matching(trimmed),
                busy = slot.busy.matching(trimmed),
                filter = needle,
                roomCount = slot.roomCount,
                subjects = slot.subjects,
                isNonTeachingToday = nonTeaching,
                notTodayReason = if (nonTeaching) calendar.notTeachingReason(now) else "",
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RoomsUiState())

    fun setDay(day: DayOfWeek) {
        val hour = state.value.query?.startHour ?: return
        picked.value = SlotQuery(day, hour)
    }

    fun setHour(hour: Int) {
        val day = state.value.query?.dayOfWeek ?: return
        picked.value = SlotQuery(day, hour)
    }

    /** Back to the slot the clock is in. */
    fun now() {
        picked.value = null
    }

    fun setFilter(text: String) {
        filter.value = text
    }

    /**
     * Substring match on the room name — "is 204 free?" is the common question.
     *
     * An empty needle returns the receiver itself rather than a copy of it, so an emission
     * that changed nothing about the list hands the same instance back and the room rows
     * skip recomposition entirely.
     */
    private fun List<RoomStatus>.matching(needle: String): List<RoomStatus> =
        if (needle.isEmpty()) this
        else filter { it.room.contains(needle, ignoreCase = true) }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RoomsViewModel(container.timetable, container.settings) }
        }
    }
}
