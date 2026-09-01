package com.attendo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.data.ImportProblem
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.Percent
import com.attendo.core.update.UpdateCheckOutcome
import com.attendo.core.update.UpdateManifest
import com.attendo.data.AppSettings
import com.attendo.data.Appearance
import com.attendo.data.AppearanceStore
import com.attendo.data.AppVersion
import com.attendo.data.AttendanceRepository
import com.attendo.data.SettingsStore
import com.attendo.data.TimetableRepository
import com.attendo.data.ThemePreference
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

data class SettingsUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val today: LocalDate = LocalDate.now(),
    /** Teaching days the term contains, and how many have already gone by. */
    val teachingDays: Int = 0,
    val teachingDaysSoFar: Int = 0,
    val courseCount: Int = 0,
    val markedSessions: Int = 0,
    /** Lines of the bundled timetable that could not be read. Empty in a shipped build. */
    val problems: List<ImportProblem> = emptyList(),
    /** This build, for the About block. The product is "Attendo"; this is only its version. */
    val version: AppVersion = AppVersion(name = "", code = 0L),
    /** The update section: hidden entirely unless this build has an update source. */
    val update: UpdatePanel = UpdatePanel(),
) {
    val calendar: AcademicCalendar get() = settings.calendar

    val holidays: List<LocalDate> get() = calendar.holidays.sorted()

    val workingSaturdays: List<LocalDate> get() = calendar.workingSaturdays.sorted()

    val sectionLabel: String get() = listOfNotNull(settings.section, settings.batch)
        .joinToString(" · ")
        .ifBlank { "Not set" }

    /** The name as typed, or the words the row shows when there is none. */
    val displayNameLabel: String get() = settings.displayName?.ifBlank { null } ?: "Not set"

    /** Which date attendance is counted from. Read at display time, never a stored figure. */
    val attendanceStart: AttendanceStart get() = settings.attendanceStart

    /**
     * Exactly what the name dialog's text field opens with: the saved name, or nothing.
     *
     * Deliberately has no fallback string. The row above it can say "Not set" because that is a
     * label being read; a text field is a value being edited, and anything put here would be
     * something the student appears to have typed and would have to delete.
     */
    val nameFieldValue: String get() = settings.displayName?.trim().orEmpty()

    /** "Version 1.0" — never joined to the product name. */
    val versionLabel: String get() = "Version ${version.name.ifBlank { "unknown" }}"
}

/**
 * The update section's own small state: whether a manual check is running, and what the
 * last one said. The update *flow* itself — available, downloading, ready — is
 * [UpdateState], straight from the [UpdateManager], because it belongs to the app and not
 * to this screen.
 */
data class UpdatePanel(
    /** Whether this build has an update source at all. The section hides when false. */
    val supported: Boolean = false,
    val checking: Boolean = false,
    /** The last manual check's one-line answer. Null while checking or before any check. */
    val checkResult: CheckResult? = null,
) {
    /** What a manual check can conclude, in the words the section shows. */
    enum class CheckResult {
        /**
         * "No update available." — said after a *successful* check, and identically
         * whether the installed build matches the latest release or is ahead of it
         * (a local build of an unpublished release). Never the answer to a failed check.
         */
        UP_TO_DATE,

        /** A check that could not complete — offline, or GitHub unreachable. */
        FAILED,
    }
}

/**
 * The term's shape: targets, dates, holidays.
 *
 * Every write here changes what the timetable implies, so each one is followed by the
 * matching correction to the sessions table — declaring a holiday cancels that day's
 * unmarked classes, adding a working Saturday generates them. Leaving that to the next app
 * open would mean a settings screen whose effect appears a day late.
 */
class SettingsViewModel(
    private val attendance: AttendanceRepository,
    private val settings: SettingsStore,
    private val timetables: TimetableRepository,
    private val version: AppVersion,
    private val updates: UpdateManager? = null,
    private val appearanceStore: AppearanceStore,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val problems = MutableStateFlow<List<ImportProblem>>(emptyList())

    private val updatePanel = MutableStateFlow(UpdatePanel(supported = updates?.supported == true))

    /** The update flow's state, or nothing when this build has no update source. */
    val updateState: StateFlow<UpdateState> =
        updates?.state ?: MutableStateFlow(UpdateState.Idle).asStateFlow()

    /**
     * What the app looks like. Exposed straight from the store, like [updateState], rather
     * than folded into [state]: MainActivity follows the same flow to theme the whole app,
     * and a preference that must agree in two places should be one value, not two copies.
     */
    val appearance: StateFlow<Appearance> = appearanceStore.appearance

    init {
        viewModelScope.launch { problems.value = timetables.timetable().problems }
    }

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        problems,
        attendance.courses,
        attendance.sessions,
        updatePanel,
    ) { appSettings, importProblems, courses, sessions, panel ->
        val today = clock()
        val calendar = appSettings.calendar
        SettingsUiState(
            loaded = true,
            settings = appSettings,
            today = today,
            teachingDays = calendar.teachingDaysBetween(calendar.termStart, calendar.termEnd).size,
            teachingDaysSoFar = calendar
                .teachingDaysBetween(calendar.termStart, minOf(today, calendar.termEnd))
                .size,
            courseCount = courses.count { !it.archived },
            markedSessions = sessions.count { !it.isAwaitingReview },
            problems = importProblems,
            version = version,
            update = panel,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setOverallTarget(target: Percent) = settings.setOverallTarget(target)

    /** Applies to courses created from here on; existing ones keep their own target. */
    fun setCourseTarget(target: Percent) = settings.setCourseTarget(target)

    fun setTermStart(date: LocalDate) {
        settings.setTermDates(date, settings.current.calendar.termEnd)
        resync()
    }

    fun setTermEnd(date: LocalDate) {
        settings.setTermDates(settings.current.calendar.termStart, date)
        resync()
    }

    fun addHoliday(date: LocalDate) {
        if (date in settings.current.calendar.holidays) return
        settings.toggleHoliday(date)
        viewModelScope.launch { attendance.cancelDayAsHoliday(date) }
    }

    fun removeHoliday(date: LocalDate) {
        if (date !in settings.current.calendar.holidays) return
        settings.toggleHoliday(date)
        viewModelScope.launch {
            attendance.restoreHoliday(date)
            attendance.syncSessions(settings.current.calendar, clock())
        }
    }

    fun addWorkingSaturday(date: LocalDate) {
        if (date in settings.current.calendar.workingSaturdays) return
        settings.toggleWorkingSaturday(date)
        viewModelScope.launch {
            attendance.restoreHoliday(date)
            attendance.syncSessions(settings.current.calendar, clock())
        }
    }

    /** A Saturday that is not a working day is not a teaching day, so it empties out. */
    fun removeWorkingSaturday(date: LocalDate) {
        if (date !in settings.current.calendar.workingSaturdays) return
        settings.toggleWorkingSaturday(date)
        viewModelScope.launch { attendance.cancelDayAsHoliday(date) }
    }

    fun clearSection() = settings.setSection(null, null)

    /**
     * Which date attendance is counted from — Case 3, the spot admission.
     *
     * Nothing is generated, corrected or deleted here, which is why neither of these setters
     * calls [resync]. The sessions from before a student joined were really held, so they stay
     * exactly where they are; this only changes the window the engine reads them through. A
     * student can switch back and forth between the university's figure and their own all
     * afternoon and lose nothing.
     */
    fun setAttendanceBasis(basis: AttendanceBasis) = settings.setAttendanceBasis(basis)

    /**
     * Records the day the student joined.
     *
     * Also switches the basis to [AttendanceBasis.PERSONAL], because setting a joining date and
     * then finding the figure unchanged is a setting that appears not to work. The date survives
     * a switch back to the university's reading — see [AttendanceStart].
     */
    fun setJoiningDate(date: LocalDate) {
        settings.setJoiningDate(date)
        settings.setAttendanceBasis(AttendanceBasis.PERSONAL)
    }

    /**
     * What the greeting calls the student. Local, and a label rather than an identity.
     *
     * Blank clears it, which is why this takes the field's contents rather than a validated
     * name: emptying the box is how you go back to a plain "Hi".
     */
    fun setDisplayName(name: String) = settings.setDisplayName(name)

    /**
     * Theme and dynamic colours. Both just record the choice — the flow MainActivity
     * follows does the rest, and there is nothing here to correct or resync, which is
     * the point of keeping the theme decision in one place.
     */
    fun setTheme(theme: ThemePreference) = appearanceStore.setTheme(theme)

    fun setDynamicColors(enabled: Boolean) = appearanceStore.setDynamicColors(enabled)

    /**
     * "Check for updates", pressed. An instruction, not a poll: never throttled, and it
     * gets an answer even when the answer is that the check could not complete — which the
     * quiet background check deliberately never says.
     */
    fun checkForUpdates() {
        val manager = updates ?: return
        if (updatePanel.value.checking) return
        viewModelScope.launch {
            updatePanel.value = updatePanel.value.copy(checking = true, checkResult = null)
            val result = when (manager.checkNow()) {
                is UpdateCheckOutcome.Available -> null // The card answers; a line would echo it.
                is UpdateCheckOutcome.UpToDate -> UpdatePanel.CheckResult.UP_TO_DATE
                is UpdateCheckOutcome.Failed -> UpdatePanel.CheckResult.FAILED
            }
            updatePanel.value = updatePanel.value.copy(checking = false, checkResult = result)
        }
    }

    /** Starts downloading the offered update. The manager owns everything from here. */
    fun downloadUpdate(manifest: UpdateManifest) = updates?.download(manifest)

    fun cancelUpdateDownload() = updates?.cancelDownload()

    /** Hands the verified APK to Android's installer — which then asks the student. */
    fun installUpdate() = updates?.install()

    /** "Not now": remembered against this version, so it is not re-offered unprompted. */
    fun dismissUpdate() = updates?.dismiss()

    private fun resync() {
        viewModelScope.launch {
            attendance.syncSessions(settings.current.calendar, clock())
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.attendance,
                    container.settings,
                    container.timetable,
                    container.version,
                    container.updates,
                    container.appearance,
                )
            }
        }
    }
}
