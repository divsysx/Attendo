package com.attendo.core.backup

import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.Semester
import com.attendo.core.model.SemesterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Format version 2: semesters, the link from a course to the one that taught it, and the date a
 * student's attendance is counted from.
 *
 * ### What is actually at risk here
 *
 * A percentage. Semesters exist so that two terms are never added together, and the attendance
 * start exists so a spot admission's figure begins on the day they arrived. Both live entirely in
 * data that a restore either carries or silently drops — and a restore that drops them does not
 * look broken. It looks like a slightly different number. So the round trips below are checked on
 * the values themselves rather than on counts, and the v1 tests check that an old file gets a
 * defensible answer rather than none.
 */
class BackupSemesterTest {

    private val original = BackupFixtures.snapshot()

    // ---- the format ---------------------------------------------------------

    @Test
    fun `the format this build writes is version two`() {
        assertEquals(2, BackupCodec.FORMAT_VERSION)
        assertEquals(1, BackupCodec.supportedFormatVersions.first)
        assertEquals(2, BackupCodec.supportedFormatVersions.last)
    }

    @Test
    fun `every supported version has a way forward to the current one`() {
        assertTrue(BackupMigrations.chainIsComplete())
    }

    // ---- semesters round trip ------------------------------------------------

    @Test
    fun `both semesters come back, with the archived one still archived`() {
        val restored = readOk(encoded(original)).snapshot

        assertEquals(2, restored.semesters.size)
        val live = restored.semesters.single { !it.archived }
        val archived = restored.semesters.single { it.archived }

        assertEquals(SemesterType.ODD, live.type)
        assertEquals(2026, live.year)
        assertEquals(LocalDate.of(2026, 7, 28), live.startDate)
        assertEquals(LocalDate.of(2026, 12, 15), live.endDate)

        assertEquals(SemesterType.EVEN, archived.type)
        assertEquals(LocalDate.of(2026, 1, 5), archived.startDate)
        assertEquals(LocalDate.of(2026, 5, 15), archived.endDate)
    }

    @Test
    fun `the live semester is the one the restored install carries on with`() {
        val restored = readOk(encoded(original)).snapshot

        val current = restored.currentSemester
        assertEquals(BackupFixtures.currentSemester.label, current?.label)
        assertEquals("Odd semester 2026–27", current?.label)
        assertFalse(current!!.archived)
    }

    @Test
    fun `each course comes back in the semester that taught it`() {
        val restored = readOk(encoded(original)).snapshot
        val current = restored.currentSemester!!

        assertEquals(3, restored.courses.size)
        restored.courses.forEach { course ->
            assertTrue(
                "${course.code} should still belong to the semester that taught it",
                course.isIn(current),
            )
        }
    }

    @Test
    fun `an archived course keeps its semester, because being put away is not being reassigned`() {
        val restored = readOk(encoded(original)).snapshot

        val evs = restored.courses.single { it.code == BackupFixtures.evs.code }
        assertTrue(evs.archived)
        assertEquals(restored.currentSemester!!.id, evs.semesterId)
    }

    @Test
    fun `a semester with no courses in it still travels`() {
        // The archived even semester has none. A decoder that rebuilt the list from the courses
        // would lose it, and with it the student's record that the term existed at all.
        val restored = readOk(encoded(original)).snapshot

        val archived = restored.semesters.single { it.archived }
        assertTrue(restored.courses.none { it.semesterId == archived.id })
        assertEquals(2026, archived.year)
    }

    @Test
    fun `a course with no semester round trips with none`() {
        // The state a pre-semester install is in, and the state a migration leaves a course it
        // declined to place in. It has to survive as "not known" rather than being invented.
        val unplaced = original.copy(
            courses = original.courses.map { it.copy(semesterId = null) },
            semesters = emptyList(),
        )

        val restored = readOk(encoded(unplaced)).snapshot

        assertTrue(restored.semesters.isEmpty())
        assertNull(restored.currentSemester)
        assertTrue(restored.courses.all { it.semesterId == null })
        assertEquals(unplaced.normalised(), restored)
    }

    @Test
    fun `an install with semesters set up but no courses yet round trips`() {
        val fresh = BackupSnapshot(
            calendar = BackupFixtures.calendar,
            semesters = listOf(BackupFixtures.currentSemester),
        )

        val restored = readOk(encoded(fresh)).snapshot

        assertEquals(fresh.normalised(), restored)
        assertEquals(1, restored.semesters.size)
        assertTrue(restored.isEmpty)
    }

    // ---- attendance start round trips ---------------------------------------

    @Test
    fun `a late admission's joining date comes back, so the percentage does not change`() {
        val restored = readOk(encoded(original)).snapshot

        assertEquals(
            AttendanceStart(AttendanceBasis.PERSONAL, LocalDate.of(2026, 8, 12)),
            restored.preferences.attendanceStart,
        )
    }

    @Test
    fun `the joining date is kept even under the university reading`() {
        // Kept so switching back does not ask for it again — see AttendanceStart.
        val universityReading = original.copy(
            preferences = original.preferences.copy(
                attendanceStart = AttendanceStart(
                    basis = AttendanceBasis.UNIVERSITY,
                    joinedOn = LocalDate.of(2026, 8, 12),
                ),
            ),
        )

        val restored = readOk(encoded(universityReading)).snapshot.preferences.attendanceStart

        assertEquals(AttendanceBasis.UNIVERSITY, restored.basis)
        assertEquals(LocalDate.of(2026, 8, 12), restored.joinedOn)
    }

    @Test
    fun `a personal basis with no date yet is a state, not a fault`() {
        val undecided = original.copy(
            preferences = original.preferences.copy(
                attendanceStart = AttendanceStart(basis = AttendanceBasis.PERSONAL, joinedOn = null),
            ),
        )

        val restored = readOk(encoded(undecided)).snapshot.preferences.attendanceStart

        assertEquals(AttendanceBasis.PERSONAL, restored.basis)
        assertNull(restored.joinedOn)
        assertTrue(restored.isIncomplete)
    }

    @Test
    fun `a file that says nothing about attendance start means the university reading`() {
        val silent = repack(encoded(original)) { payload ->
            payload.withField(
                "preferences",
                payload.getValue("preferences").jsonObject
                    .withoutField("attendanceBasis")
                    .withoutField("joinedOn"),
            )
        }

        val restored = readOk(silent).snapshot.preferences.attendanceStart

        assertEquals(AttendanceStart(), restored)
        assertEquals(AttendanceBasis.UNIVERSITY, restored.basis)
    }

    @Test
    fun `a blank joining date reads as no joining date rather than a malformed one`() {
        val blank = repack(encoded(original)) { payload ->
            payload.withField(
                "preferences",
                payload.getValue("preferences").jsonObject.withField("joinedOn", ""),
            )
        }

        assertNull(readOk(blank).snapshot.preferences.attendanceStart.joinedOn)
    }

    @Test
    fun `an unknown attendance basis is reported rather than defaulted`() {
        // Defaulting would be the one repair that changes a percentage without saying so.
        val unknown = repack(encoded(original)) { payload ->
            payload.withField(
                "preferences",
                payload.getValue("preferences").jsonObject
                    .withField("attendanceBasis", "WHENEVER"),
            )
        }

        val problem = refusal<BackupProblem.MalformedField>(unknown)
        assertEquals("preferences.attendanceBasis", problem.path)
    }

    // ---- the summary --------------------------------------------------------

    @Test
    fun `the import screen can say which semester it is looking at`() {
        val summary = readOk(encoded(original)).summary

        assertEquals(2, summary.semesters)
        assertEquals(1, summary.archivedSemesters)
        assertEquals("Odd semester 2026–27", summary.currentSemester)
        assertEquals(AttendanceBasis.PERSONAL, summary.attendanceStart.basis)
        assertEquals("My joining date (12 Aug 2026)", summary.attendanceStart.label)
    }

    // ---- what is refused ----------------------------------------------------

    @Test
    fun `a course pointing at a semester the file does not contain is refused`() {
        val dangling = repack(encoded(original)) { payload ->
            payload.withRecord("courses", "c1") { it.withField("semesterRef", "m99") }
        }

        val problem = refusal<BackupProblem.DanglingReference>(dangling)
        assertEquals("m99", problem.ref)
        assertTrue(problem.from.endsWith(".semesterRef"))
    }

    @Test
    fun `two semesters claiming the same reference are refused`() {
        val duplicated = repack(encoded(original)) { payload ->
            payload.withList("semesters") { records ->
                records.map { it.withField("ref", "m1") }
            }
        }

        assertEquals("semester", refusal<BackupProblem.DuplicateReference>(duplicated).kind)
    }

    @Test
    fun `two semesters claiming the same term are refused, not written and then rejected`() {
        // A year has one even semester. The app stores that as a uniqueness constraint, so a file
        // carrying it twice would abort a restore half way through instead of being refused
        // before it started — the same outcome, and a very different sentence to read.
        val sameTerm = repack(encoded(original)) { payload ->
            payload.withRecord("semesters", "m2") { record ->
                record.withField("year", 2026).withField("type", "EVEN")
            }
        }

        val problem = refusal<BackupProblem.InvalidValue>(sameTerm)
        assertEquals("semesters[m2]", problem.path)
        assertTrue(problem.detail, problem.detail.contains("even semester of 2026"))
    }

    @Test
    fun `a file with one of each term is not accused of repeating itself`() {
        // The guard is a duplicate check, not a "two semesters" check: the fixture's own pair —
        // the even semester and the odd one that follows it — is the ordinary case.
        val backup = readOk(encoded(original))

        assertEquals(2, backup.snapshot.semesters.size)
        assertEquals(
            setOf(SemesterType.EVEN, SemesterType.ODD),
            backup.snapshot.semesters.mapTo(mutableSetOf()) { it.type },
        )
    }

    @Test
    fun `a semester of an unknown kind is refused`() {
        val unknown = repack(encoded(original)) { payload ->
            payload.withRecord("semesters", "m1") { it.withField("type", "MONSOON") }
        }

        val problem = refusal<BackupProblem.MalformedField>(unknown)
        assertEquals("semesters[m1].type", problem.path)
    }

    @Test
    fun `a semester that ends before it starts is refused rather than swapped`() {
        val backwards = repack(encoded(original)) { payload ->
            payload.withRecord("semesters", "m1") { it.withField("endDate", "2026-01-01") }
        }

        val problem = refusal<BackupProblem.InvalidValue>(backwards)
        assertEquals("semesters[m1].endDate", problem.path)
    }

    @Test
    fun `an implausible year is reported as a bad field, not thrown out of a constructor`() {
        // Semester's own constructor would reject it. Reaching that constructor with an
        // unchecked value would turn a bad file into an exception, and decode promises never to
        // throw whatever the file says.
        val ancient = repack(encoded(original)) { payload ->
            payload.withRecord("semesters", "m1") { record ->
                record.withField("year", 0)
                    .withField("startDate", "0001-01-05")
                    .withField("endDate", "0001-05-15")
            }
        }

        val problem = refusal<BackupProblem.InvalidValue>(ancient)
        assertEquals("semesters[m1].year", problem.path)
    }

    @Test
    fun `an unreadable semester date is refused`() {
        val malformed = repack(encoded(original)) { payload ->
            payload.withRecord("semesters", "m1") { it.withField("startDate", "July 2026") }
        }

        assertEquals(
            "semesters[m1].startDate",
            refusal<BackupProblem.MalformedField>(malformed).path,
        )
    }

    // ---- reading a version 1 file -------------------------------------------

    @Test
    fun `a version one file reads, and is recorded as the version it was written as`() {
        val backup = readOk(version1(original))

        assertEquals(1, backup.meta.originalFormatVersion)
        assertEquals(BackupCodec.FORMAT_VERSION, backup.meta.formatVersion)
        assertEquals(1, backup.summary.formatVersion)
        assertEquals(original.sessions.size, backup.snapshot.sessions.size)
        assertEquals(original.courses.size, backup.snapshot.courses.size)
    }

    @Test
    fun `a version one file gets the semester its own calendar describes`() {
        val restored = readOk(version1(original)).snapshot

        val semester = restored.semesters.single()
        assertEquals(BackupFixtures.calendar.termStart, semester.startDate)
        assertEquals(BackupFixtures.calendar.termEnd, semester.endDate)
        assertEquals(SemesterType.ODD, semester.type)
        assertEquals(2026, semester.year)
        assertFalse(semester.archived)
        assertEquals("Odd semester 2026–27", semester.label)
    }

    @Test
    fun `a version one file's live courses land in that semester`() {
        val restored = readOk(version1(original)).snapshot
        val semester = restored.semesters.single()

        val live = restored.courses.filterNot { it.archived }
        assertEquals(2, live.size)
        live.forEach { assertTrue("${it.code} should be placed", it.isIn(semester)) }
    }

    @Test
    fun `a version one file's archived course is left unplaced rather than guessed at`() {
        // A v1 file says nothing about which term taught an archived course, and it may well be a
        // leftover from a previous one. Unplaced is the honest answer; the app adopts it with the
        // student watching.
        val restored = readOk(version1(original)).snapshot

        val evs = restored.courses.single { it.code == BackupFixtures.evs.code }
        assertTrue(evs.archived)
        assertNull(evs.semesterId)
    }

    @Test
    fun `a version one file means the university reading of attendance`() {
        val restored = readOk(version1(original)).snapshot

        assertEquals(AttendanceStart(), restored.preferences.attendanceStart)
        assertNull(restored.preferences.attendanceStart.joinedOn)
    }

    @Test
    fun `everything else in a version one file is untouched by the migration`() {
        val restored = readOk(version1(original)).snapshot

        // Same data, minus what v1 could not express: one derived semester in place of two, and
        // the archived course left unplaced.
        val derived = Semester.of(BackupFixtures.calendar)
        val asV1WouldRestore = original.copy(
            semesters = listOf(derived),
            courses = original.courses.map { course ->
                course.copy(semesterId = if (course.archived) null else derived.id)
            },
            preferences = original.preferences.copy(attendanceStart = AttendanceStart()),
        )

        assertEquals(asV1WouldRestore.normalised(), restored)
    }

    @Test
    fun `a version one file with no courses at all still gets its semester`() {
        val empty = BackupSnapshot(calendar = BackupFixtures.calendar)

        val restored = readOk(version1(empty)).snapshot

        assertEquals(1, restored.semesters.size)
        assertTrue(restored.courses.isEmpty())
    }

    @Test
    fun `a version one file whose calendar is unreadable is refused for the calendar`() {
        // The migration must not be the thing that fails: "the payload could not be migrated"
        // tells the student nothing about their file, and the decoder can name the real fault.
        val broken = tamper(
            repack(encoded(original)) { payload ->
                payload
                    .withField("semesters", JsonArray(emptyList()))
                    .withList("courses") { it.map { c -> c.withoutField("semesterRef") } }
                    .withField(
                        "calendar",
                        payload.getValue("calendar").jsonObject
                            .withField("termStart", "the end of July"),
                    )
            },
        ) { root -> root.withField("formatVersion", 1) }

        assertEquals("calendar.termStart", refusal<BackupProblem.MalformedField>(broken).path)
    }

    @Test
    fun `a version one file that somehow already carries semesters keeps its own`() {
        // A build that shipped the field before the version was bumped, or a hand-edited file.
        // Whatever it says is more specific than anything the migration could derive.
        val alreadyThere = tamper(encoded(original)) { root ->
            root.withField("formatVersion", 1)
        }

        val restored = readOk(alreadyThere).snapshot

        assertEquals(2, restored.semesters.size)
        assertEquals(1, restored.semesters.count { it.archived })
    }

    @Test
    fun `a restored version one file exports as version two and reads back identically`() {
        val restored = readOk(version1(original)).snapshot

        val reexported = readOk(encoded(restored))

        assertEquals(2, reexported.meta.originalFormatVersion)
        assertEquals(restored, reexported.snapshot)
    }

    // ---- semester grouping is usable after a restore -------------------------

    @Test
    fun `a restored file can be grouped into current and previous semesters`() {
        // The whole point of carrying semesters: the attendance screen has to be able to draw the
        // line, and it draws it from this data alone.
        val restored = readOk(encoded(original)).snapshot
        val current = restored.currentSemester!!

        val (thisTerm, other) = restored.courses.partition { it.isIn(current) }

        assertEquals(3, thisTerm.size)
        assertTrue(other.isEmpty())
        assertEquals(
            listOf("Even semester 2025–26"),
            restored.semesters.filter { it.archived }.map { it.label },
        )
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * The same snapshot as a version 1 file would have held it: no `semesters`, no `semesterRef`
     * on the courses, and nothing said about attendance start.
     *
     * Built by taking a real export apart rather than by hand-writing v1 JSON, so it stays a file
     * this codec genuinely produced once — and the envelope's version is changed *after* the
     * payload is re-stamped, because the checksum covers only the payload.
     */
    private fun version1(snapshot: BackupSnapshot): String = tamper(
        repack(encoded(snapshot)) { payload ->
            payload
                .withoutField("semesters")
                .withField(
                    "preferences",
                    payload.getValue("preferences").jsonObject
                        .withoutField("attendanceBasis")
                        .withoutField("joinedOn"),
                )
                .withList("courses") { courses -> courses.map { it.withoutField("semesterRef") } }
        },
    ) { root -> root.withField("formatVersion", 1) }
}

/**
 * Guards the fixture the tests above lean on, so a change to it fails here rather than showing up
 * as an unrelated assertion somewhere else.
 */
class BackupSemesterFixtureTest {

    @Test
    fun `the fixture has a live semester and an archived one`() {
        assertEquals(2, BackupFixtures.semesters.size)
        assertEquals(1, BackupFixtures.semesters.count { it.archived })
    }

    @Test
    fun `every course in the fixture is placed in the live semester`() {
        assertTrue(
            BackupFixtures.courses.all { it.semesterId == BackupFixtures.currentSemester.id },
        )
    }

    @Test
    fun `the fixture's semester and calendar describe the same term`() {
        // Several tests read the migration's derived semester against the calendar; they would
        // pass for the wrong reason if the fixture's own semester disagreed with it.
        assertEquals(BackupFixtures.calendar.termStart, BackupFixtures.currentSemester.startDate)
        assertEquals(BackupFixtures.calendar.termEnd, BackupFixtures.currentSemester.endDate)
    }

    @Test
    fun `the fixture's attendance start is not the default`() {
        assertTrue(BackupFixtures.preferences.attendanceStart != AttendanceStart())
    }

    @Test
    fun `the fixture's courses carry a semester link at all`() {
        // A fixture of unplaced courses would let a codec that never writes semesterRef pass.
        assertTrue(BackupFixtures.courses.none { it.semesterId == null })
        assertTrue(BackupFixtures.courses.isNotEmpty())
    }
}
