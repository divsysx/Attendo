package com.attendo.core.backup

import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.model.Percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The preview shown before a restore overwrites anything.
 *
 * REPLACE is destructive, so this screen is the last chance to notice that the file is the wrong
 * one — last term's export, a friend's phone, a backup taken before a month of marking. The
 * figures therefore have to be recognisable: the percentage has to be the one the old phone was
 * showing, and the date range has to be the semester the student remembers.
 *
 * Everything here is checked against the fixture's known contents by hand rather than against a
 * second call to the same code, which is the only way a test of a summary can fail usefully.
 */
class BackupSummaryTest {

    private val fixture = BackupFixtures.snapshot()
    private val summary = readOk(encoded(fixture)).summary

    // ---- how much is in the file --------------------------------------------

    @Test
    fun `the preview counts courses, timetable slots and classes`() {
        assertEquals(3, summary.courses)
        assertEquals(4, summary.patterns)
        assertEquals(11, summary.sessions)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `archived courses and retired slots are counted apart from live ones`() {
        // Both are in the file and both are in the totals above; they are called out separately
        // so "3 courses, 1 archived" reads honestly rather than looking like a course is missing.
        assertEquals(1, summary.archivedCourses)
        assertEquals(1, summary.retiredPatterns)
    }

    // ---- what state those classes are in ------------------------------------

    @Test
    fun `the preview separates reviewed classes from the ones still awaiting review`() {
        assertEquals(10, summary.reviewedSessions)
        assertEquals(1, summary.sessions - summary.reviewedSessions)
    }

    @Test
    fun `cancelled, ad-hoc and rescheduled classes are each counted`() {
        assertEquals(3, summary.cancelledSessions)
        assertEquals(2, summary.adhocSessions)
        assertEquals(1, summary.rescheduledSessions)
    }

    // ---- the figure that has to match the old phone -------------------------

    @Test
    fun `the units in the preview are the ones that count`() {
        assertEquals(12, summary.unitsHeld)
        assertEquals(9, summary.unitsAttended)
    }

    @Test
    fun `cancelled and unreviewed classes are left out of the units`() {
        // 19 hours are timetabled across the eleven rows. Five belong to cancelled classes and
        // two to a class not yet reviewed, and none of those seven may reach the denominator —
        // a preview that counted them would show a percentage the student has never seen.
        val planned = fixture.sessions.sumOf { it.unitsPlanned }

        assertEquals(19, planned)
        assertEquals(7, planned - summary.unitsHeld)
    }

    @Test
    fun `a shortened class contributes the hours it actually ran`() {
        val shortened = BackupFixtures.shortened

        assertEquals(1, shortened.unitsPlanned)
        // Its pattern says two hours; if the preview took the pattern's word for it, unitsHeld
        // would be 13 and the student would be shown a worse percentage than they have.
        assertEquals(2, BackupFixtures.adecMondayOld.units)
        assertEquals(12, summary.unitsHeld)
    }

    @Test
    fun `the percentage in the preview is the one the dashboard was showing`() {
        val onThePhone = AttendanceEngine.overallStats(
            courses = fixture.courses,
            sessions = fixture.sessions,
            overallTarget = fixture.preferences.overallTarget,
        )

        assertEquals(onThePhone.percent, summary.overallPercent)
        assertEquals(Percent.ofPercent(75.0), summary.overallPercent)
        assertEquals("75%", summary.overallPercent.toString())
    }

    // ---- when the semester ran ----------------------------------------------

    @Test
    fun `the preview shows the range the classes actually cover`() {
        // Not the term dates: a file exported in week three should say so, so an export taken
        // before a month of marking is recognisable as the wrong file.
        assertEquals(LocalDate.of(2026, 8, 3), summary.firstSession)
        assertEquals(LocalDate.of(2026, 10, 5), summary.lastSession)
    }

    @Test
    fun `the term and its exceptions travel with the preview`() {
        assertEquals(LocalDate.of(2026, 7, 28), summary.termStart)
        assertEquals(LocalDate.of(2026, 12, 15), summary.termEnd)
        assertEquals(2, summary.holidays)
        assertEquals(1, summary.workingSaturdays)
    }

    // ---- whose file it is ---------------------------------------------------

    @Test
    fun `the preview says who exported it and when`() {
        assertEquals(TEST_EXPORTED_AT, summary.exportedAt)
        assertEquals(TEST_APP_VERSION_NAME, summary.appVersionName)
        assertEquals(BackupCodec.FORMAT_VERSION, summary.formatVersion)
    }

    @Test
    fun `the section is shown, because it is how a student recognises their own file`() {
        assertEquals("2nd Yr ECE-A1", summary.section)
        assertEquals("A1", summary.batch)
    }

    @Test
    fun `written by names the person who exported it`() {
        // The point of the line is "is this my file, or the one my friend sent me?" — a version
        // number answers neither question, so the name is what goes there.
        assertEquals("Divyansh", summary.displayName)
        assertEquals("Written by Divyansh", "Written by ${summary.writtenBy}")
    }

    @Test
    fun `a file exported before a name was set is written by Attendo`() {
        val anonymous = fixture.copy(
            preferences = fixture.preferences.copy(displayName = null),
        )

        val preview = readOk(encoded(anonymous)).summary

        assertNull(preview.displayName)
        assertEquals("Attendo", preview.writtenBy)
        // Never the version: "Written by Attendo 1.4" would be naming the build, not the student.
        assertFalse(preview.writtenBy.contains(TEST_APP_VERSION_NAME))
    }

    @Test
    fun `a name that is only spaces falls back rather than showing an empty line`() {
        val padded = fixture.copy(
            preferences = fixture.preferences.copy(displayName = "   "),
        )

        assertEquals("Attendo", readOk(encoded(padded)).summary.writtenBy)
    }

    @Test
    fun `an install that never seeded a section previews without one`() {
        val noSection = fixture.copy(
            preferences = fixture.preferences.copy(section = null, batch = null),
        )

        val preview = readOk(encoded(noSection)).summary

        assertNull(preview.section)
        assertNull(preview.batch)
        assertEquals(11, preview.sessions)
    }

    // ---- the preview describes what will be restored -------------------------

    @Test
    fun `the preview of a file matches the preview of the data it restores`() {
        // The screen is a promise about what the import will produce. Reading the same figures
        // off the snapshot before it was written and off the file after it was read has to give
        // the same answer, or the preview is describing something else.
        val beforeExport = Backup(
            meta = BackupMeta(
                formatVersion = BackupCodec.FORMAT_VERSION,
                appVersionName = TEST_APP_VERSION_NAME,
                appVersionCode = TEST_APP_VERSION_CODE,
                exportedAt = TEST_EXPORTED_AT,
            ),
            snapshot = fixture,
        ).summary

        assertEquals(beforeExport, summary)
    }

    @Test
    fun `previewing a file twice says the same thing`() {
        val text = encoded(fixture)

        assertEquals(readOk(text).summary, readOk(text).summary)
    }

    // ---- the ends of the range ----------------------------------------------

    @Test
    fun `an empty backup previews as empty rather than as nothing`() {
        val preview = readOk(encoded(BackupSnapshot())).summary

        assertTrue(preview.isEmpty)
        assertEquals(0, preview.courses)
        assertEquals(0, preview.sessions)
        assertEquals(0, preview.unitsHeld)
        // Not 0%: no classes held is an undefined percentage, and showing 0% before the term
        // starts would look like a catastrophe rather than an empty file.
        assertNull(preview.overallPercent)
        assertNull(preview.firstSession)
        assertNull(preview.lastSession)
        // The term is still known even with nothing in it.
        assertEquals(fixture.calendar.termStart, preview.termStart)
    }

    @Test
    fun `a timetable with no classes yet previews as a timetable`() {
        val firstDay = fixture.copy(sessions = emptyList())

        val preview = readOk(encoded(firstDay)).summary

        assertFalse(preview.isEmpty)
        assertEquals(3, preview.courses)
        assertEquals(4, preview.patterns)
        assertEquals(0, preview.sessions)
        assertNull(preview.overallPercent)
    }

    @Test
    fun `a whole semester previews with the whole semester in it`() {
        val semester = BackupFixtures.fullSemester()

        val preview = readOk(encoded(semester)).summary

        assertEquals(semester.sessions.size, preview.sessions)
        assertTrue("expected a semester-sized file, got ${preview.sessions}", preview.sessions > 100)
        assertEquals(semester.patterns.size, preview.patterns)
        assertTrue(preview.cancelledSessions > 0)
        assertTrue(preview.unitsAttended in 1 until preview.unitsHeld)
        assertEquals(semester.sessions.minOf { it.date }, preview.firstSession)
        assertEquals(semester.sessions.maxOf { it.date }, preview.lastSession)
    }
}
