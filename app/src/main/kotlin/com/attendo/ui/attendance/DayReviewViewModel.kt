package com.attendo.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.DayAttendance
import com.attendo.core.engine.DayPlan
import com.attendo.core.engine.SessionGenerator
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.SessionKind
import com.attendo.data.AttendanceRepository
import com.attendo.data.SettingsStore
import com.attendo.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DayReviewUiState(
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
    val plan: DayPlan? = null,
    val rows: List<DayRow> = emptyList(),
    val day: DayAttendance? = null,
    val courseById: Map<Long, Course> = emptyMap(),
    /** Courses an extra class can be added to. */
    val addableCourses: List<Course> = emptyList(),
    val calendar: AcademicCalendar = AcademicCalendar.DEFAULT_2026_27,
) {
    val unitsPending: Int get() = plan?.unitsPendingApproval ?: 0

    val isFullyReviewed: Boolean get() = plan?.isFullyReviewed == true

    val isFuture: Boolean get() = date.isAfter(today)

    val isTeachingDay: Boolean get() = plan?.isTeachingDay == true

    fun codeOf(courseId: Long): String = courseById[courseId]?.code ?: "?"

    fun nameOf(courseId: Long): String = courseById[courseId]?.name.orEmpty()
}

/**
 * One day, and everything that can be said about it.
 *
 * The date lives in the ViewModel rather than the route so the arrows step between days
 * without pushing a screen per day onto the back stack — a student catching up on a week
 * would otherwise have to press back seven times.
 *
 * Drafts are materialised lazily, on the first edit that needs a row id ([materialised]).
 * Browsing forward through the term therefore writes nothing.
 */
class DayReviewViewModel(
    private val attendance: AttendanceRepository,
    private val settings: SettingsStore,
    initialDate: LocalDate,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val date = MutableStateFlow(initialDate)

    val state: StateFlow<DayReviewUiState> = combine(
        date,
        attendance.courses,
        attendance.patterns,
        attendance.sessions,
        settings.settings,
    ) { day, courses, patterns, sessions, appSettings ->
        val active = courses.filterNot { it.archived }
        val activeIds = active.mapTo(mutableSetOf()) { it.id }
        val ownSessions = sessions.filter { it.courseId in activeIds }
        val plan = SessionGenerator.dayPlan(
            date = day,
            patterns = patterns.filter { it.courseId in activeIds },
            existing = ownSessions,
            calendar = appSettings.calendar,
        )
        DayReviewUiState(
            date = day,
            today = clock(),
            loaded = true,
            plan = plan,
            rows = plan.rows(),
            day = AttendanceEngine.dayAttendance(day, ownSessions),
            courseById = courses.associateBy { it.id },
            addableCourses = active,
            calendar = appSettings.calendar,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DayReviewUiState())

    // ---- navigation ---------------------------------------------------------

    fun goTo(newDate: LocalDate) {
        date.value = newDate
    }

    fun previousDay() {
        date.value = date.value.minusDays(1)
    }

    fun nextDay() {
        date.value = date.value.plusDays(1)
    }

    fun goToToday() {
        date.value = clock()
    }

    // ---- the whole day ------------------------------------------------------

    /** Commits every pending row, drafts included, as fully attended. */
    fun approveAll() {
        viewModelScope.launch {
            attendance.approveDay(date.value, settings.current.calendar)
        }
    }

    /** Cancels every class on the day — the holiday nobody told you about. */
    fun cancelAll(reason: CancellationReason) {
        viewModelScope.launch {
            state.value.rows.forEach { row ->
                materialised(row)?.takeUnless { it.isCancelled }?.let { attendance.cancel(it, reason) }
            }
        }
    }

    // ---- one session --------------------------------------------------------

    fun toggleUnit(row: DayRow, unitIndex: Int) = edit(row) { attendance.toggleUnit(it, unitIndex) }

    fun approve(row: DayRow) = edit(row) { attendance.approve(it) }

    fun markPresent(row: DayRow) = edit(row) { attendance.markPresent(it) }

    fun markAbsent(row: DayRow) = edit(row) { attendance.markAbsent(it) }

    fun cancel(row: DayRow, reason: CancellationReason) = edit(row) { attendance.cancel(it, reason) }

    fun reopen(row: DayRow) = edit(row) { attendance.reopen(it) }

    fun resize(row: DayRow, unitsPlanned: Int) = edit(row) { attendance.resize(it, unitsPlanned) }

    fun moveSlot(row: DayRow, startHour: Int) = edit(row) { attendance.moveSlot(it, startHour) }

    fun reschedule(row: DayRow, newDate: LocalDate, newStartHour: Int, newUnits: Int) =
        edit(row) { attendance.reschedule(it, newDate, newStartHour, newUnits) }

    fun setNote(row: DayRow, note: String?) = edit(row) { attendance.setNote(it, note) }

    /**
     * Removes a row outright. Offered only for ad-hoc sessions: deleting a generated one
     * would free its `(patternId, date)` slot and the next sync would put it back.
     */
    fun delete(row: DayRow) {
        val session = (row as? DayRow.Stored)?.session ?: return
        viewModelScope.launch { attendance.deleteSession(session) }
    }

    fun addExtraClass(
        courseId: Long,
        startHour: Int,
        unitsPlanned: Int,
        kind: SessionKind,
        room: String?,
    ) {
        viewModelScope.launch {
            attendance.addAdhoc(
                courseId = courseId,
                date = date.value,
                startHour = startHour,
                unitsPlanned = unitsPlanned,
                kind = kind,
                room = room?.ifBlank { null },
            )
        }
    }

    /**
     * The stored session behind [row], writing the draft out first if it has none.
     *
     * Returns null only if the insert lost a race with another writer, which in a
     * single-process app means the row already existed and the caller's edit is stale.
     */
    private suspend fun materialised(row: DayRow): ClassSession? = when (row) {
        is DayRow.Stored -> row.session
        is DayRow.Pending -> attendance.sessionById(attendance.materialise(row.draft))
    }

    private fun edit(row: DayRow, block: suspend (ClassSession) -> Unit) {
        viewModelScope.launch { materialised(row)?.let { block(it) } }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * The date is baked into the factory rather than read from a
         * [androidx.lifecycle.SavedStateHandle] because navigation gives each `day/{date}`
         * entry its own ViewModel store — a new route means a new ViewModel anyway.
         */
        fun factory(date: LocalDate): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DayReviewViewModel(container.attendance, container.settings, date)
            }
        }
    }
}
