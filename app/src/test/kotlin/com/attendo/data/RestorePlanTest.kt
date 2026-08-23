package com.attendo.data

import com.attendo.core.backup.BackupSnapshot
import com.attendo.core.model.ClassSession
import com.attendo.core.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The ids a restore hands to Room.
 *
 * `BackupSnapshot.normalised` already renumbers everything from 1 and rewrites every reference,
 * which is exactly what a restore wants: Room writes a non-zero primary key as given, so the
 * links between courses, patterns and sessions survive without a second pass. [readyToWrite]
 * adds the one adjustment SQLite forces — see its own documentation — and these tests are about
 * that adjustment not breaking the property the restore verifies itself with.
 *
 * Nothing here touches a database. That is the point of the function being pure.
 */
class RestorePlanTest {

    // ---- the ids Room is handed ---------------------------------------------

    @Test
    fun `courses patterns and sessions are numbered from one`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        assertEquals((1L..plan.courses.size).toList(), plan.courses.map { it.id })
        assertEquals((1L..plan.patterns.size).toList(), plan.patterns.map { it.id })
        assertEquals((1L..plan.sessions.size).toList(), plan.sessions.map { it.id })
    }

    @Test
    fun `no row is left with the id that means generate one`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        // Room reads 0 on an autoGenerate key as "you decide", which would throw away the id
        // every reference in the file depends on.
        assertTrue(plan.courses.none { it.id == 0L })
        assertTrue(plan.patterns.none { it.id == 0L })
        assertTrue(plan.sessions.none { it.id == 0L })
        assertTrue(plan.sessions.none { it.patternId == 0L })
    }

    @Test
    fun `a pattern still belongs to the course it belonged to`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()
        val codeOf = plan.courses.associate { it.id to it.code }

        val lab = plan.patterns.single { it.room == "Lab 2" }
        assertEquals("SS (P)", codeOf[lab.courseId])

        val tutorial = plan.patterns.single { it.kind == SessionKind.TUTORIAL }
        assertEquals("EM-3", codeOf[tutorial.courseId])
    }

    @Test
    fun `a session still belongs to the course and the slot it belonged to`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()
        val codeOf = plan.courses.associate { it.id to it.code }
        val patterns = plan.patterns.associateBy { it.id }

        val session = plan.session(LocalDate.of(2026, 8, 17), 10)
        assertEquals("SS", codeOf[session.courseId])
        // The lecture's original Monday slot, retired at the end of September.
        assertEquals(LocalDate.of(2026, 9, 25), patterns.getValue(session.patternId!!).effectiveTo)
    }

    @Test
    fun `the two halves of a reschedule still point at each other`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        val cancelled = plan.session(LocalDate.of(2026, 8, 31), 10)
        val replacement = plan.session(LocalDate.of(2026, 9, 2), 16)

        assertEquals(replacement.id, cancelled.movedToSessionId)
        assertEquals(cancelled.id, replacement.movedFromSessionId)
    }

    // ---- sessions whose pattern was deleted ---------------------------------

    @Test
    fun `a session whose pattern was deleted keeps a pattern id`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        // Losing the reference would relabel a timetabled class as ad-hoc and free its slot for
        // the generator to fill in again.
        assertNotNull(plan.session(LocalDate.of(2026, 8, 20), 14).patternId)
        assertNotNull(plan.session(LocalDate.of(2026, 8, 21), 9).patternId)
    }

    @Test
    fun `orphan pattern ids are negative, out of autoincrement's reach`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()
        val real = plan.patterns.map { it.id }.toSet()

        val orphans = plan.sessions.mapNotNull { it.patternId }.filterNot { it in real }.distinct()

        assertEquals(2, orphans.size)
        assertTrue("orphan ids should be negative, got $orphans", orphans.all { it < 0L })
    }

    @Test
    fun `no orphan id collides with a real pattern`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()
        val real = plan.patterns.map { it.id }.toSet()

        val orphaned = plan.session(LocalDate.of(2026, 8, 20), 14).patternId
        assertTrue("$orphaned is also a real pattern id", orphaned !in real)
    }

    @Test
    fun `the orphan mapping keeps the order normalising gave them`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        val early = plan.session(LocalDate.of(2026, 8, 20), 14).patternId
        val late = plan.session(LocalDate.of(2026, 8, 21), 9).patternId

        // normalised() ranks orphans by ascending original id, so the lower original id has to
        // stay the lower rank. Negated, that makes it the *more* negative of the two. Reversing
        // them would reorder the sessions, which would renumber them, which would move the
        // movedTo/movedFrom links — the file would still be valid and would no longer say the
        // same thing.
        assertTrue(
            "expected ${AppBackupFixtures.DELETED_PATTERN_LOW} to stay the lower rank, " +
                "got $early against $late",
            early!! < late!!,
        )
    }

    @Test
    fun `a term with no deleted patterns is planned as it is normalised`() {
        val snapshot = AppBackupFixtures.withoutOrphans()

        assertEquals(snapshot.normalised(), snapshot.readyToWrite())
        assertTrue(snapshot.readyToWrite().sessions.all { (it.patternId ?: 1L) > 0L })
    }

    // ---- the property the restore verifies itself with ----------------------

    @Test
    fun `the plan normalises back to the snapshot it came from`() {
        val snapshot = AppBackupFixtures.snapshot()

        // This is what BackupRepository.write compares the rows it read back against. If the
        // negative ids did not normalise back to the same numbering, every restore of a term
        // containing a deleted pattern would roll itself back.
        assertEquals(snapshot.normalised(), snapshot.readyToWrite().normalised())
    }

    @Test
    fun `planning is idempotent`() {
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        assertEquals(plan, plan.readyToWrite())
    }

    @Test
    fun `planning does not depend on the ids the old database happened to use`() {
        val snapshot = AppBackupFixtures.snapshot()
        // The same term as it would come off a different phone: every id different, one of them
        // colliding with a number the first phone used for something else.
        val shifted = snapshot.copy(
            courses = snapshot.courses.map { it.copy(id = it.id * 7 + 3) },
            patterns = snapshot.patterns.map { it.copy(id = it.id * 3 + 1, courseId = it.courseId * 7 + 3) },
            sessions = snapshot.sessions.map { session ->
                session.copy(
                    id = session.id * 2 + 9,
                    courseId = session.courseId * 7 + 3,
                    patternId = session.patternId?.times(3)?.plus(1),
                    movedToSessionId = session.movedToSessionId?.times(2)?.plus(9),
                    movedFromSessionId = session.movedFromSessionId?.times(2)?.plus(9),
                )
            },
        )

        assertEquals(snapshot.readyToWrite(), shifted.readyToWrite())
    }

    @Test
    fun `the plan carries the calendar and preferences through untouched`() {
        val snapshot = AppBackupFixtures.snapshot()
        val plan = snapshot.readyToWrite()

        assertEquals(snapshot.calendar, plan.calendar)
        assertEquals(snapshot.preferences, plan.preferences)
    }

    @Test
    fun `the display name reaches the plan, so a restore greets the same person`() {
        // Renumbering is what planning is for, and the name is the one thing in the file that
        // must survive it untouched: it is a label, so unlike every id here it is not remapped.
        val plan = AppBackupFixtures.snapshot().readyToWrite()

        assertEquals("Divyansh", plan.preferences.displayName)
    }

    @Test
    fun `a file from an install with no name planned without one`() {
        val anonymous = AppBackupFixtures.snapshot().let { term ->
            term.copy(preferences = term.preferences.copy(displayName = null))
        }

        val plan = anonymous.readyToWrite()

        assertNull(plan.preferences.displayName)
        assertEquals(anonymous.sessions.size, plan.sessions.size)
    }

    @Test
    fun `an empty term plans to an empty plan`() {
        val plan = BackupSnapshot().readyToWrite()

        assertTrue(plan.isEmpty)
        assertEquals(BackupSnapshot().normalised(), plan)
    }

    @Test
    fun `a term of nothing but orphaned classes still plans`() {
        // Every pattern deleted and every remaining class reviewed: unlikely, and the shape a
        // student who rebuilt their timetable from scratch ends up with.
        val snapshot = AppBackupFixtures.snapshot().let { term ->
            term.copy(
                patterns = emptyList(),
                sessions = term.sessions.filter { it.patternId != null },
            )
        }

        val plan = snapshot.readyToWrite()

        assertTrue(plan.patterns.isEmpty())
        assertTrue(plan.sessions.isNotEmpty())
        assertTrue(plan.sessions.mapNotNull { it.patternId }.all { it < 0L })
        assertEquals(snapshot.normalised(), plan.normalised())
    }

    private fun BackupSnapshot.session(date: LocalDate, startHour: Int): ClassSession =
        sessions.single { it.date == date && it.startHour == startHour }
}
