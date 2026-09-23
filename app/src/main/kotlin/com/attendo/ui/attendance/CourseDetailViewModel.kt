package com.attendo.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.CalendarBuilder
import com.attendo.core.engine.CourseHistory
import com.attendo.core.engine.CourseStats
import com.attendo.core.engine.HistoryMonth
import com.attendo.core.engine.MonthGrid
import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SessionPattern
import com.attendo.data.AttendanceRepository
import com.attendo.data.SemesterRepository
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
import java.time.YearMonth

data class CourseDetailUiState(
    val loaded: Boolean = false,
    val course: Course? = null,
    val stats: CourseStats? = null,
    val patterns: List<SessionPattern> = emptyList(),
    val month: YearMonth = YearMonth.now(),
    val grid: MonthGrid? = null,
    val history: List<HistoryMonth> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    /** The semester this course is taught in, or null for one that belongs to none. */
    val semester: Semester? = null,
    /** The dates counted. Everything outside it is shown greyed and left out of the figure. */
    val window: AttendanceWindow = AttendanceWindow.OPEN,
    /** Where the percentage lands if every remaining class is attended, or none is. */
    val ifAllAttended: Percent? = null,
    val ifAllMissed: Percent? = null,
    val remainingUnits: Int = 0,
) {
    /** True once the course has been deleted from under us — the screen should pop. */
    val missing: Boolean get() = loaded && course == null

    /**
     * What the figure covers, when it is not simply "this course".
     *
     * A finished semester's course says so, because a percentage with no term attached invites
     * the reader to think it is current. A live course inside an open window says nothing.
     */
    val scopeLabel: String?
        get() = when {
            semester?.archived == true -> "${semester.label} · archived, no longer counting"
            semester != null && window != semester.window -> "Counted ${window.label}"
            else -> null
        }
}

/**
 * One subject: the number, the month, and the log.
 *
 * The projections are the reason this screen exists rather than just a bigger dashboard
 * row. "73%" tells a student where they are; "attend everything left and you finish at
 * 84%" tells them whether it is worth trying.
 *
 * The figure is read through the same window as the list that led here — the course's semester,
 * narrowed by the student's joining date if they set one. A detail screen that disagreed with the
 * row above it would make both numbers worthless, and this is the only reason it knows about
 * semesters at all.
 */
class CourseDetailViewModel(
    private val attendance: AttendanceRepository,
    private val semesters: SemesterRepository,
    private val settings: SettingsStore,
    private val courseId: Long,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.from(clock()))

    /**
     * The settings and the semester list as one value.
     *
     * Folded together because [combine] takes five flows and this screen needs six things; the
     * alternative is the untyped vararg form. Both halves only ever change when the student
     * changes something, so the extra emission costs nothing.
     */
    private val context = combine(settings.settings, semesters.semesters) { appSettings, all ->
        appSettings to all
    }

    val state: StateFlow<CourseDetailUiState> = combine(
        month,
        attendance.course(courseId),
        attendance.sessionsForCourse(courseId),
        attendance.patternsForCourse(courseId),
        context,
    ) { shownMonth, course, sessions, patterns, (appSettings, allSemesters) ->
        if (course == null) {
            return@combine CourseDetailUiState(loaded = true, month = shownMonth)
        }

        val semester = course.semesterId?.let { id -> allSemesters.firstOrNull { it.id == id } }
        val window = appSettings.attendanceStart.windowIn(semester)
        val today = clock()
        // `today` bounds the unmarked count alone: this screen tells the student how many
        // classes still need marking, and a future one they tried a what-if on is not among
        // them. Every figure on the screen is otherwise unchanged by it.
        val stats = AttendanceEngine.courseStats(course, sessions, window, today)
        // Classes still to come: whatever the term has left, using the weekly patterns.
        val remaining = remainingUnits(patterns, today, appSettings.calendar.termEnd)

        CourseDetailUiState(
            loaded = true,
            course = course,
            stats = stats,
            patterns = patterns.sortedWith(compareBy({ it.dayOfWeek }, { it.startHour })),
            month = shownMonth,
            grid = CalendarBuilder.monthGrid(shownMonth, sessions, courseId, window = window, today = today),
            // `today` bounds the history list to what has happened: a future SCHEDULED row
            // is an upcoming class, not one awaiting review, so it must not show as "To mark".
            // The tally-driving figures above still use the full `sessions`, so a future row
            // a what-if turned HELD still counts in the projections — only the audit list is
            // narrowed.
            history = CourseHistory.monthsFor(sessions, courseId, today = today),
            today = today,
            semester = semester,
            window = window,
            ifAllAttended = AttendanceEngine.projectIfAllAttended(stats.tally, remaining),
            ifAllMissed = AttendanceEngine.projectIfAllMissed(stats.tally, remaining),
            remainingUnits = remaining,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CourseDetailUiState())

    fun showMonth(target: YearMonth) {
        month.value = target
    }

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun setTarget(percent: Percent) {
        val course = state.value.course ?: return
        viewModelScope.launch {
            attendance.updateCourse(course.copy(targetPercent = percent))
        }
    }

    fun setArchived(archived: Boolean) {
        val course = state.value.course ?: return
        viewModelScope.launch { attendance.setArchived(course, archived) }
    }

    /**
     * Hours the patterns still imply between tomorrow and the end of term.
     *
     * Counted from the patterns rather than from generated rows because generation stops
     * at today — the future deliberately has no sessions in the table.
     */
    private fun remainingUnits(
        patterns: List<SessionPattern>,
        today: LocalDate,
        termEnd: LocalDate,
    ): Int {
        val calendar = settings.current.effectiveCalendar
        var date = today.plusDays(1)
        var units = 0
        while (!date.isAfter(termEnd)) {
            if (calendar.isTeachingDay(date)) {
                units += patterns
                    .filter { it.dayOfWeek == date.dayOfWeek && it.isEffectiveOn(date) }
                    .sumOf { it.units }
            }
            date = date.plusDays(1)
        }
        return units
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(courseId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CourseDetailViewModel(
                    container.attendance,
                    container.semesters,
                    container.settings,
                    courseId,
                )
            }
        }
    }
}
