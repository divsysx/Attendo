package com.attendo.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.data.SeedPlan
import com.attendo.core.data.SeedProposal
import com.attendo.core.data.SectionSeeder
import com.attendo.core.engine.RoomAvailability
import com.attendo.data.AttendanceRepository
import com.attendo.data.SettingsStore
import com.attendo.data.Timetable
import com.attendo.data.TimetableRepository
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SeedUiState(
    val loaded: Boolean = false,
    val sections: List<String> = emptyList(),
    val section: String? = null,
    val batch: String? = null,
    val batchOptions: List<String> = emptyList(),
    val plan: SeedPlan? = null,
    /** Subject codes ticked for creation. */
    val selected: Set<String> = emptySet(),
    /** Codes already tracked, which cannot be ticked — seeding twice would double-count. */
    val alreadyTracked: Set<String> = emptySet(),
    /** Lines the bundled CSV could not read, worth admitting to rather than hiding. */
    val problems: Int = 0,
    /** How many courses were created, once the apply lands. The screen's cue to leave. */
    val created: Int? = null,
) {
    val proposals: List<SeedProposal> get() = plan?.proposals.orEmpty()

    val chosen: List<SeedProposal> get() = proposals.filter { it.course.code in selected }

    val canApply: Boolean get() = chosen.isNotEmpty()

    val unitsChosen: Int get() = chosen.sumOf { it.unitsPerWeek }

    fun isTracked(proposal: SeedProposal): Boolean = proposal.course.code in alreadyTracked
}

/**
 * Turns a page of the printed timetable into courses.
 *
 * This is the difference between an app a student sets up in a minute and one they never
 * finish setting up — twenty slots typed by hand is where good intentions die. Nothing is
 * written until [apply], because the grid cannot know which elective group or lab batch
 * the student is actually in.
 */
class SeedViewModel(
    private val attendance: AttendanceRepository,
    private val timetables: TimetableRepository,
    private val settings: SettingsStore,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    /** Null until the CSV has been parsed off the main thread. */
    private val timetable = MutableStateFlow<Timetable?>(null)
    private val choice = MutableStateFlow(Choice(settings.current.section, settings.current.batch))

    /** Null means "the student has not touched the ticks", which is what [defaultFor] fills. */
    private val selection = MutableStateFlow<Set<String>?>(null)
    private val created = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch { timetable.value = timetables.timetable() }
    }

    val state: StateFlow<SeedUiState> = combine(
        timetable,
        choice,
        selection,
        created,
        attendance.courses,
    ) { loadedTimetable, current, ticked, createdCount, courses ->
        if (loadedTimetable == null) return@combine SeedUiState(created = createdCount)

        val sections = RoomAvailability.sections(loadedTimetable.bookings)
        val tracked = courses.mapTo(mutableSetOf()) { it.code }
        val plan = current.section?.let { section ->
            SectionSeeder.plan(
                bookings = loadedTimetable.bookings,
                section = section,
                termStart = settings.current.calendar.termStart,
                batch = current.batch,
                subjects = loadedTimetable.subjects,
                defaultTarget = settings.current.courseTarget,
            )
        }

        SeedUiState(
            loaded = true,
            sections = sections,
            section = current.section,
            batch = current.batch,
            batchOptions = plan?.batchOptions.orEmpty(),
            plan = plan,
            selected = ticked ?: defaultFor(plan, tracked),
            alreadyTracked = tracked,
            problems = loadedTimetable.problems.size,
            created = createdCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SeedUiState())

    fun setSection(section: String) {
        // A batch label only means something within a section, so changing section drops it.
        choice.value = Choice(section, null)
        selection.value = null
    }

    fun setBatch(batch: String?) {
        choice.value = choice.value.copy(batch = batch)
        selection.value = null
    }

    fun toggle(code: String) {
        val current = state.value.selected
        selection.value = if (code in current) current - code else current + code
    }

    fun selectAll() {
        selection.value = state.value.proposals
            .filterNot { state.value.isTracked(it) }
            .mapTo(mutableSetOf()) { it.course.code }
    }

    fun selectNone() {
        selection.value = emptySet()
    }

    /**
     * Creates the ticked courses, remembers the section, then generates the sessions the
     * term has produced so far — so the app opens with a backlog to mark rather than with
     * a correct-but-empty timetable.
     */
    fun apply() {
        val chosen = state.value.chosen
        if (chosen.isEmpty()) return
        val current = choice.value

        viewModelScope.launch {
            val count = attendance.applySeed(chosen)
            settings.setSection(current.section, current.batch)
            attendance.syncSessions(settings.current.calendar, clock())
            created.value = count
        }
    }

    /** Everything not already tracked, which is what a first-run student wants ticked. */
    private fun defaultFor(plan: SeedPlan?, tracked: Set<String>): Set<String> =
        plan?.proposals
            ?.map { it.course.code }
            ?.filterNot { it in tracked }
            ?.toSet()
            .orEmpty()

    private data class Choice(val section: String?, val batch: String?)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SeedViewModel(container.attendance, container.timetable, container.settings)
            }
        }
    }
}
