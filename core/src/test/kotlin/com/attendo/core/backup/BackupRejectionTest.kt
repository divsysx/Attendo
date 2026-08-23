package com.attendo.core.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything a backup can be refused for.
 *
 * The rule these tests exist to hold is that a refusal is total: [BackupCodec.decode] either
 * hands back a whole backup or hands back reasons, and there is no third outcome where two
 * thirds of a semester gets written over the one already on the phone. So every case here
 * asserts a refusal rather than a repair — including the tempting ones, where the codec could
 * plausibly guess (drop the bad session, clamp the impossible mask, pick a side of a broken
 * reschedule) and where guessing would silently change a student's attendance record.
 *
 * The other rule is that a refusal has to say something useful. Each `BackupProblem` carries a
 * field path and a sentence, so the assertions check the *reason*, not merely that something
 * went wrong.
 */
class BackupRejectionTest {

    private val good = encoded()

    // ---- not a backup at all ------------------------------------------------

    @Test
    fun `a file that is not json is refused`() {
        val problem = refusal<BackupProblem.NotJson>("this is my attendance, please import it")

        assertTrue(problem.message.contains("not a valid Attendo backup"))
    }

    @Test
    fun `an empty file is refused`() {
        refusal<BackupProblem.NotJson>("")
    }

    @Test
    fun `a truncated backup is refused`() {
        // The commonest real corruption: a copy interrupted part way, so the JSON never closes.
        refusal<BackupProblem.NotJson>(good.substring(0, good.length / 2))
    }

    @Test
    fun `json that is not an object is refused`() {
        refusal<BackupProblem.NotABackup>("[1, 2, 3]")
        refusal<BackupProblem.NotABackup>("\"a string\"")
    }

    @Test
    fun `json with no format version is refused`() {
        refusal<BackupProblem.NotABackup>("{}")
        refusal<BackupProblem.NotABackup>("""{"courses": []}""")
    }

    @Test
    fun `a format version that is not a number is refused`() {
        refusal<BackupProblem.NotABackup>(tamper(good) { it.withField("formatVersion", "1") })
    }

    @Test
    fun `a backup with a version but no envelope is refused`() {
        refusal<BackupProblem.NotABackup>("""{"formatVersion": 1}""")
        refusal<BackupProblem.NotABackup>(tamper(good) { it.withoutField("payload") })
        refusal<BackupProblem.NotABackup>(tamper(good) { it.withoutField("checksum") })
    }

    // ---- versions -----------------------------------------------------------

    @Test
    fun `a backup from a newer Attendo is refused with an upgrade hint`() {
        val newer = tamper(good) { it.withField("formatVersion", BackupCodec.FORMAT_VERSION + 1) }

        val problem = refusal<BackupProblem.UnsupportedFormatVersion>(newer)

        assertEquals(BackupCodec.FORMAT_VERSION + 1, problem.found)
        assertEquals(BackupCodec.supportedFormatVersions, problem.supported)
        assertTrue(problem.message.contains("newer version"))
        assertTrue(problem.message.contains("Update the app"))
    }

    @Test
    fun `a version older than anything this build reads is refused`() {
        val ancient = tamper(good) {
            it.withField("formatVersion", BackupCodec.supportedFormatVersions.first - 1)
        }

        val problem = refusal<BackupProblem.UnsupportedFormatVersion>(ancient)

        assertTrue(problem.message.contains("no longer reads"))
        assertFalse(problem.message.contains("newer version"))
    }

    @Test
    fun `the version is checked before the contents are`() {
        // A future format may have reshaped the envelope, so a file this build cannot read has
        // to be refused for its version rather than for whatever its contents look like.
        val newerAndUnreadable = tamper(good) {
            it.withField("formatVersion", BackupCodec.FORMAT_VERSION + 1)
                .withField("payload", JsonPrimitive("a shape this build has never seen"))
        }

        val problems = refusalsOf(newerAndUnreadable)

        assertEquals(1, problems.size)
        assertTrue(problems.single() is BackupProblem.UnsupportedFormatVersion)
    }

    // ---- integrity ----------------------------------------------------------

    @Test
    fun `a payload that has been altered is caught by the checksum`() {
        // Valid JSON, valid structure, plausible values — and one course renamed. Without a
        // checksum this would import cleanly as something other than what was exported.
        val altered = tamperPayload(good) {
            it.withRecord("courses", "c1") { course -> course.withField("name", "Something else") }
        }

        val problem = refusal<BackupProblem.ChecksumMismatch>(altered)

        assertNotEquals(problem.expected, problem.actual)
        assertTrue(problem.message.contains("damaged"))
    }

    @Test
    fun `sessions lost from the middle of the file are caught by the checksum`() {
        // JSON parses quite happily after losing entries from an array, which is how a corrupted
        // backup gets to look like a smaller correct one.
        val shortened = tamperPayload(good) { it.withList("sessions") { rows -> rows.drop(3) } }

        refusal<BackupProblem.ChecksumMismatch>(shortened)
    }

    @Test
    fun `a checksum this build cannot compute is refused`() {
        val other = tamper(good) {
            it.withField(
                "checksum",
                JsonObject(
                    mapOf(
                        "algorithm" to JsonPrimitive("CRC-32"),
                        "value" to JsonPrimitive("deadbeef"),
                    ),
                ),
            )
        }

        assertEquals("CRC-32", refusal<BackupProblem.UnknownChecksumAlgorithm>(other).algorithm)
    }

    @Test
    fun `reformatting a backup by hand does not break it`() {
        // The flip side of the checksum: it covers the data, not the bytes, so a file that has
        // been through a formatter or had its keys reordered still imports.
        val reordered = tamperPayload(good) { payload ->
            JsonObject(payload.entries.reversed().associate { it.toPair() })
        }

        assertEquals(readOk(good).snapshot, readOk(reordered).snapshot)
    }

    // ---- incomplete ---------------------------------------------------------

    @Test
    fun `a session missing a required field is refused rather than defaulted`() {
        val missingStatus = repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withoutField("status") }
        }

        assertEquals("payload", refusal<BackupProblem.MalformedField>(missingStatus).path)
    }

    @Test
    fun `a payload missing a whole section is refused`() {
        refusal<BackupProblem.MalformedField>(repack(good) { it.withoutField("calendar") })
        refusal<BackupProblem.MalformedField>(repack(good) { it.withoutField("preferences") })
        refusal<BackupProblem.MalformedField>(repack(good) { it.withoutField("sessions") })
        refusal<BackupProblem.MalformedField>(repack(good) { it.withoutField("courses") })
    }

    @Test
    fun `a field of the wrong type is refused`() {
        val wrongType = repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withField("unitsPlanned", "two") }
        }

        refusal<BackupProblem.MalformedField>(wrongType)
    }

    @Test
    fun `a null where a value is required is refused`() {
        val nulled = repack(good) {
            it.withRecord("courses", "c1") { course -> course.withField("code", JsonNull) }
        }

        refusal<BackupProblem.MalformedField>(nulled)
    }

    @Test
    fun `a date that is not a date is refused, naming the field`() {
        val badDate = repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withField("date", "3rd August") }
        }

        val problem = refusal<BackupProblem.MalformedField>(badDate)

        assertEquals("sessions[s1].date", problem.path)
        assertTrue(problem.message.contains("YYYY-MM-DD"))
    }

    @Test
    fun `a timestamp that is not a timestamp is refused`() {
        val badInstant = repack(good) {
            it.withRecord("sessions", "s1") { session ->
                session.withField("approvedAt", "yesterday evening")
            }
        }

        assertEquals(
            "sessions[s1].approvedAt",
            refusal<BackupProblem.MalformedField>(badInstant).path,
        )
    }

    @Test
    fun `an enum value this build does not know is refused`() {
        val unknown = repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withField("status", "PROVISIONAL") }
        }

        val problem = refusal<BackupProblem.MalformedField>(unknown)

        assertEquals("sessions[s1].status", problem.path)
        assertTrue(problem.message.contains("HELD"))
    }

    @Test
    fun `an unknown day of the week is refused`() {
        val badDay = repack(good) {
            it.withRecord("patterns", "p1") { pattern -> pattern.withField("dayOfWeek", "MOONDAY") }
        }

        assertEquals("patterns[p1].dayOfWeek", refusal<BackupProblem.MalformedField>(badDay).path)
    }

    // ---- internally inconsistent --------------------------------------------

    @Test
    fun `a session pointing at a course that is not in the file is refused`() {
        val dangling = repack(good) {
            it.withRecord("sessions", "s1") { session -> session.withField("courseRef", "c99") }
        }

        val problem = refusal<BackupProblem.DanglingReference>(dangling)

        assertEquals("sessions[s1].courseRef", problem.from)
        assertEquals("c99", problem.ref)
    }

    @Test
    fun `a pattern pointing at a course that is not in the file is refused`() {
        val dangling = repack(good) {
            it.withRecord("patterns", "p1") { pattern -> pattern.withField("courseRef", "c99") }
        }

        assertEquals("c99", refusal<BackupProblem.DanglingReference>(dangling).ref)
    }

    @Test
    fun `two records sharing a reference are refused`() {
        val duplicated = repack(good) { it.withList("courses") { rows -> rows + rows.first() } }

        val problem = refusal<BackupProblem.DuplicateReference>(duplicated)

        assertEquals("course", problem.kind)
        assertEquals("c1", problem.ref)
    }

    @Test
    fun `a blank reference is refused`() {
        val blank = repack(good) {
            it.withRecord("courses", "c1") { course -> course.withField("ref", "  ") }
        }

        refusal<BackupProblem.MalformedField>(blank)
    }

    @Test
    fun `a session whose pattern is missing is accepted, because that one is legitimate`() {
        // The single dangling reference the format allows: a marked class outlives the pattern
        // that generated it and has to stay a timetabled class. Asserted here, among the
        // rejections, because the boundary between this and the cases above is the whole point.
        val backup = readOk(good)
        val orphan = backup.snapshot.sessions.single {
            it.date == BackupFixtures.labFromDeletedPattern.date
        }

        assertNotNull(orphan.patternId)
        assertFalse(backup.snapshot.patterns.any { it.id == orphan.patternId })
    }

    // ---- values the domain model would reject -------------------------------

    @Test
    fun `a class that does not fit the teaching day is refused`() {
        val overrunning = repack(good) {
            it.withRecord("sessions", "s1") { session ->
                session.withField("startHour", 17).withField("unitsPlanned", 2)
            }
        }

        val problem = refusal<BackupProblem.InvalidValue>(overrunning)

        assertEquals("sessions[s1].startHour", problem.path)
        assertTrue(problem.message.contains("teaching day"))
    }

    @Test
    fun `a class of no length is refused`() {
        val empty = repack(good) {
            it.withRecord("sessions", "s1") { session ->
                session.withField("unitsPlanned", 0)
                    .withField("attendedUnits", JsonArray(emptyList()))
            }
        }

        assertEquals("sessions[s1].unitsPlanned", refusal<BackupProblem.InvalidValue>(empty).path)
    }

    @Test
    fun `a pattern longer than a class can be is refused`() {
        val tooLong = repack(good) {
            it.withRecord("patterns", "p1") { pattern -> pattern.withField("units", 13) }
        }

        assertEquals("patterns[p1].units", refusal<BackupProblem.InvalidValue>(tooLong).path)
    }

    @Test
    fun `attendance for an hour the class did not have is refused, not clamped`() {
        // The one fault that would inflate a percentage. A file claiming the sixth hour of a
        // two-hour class no longer says what happened, so it is refused rather than trimmed.
        val impossible = repack(good) {
            it.withRecord("sessions", "s1") { session ->
                session.withField("attendedUnits", jsonInts(0, 5))
            }
        }

        val problem = refusal<BackupProblem.InvalidValue>(impossible)

        assertEquals("sessions[s1].attendedUnits", problem.path)
        assertTrue(problem.message.contains("outside"))
    }

    @Test
    fun `a negative attended hour is refused`() {
        val negative = repack(good) {
            it.withRecord("sessions", "s1") { session ->
                session.withField("attendedUnits", jsonInts(-1))
            }
        }

        refusal<BackupProblem.InvalidValue>(negative)
    }

    @Test
    fun `a cancellation reason on a class that was not cancelled is refused`() {
        // Would make a class that counts look like one that does not, or the reverse. The app
        // cannot tell which half is the truth, so it says so rather than choosing.
        val contradictory = repack(good) {
            it.withRecord("sessions", "s1") { session ->
                session.withField("status", "HELD").withField("cancellationReason", "HOLIDAY")
            }
        }

        assertEquals(
            "sessions[s1].cancellationReason",
            refusal<BackupProblem.InvalidValue>(contradictory).path,
        )
    }

    @Test
    fun `a target that is not a percentage is refused`() {
        val overFull = repack(good) { payload ->
            payload.withSection("preferences") {
                it.withField("overallTargetBasisPoints", 20_000)
            }
        }

        assertEquals(
            "preferences.overallTargetBasisPoints",
            refusal<BackupProblem.InvalidValue>(overFull).path,
        )
    }

    @Test
    fun `a course target that is negative is refused`() {
        val negative = repack(good) {
            it.withRecord("courses", "c1") { course -> course.withField("targetBasisPoints", -100) }
        }

        assertEquals(
            "courses[c1].targetBasisPoints",
            refusal<BackupProblem.InvalidValue>(negative).path,
        )
    }

    @Test
    fun `a course with no name is refused`() {
        val nameless = repack(good) {
            it.withRecord("courses", "c1") { course -> course.withField("name", "   ") }
        }

        assertEquals("courses[c1].name", refusal<BackupProblem.InvalidValue>(nameless).path)
    }

    @Test
    fun `a term that ends before it starts is refused`() {
        val backwards = repack(good) { payload ->
            payload.withSection("calendar") { it.withField("termEnd", "2026-01-01") }
        }

        assertEquals("calendar.termEnd", refusal<BackupProblem.InvalidValue>(backwards).path)
    }

    @Test
    fun `a pattern retired before it starts is refused`() {
        val backwards = repack(good) {
            it.withRecord("patterns", "p1") { pattern ->
                pattern.withField("effectiveTo", "2020-01-01")
            }
        }

        assertEquals(
            "patterns[p1].effectiveTo",
            refusal<BackupProblem.InvalidValue>(backwards).path,
        )
    }

    @Test
    fun `a holiday that is not a date is refused`() {
        val badHoliday = repack(good) { payload ->
            payload.withSection("calendar") {
                it.withField("holidays", JsonArray(listOf(JsonPrimitive("Diwali"))))
            }
        }

        assertEquals("calendar.holidays[0]", refusal<BackupProblem.MalformedField>(badHoliday).path)
    }

    // ---- reschedules --------------------------------------------------------

    @Test
    fun `a reschedule the replacement does not acknowledge is refused`() {
        val oneSided = repack(good) { payload ->
            payload.withRecord("sessions", payload.replacementRef()) {
                it.withoutField("movedFromRef")
            }
        }

        assertTrue(
            refusal<BackupProblem.BrokenReschedule>(oneSided).message.contains("does not point back"),
        )
    }

    @Test
    fun `a replacement pointing at the wrong original is refused`() {
        val crossed = repack(good) { payload ->
            payload.withRecord("sessions", payload.replacementRef()) {
                it.withField("movedFromRef", "s1")
            }
        }

        refusal<BackupProblem.BrokenReschedule>(crossed)
    }

    @Test
    fun `an original that was moved away but is not cancelled is refused`() {
        // Counting both halves of a reschedule would double the class. The link is the only
        // thing preventing that, so the two records have to agree.
        val notCancelled = repack(good) { payload ->
            payload.withRecord("sessions", payload.originalRef()) {
                it.withField("status", "HELD").withoutField("cancellationReason")
            }
        }

        assertTrue(
            refusal<BackupProblem.BrokenReschedule>(notCancelled)
                .message.contains("count the class twice"),
        )
    }

    @Test
    fun `a reschedule pointing at a session that is not in the file is refused`() {
        val gone = repack(good) { payload ->
            payload.withRecord("sessions", payload.originalRef()) {
                it.withField("movedToRef", "s999")
            }
        }

        assertEquals("s999", refusal<BackupProblem.DanglingReference>(gone).ref)
    }

    // ---- how refusals behave ------------------------------------------------

    @Test
    fun `every fault in a file is reported in one pass`() {
        // A student handed one fault at a time cannot tell a truncated file from a wrong one.
        val several = repack(good) { payload ->
            payload
                .withRecord("sessions", "s1") { it.withField("courseRef", "c99") }
                .withRecord("sessions", "s2") { it.withField("date", "not a date") }
                .withRecord("courses", "c1") { it.withField("name", " ") }
        }

        val problems = refusalsOf(several)

        assertTrue("expected several problems, got $problems", problems.size >= 3)
        assertTrue(problems.any { it is BackupProblem.DanglingReference })
        assertTrue(problems.any { it is BackupProblem.MalformedField })
        assertTrue(problems.any { it is BackupProblem.InvalidValue })
    }

    @Test
    fun `nothing is imported when one class out of a semester is wrong`() {
        val semester = BackupFixtures.fullSemester()
        val oneBadSession = repack(encoded(semester)) {
            it.withRecord("sessions", "s40") { session -> session.withField("startHour", 23) }
        }

        // Not "220 of your 221 classes restored": the result carries problems and no snapshot,
        // because a partly-restored semester is not a value this type can hold.
        val result = BackupCodec.decode(oneBadSession)

        assertTrue(result is BackupReadResult.Failed)
        assertTrue((result as BackupReadResult.Failed).problems.isNotEmpty())
    }

    @Test
    fun `a failure always says at least one thing about why`() {
        listOf(
            "",
            "{",
            "null",
            "[]",
            "{}",
            "0",
            " ",
            good.substring(0, 20),
            good.reversed(),
            tamper(good) { it.withoutField("payload") },
            tamper(good) { it.withField("formatVersion", 99) },
        ).forEach { text ->
            val problems = refusalsOf(text)
            assertTrue("no problems reported for <$text>", problems.isNotEmpty())
            assertTrue("blank message for <$text>", problems.all { it.message.isNotBlank() })
        }
    }

    @Test
    fun `fields from a newer build are ignored rather than refused`() {
        // Additive changes must not break older readers, or every new field would be a broken
        // restore for anyone who had not updated yet.
        val withExtras = repack(good) { payload ->
            payload
                .withField("somethingNew", "ignore me")
                .withRecord("sessions", "s1") { it.withField("moodRating", 4) }
        }

        assertEquals(readOk(good).snapshot, readOk(withExtras).snapshot)
    }

    // ---- helpers ------------------------------------------------------------

    private fun JsonObject.withSection(key: String, edit: (JsonObject) -> JsonObject): JsonObject =
        withField(key, edit(getValue(key) as JsonObject))

    /** The cancelled half of the fixture's reschedule. */
    private fun JsonObject.originalRef(): String = sessionRefWhere("movedToRef")

    /** The ad-hoc half. */
    private fun JsonObject.replacementRef(): String = sessionRefWhere("movedFromRef")

    private fun JsonObject.sessionRefWhere(link: String): String {
        val sessions = (getValue("sessions") as JsonArray).map { it as JsonObject }
        val match = sessions.single { it[link].let { ref -> ref != null && ref != JsonNull } }
        return (match.getValue("ref") as JsonPrimitive).content
    }

    private fun jsonInts(vararg values: Int): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })
}
