package com.attendo.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.CourseStats
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.data.AttendanceRepository
import com.attendo.data.SemesterRepository
import com.attendo.data.SettingsStore
import com.attendo.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * One semester's courses and one semester's figure.
 *
 * The tally is computed over this group's courses alone, bounded by this group's own window.
 * That is the mechanism behind "never mix semesters in one attendance calculation" — there is no
 * combined figure anywhere in this state to mix them into, because there is no such number.
 */
data class SemesterGroup(
    /** The semester, or null for the courses that belong to none. */
    val semester: Semester?,
    val courses: List<CourseStats>,
    val overall: Percent?,
    val isCurrent: Boolean,
) {
    /** "Odd semester 2026–27", or the words for a course with no term. */
    val label: String get() = semester?.label ?: "Not in a semester"

    val subtitle: String? get() = semester?.rangeLabel

    val isEmpty: Boolean get() = courses.isEmpty()
}

data class CoursesUiState(
    val loaded: Boolean = false,
    /** The live semester's courses, or every course on an install with no semester. */
    val current: SemesterGroup? = null,
    val archived: List<Course> = emptyList(),
) {
    val active: List<CourseStats> get() = current?.courses.orEmpty()

    val isEmpty: Boolean
        get() = active.isEmpty() && archived.isEmpty()
}

/**
 * The course list: what is being tracked, and the way in to changing it.
 *
 * Archived courses are kept apart rather than hidden. A dropped elective should stop
 * dragging the overall percentage down, but deleting it would erase a term's worth of
 * genuine record — so archiving is the ordinary act and deleting is the deliberate one.
 *
 * Attendo keeps exactly one active semester; a previous term is preserved only as an exported
 * record or backup and then cleared (see the rollover screen), so this list is a single
 * semester's courses plus the ones a student has set aside mid-term.
 */
class CoursesViewModel(
    private val attendance: AttendanceRepository,
    private val semesters: SemesterRepository,
    private val settings: SettingsStore,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    val state: StateFlow<CoursesUiState> = combine(
        attendance.courses,
        attendance.sessions,
        semesters.current,
        settings.settings,
    ) { courses, sessions, current, appSettings ->
        val (archived, live) = courses.partition { it.archived }
        val start = appSettings.attendanceStart
        // Read once per emission rather than per group: the rows carry an unmarked count, and
        // a class that has not happened yet is not one of the things left to mark.
        val today = clock()

        // Orphans sit with the live semester for the same reason they do on the dashboard: they
        // belong to no term, and a course shown in no group at all is a course a student cannot
        // find.
        val currentCourses = live.filter {
            current == null || it.semesterId == null || it.isIn(current)
        }

        CoursesUiState(
            loaded = true,
            current = group(current, currentCourses, sessions, start, appSettings.overallTarget, today, true),
            archived = archived,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CoursesUiState())

    /**
     * One group's figures, each course read through that semester's own window.
     *
     * The window is the semester's, narrowed by the student's joining date where they set one —
     * so a late admission's figure is right in every semester at once, and no session is deleted
     * to make it so.
     *
     * [today] bounds the per-course unmarked counts and nothing else — see
     * [AttendanceEngine.courseStats].
     */
    private fun group(
        semester: Semester?,
        courses: List<Course>,
        sessions: List<ClassSession>,
        start: AttendanceStart,
        overallTarget: Percent,
        today: LocalDate,
        isCurrent: Boolean,
    ): SemesterGroup {
        val stats = AttendanceEngine.overallStats(
            courses = courses,
            sessions = sessions,
            overallTarget = overallTarget,
            window = start.windowIn(semester),
            today = today,
        )
        return SemesterGroup(
            semester = semester,
            courses = stats.perCourse,
            overall = stats.percent,
            isCurrent = isCurrent,
        )
    }

    fun setArchived(course: Course, archived: Boolean) {
        viewModelScope.launch { attendance.setArchived(course, archived) }
    }

    /** Takes every pattern and session the course ever had with it. */
    fun delete(course: Course) {
        viewModelScope.launch { attendance.deleteCourse(course) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CoursesViewModel(
                    container.attendance,
                    container.semesters,
                    container.settings,
                )
            }
        }
    }
}
