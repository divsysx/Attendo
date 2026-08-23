package com.attendo.data

import android.content.Context
import android.content.SharedPreferences
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.Percent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Everything the app needs to know that is not a course, a pattern or a session: the
 * overall attendance target, the term dates, the holiday list, which section the student
 * seeded from, and what to call them.
 *
 * Held in [SharedPreferences] rather than a Room table because it is a single row of
 * scalars that the UI reads constantly and writes rarely; a table would mean a
 * migration every time a preference is added.
 */
data class AppSettings(
    /** The threshold the dashboard's overall figure is judged against. */
    val overallTarget: Percent = Percent.DEFAULT_TARGET,
    /** The default applied to newly created courses. */
    val courseTarget: Percent = Percent.DEFAULT_TARGET,
    val calendar: AcademicCalendar = AcademicCalendar.DEFAULT_2026_27,
    /** The section last seeded from, so the Rooms tab can offer "my section" first. */
    val section: String? = null,
    /** The student's lab batch within that section, e.g. "A1". */
    val batch: String? = null,
    /**
     * What to call the student in the greeting — null or blank when they have not said.
     *
     * A label, not an identity: nothing is keyed by it, no row references it, and it is
     * never used to tell one install's data from another's. It is stored here, offline, and
     * travels in a backup only so a restored install greets the same person.
     */
    val displayName: String? = null,
    /**
     * Which date attendance is counted from — the semester's, or the student's own.
     *
     * A setting rather than a timetable mode. A spot admission changes no class and no
     * pattern; it changes which of the classes that already exist are the student's, and that
     * is applied at read time by the engine. Keeping it here is what makes it reversible.
     */
    val attendanceStart: AttendanceStart = AttendanceStart(),
) {
    /** "Hi, Divyansh" once a name is set; plain "Hi" until then. */
    val greeting: String
        get() = displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { "Hi, $it" } ?: "Hi"
}

/**
 * Reads and writes [AppSettings].
 *
 * Exposes a [StateFlow] rather than a callback: every screen that shows a percentage
 * needs the target, and a flow lets those screens recompose when it changes without
 * anything having to remember to tell them.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun setOverallTarget(target: Percent) = edit { putInt(KEY_OVERALL_TARGET, target.basisPoints) }

    fun setCourseTarget(target: Percent) = edit { putInt(KEY_COURSE_TARGET, target.basisPoints) }

    fun setTermDates(start: LocalDate, end: LocalDate) = edit {
        // Stored in the order given; AcademicCalendar rejects an end before its start, so
        // a reversed pair is corrected here rather than crashing on the next read.
        val (from, to) = if (end.isBefore(start)) end to start else start to end
        putString(KEY_TERM_START, from.toString())
        putString(KEY_TERM_END, to.toString())
    }

    fun setHolidays(dates: Set<LocalDate>) = edit { putString(KEY_HOLIDAYS, joinDates(dates)) }

    fun toggleHoliday(date: LocalDate) {
        val holidays = current.calendar.holidays
        setHolidays(if (date in holidays) holidays - date else holidays + date)
    }

    fun setWorkingSaturdays(dates: Set<LocalDate>) =
        edit { putString(KEY_WORKING_SATURDAYS, joinDates(dates)) }

    fun toggleWorkingSaturday(date: LocalDate) {
        val saturdays = current.calendar.workingSaturdays
        setWorkingSaturdays(if (date in saturdays) saturdays - date else saturdays + date)
    }

    fun setSection(section: String?, batch: String?) = edit {
        putString(KEY_SECTION, section)
        putString(KEY_BATCH, batch)
    }

    /** Stored trimmed, and cleared outright when the field is emptied. */
    fun setDisplayName(name: String?) = edit {
        putString(KEY_DISPLAY_NAME, name?.trim()?.ifEmpty { null })
    }

    /**
     * Which date attendance counts from.
     *
     * The joining date is written even when the basis is [AttendanceBasis.UNIVERSITY], so a
     * student who switches back to the university's figure and then changes their mind is not
     * asked for the date again — and, more to the point, so that switching is a toggle rather
     * than a thing that loses information.
     */
    fun setAttendanceStart(start: AttendanceStart) = edit {
        putString(KEY_ATTENDANCE_BASIS, start.basis.name)
        putString(KEY_JOINED_ON, start.joinedOn?.toString())
    }

    fun setAttendanceBasis(basis: AttendanceBasis) =
        setAttendanceStart(current.attendanceStart.copy(basis = basis))

    fun setJoiningDate(date: LocalDate?) =
        setAttendanceStart(current.attendanceStart.copy(joinedOn = date))

    /**
     * Overwrites every setting at once, for a restore.
     *
     * `clear()` first, so a preference that is absent from the backup goes back to its
     * default instead of surviving from the install being replaced — a restore that left
     * last term's holidays in place would generate classes on the wrong days.
     */
    fun replaceAll(settings: AppSettings) = edit {
        clear()
        putInt(KEY_OVERALL_TARGET, settings.overallTarget.basisPoints)
        putInt(KEY_COURSE_TARGET, settings.courseTarget.basisPoints)
        putString(KEY_TERM_START, settings.calendar.termStart.toString())
        putString(KEY_TERM_END, settings.calendar.termEnd.toString())
        putString(KEY_HOLIDAYS, joinDates(settings.calendar.holidays))
        putString(KEY_WORKING_SATURDAYS, joinDates(settings.calendar.workingSaturdays))
        putString(KEY_SECTION, settings.section)
        putString(KEY_BATCH, settings.batch)
        putString(KEY_DISPLAY_NAME, settings.displayName?.trim()?.ifEmpty { null })
        putString(KEY_ATTENDANCE_BASIS, settings.attendanceStart.basis.name)
        putString(KEY_JOINED_ON, settings.attendanceStart.joinedOn?.toString())
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        // Re-read rather than patching the cached copy, so the flow can never drift from
        // what is actually stored.
        _settings.value = read()
    }

    private fun read(): AppSettings {
        val default = AcademicCalendar.DEFAULT_2026_27
        return AppSettings(
            overallTarget = Percent.ofBasisPoints(
                prefs.getInt(KEY_OVERALL_TARGET, Percent.DEFAULT_TARGET.basisPoints),
            ),
            courseTarget = Percent.ofBasisPoints(
                prefs.getInt(KEY_COURSE_TARGET, Percent.DEFAULT_TARGET.basisPoints),
            ),
            calendar = AcademicCalendar(
                termStart = readDate(KEY_TERM_START) ?: default.termStart,
                termEnd = readDate(KEY_TERM_END) ?: default.termEnd,
                holidays = readDates(KEY_HOLIDAYS),
                workingSaturdays = readDates(KEY_WORKING_SATURDAYS),
            ),
            section = prefs.getString(KEY_SECTION, null),
            batch = prefs.getString(KEY_BATCH, null),
            displayName = prefs.getString(KEY_DISPLAY_NAME, null)?.ifBlank { null },
            attendanceStart = AttendanceStart(
                // An unrecognised basis reads as the university's, which is the reading that
                // counts everything: a corrupt preference must not silently shrink a tally.
                basis = prefs.getString(KEY_ATTENDANCE_BASIS, null)
                    ?.let { name -> AttendanceBasis.entries.firstOrNull { it.name == name } }
                    ?: AttendanceBasis.UNIVERSITY,
                joinedOn = readDate(KEY_JOINED_ON),
            ),
        )
    }

    private fun readDate(key: String): LocalDate? =
        prefs.getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun readDates(key: String): Set<LocalDate> =
        prefs.getString(key, null)
            ?.split(',')
            ?.mapNotNull { text -> runCatching { LocalDate.parse(text.trim()) }.getOrNull() }
            ?.toSet()
            .orEmpty()

    private fun joinDates(dates: Set<LocalDate>): String =
        dates.sorted().joinToString(",") { it.toString() }

    companion object {
        /** Matches the paths named in the backup rules XML. */
        const val FILE_NAME: String = "attendo-settings"

        private const val KEY_OVERALL_TARGET = "overall_target_bp"
        private const val KEY_COURSE_TARGET = "course_target_bp"
        private const val KEY_TERM_START = "term_start"
        private const val KEY_TERM_END = "term_end"
        private const val KEY_HOLIDAYS = "holidays"
        private const val KEY_WORKING_SATURDAYS = "working_saturdays"
        private const val KEY_SECTION = "section"
        private const val KEY_BATCH = "batch"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ATTENDANCE_BASIS = "attendance_basis"
        private const val KEY_JOINED_ON = "joined_on"
    }
}
