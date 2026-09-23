package com.attendo.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.DayPlan
import com.attendo.core.engine.OverallStats
import com.attendo.core.engine.SessionGenerator
import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceWindow
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.Course
import com.attendo.core.model.Semester
import com.attendo.core.update.UpdateManifest
import com.attendo.data.AppSettings
import com.attendo.data.AttendanceRepository
import com.attendo.data.SemesterRepository
import com.attendo.data.SettingsStore
import com.attendo.data.update.UpdateManager
import com.attendo.data.update.UpdateState
import com.attendo.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class DashboardUiState(
    val today: LocalDate = LocalDate.now(),
    /** False only for the instant before the first database emission. */
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val overall: OverallStats? = null,
    /** The semester the figure above is for, or null on an install with none. */
    val semester: Semester? = null,
    /** The dates [overall] counts. Worth showing when it is not the whole semester. */
    val window: AttendanceWindow = AttendanceWindow.OPEN,
    /** Dates holding unmarked backlog classes, oldest first — today only once its classes have finished. */
    val backlog: List<LocalDate> = emptyList(),
    val todayPlan: DayPlan? = null,
    val anyCourses: Boolean = false,
    val courseById: Map<Long, Course> = emptyMap(),
) {
    val hasSomethingToday: Boolean get() = todayPlan?.hasAnything == true

    /** Hours "mark all present" would commit right now. */
    val unitsPendingToday: Int get() = todayPlan?.unitsPendingApproval ?: 0

    /**
     * What the headline percentage covers — shown only when it is narrower than the semester.
     *
     * Silence when the window is the whole semester: a line saying "counted from the start of semester"
     * under every student's figure is noise. A student counting from their joining date, or one
     * with no semester set up, is looking at something narrower than they might assume, and that
     * is worth a line.
     */
    val scopeLabel: String?
        get() = when {
            settings.attendanceStart.basis == AttendanceBasis.PERSONAL &&
                settings.attendanceStart.joinedOn != null ->
                "${semester?.label ?: "All courses"} · counted ${window.label}"

            semester != null -> null
            else -> "No semester set up yet, so every course you have is counted."
        }
}

/**
 * The Attendance tab's landing screen.
 *
 * Everything here is derived from four tables and the settings, so the whole state is one
 * [combine] over five flows and a call into the pure engine. Nothing is cached and nothing
 * needs invalidating: marking an hour writes a row, the flow re-emits, and the percentage,
 * the advice and today's list all move together.
 *
 * ### One semester, one window
 *
 * The headline figure is the *current* semester's, read through the window the student chose —
 * which is where both Case 2 and Case 3 land on this screen. Last term's courses are still in
 * the database and still readable under Courses; they are simply not in this number, because six
 * subjects that finished in December averaged with five that started in January is not a figure
 * anybody has a use for.
 */
class DashboardViewModel(
    private val attendance: AttendanceRepository,
    private val semesters: SemesterRepository,
    private val settings: SettingsStore,
    // A null manager is a Play-installed build: its state flow never leaves Idle, so no
    // card shows and every action below is a no-op.
    private val updates: UpdateManager? = null,
    // A moment, not a date: today's classes join the backlog only after their scheduled
    // end time, so the eligibility question needs the time of day as well.
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    /**
     * The update flow's state, exposed straight from the manager — the same flow
     * Settings follows, so the card on this screen and the one in the Updates section
     * are one truth, not two copies that could disagree. This is the screen the app
     * opens on, which makes it where a launch-time check's answer is actually seen.
     */
    val updateState: StateFlow<UpdateState> =
        updates?.state ?: MutableStateFlow(UpdateState.Idle).asStateFlow()

    val state: StateFlow<DashboardUiState> = combine(
        attendance.courses,
        attendance.patterns,
        attendance.sessions,
        settings.settings,
        semesters.current,
    ) { courses, patterns, sessions, appSettings, semester ->
        val now = clock()
        val today = now.toLocalDate()
        // Orphans — courses with no semester — are counted with the live one. They are the
        // hand-added course and the pre-semesters install, and they belong to no term, so
        // leaving them out would make a student's own courses silently vanish from their
        // percentage. The window still bounds them by date, so nothing outside the term counts.
        val active = courses.filterNot { it.archived }
            .filter { semester == null || it.semesterId == null || it.isIn(semester) }
        val activeIds = active.mapTo(mutableSetOf()) { it.id }
        val ownSessions = sessions.filter { it.courseId in activeIds }
        val window = appSettings.attendanceStart.windowIn(semester)

        DashboardUiState(
            today = today,
            loaded = true,
            settings = appSettings,
            overall = AttendanceEngine.overallStats(
                courses = active,
                sessions = ownSessions,
                overallTarget = appSettings.overallTarget,
                window = window,
                // Bounds the "N to mark" counts under each course to classes that have
                // actually happened. A future session the student marked in a what-if and then
                // unmarked is still sitting there SCHEDULED, and it is not review work.
                today = today,
            ),
            semester = semester,
            window = window,
            // Windowed as well, because the backlog is a list of things to do. Classes held
            // before a student joined are not theirs to mark, and asking them to review three
            // weeks they were not enrolled for would be a queue that never empties.
            backlog = AttendanceEngine.daysAwaitingReview(ownSessions, now, window),
            todayPlan = SessionGenerator.dayPlan(
                date = today,
                patterns = patterns.filter { it.courseId in activeIds },
                existing = ownSessions,
                calendar = appSettings.effectiveCalendar,
            ),
            anyCourses = courses.isNotEmpty(),
            courseById = courses.associateBy { it.id },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())

    init {
        // Safe to run on every open — see AttendanceRepository.syncSessions. This is the
        // only place it is called from, so the sessions table catches up with the timetable
        // the moment the app is opened, however many days have been missed.
        viewModelScope.launch {
            attendance.syncSessions(settings.current.effectiveCalendar, clock().toLocalDate())
        }
    }

    /** The one-tap path: everything today, marked fully attended. */
    fun approveToday() {
        viewModelScope.launch {
            attendance.approveDay(clock().toLocalDate(), settings.current.effectiveCalendar)
        }
    }

    /**
     * The whole backlog in one stroke: every class still unmarked from the oldest pending
     * date to the newest, recorded as missed.
     *
     * The range is the backlog's own span rather than "everything before today", so a day
     * in the middle that is already fully marked contributes nothing — but nothing outside
     * the student's window can be swept in either, because the backlog itself is windowed.
     * Only backlog-*eligible* classes are touched: a class still running or still to come
     * today is left for the day's own card — see [AttendanceRepository.markBacklogAbsent].
     */
    fun markBacklogAbsent() {
        val dates = state.value.backlog
        if (dates.isEmpty()) return
        viewModelScope.launch {
            attendance.markBacklogAbsent(dates.first(), dates.last(), settings.current.effectiveCalendar, clock())
        }
    }

    /** The whole backlog in one stroke: every still-unmarked eligible class cancelled with [reason]. */
    fun cancelBacklog(reason: CancellationReason) {
        val dates = state.value.backlog
        if (dates.isEmpty()) return
        viewModelScope.launch {
            attendance.cancelBacklog(dates.first(), dates.last(), reason, settings.current.effectiveCalendar, clock())
        }
    }

    // The update card's actions. Each is one call into the manager, which owns everything
    // from here — the same calls the Updates section in Settings makes, against the same
    // state, so a download started on this screen is the download continued there.
    /** Starts downloading the offered update. */
    fun downloadUpdate(manifest: UpdateManifest) = updates?.download(manifest)

    fun cancelUpdateDownload() = updates?.cancelDownload()

    /** Hands the verified APK to Android's installer — which then asks the student. */
    fun installUpdate() = updates?.install()

    /** "Not now": remembered against this version, so it is not re-offered unprompted. */
    fun dismissUpdate() = updates?.dismiss()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardViewModel(
                    container.attendance,
                    container.semesters,
                    container.settings,
                    container.updates,
                )
            }
        }
    }
}
