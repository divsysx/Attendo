package com.attendo.data

import com.attendo.core.backup.BackupPreferences
import com.attendo.core.backup.BackupSnapshot
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * A term with every awkward case in it, for the `:app` tests that never touch Android.
 *
 * `:core` has a fixture of its own, and it cannot be shared: test sources are internal to their
 * module. This one is not a copy for its own sake — it exists to exercise the two things the app
 * layer adds on top of the format, which are the ids the database is handed and the mapping
 * between `:core` models and Room's entities. So it leans on what those care about: references
 * that must survive renumbering, and *two* sessions whose patterns have been deleted, because
 * one orphan cannot show whether their order is preserved.
 *
 * Ids are scattered and unordered on purpose. Real ones are whatever the database handed out
 * over a semester of edits, and a fixture numbered 1, 2, 3 would let an id bug pass unnoticed.
 */
internal object AppBackupFixtures {

    /** Two patterns that have since been deleted. Nothing but a marked session remembers them. */
    const val DELETED_PATTERN_LOW: Long = 640L
    const val DELETED_PATTERN_HIGH: Long = 1290L

    // ---- courses ------------------------------------------------------------

    val signals: Course = Course(
        id = 88L,
        name = "Signals and Systems",
        code = "SS",
        targetPercent = Percent.ofPercent(75.0),
        colorArgb = 0xFF2244AA.toInt(),
    )

    val signalsLab: Course = Course(
        id = 12L,
        name = "Signals and Systems Lab",
        code = "SS (P)",
        targetPercent = Percent.ofPercent(85.0),
        colorArgb = 0xFF00A86B.toInt(),
    )

    /** Archived, and still carrying the history that earned its percentage. */
    val maths: Course = Course(
        id = 55L,
        name = "Engineering Mathematics III",
        code = "EM-3",
        targetPercent = Percent.ofPercent(65.0),
        archived = true,
    )

    // ---- patterns -----------------------------------------------------------

    /** Retired when the timetable was revised mid-term. */
    val lectureOld: SessionPattern = SessionPattern(
        id = 410L,
        courseId = signals.id,
        dayOfWeek = DayOfWeek.MONDAY,
        startHour = 10,
        units = 2,
        kind = SessionKind.LECTURE,
        room = "F-108",
        effectiveFrom = LocalDate.of(2026, 8, 3),
        effectiveTo = LocalDate.of(2026, 9, 25),
    )

    /** Where the same lecture went. Open-ended. */
    val lectureNew: SessionPattern = SessionPattern(
        id = 77L,
        courseId = signals.id,
        dayOfWeek = DayOfWeek.MONDAY,
        startHour = 14,
        units = 2,
        kind = SessionKind.LECTURE,
        room = "F-110",
        effectiveFrom = LocalDate.of(2026, 9, 28),
    )

    val lab: SessionPattern = SessionPattern(
        id = 950L,
        courseId = signalsLab.id,
        dayOfWeek = DayOfWeek.THURSDAY,
        startHour = 14,
        units = 2,
        kind = SessionKind.PRACTICAL,
        room = "Lab 2",
        effectiveFrom = LocalDate.of(2026, 8, 3),
    )

    val tutorial: SessionPattern = SessionPattern(
        id = 306L,
        courseId = maths.id,
        dayOfWeek = DayOfWeek.FRIDAY,
        startHour = 9,
        units = 1,
        kind = SessionKind.TUTORIAL,
        effectiveFrom = LocalDate.of(2026, 8, 3),
    )

    // ---- sessions -----------------------------------------------------------

    private val marked: Instant = Instant.parse("2026-08-10T13:00:00Z")

    val attendedInFull: ClassSession = ClassSession(
        id = 3100L,
        courseId = signals.id,
        patternId = lectureOld.id,
        date = LocalDate.of(2026, 8, 10),
        startHour = 10,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-108",
        approvedAt = marked,
    )

    /** Second hour only. The fractional case the app exists for. */
    val attendedInPart: ClassSession = ClassSession(
        id = 3101L,
        courseId = signals.id,
        patternId = lectureOld.id,
        date = LocalDate.of(2026, 8, 17),
        startHour = 10,
        unitsPlanned = 2,
        unitsMask = UnitMask.of(1),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-108",
        note = "Bus was late",
        approvedAt = marked.plusSeconds(7 * 86_400),
        lastEditedAt = Instant.parse("2026-08-18T05:20:00Z"),
    )

    /** Held and entirely missed — not the same thing as cancelled, and must stay not the same. */
    val missed: ClassSession = ClassSession(
        id = 3102L,
        courseId = signals.id,
        patternId = lectureOld.id,
        date = LocalDate.of(2026, 8, 24),
        startHour = 10,
        unitsPlanned = 2,
        unitsMask = UnitMask.NONE,
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-108",
        approvedAt = marked.plusSeconds(14 * 86_400),
    )

    /** A two-hour block that ran one hour: unitsPlanned below the pattern's units. */
    val shortened: ClassSession = ClassSession(
        id = 3103L,
        courseId = signalsLab.id,
        patternId = lab.id,
        date = LocalDate.of(2026, 8, 6),
        startHour = 14,
        unitsPlanned = 1,
        unitsMask = UnitMask.of(0),
        status = SessionStatus.HELD,
        kind = SessionKind.PRACTICAL,
        room = "Lab 2",
        note = "Ran an hour, power cut",
        approvedAt = marked,
    )

    val cancelledByFaculty: ClassSession = ClassSession(
        id = 3104L,
        courseId = signalsLab.id,
        patternId = lab.id,
        date = LocalDate.of(2026, 8, 13),
        startHour = 14,
        unitsPlanned = 2,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.FACULTY_CANCELLED,
        kind = SessionKind.PRACTICAL,
        room = "Lab 2",
    )

    val cancelledForHoliday: ClassSession = ClassSession(
        id = 3105L,
        courseId = maths.id,
        patternId = tutorial.id,
        date = LocalDate.of(2026, 8, 14),
        startHour = 9,
        unitsPlanned = 1,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.HOLIDAY,
        kind = SessionKind.TUTORIAL,
    )

    /** Half one of a reschedule: cancelled, pointing forward at what replaced it. */
    val movedAway: ClassSession = ClassSession(
        id = 3106L,
        courseId = signals.id,
        patternId = lectureOld.id,
        date = LocalDate.of(2026, 8, 31),
        startHour = 10,
        unitsPlanned = 2,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.RESCHEDULED,
        kind = SessionKind.LECTURE,
        room = "F-108",
        movedToSessionId = 3107L,
    )

    /** Half two: the ad-hoc landing slot, pointing back. This is the row with the attendance. */
    val movedTo: ClassSession = ClassSession(
        id = 3107L,
        courseId = signals.id,
        date = LocalDate.of(2026, 9, 2),
        startHour = 16,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-201",
        approvedAt = Instant.parse("2026-09-02T17:30:00Z"),
        movedFromSessionId = 3106L,
    )

    /** An extra class with nothing on either side of it. */
    val extra: ClassSession = ClassSession(
        id = 3108L,
        courseId = signalsLab.id,
        date = LocalDate.of(2026, 9, 5),
        startHour = 11,
        unitsPlanned = 1,
        unitsMask = UnitMask.of(0),
        status = SessionStatus.HELD,
        kind = SessionKind.PRACTICAL,
        room = "Lab 2",
        note = "Extra viva slot",
        approvedAt = Instant.parse("2026-09-05T12:00:00Z"),
    )

    /** Generated, never reviewed. Counts for nothing, and must not start counting. */
    val awaitingReview: ClassSession = ClassSession(
        id = 3109L,
        courseId = signals.id,
        patternId = lectureNew.id,
        date = LocalDate.of(2026, 10, 5),
        startHour = 14,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.SCHEDULED,
        kind = SessionKind.LECTURE,
        room = "F-110",
    )

    /**
     * Marked, and its pattern deleted afterwards — the lower of the two orphan ids.
     *
     * `SessionDao.deleteUnreviewedForPattern` keeps reviewed classes when a slot is removed, so
     * a `patternId` naming a pattern that no longer exists is legitimate rather than corrupt. It
     * has to stay a *timetabled* class: origin is derived from `patternId`, and dropping the
     * reference would both relabel it as ad-hoc and free its `(patternId, date)` slot for the
     * generator to fill again.
     */
    val orphanedEarly: ClassSession = ClassSession(
        id = 3110L,
        courseId = signalsLab.id,
        patternId = DELETED_PATTERN_LOW,
        date = LocalDate.of(2026, 8, 20),
        startHour = 14,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.PRACTICAL,
        room = "Lab 2",
        approvedAt = Instant.parse("2026-08-20T16:00:00Z"),
    )

    /** The higher of the two orphan ids, so their relative order is observable. */
    val orphanedLate: ClassSession = ClassSession(
        id = 3111L,
        courseId = maths.id,
        patternId = DELETED_PATTERN_HIGH,
        date = LocalDate.of(2026, 8, 21),
        startHour = 9,
        unitsPlanned = 1,
        unitsMask = UnitMask.NONE,
        status = SessionStatus.HELD,
        kind = SessionKind.TUTORIAL,
        approvedAt = Instant.parse("2026-08-21T10:30:00Z"),
    )

    // ---- assembled ----------------------------------------------------------

    val calendar: AcademicCalendar = AcademicCalendar(
        termStart = LocalDate.of(2026, 8, 3),
        termEnd = LocalDate.of(2026, 12, 12),
        holidays = setOf(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 10, 2)),
        workingSaturdays = setOf(LocalDate.of(2026, 9, 19)),
    )

    val preferences: BackupPreferences = BackupPreferences(
        overallTarget = Percent.ofPercent(70.0),
        courseTarget = Percent.ofPercent(85.0),
        section = "2nd Yr ECE-A1",
        batch = "A1",
        displayName = "Divyansh",
    )

    val courses: List<Course> = listOf(maths, signals, signalsLab)

    val patterns: List<SessionPattern> = listOf(lab, lectureNew, tutorial, lectureOld)

    val sessions: List<ClassSession> = listOf(
        awaitingReview,
        movedTo,
        attendedInFull,
        orphanedLate,
        cancelledByFaculty,
        shortened,
        movedAway,
        missed,
        orphanedEarly,
        extra,
        attendedInPart,
        cancelledForHoliday,
    )

    /** The whole fixture, with the lists deliberately unsorted. */
    fun snapshot(): BackupSnapshot = BackupSnapshot(
        courses = courses,
        patterns = patterns,
        sessions = sessions,
        calendar = calendar,
        preferences = preferences,
    )

    /** The same term with nothing whose pattern has been deleted. */
    fun withoutOrphans(): BackupSnapshot = snapshot().copy(
        sessions = sessions - orphanedEarly - orphanedLate,
    )
}
