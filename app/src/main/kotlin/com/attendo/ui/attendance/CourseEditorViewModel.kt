package com.attendo.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.TimeGrid
import com.attendo.data.AttendanceRepository
import com.attendo.data.SettingsStore
import com.attendo.ui.Routes
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A weekly slot as the editor holds it: a [SessionPattern] with the room as a plain string
 * and no effective dates, both of which the repository fills in.
 */
data class SlotDraft(
    val patternId: Long = 0L,
    val dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val startHour: Int = TimeGrid.FIRST_START_HOUR,
    val units: Int = 1,
    val kind: SessionKind = SessionKind.LECTURE,
    val room: String = "",
) {
    val isNew: Boolean get() = patternId == 0L

    val slotLabel: String get() = TimeGrid.rangeLabel(startHour, units)

    val fits: Boolean get() = TimeGrid.fits(startHour, units)
}

fun SessionPattern.toDraft(): SlotDraft = SlotDraft(
    patternId = id,
    dayOfWeek = dayOfWeek,
    startHour = startHour,
    units = units,
    kind = kind,
    room = room.orEmpty(),
)

data class CourseEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val name: String = "",
    val code: String = "",
    val target: Percent = Percent.DEFAULT_TARGET,
    val archived: Boolean = false,
    val slots: List<SlotDraft> = emptyList(),
    /** Flips once the course is written, which is the screen's cue to pop. */
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = code.isNotBlank() && name.isNotBlank()

    val unitsPerWeek: Int get() = slots.sumOf { it.units }
}

/**
 * Add or change one course, and the weekly slots that generate its classes.
 *
 * Slots live here rather than on a screen of their own because a course without slots
 * generates nothing — putting them behind another navigation step is how a student ends up
 * with a course that never produces a class.
 *
 * A course that does not exist yet cannot own patterns, so slots added to a new course are
 * held in [pending] and written the moment the insert returns an id.
 */
class CourseEditorViewModel(
    private val attendance: AttendanceRepository,
    private val settings: SettingsStore,
    private val courseId: Long,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val isNew = courseId == Routes.NEW

    private val edits = MutableStateFlow(Edits())
    private val pending = MutableStateFlow<List<SlotDraft>>(emptyList())
    private val loaded = MutableStateFlow(isNew)
    private val saved = MutableStateFlow(false)

    init {
        if (!isNew) {
            viewModelScope.launch {
                attendance.course(courseId).first()?.let { course ->
                    edits.value = Edits(
                        name = course.name,
                        code = course.code,
                        target = course.targetPercent,
                        archived = course.archived,
                    )
                }
                loaded.value = true
            }
        }
    }

    val state: StateFlow<CourseEditorUiState> = combine(
        edits,
        pending,
        loaded,
        saved,
        attendance.patternsForCourse(courseId),
    ) { current, pendingSlots, isLoaded, isSaved, patterns ->
        val slots = if (isNew) {
            pendingSlots
        } else {
            patterns.map { it.toDraft() }
        }
        CourseEditorUiState(
            loaded = isLoaded,
            isNew = isNew,
            name = current.name,
            code = current.code,
            target = current.target,
            archived = current.archived,
            slots = slots.sortedWith(compareBy({ it.dayOfWeek }, { it.startHour })),
            saved = isSaved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CourseEditorUiState())

    // ---- the course itself --------------------------------------------------

    fun setName(value: String) = edits.update { it.copy(name = value) }

    fun setCode(value: String) = edits.update { it.copy(code = value) }

    fun setTarget(value: Percent) = edits.update { it.copy(target = value) }

    fun setArchived(value: Boolean) = edits.update { it.copy(archived = value) }

    fun save() {
        val current = edits.value
        if (current.code.isBlank() || current.name.isBlank()) return

        viewModelScope.launch {
            if (isNew) {
                val newId = attendance.addCourse(current.toCourse(id = Routes.NEW))
                pending.value.forEach { draft -> attendance.addPattern(draft.toPattern(newId)) }
                pending.value = emptyList()
            } else {
                val existing = attendance.course(courseId).first() ?: return@launch
                attendance.updateCourse(
                    existing.copy(
                        name = current.name.trim(),
                        code = current.code.trim(),
                        targetPercent = current.target,
                        archived = current.archived,
                    ),
                )
            }
            saved.value = true
        }
    }

    // ---- weekly slots -------------------------------------------------------

    fun addSlot(draft: SlotDraft) {
        if (!draft.fits) return
        if (isNew) {
            // Negative placeholder ids keep list keys distinct until the real ones arrive.
            pending.update { it + draft.copy(patternId = -(it.size + 1).toLong()) }
            return
        }
        viewModelScope.launch { attendance.addPattern(draft.toPattern(courseId)) }
    }

    /**
     * Applies an edit to an existing slot.
     *
     * How it is applied depends on whether the slot has produced anything yet. A pattern
     * with marked or cancelled sessions behind it is retired and replaced, so those
     * sessions keep pointing at the timetable that actually produced them; one that has
     * only ever generated unreviewed rows is simply corrected in place, which is what
     * fixing a typo on the first day of term should do.
     */
    fun updateSlot(draft: SlotDraft) {
        if (!draft.fits) return
        if (isNew) {
            pending.update { slots ->
                slots.map { if (it.patternId == draft.patternId) draft else it }
            }
            return
        }
        viewModelScope.launch {
            val old = patternById(draft.patternId) ?: return@launch
            val replacement = draft.toPattern(courseId)
            if (hasHistory(old.id)) {
                attendance.replacePattern(old, replacement.copy(id = 0L), clock())
            } else {
                attendance.updatePattern(
                    replacement.copy(
                        id = old.id,
                        effectiveFrom = old.effectiveFrom,
                        effectiveTo = old.effectiveTo,
                    ),
                )
            }
        }
    }

    /**
     * Removes a slot: retired from today if it has history, deleted outright if it has
     * none. A slot that has been attended is a fact about the term and does not get
     * un-happened by a later edit.
     */
    fun removeSlot(draft: SlotDraft) {
        if (isNew) {
            pending.update { slots -> slots.filterNot { it.patternId == draft.patternId } }
            return
        }
        viewModelScope.launch {
            val pattern = patternById(draft.patternId) ?: return@launch
            if (hasHistory(pattern.id)) {
                attendance.retirePattern(pattern, clock())
            } else {
                attendance.deletePattern(pattern)
            }
        }
    }

    private suspend fun patternById(id: Long): SessionPattern? =
        attendance.patternsForCourse(courseId).first().firstOrNull { it.id == id }

    /** Whether anything the pattern generated has been marked or cancelled. */
    private suspend fun hasHistory(patternId: Long): Boolean =
        attendance.sessionsForCourse(courseId).first().any {
            it.patternId == patternId && it.status != SessionStatus.SCHEDULED
        }

    private fun Edits.toCourse(id: Long): Course = Course(
        id = id,
        name = name.trim(),
        code = code.trim(),
        targetPercent = target,
        archived = archived,
    )

    private fun SlotDraft.toPattern(forCourseId: Long): SessionPattern = SessionPattern(
        courseId = forCourseId,
        dayOfWeek = dayOfWeek,
        startHour = startHour,
        units = units,
        kind = kind,
        room = room.trim().ifBlank { null },
        effectiveFrom = settings.current.calendar.termStart,
    )

    private data class Edits(
        val name: String = "",
        val code: String = "",
        val target: Percent = Percent.DEFAULT_TARGET,
        val archived: Boolean = false,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(courseId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CourseEditorViewModel(container.attendance, container.settings, courseId)
            }
        }
    }
}
