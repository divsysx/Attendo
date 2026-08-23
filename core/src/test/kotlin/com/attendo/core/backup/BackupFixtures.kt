package com.attendo.core.backup

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionPattern
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A semester with one of everything in it.
 *
 * The point of a single shared fixture is that the round-trip tests, the summary tests and the
 * CSV tests all argue about the *same* data, so a state that survives one is not quietly
 * missing from another. Every branch the backup format has to carry appears here: a retired
 * pattern and its replacement, a class attended in part, a class held and entirely missed, a
 * shortened class, two flavours of cancellation, a reschedule as its two linked rows, a
 * standalone extra class, a class still awaiting review, an archived course, and a marked class
 * whose pattern has since been deleted.
 *
 * Ids are deliberately scattered and out of order. Real ones are whatever the database handed
 * out over a semester of edits, and a backup has to survive being renumbered on the way in — a
 * fixture numbered 1, 2, 3 would let an id bug pass unnoticed.
 */
internal object BackupFixtures {

    // Semesters ----------------------------------------------------------------

    /** The term the rest of the fixture happens in. Its span matches [calendar] exactly. */
    val currentSemester: Semester = Semester(
        id = 88L,
        year = 2026,
        type = SemesterType.ODD,
        startDate = LocalDate.of(2026, 7, 28),
        endDate = LocalDate.of(2026, 12, 15),
    )

    /**
     * The term before it, archived.
     *
     * Empty of courses on purpose: an archived semester whose subjects the student deleted is
     * still a semester, and it is the case that catches a decoder which infers the semester list
     * from the courses rather than reading it. It is also what makes [BackupSnapshot.currentSemester]
     * a real choice instead of "the only one".
     */
    val previousSemester: Semester = Semester(
        id = 12L,
        year = 2026,
        type = SemesterType.EVEN,
        startDate = LocalDate.of(2026, 1, 5),
        endDate = LocalDate.of(2026, 5, 15),
        archived = true,
    )

    // Courses ------------------------------------------------------------------

    val adec: Course = Course(
        id = 41L,
        name = "Analog and Digital Electronic Circuits",
        code = "ADEC",
        targetPercent = Percent.ofPercent(75.0),
        colorArgb = 0xFF3366CC.toInt(),
        semesterId = currentSemester.id,
    )

    val adecLab: Course = Course(
        id = 17L,
        name = "Analog and Digital Electronic Circuits Lab",
        code = "ADEC (P)",
        targetPercent = Percent.ofPercent(80.0),
        colorArgb = 0xFF11AA55.toInt(),
        semesterId = currentSemester.id,
    )

    /**
     * Archived: a course that finished mid-semester still belongs in the backup.
     *
     * Archived and yet in the *current* semester, which is the pair of states that has to be
     * kept apart — a course being put away says nothing about which term taught it, and its
     * sessions in August are the proof.
     */
    val evs: Course = Course(
        id = 63L,
        name = "Environmental Studies",
        code = "EVS-2",
        targetPercent = Percent.ofPercent(65.0),
        archived = true,
        semesterId = currentSemester.id,
    )

    // Patterns -----------------------------------------------------------------

    /** Retired when the timetable was revised — the reason effectiveTo exists. */
    val adecMondayOld: SessionPattern = SessionPattern(
        id = 205L,
        courseId = adec.id,
        dayOfWeek = DayOfWeek.MONDAY,
        startHour = 9,
        units = 2,
        kind = SessionKind.LECTURE,
        room = "F-212",
        effectiveFrom = LocalDate.of(2026, 7, 28),
        effectiveTo = LocalDate.of(2026, 9, 30),
    )

    /** The slot the same lecture moved to, open-ended. */
    val adecMondayNew: SessionPattern = SessionPattern(
        id = 118L,
        courseId = adec.id,
        dayOfWeek = DayOfWeek.MONDAY,
        startHour = 11,
        units = 2,
        kind = SessionKind.LECTURE,
        room = "F-214",
        effectiveFrom = LocalDate.of(2026, 10, 1),
    )

    val labWednesday: SessionPattern = SessionPattern(
        id = 902L,
        courseId = adecLab.id,
        dayOfWeek = DayOfWeek.WEDNESDAY,
        startHour = 14,
        units = 2,
        kind = SessionKind.PRACTICAL,
        room = "Lab 3",
        effectiveFrom = LocalDate.of(2026, 7, 28),
    )

    val evsTutorial: SessionPattern = SessionPattern(
        id = 333L,
        courseId = evs.id,
        dayOfWeek = DayOfWeek.FRIDAY,
        startHour = 15,
        units = 1,
        kind = SessionKind.TUTORIAL,
        effectiveFrom = LocalDate.of(2026, 7, 28),
    )

    /** A pattern that no longer exists. Nothing references it except [labFromDeletedPattern]. */
    const val DELETED_PATTERN_ID: Long = 1234L

    // Sessions -----------------------------------------------------------------

    private val approved: Instant = Instant.parse("2026-08-03T12:30:00Z")
    private val edited: Instant = Instant.parse("2026-08-24T18:05:00Z")

    val fullyAttended: ClassSession = ClassSession(
        id = 7001L,
        courseId = adec.id,
        patternId = adecMondayOld.id,
        date = LocalDate.of(2026, 8, 3),
        startHour = 9,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-212",
        approvedAt = approved,
    )

    /** Second hour only — the fractional case the whole app exists for. */
    val halfAttended: ClassSession = ClassSession(
        id = 7002L,
        courseId = adec.id,
        patternId = adecMondayOld.id,
        date = LocalDate.of(2026, 8, 10),
        startHour = 9,
        unitsPlanned = 2,
        unitsMask = UnitMask.of(1),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-212",
        approvedAt = approved.plusSeconds(7 * 86_400),
        note = "Missed the first hour",
    )

    /** Held and entirely missed. Genuinely different from cancelled, and must stay so. */
    val fullyMissed: ClassSession = ClassSession(
        id = 7003L,
        courseId = adec.id,
        patternId = adecMondayOld.id,
        date = LocalDate.of(2026, 8, 17),
        startHour = 9,
        unitsPlanned = 2,
        unitsMask = UnitMask.NONE,
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-212",
        approvedAt = approved.plusSeconds(14 * 86_400),
    )

    /** A two-hour block that ran one hour: unitsPlanned below the pattern's units. */
    val shortened: ClassSession = ClassSession(
        id = 7004L,
        courseId = adec.id,
        patternId = adecMondayOld.id,
        date = LocalDate.of(2026, 8, 24),
        startHour = 9,
        unitsPlanned = 1,
        unitsMask = UnitMask.of(0),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-212",
        note = "Ran one hour only",
        approvedAt = approved.plusSeconds(21 * 86_400),
        lastEditedAt = edited,
    )

    /** The note carries a comma and a quote, which the CSV export has to escape. */
    val cancelledByFaculty: ClassSession = ClassSession(
        id = 7005L,
        courseId = adecLab.id,
        patternId = labWednesday.id,
        date = LocalDate.of(2026, 8, 5),
        startHour = 14,
        unitsPlanned = 2,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.FACULTY_CANCELLED,
        kind = SessionKind.PRACTICAL,
        room = "Lab 3",
        note = "Sir was away, said \"we'll cover it later\"",
    )

    val cancelledForHoliday: ClassSession = ClassSession(
        id = 7006L,
        courseId = evs.id,
        patternId = evsTutorial.id,
        date = LocalDate.of(2026, 8, 7),
        startHour = 15,
        unitsPlanned = 1,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.HOLIDAY,
        kind = SessionKind.TUTORIAL,
    )

    /** Half one of a reschedule: cancelled, pointing forward at its replacement. */
    val movedAway: ClassSession = ClassSession(
        id = 7007L,
        courseId = adec.id,
        patternId = adecMondayOld.id,
        date = LocalDate.of(2026, 8, 31),
        startHour = 9,
        unitsPlanned = 2,
        status = SessionStatus.CANCELLED,
        cancellationReason = CancellationReason.RESCHEDULED,
        kind = SessionKind.LECTURE,
        room = "F-212",
        movedToSessionId = 7008L,
    )

    /** Half two: the ad-hoc landing slot, pointing back. This one carries the attendance. */
    val movedTo: ClassSession = ClassSession(
        id = 7008L,
        courseId = adec.id,
        date = LocalDate.of(2026, 9, 2),
        startHour = 16,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.LECTURE,
        room = "F-301",
        approvedAt = Instant.parse("2026-09-02T18:00:00Z"),
        movedFromSessionId = 7007L,
    )

    /** A plain extra class: ad-hoc, with nothing on either side of it. */
    val extraClass: ClassSession = ClassSession(
        id = 7009L,
        courseId = adecLab.id,
        date = LocalDate.of(2026, 9, 4),
        startHour = 10,
        unitsPlanned = 1,
        unitsMask = UnitMask.of(0),
        status = SessionStatus.HELD,
        kind = SessionKind.PRACTICAL,
        room = "Lab 3",
        note = "Makeup viva",
        approvedAt = Instant.parse("2026-09-04T11:15:00Z"),
    )

    /** Generated but never reviewed: counts for nothing, and must not start counting. */
    val awaitingReview: ClassSession = ClassSession(
        id = 7010L,
        courseId = adec.id,
        patternId = adecMondayNew.id,
        date = LocalDate.of(2026, 10, 5),
        startHour = 11,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.SCHEDULED,
        kind = SessionKind.LECTURE,
        room = "F-214",
    )

    /**
     * Marked, and its pattern has since been deleted.
     *
     * `SessionDao.deleteUnreviewedForPattern` keeps reviewed classes when a slot is removed, so
     * this row's patternId names a pattern that is not in [snapshot]. It has to come back as a
     * *timetabled* class all the same — origin is derived from patternId, and an empty
     * `(patternId, date)` slot is one the generator would fill in again.
     */
    val labFromDeletedPattern: ClassSession = ClassSession(
        id = 7011L,
        courseId = adecLab.id,
        patternId = DELETED_PATTERN_ID,
        date = LocalDate.of(2026, 8, 12),
        startHour = 14,
        unitsPlanned = 2,
        unitsMask = UnitMask.allPresent(2),
        status = SessionStatus.HELD,
        kind = SessionKind.PRACTICAL,
        room = "Lab 3",
        approvedAt = Instant.parse("2026-08-12T16:00:00Z"),
    )

    // Assembled ----------------------------------------------------------------

    val calendar: AcademicCalendar = AcademicCalendar(
        termStart = LocalDate.of(2026, 7, 28),
        termEnd = LocalDate.of(2026, 12, 15),
        holidays = setOf(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 10, 2)),
        workingSaturdays = setOf(LocalDate.of(2026, 9, 12)),
    )

    val preferences: BackupPreferences = BackupPreferences(
        overallTarget = Percent.ofPercent(70.0),
        courseTarget = Percent.ofPercent(80.0),
        section = "2nd Yr ECE-A1",
        batch = "A1",
        displayName = "Divyansh",
        // A late admission, because it is the setting that changes a percentage rather than a
        // label. A fixture on the default would let a codec that never writes the field pass.
        attendanceStart = AttendanceStart(
            basis = AttendanceBasis.PERSONAL,
            joinedOn = LocalDate.of(2026, 8, 12),
        ),
    )

    val semesters: List<Semester> = listOf(currentSemester, previousSemester)

    val courses: List<Course> = listOf(evs, adec, adecLab)

    val patterns: List<SessionPattern> =
        listOf(labWednesday, adecMondayNew, adecMondayOld, evsTutorial)

    val sessions: List<ClassSession> = listOf(
        awaitingReview,
        cancelledByFaculty,
        movedTo,
        fullyAttended,
        labFromDeletedPattern,
        movedAway,
        shortened,
        halfAttended,
        extraClass,
        cancelledForHoliday,
        fullyMissed,
    )

    /** The whole fixture, with the lists deliberately unsorted. */
    fun snapshot(): BackupSnapshot = BackupSnapshot(
        courses = courses,
        patterns = patterns,
        sessions = sessions,
        calendar = calendar,
        preferences = preferences,
        semesters = semesters,
    )

    /**
     * A full term of classes generated off a realistic timetable — a couple of hundred sessions,
     * which is what a backup at the end of a semester actually holds.
     *
     * The four patterns above are joined by the rest of a plausible week, because the size is
     * part of what is being tested: a format that round-trips eleven rows and a format that
     * round-trips a semester are not obviously the same format. Attendance and cancellations
     * cycle rather than being random, so the file is large and varied but the test is
     * reproducible.
     *
     * Only generated sessions: the hand-built ones above sit on dates the generator also covers,
     * and `(patternId, date)` is unique in the database, so mixing the two would build a
     * semester that could not exist.
     */
    fun fullSemester(): BackupSnapshot {
        var nextPatternId = 500L
        val restOfTheWeek = listOf(
            Slot(adec, DayOfWeek.TUESDAY, 9, 2, SessionKind.LECTURE, "F-212"),
            Slot(adec, DayOfWeek.THURSDAY, 11, 1, SessionKind.TUTORIAL, "F-212"),
            Slot(adecLab, DayOfWeek.TUESDAY, 14, 2, SessionKind.PRACTICAL, "Lab 3"),
            Slot(adecLab, DayOfWeek.SATURDAY, 9, 2, SessionKind.PRACTICAL, "Lab 3"),
            Slot(evs, DayOfWeek.WEDNESDAY, 11, 1, SessionKind.LECTURE, "C-104"),
            Slot(evs, DayOfWeek.THURSDAY, 9, 1, SessionKind.LECTURE, "C-104"),
            Slot(evs, DayOfWeek.FRIDAY, 9, 2, SessionKind.LECTURE, "C-104"),
        ).map { slot ->
            SessionPattern(
                id = nextPatternId++,
                courseId = slot.course.id,
                dayOfWeek = slot.day,
                startHour = slot.startHour,
                units = slot.units,
                kind = slot.kind,
                room = slot.room,
                effectiveFrom = calendar.termStart,
            )
        }
        val timetable = patterns + restOfTheWeek

        var nextId = 9000L
        val generated = timetable.flatMap { pattern ->
            calendar
                .teachingDaysBetween(pattern.effectiveFrom, pattern.effectiveTo ?: calendar.termEnd)
                .filter { it.dayOfWeek == pattern.dayOfWeek }
                .mapIndexed { index, date ->
                    val cancelled = index % 7 == 6
                    ClassSession(
                        id = nextId++,
                        courseId = pattern.courseId,
                        patternId = pattern.id,
                        date = date,
                        startHour = pattern.startHour,
                        unitsPlanned = pattern.units,
                        unitsMask = when {
                            cancelled -> UnitMask.NONE
                            index % 4 == 1 -> UnitMask.of(0)
                            index % 4 == 2 -> UnitMask.NONE
                            else -> UnitMask.allPresent(pattern.units)
                        },
                        status = if (cancelled) SessionStatus.CANCELLED else SessionStatus.HELD,
                        cancellationReason =
                            if (cancelled) CancellationReason.FACULTY_CANCELLED else null,
                        kind = pattern.kind,
                        room = pattern.room,
                        approvedAt =
                            if (cancelled) null else date.atTime(18, 0).toInstant(ZoneOffset.UTC),
                    )
                }
        }
        return snapshot().copy(patterns = timetable, sessions = generated)
    }

    private data class Slot(
        val course: Course,
        val day: DayOfWeek,
        val startHour: Int,
        val units: Int,
        val kind: SessionKind,
        val room: String,
    )
}
