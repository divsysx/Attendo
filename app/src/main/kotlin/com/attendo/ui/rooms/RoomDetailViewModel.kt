package com.attendo.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.data.Glossary
import com.attendo.core.engine.NowClaim
import com.attendo.core.engine.RoomAvailability
import com.attendo.core.engine.RoomStatus
import com.attendo.core.engine.RoomWeek
import com.attendo.core.engine.SlotQuery
import com.attendo.core.engine.roomNowClaim
import com.attendo.data.SettingsStore
import com.attendo.data.TimetableRepository
import com.attendo.ui.container
import com.attendo.ui.notTeachingReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDateTime

data class RoomDetailUiState(
    val loaded: Boolean = false,
    val room: String = "",
    val week: RoomWeek? = null,
    val days: List<DayOfWeek> = emptyList(),
    val today: DayOfWeek = DayOfWeek.MONDAY,
    /**
     * True when the calendar says today is not a teaching day — the "Now:" and "Nothing
     * until …" claims are suppressed and the subtitle says why. The week list below them
     * is a pattern, not a claim about today, and keeps rendering whatever the day is.
     */
    val isNonTeachingToday: Boolean = false,
    /** Why today does not teach, when [isNonTeachingToday] holds; empty otherwise. */
    val nonTeachingReason: String = "",
    /** The room's state in the current slot; null outside teaching hours. */
    val statusNow: RoomStatus? = null,
    /** Where the timetable resumes, for when [statusNow] is null. */
    val nextSlot: SlotQuery? = null,
    val subjects: Glossary = Glossary.EMPTY,
) {
    val freeHoursThisWeek: Int get() = week?.totalFreeHours ?: 0

    val isNeverBooked: Boolean get() = week?.isNeverBooked ?: false

    fun freeOn(day: DayOfWeek) = week?.freeOn(day).orEmpty()

    fun bookingsOn(day: DayOfWeek) = week?.bookingsOn(day).orEmpty()
}

/**
 * One room, all week.
 *
 * The whole screen is one read of a fixed weekly grid, so there is nothing to observe:
 * the state is computed once when the room is opened. The trade-off is that "free now"
 * ages while the screen sits open, which costs a re-entry at worst.
 *
 * The "now" claims are the calendar's to allow (see [roomNowClaim]): the weekly grid is
 * a pattern with no opinion about any particular date, and on a holiday Wednesday its
 * bookings are classes that are not running.
 */
class RoomDetailViewModel(
    private val room: String,
    private val timetables: TimetableRepository,
    private val settings: SettingsStore,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    private val _state = MutableStateFlow(RoomDetailUiState(room = room))
    val state: StateFlow<RoomDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val timetable = timetables.timetable()
            // A week of free runs for one room, off the main thread: this coroutine is
            // started while the screen is still animating in, and the arithmetic is the
            // only thing standing between the tap and the first frame.
            _state.value = withContext(Dispatchers.Default) {
                val bookings = timetable.bookings
                val days = RoomAvailability.weekdaysFor(bookings)
                val now = clock()
                val calendar = settings.current.effectiveCalendar
                val claim = roomNowClaim(now, calendar, days)

                RoomDetailUiState(
                    loaded = true,
                    room = room,
                    week = RoomAvailability.weekFor(bookings, room, days),
                    days = days,
                    today = now.dayOfWeek,
                    isNonTeachingToday = claim is NowClaim.NonTeachingDay,
                    nonTeachingReason =
                        if (claim is NowClaim.NonTeachingDay) calendar.notTeachingReason(now.toLocalDate()) else "",
                    statusNow = (claim as? NowClaim.InSlot)
                        ?.let { RoomAvailability.statusAt(bookings, room, it.slot) },
                    nextSlot = (claim as? NowClaim.Later)?.next,
                    subjects = timetable.subjects,
                )
            }
        }
    }

    companion object {
        fun factory(room: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { RoomDetailViewModel(room, container.timetable, container.settings) }
        }
    }
}
