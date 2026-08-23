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
import com.attendo.core.model.TimeGrid
import com.attendo.core.model.UnitMask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Reads and writes Attendo backup files.
 *
 * ### The contract
 *
 * [encode] is total: any snapshot the app holds can be written out. [decode] is the opposite
 * — it trusts nothing. A backup arrives from outside the app, possibly across a year of
 * versions, possibly truncated by whatever carried it, possibly hand-edited by a curious
 * student. So it is checked in the order that lets each stage assume the previous one
 * succeeded:
 *
 * 1. **Is it JSON?** Anything else and there is nothing to look at.
 * 2. **What format version?** Read from the raw tree before the envelope is decoded, because
 *    a future version is entitled to have changed the envelope's shape too. Refused if
 *    unsupported, rather than read hopefully.
 * 3. **Does the checksum match?** Verified against the payload *as written*, before any
 *    migration touches it — the hash belongs to the bytes the exporting build produced.
 * 4. **Migrate** the payload forward to the current shape.
 * 5. **Decode** the payload's structure.
 * 6. **Validate** every value and every reference, collecting *all* the problems.
 * 7. **Build** the domain objects.
 *
 * Nothing is returned until step 7 succeeds. There is no partial result — a caller cannot
 * accidentally restore three quarters of a backup, because three quarters of a backup is not
 * a value this type can produce.
 *
 * ### Why validation collects rather than fails fast
 *
 * A student handed "import failed" learns nothing; a student handed "this backup is
 * incomplete at sessions[41].status" can at least tell whether the file was truncated. And
 * collecting means one pass reports every fault, so a diagnosis does not arrive one round
 * trip at a time.
 */
object BackupCodec {

    /** The format version this build writes. */
    const val FORMAT_VERSION: Int = BackupMigrations.CURRENT

    val supportedFormatVersions: IntRange get() = BackupMigrations.supported

    const val APP_NAME: String = "Attendo"

    const val MIME_TYPE: String = "application/json"

    const val FILE_EXTENSION: String = "json"

    /** `attendo-backup-2026-08-19.json` — sorts chronologically, says what it is. */
    fun suggestedFileName(on: LocalDate): String = "attendo-backup-$on.$FILE_EXTENSION"

    // ---- writing ------------------------------------------------------------

    /**
     * Serialises [snapshot] as a complete backup file.
     *
     * The snapshot is normalised first, so exporting the same data twice produces the same
     * bytes (given the same [exportedAt]). That is worth having: it makes two backups
     * comparable, and it means the refs in the file follow a documented order rather than
     * whatever order the database handed the rows over in.
     *
     * @param pretty indented output. On by default — a backup a student can open and read is
     *   a backup they can sanity-check, and the size difference is a few kilobytes.
     */
    fun encode(
        snapshot: BackupSnapshot,
        appVersionName: String,
        appVersionCode: Long,
        exportedAt: Instant,
        pretty: Boolean = true,
    ): String {
        val payload = payloadOf(snapshot.normalised())
        val payloadTree = json.encodeToJsonElement(WirePayload.serializer(), payload) as JsonObject
        val envelope = WireEnvelope(
            formatVersion = FORMAT_VERSION,
            app = WireApp(APP_NAME, appVersionName, appVersionCode),
            exportedAt = exportedAt.toString(),
            checksum = WireChecksum(BackupChecksum.ALGORITHM, BackupChecksum.of(payloadTree)),
            payload = payloadTree,
        )
        val writer = if (pretty) prettyJson else json
        return writer.encodeToString(WireEnvelope.serializer(), envelope)
    }

    private fun payloadOf(s: BackupSnapshot): WirePayload = WirePayload(
        preferences = WirePreferences(
            overallTargetBasisPoints = s.preferences.overallTarget.basisPoints,
            courseTargetBasisPoints = s.preferences.courseTarget.basisPoints,
            section = s.preferences.section,
            batch = s.preferences.batch,
            displayName = s.preferences.displayName,
            attendanceBasis = s.preferences.attendanceStart.basis.name,
            // Written whichever basis is in force, because it is kept whichever basis is in
            // force: a student who switches to the university reading and back should not be
            // asked for their joining date again on the other side of a restore.
            joinedOn = s.preferences.attendanceStart.joinedOn?.toString(),
        ),
        calendar = WireCalendar(
            termStart = s.calendar.termStart.toString(),
            termEnd = s.calendar.termEnd.toString(),
            holidays = s.calendar.holidays.sorted().map(LocalDate::toString),
            workingSaturdays = s.calendar.workingSaturdays.sorted().map(LocalDate::toString),
        ),
        semesters = s.semesters.map { semester ->
            WireSemester(
                ref = semesterRef(semester.id),
                year = semester.year,
                type = semester.type.name,
                startDate = semester.startDate.toString(),
                endDate = semester.endDate.toString(),
                archived = semester.archived,
            )
        },
        courses = s.courses.map { course ->
            WireCourse(
                ref = courseRef(course.id),
                name = course.name,
                code = course.code,
                targetBasisPoints = course.targetPercent.basisPoints,
                colorArgb = course.colorArgb,
                archived = course.archived,
                semesterRef = course.semesterId?.let(::semesterRef),
            )
        },
        patterns = s.patterns.map { pattern ->
            WirePattern(
                ref = patternRef(pattern.id),
                courseRef = courseRef(pattern.courseId),
                dayOfWeek = pattern.dayOfWeek.name,
                startHour = pattern.startHour,
                units = pattern.units,
                kind = pattern.kind.name,
                room = pattern.room,
                effectiveFrom = pattern.effectiveFrom.toString(),
                effectiveTo = pattern.effectiveTo?.toString(),
            )
        },
        sessions = s.sessions.map { session ->
            WireSession(
                ref = sessionRef(session.id),
                courseRef = courseRef(session.courseId),
                patternRef = session.patternId?.let(::patternRef),
                date = session.date.toString(),
                startHour = session.startHour,
                unitsPlanned = session.unitsPlanned,
                attendedUnits = session.unitsMask.attendedIndices(session.unitsPlanned),
                status = session.status.name,
                cancellationReason = session.cancellationReason?.name,
                kind = session.kind.name,
                room = session.room,
                note = session.note,
                approvedAt = session.approvedAt?.toString(),
                lastEditedAt = session.lastEditedAt?.toString(),
                movedToRef = session.movedToSessionId?.let(::sessionRef),
                movedFromRef = session.movedFromSessionId?.let(::sessionRef),
            )
        },
    )

    // ---- reading ------------------------------------------------------------

    /** Reads a backup file. Never throws: every failure comes back as [BackupReadResult.Failed]. */
    fun decode(text: String): BackupReadResult {
        val root = runCatching { Json.parseToJsonElement(text) }
            .getOrElse { return failed(BackupProblem.NotJson(it.explain())) }

        val tree = root as? JsonObject
            ?: return failed(BackupProblem.NotABackup("Its top level is not a JSON object."))

        // Read straight out of the tree rather than via the envelope: a newer format is
        // entitled to have reshaped the envelope, and the version is what decides whether
        // this build may read it at all.
        val version = (tree["formatVersion"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toIntOrNull()
            ?: return failed(
                BackupProblem.NotABackup("It has no numeric \"formatVersion\" field."),
            )

        if (!BackupMigrations.isSupported(version)) {
            return failed(
                BackupProblem.UnsupportedFormatVersion(version, BackupMigrations.supported),
            )
        }

        val envelope = runCatching { json.decodeFromJsonElement(WireEnvelope.serializer(), tree) }
            .getOrElse { return failed(BackupProblem.NotABackup(it.explain())) }

        if (!BackupChecksum.isSupported(envelope.checksum.algorithm)) {
            return failed(BackupProblem.UnknownChecksumAlgorithm(envelope.checksum.algorithm))
        }
        val actual = BackupChecksum.of(envelope.payload)
        if (!BackupChecksum.matches(envelope.checksum.value, actual)) {
            return failed(BackupProblem.ChecksumMismatch(envelope.checksum.value, actual))
        }

        val migrated = runCatching { BackupMigrations.migrate(envelope.payload, version) }
            .getOrElse { return failed(BackupProblem.MalformedField("payload", it.explain())) }

        val payload = runCatching { json.decodeFromJsonElement(WirePayload.serializer(), migrated) }
            .getOrElse { return failed(BackupProblem.MalformedField("payload", it.explain())) }

        val exportedAt = runCatching { Instant.parse(envelope.exportedAt) }.getOrNull()
            ?: return failed(
                BackupProblem.MalformedField(
                    "exportedAt",
                    "\"${envelope.exportedAt}\" is not an ISO-8601 instant.",
                ),
            )

        return build(
            payload = payload,
            meta = BackupMeta(
                formatVersion = FORMAT_VERSION,
                appVersionName = envelope.app.versionName,
                appVersionCode = envelope.app.versionCode,
                exportedAt = exportedAt,
                originalFormatVersion = version,
            ),
        )
    }

    private fun build(payload: WirePayload, meta: BackupMeta): BackupReadResult {
        val reader = WireReader()

        // Semesters first: a course carries a link to one, so the refs have to be resolvable
        // before the courses are read.
        val semesterIds = reader.identify(payload.semesters.map { it.ref }, "semester")
        val courseIds = reader.identify(payload.courses.map { it.ref }, "course")
        val patternIds = reader.identify(payload.patterns.map { it.ref }, "pattern")
        val sessionIds = reader.identify(payload.sessions.map { it.ref }, "session")

        // A patternRef with no pattern behind it is legitimate — see BackupWire — but it still
        // needs an id, or the session it belongs to would come back as an ad-hoc class.
        val orphanPatternRefs = payload.sessions
            .mapNotNull { it.patternRef }
            .distinct()
            .filter { it !in patternIds }
            .sorted()
        val allPatternIds = patternIds + orphanPatternRefs.withIndex()
            .associate { (index, ref) -> ref to patternIds.size + 1L + index }

        val calendar = reader.calendar(payload.calendar)
        val preferences = reader.preferences(payload.preferences)
        val semesters = payload.semesters.map { reader.semester(it, semesterIds) }
        val courses = payload.courses.map { reader.course(it, courseIds, semesterIds) }
        val patterns = payload.patterns.map { reader.pattern(it, patternIds, courseIds) }
        val sessions = payload.sessions.map {
            reader.session(it, sessionIds, allPatternIds, courseIds)
        }
        reader.checkReschedules(payload.sessions)
        reader.checkSemesterTerms(payload.semesters)

        if (reader.problems.isNotEmpty()) return BackupReadResult.Failed(reader.problems.toList())

        // Every builder above records a problem when it returns null, and the check just above
        // proved there were none — so this cannot fire. It stays because `decode` promises never
        // to throw, and a bug in a builder must not turn that promise into a crash on a file the
        // student was told to trust.
        if (calendar == null || preferences == null || semesters.any { it == null } ||
            courses.any { it == null } || patterns.any { it == null } || sessions.any { it == null }
        ) {
            return failed(BackupProblem.NotABackup("Its contents could not be reconstructed."))
        }

        return BackupReadResult.Ok(
            Backup(
                meta = meta,
                snapshot = BackupSnapshot(
                    courses = courses.filterNotNull(),
                    patterns = patterns.filterNotNull(),
                    sessions = sessions.filterNotNull(),
                    calendar = calendar,
                    preferences = preferences,
                    semesters = semesters.filterNotNull(),
                ),
            ),
        )
    }

    // ---- refs ---------------------------------------------------------------

    private fun semesterRef(id: Long) = "m$id"

    private fun courseRef(id: Long) = "c$id"

    private fun patternRef(id: Long) = "p$id"

    private fun sessionRef(id: Long) = "s$id"

    // ---- json ---------------------------------------------------------------

    /**
     * `ignoreUnknownKeys` is asymmetric on purpose: a *missing* required field is a fault, but
     * an *extra* one is only a newer build having written something this one has no use for.
     * Refusing the whole file over a field we would ignore anyway would turn every additive
     * change into a broken restore.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Indent width is left at the library default rather than set: `prettyPrintIndent` is an
    // experimental API, and this repo opts in to exactly two of those, neither of them here.
    private val prettyJson = Json(json) {
        prettyPrint = true
    }

    private fun failed(problem: BackupProblem) = BackupReadResult.Failed(listOf(problem))

    private fun Throwable.explain(): String =
        message?.takeIf { it.isNotBlank() }?.lineSequence()?.first()?.trim()
            ?: (this::class.simpleName ?: "Unreadable.")
}

/**
 * Turns wire records into domain objects, recording a problem instead of throwing.
 *
 * Every method returns null on failure *and* appends to [problems], so the caller can carry
 * on and gather the rest of the faults rather than stopping at the first one. Nothing here
 * repairs anything: a value that does not make sense is reported, never guessed at.
 */
private class WireReader {

    val problems = mutableListOf<BackupProblem>()

    /**
     * Assigns each ref a row id, in file order, and reports any ref used twice.
     *
     * Ids start at 1 and follow the file, which is what makes a decode of an [encode]
     * byte-for-byte equal to the normalised snapshot it came from.
     */
    fun identify(refs: List<String>, kind: String): Map<String, Long> {
        val ids = LinkedHashMap<String, Long>()
        refs.forEach { ref ->
            when {
                ref.isBlank() ->
                    problems += BackupProblem.MalformedField("$kind.ref", "A ref is blank.")

                ref in ids -> problems += BackupProblem.DuplicateReference(kind, ref)

                else -> ids[ref] = ids.size + 1L
            }
        }
        return ids
    }

    fun calendar(wire: WireCalendar): AcademicCalendar? {
        val start = date(wire.termStart, "calendar.termStart") ?: return null
        val end = date(wire.termEnd, "calendar.termEnd") ?: return null
        if (end.isBefore(start)) {
            problems += BackupProblem.InvalidValue(
                "calendar.termEnd",
                "the term ends ($end) before it starts ($start).",
            )
            return null
        }
        val holidays = dates(wire.holidays, "calendar.holidays") ?: return null
        val saturdays = dates(wire.workingSaturdays, "calendar.workingSaturdays") ?: return null
        return AcademicCalendar(start, end, holidays.toSet(), saturdays.toSet())
    }

    fun preferences(wire: WirePreferences): BackupPreferences? {
        val overall = percent(wire.overallTargetBasisPoints, "preferences.overallTargetBasisPoints")
        val course = percent(wire.courseTargetBasisPoints, "preferences.courseTargetBasisPoints")
        if (overall == null || course == null) return null
        // Absent means the university reading, which is both the default and what every file
        // written before version 2 meant by saying nothing.
        val basis = wire.attendanceBasis
            ?.let { enum(it, AttendanceBasis.values(), "preferences.attendanceBasis") ?: return null }
            ?: AttendanceBasis.UNIVERSITY
        val joinedOn = wire.joinedOn?.ifBlank { null }
            ?.let { date(it, "preferences.joinedOn") ?: return null }
        return BackupPreferences(
            overallTarget = overall,
            courseTarget = course,
            section = wire.section?.ifBlank { null },
            batch = wire.batch?.ifBlank { null },
            // Trimmed, not just blank-checked: this one is stored trimmed by the app, so a
            // hand-edited file with a padded name would otherwise fail the restore's own
            // read-back comparison for a difference nobody can see.
            displayName = wire.displayName?.trim()?.ifEmpty { null },
            // A personal basis with no date is a legitimate stored state, not a fault: the
            // student chose it and has not filled the date in. AttendanceStart falls back to the
            // semester start until they do, so it counts the same as the university reading
            // rather than counting nothing.
            attendanceStart = AttendanceStart(basis = basis, joinedOn = joinedOn),
        )
    }

    fun semester(wire: WireSemester, semesterIds: Map<String, Long>): Semester? {
        val at = "semesters[${wire.ref}]"
        val id = semesterIds[wire.ref] ?: return null
        val type = enum(wire.type, SemesterType.values(), "$at.type") ?: return null
        val start = date(wire.startDate, "$at.startDate") ?: return null
        val end = date(wire.endDate, "$at.endDate") ?: return null
        if (end.isBefore(start)) {
            problems += BackupProblem.InvalidValue(
                "$at.endDate",
                "the semester ends ($end) before it starts ($start).",
            )
            return null
        }
        if (wire.year !in Semester.PLAUSIBLE_YEARS) {
            problems += BackupProblem.InvalidValue(
                "$at.year",
                "${wire.year} is not an academic year between ${Semester.PLAUSIBLE_YEARS.first} " +
                    "and ${Semester.PLAUSIBLE_YEARS.last}.",
            )
            return null
        }
        return Semester(
            id = id,
            year = wire.year,
            type = type,
            startDate = start,
            endDate = end,
            archived = wire.archived,
        )
    }

    /**
     * Two semesters claiming the same term.
     *
     * A year has one odd semester and one even one — the app stores that as a uniqueness
     * constraint, so a file carrying both would abort the restore half way through instead of
     * being refused before it started. Caught here so the answer is "this file was not
     * accepted" rather than "the restore was cancelled", which are the same outcome and very
     * different sentences to read.
     *
     * Reported once per duplicate, naming the ref that repeats rather than the one it repeats,
     * so a file with three copies of a term says so three times minus one.
     */
    fun checkSemesterTerms(semesters: List<WireSemester>) {
        val seen = mutableSetOf<Pair<Int, String>>()
        semesters.forEach { wire ->
            if (!seen.add(wire.year to wire.type)) {
                problems += BackupProblem.InvalidValue(
                    "semesters[${wire.ref}]",
                    "the ${wire.type.lowercase()} semester of ${wire.year} appears more than " +
                        "once; a term can only have one.",
                )
            }
        }
    }

    fun course(
        wire: WireCourse,
        courseIds: Map<String, Long>,
        semesterIds: Map<String, Long>,
    ): Course? {
        val at = "courses[${wire.ref}]"
        val id = courseIds[wire.ref] ?: return null
        val target = percent(wire.targetBasisPoints, "$at.targetBasisPoints") ?: return null
        // Absent is valid and means "not known" — a file written before version 2, or a course a
        // migration declined to place. Present but unresolvable is a fault: a number pointing at
        // no semester would put the course in a term that is not in the file.
        val semesterId = wire.semesterRef
            ?.let { reference(it, semesterIds, "$at.semesterRef") ?: return null }
        if (wire.name.isBlank()) {
            problems += BackupProblem.InvalidValue("$at.name", "a course must have a name.")
            return null
        }
        if (wire.code.isBlank()) {
            problems += BackupProblem.InvalidValue("$at.code", "a course must have a code.")
            return null
        }
        return Course(
            id = id,
            name = wire.name,
            code = wire.code,
            targetPercent = target,
            colorArgb = wire.colorArgb,
            archived = wire.archived,
            semesterId = semesterId,
        )
    }

    fun pattern(
        wire: WirePattern,
        patternIds: Map<String, Long>,
        courseIds: Map<String, Long>,
    ): SessionPattern? {
        val at = "patterns[${wire.ref}]"
        val id = patternIds[wire.ref] ?: return null
        val courseId = reference(wire.courseRef, courseIds, "$at.courseRef") ?: return null
        val day = enum(wire.dayOfWeek, DayOfWeek.values(), "$at.dayOfWeek") ?: return null
        val kind = enum(wire.kind, SessionKind.values(), "$at.kind") ?: return null
        val from = date(wire.effectiveFrom, "$at.effectiveFrom") ?: return null
        val to = wire.effectiveTo?.let { date(it, "$at.effectiveTo") ?: return null }

        if (!slotFits(wire.startHour, wire.units, at, "units")) return null
        if (to != null && to.isBefore(from)) {
            problems += BackupProblem.InvalidValue(
                "$at.effectiveTo",
                "the slot is retired on $to, before it starts on $from.",
            )
            return null
        }
        return SessionPattern(
            id = id,
            courseId = courseId,
            dayOfWeek = day,
            startHour = wire.startHour,
            units = wire.units,
            kind = kind,
            room = wire.room?.ifBlank { null },
            effectiveFrom = from,
            effectiveTo = to,
        )
    }

    fun session(
        wire: WireSession,
        sessionIds: Map<String, Long>,
        patternIds: Map<String, Long>,
        courseIds: Map<String, Long>,
    ): ClassSession? {
        val at = "sessions[${wire.ref}]"
        val id = sessionIds[wire.ref] ?: return null
        val courseId = reference(wire.courseRef, courseIds, "$at.courseRef") ?: return null
        // Deliberately not checked against the patterns in the file: a marked class outlives
        // the pattern it came from, and [BackupCodec.build] has already given every such ref
        // an id of its own.
        val patternId = wire.patternRef?.let { patternIds[it] }
        val status = enum(wire.status, SessionStatus.values(), "$at.status") ?: return null
        val kind = enum(wire.kind, SessionKind.values(), "$at.kind") ?: return null
        val date = date(wire.date, "$at.date") ?: return null
        val approvedAt = instant(wire.approvedAt, "$at.approvedAt")
        val lastEditedAt = instant(wire.lastEditedAt, "$at.lastEditedAt")
        val reason = wire.cancellationReason
            ?.let { enum(it, CancellationReason.values(), "$at.cancellationReason") ?: return null }

        if (!slotFits(wire.startHour, wire.unitsPlanned, at, "unitsPlanned")) return null

        if (reason != null && status != SessionStatus.CANCELLED) {
            problems += BackupProblem.InvalidValue(
                "$at.cancellationReason",
                "a class that is $status cannot have a cancellation reason ($reason).",
            )
            return null
        }

        val stray = wire.attendedUnits.filterNot { it in 0 until wire.unitsPlanned }
        if (stray.isNotEmpty()) {
            // The one fault that would silently inflate a percentage, so it is refused rather
            // than clamped: an hour attended outside the hours planned is not a rounding
            // question, it is a file that no longer says what happened.
            problems += BackupProblem.InvalidValue(
                "$at.attendedUnits",
                "hour ${stray.joinToString()} is outside the ${wire.unitsPlanned} hour(s) this " +
                    "class was planned for.",
            )
            return null
        }

        val movedTo = wire.movedToRef?.let { reference(it, sessionIds, "$at.movedToRef") ?: return null }
        val movedFrom = wire.movedFromRef
            ?.let { reference(it, sessionIds, "$at.movedFromRef") ?: return null }

        return ClassSession(
            id = id,
            courseId = courseId,
            patternId = patternId,
            date = date,
            startHour = wire.startHour,
            unitsPlanned = wire.unitsPlanned,
            unitsMask = UnitMask.of(*wire.attendedUnits.toIntArray()),
            status = status,
            cancellationReason = reason,
            kind = kind,
            room = wire.room?.ifBlank { null },
            note = wire.note?.ifBlank { null },
            approvedAt = approvedAt,
            lastEditedAt = lastEditedAt,
            movedToSessionId = movedTo,
            movedFromSessionId = movedFrom,
        )
    }

    /**
     * Checks that each half of a reschedule agrees with the other.
     *
     * The link between a cancelled original and its ad-hoc replacement is the only thing that
     * stops one moved class being counted as two, so a one-sided link is reported rather than
     * repaired — the app cannot tell which half is the truthful one, and quietly picking would
     * change a percentage without saying so.
     *
     * Only a link that *is* present is checked. A cancelled-as-rescheduled row with no link at
     * all is a class whose replacement was later deleted on its own; the cancellation is still
     * true, so it stands.
     */
    fun checkReschedules(sessions: List<WireSession>) {
        val byRef = sessions.associateBy { it.ref }

        sessions.forEach { session ->
            session.movedToRef?.let { targetRef ->
                val target = byRef[targetRef] ?: return@let
                if (target.movedFromRef != session.ref) {
                    problems += BackupProblem.BrokenReschedule(
                        session.ref,
                        "it says it moved to \"$targetRef\", but that class does not point back " +
                            "at it (${target.movedFromRef ?: "nothing"}).",
                    )
                }
                if (session.status != SessionStatus.CANCELLED.name) {
                    problems += BackupProblem.BrokenReschedule(
                        session.ref,
                        "it was moved to \"$targetRef\" but is recorded as ${session.status} " +
                            "rather than cancelled, which would count the class twice.",
                    )
                }
            }

            session.movedFromRef?.let { originRef ->
                val origin = byRef[originRef] ?: return@let
                if (origin.movedToRef != session.ref) {
                    problems += BackupProblem.BrokenReschedule(
                        session.ref,
                        "it says it replaced \"$originRef\", but that class points at " +
                            "${origin.movedToRef ?: "nothing"} instead.",
                    )
                }
            }
        }
    }

    // ---- field readers ------------------------------------------------------

    private fun reference(ref: String, ids: Map<String, Long>, path: String): Long? {
        val id = ids[ref]
        if (id == null) problems += BackupProblem.DanglingReference(path, ref)
        return id
    }

    private fun date(raw: String, path: String): LocalDate? =
        runCatching { LocalDate.parse(raw) }.getOrElse {
            problems += BackupProblem.MalformedField(
                path,
                "\"$raw\" is not a date in YYYY-MM-DD form.",
            )
            null
        }

    private fun dates(raw: List<String>, path: String): List<LocalDate>? {
        val parsed = raw.mapIndexed { index, text -> date(text, "$path[$index]") }
        return if (parsed.any { it == null }) null else parsed.filterNotNull()
    }

    private fun instant(raw: String?, path: String): Instant? {
        if (raw == null) return null
        return runCatching { Instant.parse(raw) }.getOrElse {
            problems += BackupProblem.MalformedField(path, "\"$raw\" is not an ISO-8601 instant.")
            null
        }
    }

    private fun <T : Enum<T>> enum(raw: String, values: Array<T>, path: String): T? {
        val match = values.firstOrNull { it.name == raw }
        if (match == null) {
            problems += BackupProblem.MalformedField(
                path,
                "\"$raw\" is not one of ${values.joinToString { value -> value.name }}.",
            )
        }
        return match
    }

    private fun percent(basisPoints: Int, path: String): Percent? =
        if (basisPoints in 0..Percent.BP_FULL) {
            Percent.ofBasisPoints(basisPoints)
        } else {
            problems += BackupProblem.InvalidValue(
                path,
                "$basisPoints basis points is not a percentage between 0 and ${Percent.BP_FULL}.",
            )
            null
        }

    /** The teaching-day and mask-width bounds the domain model enforces in its constructors. */
    private fun slotFits(startHour: Int, units: Int, at: String, field: String): Boolean = when {
        units < 1 -> {
            problems += BackupProblem.InvalidValue(
                "$at.$field",
                "a class must last at least an hour.",
            )
            false
        }

        units > UnitMask.MAX_UNITS -> {
            problems += BackupProblem.InvalidValue(
                "$at.$field",
                "$units hours is longer than the ${UnitMask.MAX_UNITS} a class can be.",
            )
            false
        }

        !TimeGrid.fits(startHour, units) -> {
            problems += BackupProblem.InvalidValue(
                "$at.startHour",
                "a ${units}-hour class starting at $startHour does not fit the " +
                    "${TimeGrid.FIRST_START_HOUR}–${TimeGrid.LAST_END_HOUR} teaching day.",
            )
            false
        }

        else -> true
    }
}
