package com.attendo.core.rollover

import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.engine.CourseStats
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SessionStatus
import java.time.LocalDate

/**
 * One course's line in the Semester Record.
 *
 * The numbers here are the same numbers the app already shows: [AttendanceEngine.courseStats]
 * is the one source of truth, and this row is its output laid flat for printing. Nothing is
 * recomputed — see [SemesterRecordRenderer.render].
 *
 * [percent] is null when the course has held no classes yet, the same "nothing held" state the
 * dashboard renders as an em dash rather than 0%.
 */
data class CourseRow(
    val code: String,
    val name: String,
    val percent: Percent?,
    val unitsHeld: Int,
    val unitsAttended: Int,
    val target: Percent,
    val meetsTarget: Boolean,
    val sessionsHeld: Int,
    val sessionsCancelled: Int,
    val sessionsAwaitingReview: Int,
)

/**
 * One session in the optional history appendix of the record.
 *
 * The appendix is what makes the record a record rather than a summary: the per-course figures
 * are the headline, but the row-by-row history is the detail a student needs to challenge a
 * faculty's register or reconstruct a disputed day.
 *
 * [statusLabel] is a printed word ("Held", "Cancelled", "Scheduled") rather than the enum, so the
 * PDF rendering layer does not have to know the engine's vocabulary. [unitsAttended] is reported
 * as zero unless the session was [SessionStatus.HELD]: a SCHEDULED row carries a pre-filled
 * present mask, but it is a class nobody decided, so printing its mask count would read as
 * attendance that was never recorded.
 */
data class SessionRow(
    val date: LocalDate,
    val courseCode: String,
    val startHour: Int,
    val unitsPlanned: Int,
    val unitsAttended: Int,
    val statusLabel: String,
)

/**
 * The complete, render-ready contents of a Semester Record PDF.
 *
 * Pure data — no Android, no I/O. [SemesterRecordRenderer.render] builds it; the app's PDF layer
 * ([com.attendo.data.SemesterRecordPdf]) paints it. Splitting the two is what lets the document's
 * contents be unit-tested without a PDF library: the tests check that the right numbers land in
 * the right fields, and a viewer check that the right fields land on the page are separate concerns.
 *
 * [overallPercent] is null when nothing has been held across the whole semester — the same
 * "undefined, not 0%" convention the engine uses everywhere.
 */
data class SemesterRecordDoc(
    val title: String,
    val displayName: String?,
    val semesterLabel: String,
    val academicYear: String,
    val dateRange: String,
    val exportedOn: LocalDate,
    val overallPercent: Percent?,
    val overallUnitsAttended: Int,
    val overallUnitsHeld: Int,
    val target: Percent,
    val section: String?,
    val batch: String?,
    val courses: List<CourseRow>,
    val sessions: List<SessionRow>,
)

/**
 * Builds the [SemesterRecordDoc] for one semester.
 *
 * The work is delegated to [AttendanceEngine.semesterStats], the same pure function the
 * dashboard and the course detail screen read from. That keeps the record's figures identical to
 * the figures the student has been looking at all term — the record is a printed copy of the
 * screen, not a second calculation that could disagree with it.
 *
 * Sessions in the appendix are bounded to the semester's window and sorted oldest-first, so the
 * appendix reads as a chronological log of the term.
 */
object SemesterRecordRenderer {

    const val MIME_TYPE: String = "application/pdf"

    /** "attendo-semester-odd-2026-2027-2026-08-23.pdf" — reads on any device without opening it. */
    fun suggestedFileName(semester: Semester, on: LocalDate): String {
        val type = semester.type.name.lowercase()
        val year = semester.year
        val academic = semester.academicYear.replace("–", "-")
        return "attendo-semester-$type-$year-$academic-${on}.pdf"
    }

    /**
     * Renders the record for [semester] from the raw courses and sessions.
     *
     * [courses] and [sessions] may be the whole install's — [semesterStats] narrows both to the
     * semester, which is what stops another term's classes leaking into this record. [start] and
     * [today] are passed through unchanged and mean the same thing they do on the dashboard.
     */
    fun render(
        semester: Semester,
        courses: List<Course>,
        sessions: List<ClassSession>,
        overallTarget: Percent,
        start: AttendanceStart,
        today: LocalDate,
        displayName: String?,
        section: String?,
        batch: String?,
        exportedOn: LocalDate,
    ): SemesterRecordDoc {
        val stats = AttendanceEngine.semesterStats(
            semester = semester,
            courses = courses,
            sessions = sessions,
            overallTarget = overallTarget,
            start = start,
            today = today,
        )
        val window = start.windowIn(semester)
        return SemesterRecordDoc(
            title = "Attendo — Semester Record",
            displayName = displayName?.trim()?.ifBlank { null },
            semesterLabel = semester.label,
            academicYear = semester.academicYear,
            dateRange = semester.rangeLabel,
            exportedOn = exportedOn,
            overallPercent = stats.percent,
            overallUnitsAttended = stats.tally.unitsAttended,
            overallUnitsHeld = stats.tally.unitsHeld,
            target = overallTarget,
            section = section?.ifBlank { null },
            batch = batch?.ifBlank { null },
            courses = stats.perCourse
                .filter { it.sessionsHeld > 0 || it.sessionsCancelled > 0 || it.sessionsAwaitingReview > 0 }
                .sortedWith(compareBy({ it.course.code }, { it.course.name }))
                .map { it.toRow() },
            sessions = sessions
                .filter { it.date in window }
                .sortedWith(compareBy({ it.date }, { it.startHour }))
                .map { it.toRow(courses) },
        )
    }
}

/** Maps an engine [CourseStats] to a printable row, keeping its figures verbatim. */
private fun CourseStats.toRow(): CourseRow = CourseRow(
    code = course.code,
    name = course.name,
    percent = percent,
    unitsHeld = tally.unitsHeld,
    unitsAttended = tally.unitsAttended,
    target = target.target,
    meetsTarget = target.meetsTarget,
    sessionsHeld = sessionsHeld,
    sessionsCancelled = sessionsCancelled,
    sessionsAwaitingReview = sessionsAwaitingReview,
)

/** Maps a session to a printable row, resolving its course code from [courses]. */
private fun ClassSession.toRow(courses: List<Course>): SessionRow {
    val code = courses.firstOrNull { it.id == courseId }?.code ?: "—"
    val label = when (status) {
        SessionStatus.HELD -> "Held"
        SessionStatus.CANCELLED -> "Cancelled"
        SessionStatus.SCHEDULED -> "Scheduled"
    }
    return SessionRow(
        date = date,
        courseCode = code,
        startHour = startHour,
        unitsPlanned = unitsPlanned,
        unitsAttended = if (status == SessionStatus.HELD) unitsAttended else 0,
        statusLabel = label,
    )
}
