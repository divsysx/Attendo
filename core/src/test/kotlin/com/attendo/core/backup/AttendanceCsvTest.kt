package com.attendo.core.backup

import com.attendo.core.data.TimetableCsv
import com.attendo.core.engine.AttendanceEngine
import com.attendo.core.model.ClassSession
import com.attendo.core.model.Percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The spreadsheet export.
 *
 * This file is the one a student sends to somebody — a tutor asking why a percentage is low, an
 * office wanting a record, a spreadsheet they keep themselves. So the tests here are about
 * *legibility* rather than fidelity: one row per class in the order they happened, states named
 * in words, and the awkward characters in a free-text note escaped well enough that the file
 * opens in Excel with its columns intact.
 *
 * Fields are read back with [TimetableCsv.splitCsv] — the reader already in this app — so the
 * quoting is checked against a real parser rather than against a second copy of the same
 * escaping rules.
 */
class AttendanceCsvTest {

    private val fixture = BackupFixtures.snapshot()
    private val csv = AttendanceCsv.export(fixture)
    private val rows = rowsOf(csv)

    // ---- shape --------------------------------------------------------------

    @Test
    fun `the header names every column a reader needs`() {
        assertEquals(
            "date,day,course_code,course_name,kind,start_hour,slot," +
                "units_planned,units_attended,percent,status,cancellation_reason,origin,room,note",
            AttendanceCsv.HEADER,
        )
        assertEquals(AttendanceCsv.HEADER, csv.trimEnd('\n').lines().first())
    }

    @Test
    fun `there is one row per class and one header`() {
        assertEquals(fixture.sessions.size, rows.size)
        assertTrue("the file should end with a newline", csv.endsWith("\n"))
        assertFalse("no blank rows in the middle", csv.contains("\n\n"))
    }

    @Test
    fun `every row has exactly as many fields as the header`() {
        val expected = AttendanceCsv.HEADER.split(",").size

        rows.forEach { row ->
            assertEquals("wrong field count in $row", expected, row.size)
        }
    }

    @Test
    fun `rows are in the order the classes happened`() {
        val dates = rows.map { LocalDate.parse(it.field("date")) }

        assertEquals(dates.sorted(), dates)
        assertEquals(LocalDate.of(2026, 8, 3), dates.first())
        assertEquals(LocalDate.of(2026, 10, 5), dates.last())
    }

    // ---- one row of each kind, in full --------------------------------------

    @Test
    fun `a fully attended class reads as a full class`() {
        assertEquals(
            "2026-08-03,Mon,ADEC,Analog and Digital Electronic Circuits," +
                "lecture,9,9–11 AM,2,2,100,held,,generated,F-212,",
            lineFor(BackupFixtures.fullyAttended),
        )
    }

    @Test
    fun `an hour of a two-hour class reads as fifty percent, not as present or absent`() {
        val row = rowFor(BackupFixtures.halfAttended)

        assertEquals("2", row.field("units_planned"))
        assertEquals("1", row.field("units_attended"))
        assertEquals("50", row.field("percent"))
        assertEquals("held", row.field("status"))
    }

    @Test
    fun `a class held and entirely missed is a row of zeroes, not a missing row`() {
        val row = rowFor(BackupFixtures.fullyMissed)

        assertEquals("held", row.field("status"))
        assertEquals("2", row.field("units_planned"))
        assertEquals("0", row.field("units_attended"))
        assertEquals("0", row.field("percent"))
        assertEquals("", row.field("cancellation_reason"))
    }

    @Test
    fun `a shortened class reports the hours it actually ran`() {
        val row = rowFor(BackupFixtures.shortened)

        // The slot label shrinks with it, so the row does not claim a 9–11 class ran.
        assertEquals("9–10 AM", row.field("slot"))
        assertEquals("1", row.field("units_planned"))
        assertEquals("1", row.field("units_attended"))
        assertEquals("100", row.field("percent"))
    }

    @Test
    fun `a cancelled class has no attendance and says why it did not happen`() {
        val row = rowFor(BackupFixtures.cancelledByFaculty)

        assertEquals("cancelled", row.field("status"))
        assertEquals("faculty_cancelled", row.field("cancellation_reason"))
        assertEquals("2", row.field("units_planned"))
        // Blank rather than 0: nobody missed anything, and a 0 in this column would read as an
        // absence in any spreadsheet that sums it.
        assertEquals("", row.field("units_attended"))
        assertEquals("", row.field("percent"))
    }

    @Test
    fun `a class cancelled for a holiday says so`() {
        val row = rowFor(BackupFixtures.cancelledForHoliday)

        assertEquals("cancelled", row.field("status"))
        assertEquals("holiday", row.field("cancellation_reason"))
        assertEquals("tutorial", row.field("kind"))
        assertEquals("", row.field("room"))
    }

    @Test
    fun `a class still awaiting review reports no attendance, because none was confirmed`() {
        val row = rowFor(BackupFixtures.awaitingReview)

        assertEquals("scheduled", row.field("status"))
        assertEquals("2", row.field("units_planned"))
        // The app pre-fills these hours as present, but that is a suggestion on a screen, not a
        // record. Exporting it would print a guess as evidence.
        assertEquals("", row.field("units_attended"))
        assertEquals("", row.field("percent"))
    }

    @Test
    fun `both halves of a reschedule appear, and only one of them carries the attendance`() {
        val original = rowFor(BackupFixtures.movedAway)
        val replacement = rowFor(BackupFixtures.movedTo)

        assertEquals("cancelled", original.field("status"))
        assertEquals("rescheduled", original.field("cancellation_reason"))
        assertEquals("", original.field("units_attended"))

        assertEquals("held", replacement.field("status"))
        assertEquals("adhoc", replacement.field("origin"))
        assertEquals("2", replacement.field("units_attended"))
        assertEquals("4–6 PM", replacement.field("slot"))
        // Different dates, which is the whole point of the two rows.
        assertEquals("2026-08-31", original.field("date"))
        assertEquals("2026-09-02", replacement.field("date"))
    }

    @Test
    fun `an extra class is marked as one`() {
        val row = rowFor(BackupFixtures.extraClass)

        assertEquals("adhoc", row.field("origin"))
        assertEquals("Makeup viva", row.field("note"))
        assertEquals("10–11 AM", row.field("slot"))
    }

    @Test
    fun `a class whose timetable slot was deleted still reads as a timetabled class`() {
        val row = rowFor(BackupFixtures.labFromDeletedPattern)

        assertEquals("generated", row.field("origin"))
        assertEquals("held", row.field("status"))
    }

    @Test
    fun `classes of an archived course are still in the file`() {
        val archived = fixture.courses.single { it.archived }

        val theirs = rows.filter { it.field("course_code") == archived.code }

        assertTrue("an archived course's history is history all the same", theirs.isNotEmpty())
        assertEquals(1, theirs.size)
    }

    // ---- readable by a human, groupable by a machine ------------------------

    @Test
    fun `every row names both the course code and its full name`() {
        val codes = fixture.courses.associate { it.code to it.name }

        rows.forEach { row ->
            val code = row.field("course_code")
            assertTrue("unknown course code $code", code in codes)
            assertEquals(codes.getValue(code), row.field("course_name"))
        }
    }

    @Test
    fun `the day of the week is spelled for a reader who will not compute it`() {
        assertEquals("Mon", rowFor(BackupFixtures.fullyAttended).field("day"))
        assertEquals("Wed", rowFor(BackupFixtures.cancelledByFaculty).field("day"))
        assertEquals("Fri", rowFor(BackupFixtures.cancelledForHoliday).field("day"))

        rows.forEach { row ->
            val date = LocalDate.parse(row.field("date"))
            assertEquals(
                "the day must match the date on ${row.field("date")}",
                date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                row.field("day"),
            )
        }
    }

    @Test
    fun `the slot column names the hours, and the start hour stays machine-sortable`() {
        val row = rowFor(BackupFixtures.awaitingReview)

        assertEquals("11", row.field("start_hour"))
        assertEquals("11 AM–1 PM", row.field("slot"))
    }

    @Test
    fun `every status and reason is a lowercase word, not a number`() {
        rows.forEach { row ->
            assertTrue(
                "status ${row.field("status")} should be a word",
                row.field("status") in setOf("held", "cancelled", "scheduled"),
            )
            assertTrue(
                "origin ${row.field("origin")} should be a word",
                row.field("origin") in setOf("generated", "adhoc"),
            )
            assertEquals(row.field("kind"), row.field("kind").lowercase())
        }
    }

    // ---- escaping -----------------------------------------------------------

    @Test
    fun `a note with a comma and quotes in it survives as a single field`() {
        val session = BackupFixtures.cancelledByFaculty

        assertEquals(
            "2026-08-05,Wed,ADEC (P),Analog and Digital Electronic Circuits Lab," +
                "practical,14,2–4 PM,2,,,cancelled,faculty_cancelled,generated,Lab 3," +
                "\"Sir was away, said \"\"we'll cover it later\"\"\"",
            lineFor(session),
        )
        // And a reader gets the original text back, comma, quotes and all.
        assertEquals(session.note, rowFor(session).field("note"))
    }

    @Test
    fun `a note written across two lines is quoted so the row stays one row`() {
        val session = BackupFixtures.extraClass.copy(note = "Viva part 1\nViva part 2")

        val exported = AttendanceCsv.export(fixture.copy(sessions = listOf(session)))

        assertTrue(exported, exported.contains("\"Viva part 1\nViva part 2\""))
        // Deliberately not read back with splitCsv: that reader works a line at a time, which is
        // fine for the timetable files it was written for. A spreadsheet honours the quotes.
    }

    @Test
    fun `fields that need no quoting are left alone, so the file stays readable`() {
        val plain = lineFor(BackupFixtures.fullyAttended)

        assertFalse("nothing here needs escaping: $plain", plain.contains('"'))
    }

    @Test
    fun `a course code with a comma in it would not break the columns`() {
        // No course is named like this today, but a name is free text and one day one will be.
        val awkward = BackupFixtures.adec.copy(name = "Analog, Digital \"and\" Other Circuits")
        val session = BackupFixtures.fullyAttended

        val exported = AttendanceCsv.export(
            fixture.copy(courses = listOf(awkward), sessions = listOf(session)),
        )
        val row = rowsOf(exported).single()

        assertEquals(awkward.name, row.field("course_name"))
        assertEquals(AttendanceCsv.HEADER.split(",").size, row.size)
    }

    // ---- the file has to agree with the app --------------------------------

    @Test
    fun `the held rows add up to the percentage the app shows`() {
        // The point of the export is that somebody can check the app's arithmetic. If summing the
        // spreadsheet gave a different answer from the dashboard, the file would be worse than
        // none at all.
        val held = rows.filter { it.field("status") == "held" }
        val planned = held.sumOf { it.field("units_planned").toInt() }
        val attended = held.sumOf { it.field("units_attended").toInt() }
        val onThePhone = AttendanceEngine.tallyOf(fixture.sessions)

        assertEquals(onThePhone.unitsHeld, planned)
        assertEquals(onThePhone.unitsAttended, attended)
        assertEquals(Percent.ofPercent(75.0), Percent.ofRatio(attended, planned))
    }

    @Test
    fun `each row's own percentage is the hours it reports`() {
        rows.filter { it.field("status") == "held" }.forEach { row ->
            val expected = Percent.ofRatio(
                row.field("units_attended").toInt(),
                row.field("units_planned").toInt(),
            )
            assertEquals(row.toString(), expected?.format(), row.field("percent"))
        }
    }

    // ---- the ends of the range ----------------------------------------------

    @Test
    fun `an empty record still exports a header, so the file opens`() {
        val exported = AttendanceCsv.export(BackupSnapshot())

        assertEquals(AttendanceCsv.HEADER + "\n", exported)
        assertTrue(rowsOf(exported).isEmpty())
    }

    @Test
    fun `a whole semester exports one row per class`() {
        val semester = BackupFixtures.fullSemester()

        val exported = rowsOf(AttendanceCsv.export(semester))

        assertEquals(semester.sessions.size, exported.size)
        assertTrue("expected a semester-sized file", exported.size > 100)
        val dates = exported.map { LocalDate.parse(it.field("date")) }
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `a class of a course that is somehow not in the file still gets a row`() {
        // Defensive: a row with a blank course beats a silently dropped class, because a missing
        // row is the one thing this file exists to rule out.
        val orphan = AttendanceCsv.export(
            fixture.copy(courses = emptyList(), sessions = listOf(BackupFixtures.fullyAttended)),
        )
        val row = rowsOf(orphan).single()

        assertEquals("", row.field("course_code"))
        assertEquals("", row.field("course_name"))
        assertEquals("2", row.field("units_attended"))
    }

    // ---- the file itself ----------------------------------------------------

    @Test
    fun `the suggested name says what it is and when it was taken`() {
        assertEquals(
            "attendo-attendance-2026-08-19.csv",
            AttendanceCsv.suggestedFileName(LocalDate.of(2026, 8, 19)),
        )
        assertEquals("text/csv", AttendanceCsv.MIME_TYPE)
        // Not the backup's name or type: picking the wrong one of these two files is exactly the
        // mistake the naming is there to prevent.
        assertFalse(
            AttendanceCsv.suggestedFileName(LocalDate.of(2026, 8, 19))
                .endsWith(BackupCodec.FILE_EXTENSION),
        )
        assertNotEquals(AttendanceCsv.MIME_TYPE, BackupCodec.MIME_TYPE)
    }

    // ---- helpers ------------------------------------------------------------

    private val columns = AttendanceCsv.HEADER.split(",")

    private fun rowsOf(text: String): List<List<String>> =
        text.trimEnd('\n').lines().drop(1).map(TimetableCsv::splitCsv)

    private fun List<String>.field(name: String): String {
        val index = columns.indexOf(name)
        assertTrue("no column named $name", index >= 0)
        return this[index]
    }

    private fun lineFor(session: ClassSession): String =
        csv.trimEnd('\n').lines().drop(1).single { line ->
            val fields = TimetableCsv.splitCsv(line)
            fields.field("date") == session.date.toString() &&
                fields.field("start_hour") == session.startHour.toString()
        }

    private fun rowFor(session: ClassSession): List<String> =
        TimetableCsv.splitCsv(lineFor(session))
}
