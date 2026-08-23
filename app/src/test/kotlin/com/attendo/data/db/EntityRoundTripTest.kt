package com.attendo.data.db

import com.attendo.core.backup.BackupSnapshot
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.Percent
import com.attendo.core.model.Semester
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionOrigin
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import com.attendo.data.AppBackupFixtures
import com.attendo.data.readyToWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The boundary between `:core`'s models and the rows Room stores.
 *
 * A restore is only as good as this mapping. Everything else in the backup path is checked by
 * `:core`'s tests — the file round-trips, the checksum catches damage, a truncated file is
 * refused — and all of it is wasted if the last step, turning a decoded [ClassSession] into a
 * row and back, quietly drops the field that says which hour of a lab was attended.
 *
 * So the claim here is the same one `BackupRepository.write` makes at runtime, tested as pure
 * functions: a snapshot mapped to rows and read back is *logically the same snapshot*, not a
 * snapshot that happens to produce the same percentage.
 */
class EntityRoundTripTest {

    // ---- the whole plan ------------------------------------------------------

    @Test
    fun `the restore plan maps to rows and back to the same data`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        assertEquals(plan.normalised(), plan.throughTheDatabase().normalised())
    }

    @Test
    fun `a term survives export ids, the plan, and the trip through the rows`() {
        val term = AppBackupFixtures.snapshot()

        // The full path a restore takes, minus the file itself: normalise, fix the orphan ids,
        // write, read back. What comes out has to mean what went in.
        assertEquals(term.normalised(), term.readyToWrite().throughTheDatabase().normalised())
    }

    @Test
    fun `nothing is lost or gained on the way`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()
        val stored = plan.throughTheDatabase()

        assertEquals(plan.courses.size, stored.courses.size)
        assertEquals(plan.patterns.size, stored.patterns.size)
        assertEquals(plan.sessions.size, stored.sessions.size)
    }

    // ---- courses -------------------------------------------------------------

    @Test
    fun `a course keeps its name, code, colour and archived flag`() {
        val course = AppBackupFixtures.maths

        assertEquals(course, course.toEntity().toModel())
    }

    @Test
    fun `a target percentage is stored exactly, not as a rounded double`() {
        // Basis points, so 66.67% is 6667 and comes back as 6667 rather than 66.6699999.
        val course = AppBackupFixtures.signals.copy(targetPercent = Percent.ofPercent(66.67))

        assertEquals(Percent.ofPercent(66.67), course.toEntity().toModel().targetPercent)
        assertEquals(6667, course.toEntity().targetBasisPoints)
    }

    // ---- patterns ------------------------------------------------------------

    @Test
    fun `a pattern keeps its day, hour, length, kind and room`() {
        val pattern = AppBackupFixtures.lab

        assertEquals(pattern, pattern.toEntity().toModel())
    }

    @Test
    fun `a retired pattern keeps the date it was retired`() {
        val retired = AppBackupFixtures.lectureOld

        val stored = retired.toEntity().toModel()

        assertEquals(LocalDate.of(2026, 9, 25), stored.effectiveTo)
        assertEquals(retired.effectiveFrom, stored.effectiveFrom)
    }

    @Test
    fun `an open-ended pattern has no retirement date invented for it`() {
        assertNull(AppBackupFixtures.lectureNew.toEntity().toModel().effectiveTo)
    }

    // ---- sessions ------------------------------------------------------------

    @Test
    fun `a fully attended class comes back fully attended`() {
        val session = AppBackupFixtures.attendedInFull

        val stored = session.toEntity().toModel()

        assertEquals(session, stored)
        assertEquals(2, stored.unitsAttended)
        assertEquals(Percent.ofPercent(100.0), stored.sessionPercent)
    }

    @Test
    fun `a class attended in part keeps which hour was attended`() {
        val session = AppBackupFixtures.attendedInPart

        val stored = session.toEntity().toModel()

        assertEquals(session, stored)
        // Not "one of two hours" — the *second* of two hours. Which one is a fact about the day
        // and the only thing an argument about it can be settled with.
        assertFalse(stored.isUnitAttended(0))
        assertTrue(stored.isUnitAttended(1))
        assertEquals(Percent.ofPercent(50.0), stored.sessionPercent)
    }

    @Test
    fun `a class held and entirely missed stays held and missed`() {
        val stored = AppBackupFixtures.missed.toEntity().toModel()

        assertEquals(SessionStatus.HELD, stored.status)
        assertEquals(0, stored.unitsAttended)
        assertEquals(2, stored.unitsPlanned)
        assertFalse(stored.isCancelled)
    }

    @Test
    fun `a shortened class keeps the hours it actually ran`() {
        val stored = AppBackupFixtures.shortened.toEntity().toModel()

        assertEquals(AppBackupFixtures.shortened, stored)
        assertEquals(1, stored.unitsPlanned)
        assertEquals(1, stored.unitsAttended)
        assertEquals("Ran an hour, power cut", stored.note)
    }

    @Test
    fun `a cancelled class keeps why it was cancelled`() {
        val faculty = AppBackupFixtures.cancelledByFaculty.toEntity().toModel()
        val holiday = AppBackupFixtures.cancelledForHoliday.toEntity().toModel()

        assertEquals(CancellationReason.FACULTY_CANCELLED, faculty.cancellationReason)
        assertEquals(CancellationReason.HOLIDAY, holiday.cancellationReason)
        // Cancelled counts for neither side, so a stored percentage would be a wrong answer to a
        // question that has none.
        assertNull(faculty.sessionPercent)
        assertNull(holiday.sessionPercent)
    }

    @Test
    fun `both halves of a reschedule keep their link to the other`() {
        val away = AppBackupFixtures.movedAway.toEntity().toModel()
        val landed = AppBackupFixtures.movedTo.toEntity().toModel()

        assertEquals(landed.id, away.movedToSessionId)
        assertEquals(away.id, landed.movedFromSessionId)
        assertTrue(away.wasRescheduledAway)
        assertEquals(CancellationReason.RESCHEDULED, away.cancellationReason)
        // The replacement is the row that carries the attendance, and it is ad-hoc.
        assertEquals(SessionOrigin.ADHOC, landed.origin)
        assertEquals(2, landed.unitsAttended)
    }

    @Test
    fun `an ad-hoc class stays ad-hoc`() {
        val stored = AppBackupFixtures.extra.toEntity().toModel()

        assertNull(stored.patternId)
        assertEquals(SessionOrigin.ADHOC, stored.origin)
        assertEquals("Extra viva slot", stored.note)
    }

    @Test
    fun `a class awaiting review comes back awaiting review`() {
        val stored = AppBackupFixtures.awaitingReview.toEntity().toModel()

        assertTrue(stored.isAwaitingReview)
        assertFalse(stored.countsTowardAttendance)
        assertNull(stored.approvedAt)
        // Pre-filled present, and it must not start counting just because it was restored.
        assertEquals(UnitMask.allPresent(2), stored.unitsMask)
    }

    @Test
    fun `a class whose pattern was deleted stays a timetabled class`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()
        val orphan = plan.sessions.single {
            it.date == LocalDate.of(2026, 8, 20) && it.startHour == 14
        }

        val stored = orphan.toEntity().toModel()

        assertEquals(orphan.patternId, stored.patternId)
        assertEquals(SessionOrigin.GENERATED, stored.origin)
    }

    @Test
    fun `the timestamps that say when a class was marked and edited survive`() {
        val stored = AppBackupFixtures.attendedInPart.toEntity().toModel()

        assertEquals(AppBackupFixtures.attendedInPart.approvedAt, stored.approvedAt)
        assertEquals(AppBackupFixtures.attendedInPart.lastEditedAt, stored.lastEditedAt)
    }

    @Test
    fun `a stale attendance bit is dropped rather than stored`() {
        // A two-hour class shortened to one, with the second hour still ticked from before. The
        // mask is narrowed at the boundary so a stale bit can never inflate a percentage.
        val stale = AppBackupFixtures.shortened.copy(unitsMask = UnitMask.allPresent(2))

        val stored = stale.toEntity().toModel()

        assertEquals(UnitMask.of(0), stored.unitsMask)
        assertEquals(1, stored.unitsAttended)
        assertEquals(Percent.ofPercent(100.0), stored.sessionPercent)
    }

    @Test
    fun `a nine-hour class fits in the mask column`() {
        // The widest a session can be. Worth pinning: the mask is an Int of bits, and a class
        // this long is the only case where the top of that range is anywhere near.
        val long = AppBackupFixtures.extra.copy(
            startHour = 9,
            unitsPlanned = 9,
            unitsMask = UnitMask.of(0, 2, 4, 6, 8),
            kind = SessionKind.PRACTICAL,
        )

        val stored = long.toEntity().toModel()

        assertEquals(long, stored)
        assertEquals(5, stored.unitsAttended)
    }

    // ---- semesters -----------------------------------------------------------

    @Test
    fun `a semester keeps its year, type, dates and archived flag`() {
        assertEquals(odd, odd.toEntity().toModel())
        assertEquals(even, even.toEntity().toModel())
    }

    @Test
    fun `an archived semester comes back archived rather than live`() {
        // The recommended answer on the import screen. If this flag were dropped in the mapping,
        // a restore would quietly reactivate last term and start averaging it with this one.
        val stored = odd.toEntity().toModel()

        assertTrue(stored.archived)
        assertFalse(even.toEntity().toModel().archived)
        assertEquals(odd.label, stored.label)
    }

    @Test
    fun `the odd and even readings survive the trip`() {
        // July–December odd, January–May even. The type is what the whole semester model turns
        // on, and it is stored as a name rather than an ordinal for exactly this reason.
        assertEquals(SemesterType.ODD, odd.toEntity().toModel().type)
        assertEquals(SemesterType.EVEN, even.toEntity().toModel().type)
        assertEquals("ODD", odd.toEntity().type.name)
    }

    @Test
    fun `which semester a course belongs to survives the trip`() {
        val thisTerm = AppBackupFixtures.maths.copy(semesterId = even.id)

        val stored = thisTerm.toEntity().toModel()

        assertEquals(even.id, stored.semesterId)
        assertTrue(stored.isIn(even))
        assertFalse(stored.isIn(odd))
    }

    @Test
    fun `a course in no semester keeps having none, rather than being adopted`() {
        // The hand-added course and the pre-semesters install. Null has to stay null: a mapper
        // that defaulted it to 0 would put every orphan in a semester that does not exist.
        val orphan = AppBackupFixtures.signals.copy(semesterId = null)

        assertNull(orphan.toEntity().semesterId)
        assertNull(orphan.toEntity().toModel().semesterId)
    }

    @Test
    fun `a term's semesters survive the plan and the trip through the rows`() {
        // The specification's backup requirement, at the boundary that carries it: two semesters
        // and a course in each, written as rows and read back meaning the same thing.
        val term = AppBackupFixtures.snapshot().let { base ->
            base.copy(
                semesters = listOf(odd, even),
                courses = base.courses.mapIndexed { index, course ->
                    course.copy(semesterId = if (index == 0) odd.id else even.id)
                },
            )
        }

        val stored = term.readyToWrite().throughTheDatabase()

        assertEquals(term.normalised(), stored.normalised())
        assertEquals(2, stored.semesters.size)
        // The live one, told apart from the archived one after the round trip. By term rather
        // than by id: a restore renumbers rows, and which term is running has to survive that.
        val live = stored.currentSemester!!
        assertTrue(live.isSameTermAs(even))
        assertFalse(live.archived)
        assertEquals(even.startDate, live.startDate)
        assertEquals(even.endDate, live.endDate)
        // And the archived one is still here, still archived, not quietly reactivated.
        assertTrue(stored.semesters.single { it.archived }.isSameTermAs(odd))
    }

    /** Every row written and read back, as `BackupRepository.write` does it. */
    private fun BackupSnapshot.throughTheDatabase(): BackupSnapshot = BackupSnapshot(
        courses = courses.map { it.toEntity().toModel() },
        patterns = patterns.map { it.toEntity().toModel() },
        sessions = sessions.map { it.toEntity().toModel() },
        calendar = calendar,
        preferences = preferences,
        semesters = semesters.map { it.toEntity().toModel() },
    )

    private companion object {
        /** Last term, archived when this one was imported. */
        val odd = Semester(
            id = 41L,
            year = 2026,
            type = SemesterType.ODD,
            startDate = LocalDate.of(2026, 7, 20),
            endDate = LocalDate.of(2026, 12, 12),
            archived = true,
        )

        /** This term, still running. */
        val even = Semester(
            id = 42L,
            year = 2027,
            type = SemesterType.EVEN,
            startDate = LocalDate.of(2027, 1, 5),
            endDate = LocalDate.of(2027, 5, 15),
        )
    }
}
