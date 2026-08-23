package com.attendo.core.backup

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import java.time.Instant
import java.time.LocalDate

/**
 * The settings that belong to a backup — everything in the app's preferences except the
 * calendar, which travels as its own object because it is a domain type in its own right.
 *
 * Deliberately not the app's `AppSettings`: that lives in `:app` and may grow fields that
 * describe the installation rather than the semester (a theme choice, a dismissed hint).
 * A backup carries what a student would be upset to lose.
 */
data class BackupPreferences(
    val overallTarget: Percent = Percent.DEFAULT_TARGET,
    val courseTarget: Percent = Percent.DEFAULT_TARGET,
    /** The section last seeded from, so a restored install still knows "my section". */
    val section: String? = null,
    val batch: String? = null,
    /**
     * What the student calls themselves, for the greeting — null when they never set one.
     *
     * It travels with the backup so a restored install greets the same person, and so the
     * import screen can say whose file it is looking at. It is a label and nothing else:
     * nothing keys off it, and two students with the same name are not related in any way.
     */
    val displayName: String? = null,
    /**
     * When the student's attendance is counted from — the university's semester start, or the
     * day they were admitted.
     *
     * This travels because it is not a display preference: it changes the percentage. A student
     * who joined in week three and restored onto a new phone without it would find their figure
     * had quietly changed to the university's reading of it.
     */
    val attendanceStart: AttendanceStart = AttendanceStart(),
)

/**
 * Everything Attendo knows, in domain types.
 *
 * This is the whole logical state, not the visible one: retired patterns, cancelled
 * sessions, the ad-hoc landing slots of reschedules and the links between them all travel,
 * because a restore has to be able to reproduce the *history*, not just today's
 * percentage. Ids are carried as they were, and the cross-references between sessions
 * ([ClassSession.movedToSessionId]) are id-based, so anything that renumbers rows must go
 * through [normalised] or the reference-based wire format rather than reassigning by hand.
 */
data class BackupSnapshot(
    val courses: List<Course> = emptyList(),
    val patterns: List<SessionPattern> = emptyList(),
    val sessions: List<ClassSession> = emptyList(),
    val calendar: AcademicCalendar = AcademicCalendar.DEFAULT_2026_27,
    val preferences: BackupPreferences = BackupPreferences(),
    /**
     * Every semester, live and archived, in no particular order.
     *
     * They travel because a semester is what makes a percentage mean something. Without them a
     * restored install has six courses from last term and five from this one and no way to tell
     * which is which, and the one guarantee this app makes about semesters — that it never adds
     * two of them together — becomes unenforceable.
     */
    val semesters: List<Semester> = emptyList(),
) {
    val isEmpty: Boolean get() = courses.isEmpty() && patterns.isEmpty() && sessions.isEmpty()

    /** The semester still running, if the file names one. */
    val currentSemester: Semester?
        get() = semesters.filterNot { it.archived }.maxByOrNull { it.startDate }

    /**
     * The same data with row ids reassigned canonically.
     *
     * Ids are an artefact of whichever database wrote the rows: export from one phone,
     * import to another, and every primary key changes while the data means exactly the
     * same thing. Normalising renumbers courses, patterns and sessions from a
     * content-derived ordering and rewrites every reference to match, so two snapshots can
     * be compared for *logical* equality — which is the only equality a backup can promise.
     *
     * Ordering is content-derived rather than id-derived so it survives the trip.
     */
    fun normalised(): BackupSnapshot {
        // Semesters first: a course's semester link is rewritten with the rest of its fields,
        // so the mapping has to exist before the courses are renumbered.
        val orderedSemesters = semesters.sortedWith(
            compareBy({ it.startDate }, { it.year }, { it.type.ordinal }, { it.id }),
        )
        val semesterIds = orderedSemesters.withIndex().associate { (i, s) -> s.id to i + 1L }

        val orderedCourses = courses.sortedWith(
            compareBy({ it.code }, { it.name }, { it.id }),
        )
        val courseIds = orderedCourses.withIndex().associate { (i, c) -> c.id to i + 1L }

        val orderedPatterns = patterns.sortedWith(
            compareBy(
                { courseIds[it.courseId] ?: Long.MAX_VALUE },
                { it.dayOfWeek.value },
                { it.startHour },
                { it.units },
                { it.effectiveFrom },
                { it.effectiveTo?.toString() ?: "" },
                { it.id },
            ),
        )
        val patternIds = orderedPatterns.withIndex()
            .associate { (i, p) -> p.id to i + 1L }
            .toMutableMap()

        // A session can outlive the pattern that generated it. Deleting a pattern keeps the
        // classes already marked — see SessionDao.deleteUnreviewedForPattern — so a reviewed
        // row's patternId can name a pattern that is no longer there. Those ids get numbers
        // of their own past the end of the real patterns rather than being dropped: origin
        // is derived from patternId, so dropping one would relabel a timetabled class as
        // ad-hoc *and* free its (patternId, date) slot for the generator to fill again.
        sessions.mapNotNull { it.patternId }
            .distinct()
            .filter { it !in patternIds }
            .sorted()
            .forEachIndexed { index, orphan ->
                patternIds[orphan] = orderedPatterns.size + 1L + index
            }

        val orderedSessions = sessions.sortedWith(
            compareBy(
                { it.date },
                { it.startHour },
                { courseIds[it.courseId] ?: Long.MAX_VALUE },
                { it.patternId?.let(patternIds::get) ?: Long.MAX_VALUE },
                { it.id },
            ),
        )
        val sessionIds = orderedSessions.withIndex().associate { (i, s) -> s.id to i + 1L }

        return copy(
            semesters = orderedSemesters.mapIndexed { i, semester -> semester.copy(id = i + 1L) },
            courses = orderedCourses.mapIndexed { i, course ->
                // A link to a semester that is not in the file is not a link. It becomes null
                // rather than a number pointing nowhere, which puts the course in exactly the
                // state a pre-semester install leaves it in — and that state has a defined
                // outcome: the app adopts it into the current semester on first run.
                course.copy(id = i + 1L, semesterId = course.semesterId?.let(semesterIds::get))
            },
            patterns = orderedPatterns.mapIndexed { i, pattern ->
                pattern.copy(id = i + 1L, courseId = courseIds[pattern.courseId] ?: 0L)
            },
            sessions = orderedSessions.mapIndexed { i, session ->
                session.copy(
                    id = i + 1L,
                    courseId = courseIds[session.courseId] ?: 0L,
                    patternId = session.patternId?.let(patternIds::getValue),
                    // A link to a session that is not here is not a link. The replacement of
                    // a reschedule is an ad-hoc row and can be deleted on its own, which
                    // leaves the cancelled original pointing at nothing; the cancellation
                    // itself is still true, so the row keeps its status and loses the arrow.
                    movedToSessionId = session.movedToSessionId?.let(sessionIds::get),
                    movedFromSessionId = session.movedFromSessionId?.let(sessionIds::get),
                )
            },
        )
    }
}

/** Who wrote a backup file, when, and to which version of the format. */
data class BackupMeta(
    val formatVersion: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val exportedAt: Instant,
    /** The version the file was written as, before any migration on the way in. */
    val originalFormatVersion: Int = formatVersion,
)

/** A backup file, read and understood: its provenance plus its contents. */
data class Backup(
    val meta: BackupMeta,
    val snapshot: BackupSnapshot,
) {
    val summary: BackupSummary get() = BackupSummary.of(this)
}

/**
 * What the import screen shows before anything is written.
 *
 * A restore in REPLACE mode is destructive by design, so the student gets to see what they
 * are about to swap in — how many courses, how much history, whose semester it is — and
 * recognise a file picked by mistake before it costs them anything.
 */
data class BackupSummary(
    val exportedAt: Instant,
    val appVersionName: String,
    val formatVersion: Int,
    val courses: Int,
    val archivedCourses: Int,
    val patterns: Int,
    val retiredPatterns: Int,
    val sessions: Int,
    val reviewedSessions: Int,
    val cancelledSessions: Int,
    val adhocSessions: Int,
    val rescheduledSessions: Int,
    val unitsHeld: Int,
    val unitsAttended: Int,
    val firstSession: LocalDate?,
    val lastSession: LocalDate?,
    val termStart: LocalDate,
    val termEnd: LocalDate,
    val holidays: Int,
    val workingSaturdays: Int,
    val section: String?,
    val batch: String?,
    /** The display name the file was written with, or null if it was written without one. */
    val displayName: String? = null,
    val semesters: Int = 0,
    val archivedSemesters: Int = 0,
    /** "Odd semester 2026–27", or null for a file written before semesters existed. */
    val currentSemester: String? = null,
    val attendanceStart: AttendanceStart = AttendanceStart(),
) {
    /**
     * Who made this file — the point of showing it at all.
     *
     * A student moving to a new phone wants to recognise their own export, so this is their
     * name when the file carries one. Files written before display names existed, and by
     * anyone who never set one, fall back to the app's name: [BackupCodec.APP_NAME], and
     * never the app's *version*, which says nothing about whose semester is in the file.
     */
    val writtenBy: String get() = displayName?.takeIf { it.isNotBlank() } ?: BackupCodec.APP_NAME

    /** The percentage the restored install will show, so it can be checked against the old phone. */
    val overallPercent: Percent? get() = Percent.ofRatio(unitsAttended, unitsHeld)

    val isEmpty: Boolean get() = courses == 0 && patterns == 0 && sessions == 0

    companion object {
        fun of(backup: Backup): BackupSummary {
            val snapshot = backup.snapshot
            val held = snapshot.sessions.filter { it.status == SessionStatus.HELD }
            return BackupSummary(
                exportedAt = backup.meta.exportedAt,
                appVersionName = backup.meta.appVersionName,
                formatVersion = backup.meta.originalFormatVersion,
                courses = snapshot.courses.size,
                archivedCourses = snapshot.courses.count { it.archived },
                patterns = snapshot.patterns.size,
                retiredPatterns = snapshot.patterns.count { it.effectiveTo != null },
                sessions = snapshot.sessions.size,
                reviewedSessions = snapshot.sessions.count { !it.isAwaitingReview },
                cancelledSessions = snapshot.sessions.count { it.isCancelled },
                adhocSessions = snapshot.sessions.count { it.patternId == null },
                rescheduledSessions = snapshot.sessions.count { it.wasRescheduledAway },
                unitsHeld = held.sumOf { it.unitsPlanned },
                unitsAttended = held.sumOf { it.unitsAttended },
                firstSession = snapshot.sessions.minOfOrNull { it.date },
                lastSession = snapshot.sessions.maxOfOrNull { it.date },
                termStart = snapshot.calendar.termStart,
                termEnd = snapshot.calendar.termEnd,
                holidays = snapshot.calendar.holidays.size,
                workingSaturdays = snapshot.calendar.workingSaturdays.size,
                section = snapshot.preferences.section,
                batch = snapshot.preferences.batch,
                displayName = snapshot.preferences.displayName,
                semesters = snapshot.semesters.size,
                archivedSemesters = snapshot.semesters.count { it.archived },
                currentSemester = snapshot.currentSemester?.label,
                attendanceStart = snapshot.preferences.attendanceStart,
            )
        }
    }
}
