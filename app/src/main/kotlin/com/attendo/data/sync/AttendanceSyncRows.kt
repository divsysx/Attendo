package com.attendo.data.sync

import com.attendo.core.model.AttendanceBasis
import com.attendo.core.model.AttendanceStart
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionStatus
import com.attendo.core.model.UnitMask
import com.attendo.core.sync.SyncTable
import com.attendo.data.AppSettings
import com.attendo.data.db.CourseEntity
import com.attendo.data.db.PatternEntity
import com.attendo.data.db.SemesterEntity
import com.attendo.data.db.SessionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The shape of a row in `attendance.*` — one data class per table, and the conversion
 * between it and the local entity.
 *
 * These are the columns migration 0020 declares, spelled exactly. PostgREST is reached
 * with `PropertyConversionMethod.NONE` (see [com.attendo.data.community.CommunityClient]),
 * so every wire name is an explicit `@SerialName` here rather than something derived from
 * a Kotlin property name — which means a column renamed in a later migration fails to
 * decode loudly instead of mapping silently onto the wrong field.
 *
 * ### What is not sent
 *
 * `revision` and `updated_at` are on the read shape and stripped from the write shape by
 * [wireRow]. Migration 0020 is explicit that they are server-controlled — "values in an
 * incoming payload are ignored/overwritten" — and the insert and update triggers do
 * overwrite them unconditionally, so sending them would not corrupt anything. Not sending
 * them is the clearer statement of the same fact, and it means a client can never look
 * like it is trying to choose its own cursor position.
 *
 * ### Timestamps
 *
 * Wire timestamps are ISO-8601 strings and are parsed as such; the local columns are
 * epoch millis ([Instant.toEpochMilli]). Decimals are carried through unchanged, because
 * `client_updated_at` decides which device wins — truncating it locally would make two
 * edits in the same second tie, and a tie loses.
 *
 * ### Nulls
 *
 * Writes are encoded with explicit nulls. That is load-bearing: a local `room` cleared to
 * null must clear the cloud's, and an upsert that simply omitted the key would leave the
 * old value in place. PostgREST sets exactly the columns present in the body.
 */
internal val wireJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** The two columns no client may choose. */
private val SERVER_COLUMNS = setOf("revision", "updated_at")

private fun instantOrNull(millis: Long?): String? = millis?.let { Instant.ofEpochMilli(it).toString() }

internal fun millisOrNull(text: String?): Long? {
    if (text == null) return null
    // `Instant.parse` handles both the `Z` and the explicit offset PostgREST produces; the
    // offset formatter is the fallback for a shape it declines, so a timestamp this client
    // simply does not recognise is never mistaken for a stale one.
    return runCatching { Instant.parse(text).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .getOrNull()
}

private fun dateOrNull(date: LocalDate?): String? = date?.toString()

internal fun localDateOrNull(text: String?): LocalDate? =
    text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

// ---------------------------------------------------------------- semesters

@kotlinx.serialization.Serializable
data class SemesterRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val year: Int,
    val kind: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val archived: Boolean,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    val revision: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

fun SemesterEntity.toRow(userId: String): SemesterRow = SemesterRow(
    id = requireNotNull(cloudId) { "a semester with no cloud id cannot be pushed" },
    userId = userId,
    year = year,
    kind = type.name,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    archived = archived,
    clientUpdatedAt = Instant.ofEpochMilli(requireNotNull(clientUpdatedAt)).toString(),
    deletedAt = instantOrNull(deletedAt),
)

// ------------------------------------------------------------------ courses

@kotlinx.serialization.Serializable
data class CourseRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("semester_id") val semesterId: String? = null,
    val name: String,
    val code: String,
    @SerialName("target_bp") val targetBp: Int,
    @SerialName("color_argb") val colorArgb: Int,
    val archived: Boolean,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    val revision: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

fun CourseEntity.toRow(userId: String, semesterCloudId: String?): CourseRow = CourseRow(
    id = requireNotNull(cloudId) { "a course with no cloud id cannot be pushed" },
    userId = userId,
    semesterId = semesterCloudId,
    name = name,
    code = code,
    targetBp = targetBasisPoints,
    colorArgb = colorArgb,
    archived = archived,
    clientUpdatedAt = Instant.ofEpochMilli(requireNotNull(clientUpdatedAt)).toString(),
    deletedAt = instantOrNull(deletedAt),
)

// ----------------------------------------------------------------- patterns

@kotlinx.serialization.Serializable
data class PatternRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("course_id") val courseId: String,
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("start_hour") val startHour: Int,
    val units: Int,
    val kind: String,
    val room: String? = null,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("effective_to") val effectiveTo: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    val revision: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

fun PatternEntity.toRow(userId: String, courseCloudId: String): PatternRow = PatternRow(
    id = requireNotNull(cloudId) { "a pattern with no cloud id cannot be pushed" },
    userId = userId,
    courseId = courseCloudId,
    dayOfWeek = dayOfWeek.value,
    startHour = startHour,
    units = units,
    kind = kind.name,
    room = room,
    effectiveFrom = effectiveFrom.toString(),
    effectiveTo = dateOrNull(effectiveTo),
    clientUpdatedAt = Instant.ofEpochMilli(requireNotNull(clientUpdatedAt)).toString(),
    deletedAt = instantOrNull(deletedAt),
)

// ----------------------------------------------------------------- sessions

@kotlinx.serialization.Serializable
data class SessionRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("course_id") val courseId: String,
    @SerialName("pattern_id") val patternId: String? = null,
    val date: String,
    @SerialName("start_hour") val startHour: Int,
    @SerialName("units_planned") val unitsPlanned: Int,
    @SerialName("units_mask_bits") val unitsMaskBits: Int,
    val status: String,
    @SerialName("cancellation_reason") val cancellationReason: String? = null,
    val kind: String,
    val room: String? = null,
    val note: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("last_edited_at") val lastEditedAt: String? = null,
    @SerialName("moved_to_session_id") val movedToSessionId: String? = null,
    @SerialName("moved_from_session_id") val movedFromSessionId: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    val revision: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

fun SessionEntity.toRow(
    userId: String,
    courseCloudId: String,
    patternCloudId: String?,
    movedToCloudId: String?,
    movedFromCloudId: String?,
): SessionRow = SessionRow(
    id = requireNotNull(cloudId) { "a session with no cloud id cannot be pushed" },
    userId = userId,
    courseId = courseCloudId,
    patternId = patternCloudId,
    date = date.toString(),
    startHour = startHour,
    unitsPlanned = unitsPlanned,
    unitsMaskBits = unitsMaskBits,
    status = status.name,
    cancellationReason = cancellationReason?.name,
    kind = kind.name,
    room = room,
    note = note,
    approvedAt = approvedAt?.toString(),
    lastEditedAt = lastEditedAt?.toString(),
    movedToSessionId = movedToCloudId,
    movedFromSessionId = movedFromCloudId,
    clientUpdatedAt = Instant.ofEpochMilli(requireNotNull(clientUpdatedAt)).toString(),
    deletedAt = instantOrNull(deletedAt),
)

// ----------------------------------------------------------------- accounts

/**
 * `attendance.accounts` — the settings that *are* attendance state.
 *
 * Its local home is [com.attendo.data.SettingsStore], a preferences file, so this row is
 * the one table with no entity on the other side. `user_id` is the primary key: there is
 * one row per account, not one per device.
 */
@kotlinx.serialization.Serializable
data class AccountRow(
    @SerialName("user_id") val userId: String,
    @SerialName("term_start") val termStart: String,
    @SerialName("term_end") val termEnd: String,
    val holidays: List<String> = emptyList(),
    @SerialName("working_saturdays") val workingSaturdays: List<String> = emptyList(),
    @SerialName("overall_target_bp") val overallTargetBp: Int,
    @SerialName("default_target_bp") val defaultTargetBp: Int,
    val section: String? = null,
    val batch: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("attendance_basis") val attendanceBasis: String,
    @SerialName("joined_on") val joinedOn: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    val revision: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

fun AppSettings.toRow(userId: String, clientUpdatedAtMillis: Long): AccountRow = AccountRow(
    userId = userId,
    termStart = calendar.termStart.toString(),
    termEnd = calendar.termEnd.toString(),
    holidays = calendar.holidays.sorted().map { it.toString() },
    workingSaturdays = calendar.workingSaturdays.sorted().map { it.toString() },
    overallTargetBp = overallTarget.basisPoints,
    defaultTargetBp = courseTarget.basisPoints,
    section = section,
    batch = batch,
    displayName = displayName,
    attendanceBasis = attendanceStart.basis.name,
    joinedOn = attendanceStart.joinedOn?.toString(),
    clientUpdatedAt = Instant.ofEpochMilli(clientUpdatedAtMillis).toString(),
)

/**
 * The settings a pulled accounts row describes, or null when the row cannot be read.
 *
 * Unreadable rather than repaired: an unknown `attendance_basis`, a term whose end
 * precedes its start, a `PERSONAL` basis with no joining date — each of these is a value
 * the *server's* constraints already forbid, so meeting one means the row is not what
 * this client thinks an accounts row is. Returning null leaves the local settings exactly
 * as they are, which is the only safe reading of "the cloud said something impossible".
 */
fun AccountRow.toSettings(current: AppSettings): AppSettings? {
    val basis = AttendanceBasis.entries.firstOrNull { it.name == attendanceBasis } ?: return null
    val start = localDateOrNull(termStart) ?: return null
    val end = localDateOrNull(termEnd) ?: return null
    if (end.isBefore(start)) return null
    val joined = localDateOrNull(joinedOn)
    if (basis == AttendanceBasis.PERSONAL && joined == null) return null

    return current.copy(
        overallTarget = com.attendo.core.model.Percent.ofBasisPoints(
            overallTargetBp.coerceIn(0, 10_000),
        ),
        courseTarget = com.attendo.core.model.Percent.ofBasisPoints(
            defaultTargetBp.coerceIn(0, 10_000),
        ),
        calendar = AcademicCalendar(
            termStart = start,
            termEnd = end,
            holidays = holidays.mapNotNull { localDateOrNull(it) }.toSet(),
            workingSaturdays = workingSaturdays.mapNotNull { localDateOrNull(it) }.toSet(),
        ),
        section = section,
        batch = batch,
        displayName = displayName?.take(60),
        attendanceStart = AttendanceStart(basis = basis, joinedOn = joined),
    )
}

// ------------------------------------------------------------------- shared

/**
 * The local row a pulled `semesters` row describes, or null when it cannot be read.
 *
 * The direction `toEntity()` has always gone in this app, for the same reason: the wire is
 * untrusted and the entity is constrained, so the conversion is where a value the schema
 * forbids becomes "do not store this" rather than a wrong row. Each of these returns null
 * for anything it cannot read faithfully — an unknown enum, an unparseable date, a term
 * ending before it starts — and the caller lets the row go.
 *
 * `clientUpdatedAtMillis` is passed in rather than parsed here, because the caller has
 * already had to parse it to decide whether this row wins at all; parsing it twice would
 * be two chances to disagree.
 *
 * [existing] is the local row with the same cloud id, if there is one. It supplies the two
 * things the cloud does not know: the local primary key — so references to this row from
 * other local rows keep pointing at it — and nothing else. Every other column comes from
 * the incoming row, because it has already won the LWW comparison.
 */
internal fun SemesterRow.toEntity(existing: SemesterEntity?, clientUpdatedAtMillis: Long): SemesterEntity? {
    val type = semesterTypeOf(kind) ?: return null
    val start = localDateOrNull(startDate) ?: return null
    val end = localDateOrNull(endDate) ?: return null
    if (end.isBefore(start)) return null
    return SemesterEntity(
        id = existing?.id ?: 0L,
        year = year,
        type = type,
        startDate = start,
        endDate = end,
        archived = archived,
        cloudId = id,
        clientUpdatedAt = clientUpdatedAtMillis,
        deletedAt = null,
        dirty = false,
    )
}

internal fun CourseRow.toEntity(
    existing: CourseEntity?,
    clientUpdatedAtMillis: Long,
    semesterId: Long?,
): CourseEntity = CourseEntity(
    id = existing?.id ?: 0L,
    name = name.take(120),
    code = code.take(40),
    targetBasisPoints = targetBp.coerceIn(0, 10_000),
    colorArgb = colorArgb,
    archived = archived,
    // A semester this device does not have reads as no semester. The column is deliberately
    // not a foreign key, and a course belonging to no term is a state the app already
    // models — see `CourseEntity.semesterId`.
    semesterId = semesterId,
    cloudId = id,
    clientUpdatedAt = clientUpdatedAtMillis,
    deletedAt = null,
    dirty = false,
)

internal fun PatternRow.toEntity(
    existing: PatternEntity?,
    clientUpdatedAtMillis: Long,
    courseId: Long,
): PatternEntity? {
    val day = dayOfWeekOf(dayOfWeek) ?: return null
    val kind = sessionKindOf(kind) ?: return null
    val from = localDateOrNull(effectiveFrom) ?: return null
    return PatternEntity(
        id = existing?.id ?: 0L,
        courseId = courseId,
        dayOfWeek = day,
        startHour = startHour.coerceIn(9, 17),
        units = units.coerceIn(1, 9),
        kind = kind,
        room = room,
        effectiveFrom = from,
        effectiveTo = localDateOrNull(effectiveTo),
        cloudId = id,
        clientUpdatedAt = clientUpdatedAtMillis,
        deletedAt = null,
        dirty = false,
    )
}

internal fun SessionRow.toEntity(
    existing: SessionEntity?,
    clientUpdatedAtMillis: Long,
    courseId: Long,
    patternId: Long?,
    movedToSessionId: Long?,
    movedFromSessionId: Long?,
): SessionEntity? {
    val on = localDateOrNull(date) ?: return null
    val sessionStatus = sessionStatusOf(status) ?: return null
    val sessionKind = sessionKindOf(kind) ?: return null
    val planned = unitsPlanned.coerceIn(1, 9)
    return SessionEntity(
        id = existing?.id ?: 0L,
        courseId = courseId,
        // A pattern this device does not have reads as no pattern. The column is deliberately
        // not a foreign key, and a class that outlives the slot that produced it is an
        // ordinary state — see `SessionEntity.patternId`.
        patternId = patternId,
        date = on,
        startHour = startHour.coerceIn(9, 17),
        unitsPlanned = planned,
        // Narrowed at this boundary exactly as `toEntity()` narrows it: the mask is the one
        // field where a stale bit would inflate a percentage, so it is clipped to the units
        // the row actually plans.
        unitsMaskBits = UnitMask(unitsMaskBits.coerceAtLeast(0)).clampedTo(planned).bits,
        status = sessionStatus,
        cancellationReason = cancellationReasonOf(cancellationReason),
        kind = sessionKind,
        room = room,
        note = note?.take(500),
        approvedAt = millisOrNull(approvedAt)?.let { Instant.ofEpochMilli(it) },
        lastEditedAt = millisOrNull(lastEditedAt)?.let { Instant.ofEpochMilli(it) },
        movedToSessionId = movedToSessionId,
        movedFromSessionId = movedFromSessionId,
        cloudId = id,
        clientUpdatedAt = clientUpdatedAtMillis,
        deletedAt = null,
        dirty = false,
    )
}

/**
 * The row as the server should receive it: the same JSON, minus the two columns the
 * server owns. See [SERVER_COLUMNS].
 *
 * Built by encoding the typed row and dropping the keys, rather than by a second set of
 * write-only data classes — two shapes for one table is exactly how a column gets added
 * to one and forgotten in the other.
 */
internal inline fun <reified T> wireBodyOf(row: T): JsonObject =
    JsonObject(wireJson.encodeToJsonElement(row).jsonObject - SERVER_COLUMNS)

/**
 * The row's columns as a stable string, for the accounts settings checkpoint.
 *
 * Only ever compared against another value of itself, never parsed — so the exact
 * encoding does not matter, only that the same settings always produce the same string
 * and different settings never do.
 */
internal fun AccountRow.fingerprint(): String = buildString {
    append(termStart).append('|').append(termEnd)
    append('|').append(holidays.joinToString(","))
    append('|').append(workingSaturdays.joinToString(","))
    append('|').append(overallTargetBp).append('|').append(defaultTargetBp)
    append('|').append(section.orEmpty()).append('|').append(batch.orEmpty())
    append('|').append(displayName.orEmpty())
    append('|').append(attendanceBasis).append('|').append(joinedOn.orEmpty())
}

/** Every table's wire name, for the paths that iterate rather than name one. */
internal val SyncTable.entityName: String
    get() = when (this) {
        SyncTable.SEMESTERS -> "semester"
        SyncTable.COURSES -> "course"
        SyncTable.PATTERNS -> "pattern"
        SyncTable.SESSIONS -> "session"
    }

/**
 * The wire enum values this client is willing to store.
 *
 * A row carrying anything else is a row written by a version of the app that knows something
 * this one does not, and guessing at it would write a wrong class into a percentage. Every
 * one of these returns null rather than a default, and every caller treats null as "do not
 * apply this row" — `AcademicCalendar`'s own lesson, kept here so the sync path cannot become
 * the hole in it.
 */
internal fun sessionKindOf(name: String): SessionKind? =
    SessionKind.entries.firstOrNull { it.name == name }

internal fun sessionStatusOf(name: String): SessionStatus? =
    SessionStatus.entries.firstOrNull { it.name == name }

internal fun cancellationReasonOf(name: String?): CancellationReason? =
    name?.let { wire -> CancellationReason.entries.firstOrNull { it.name == wire } }

internal fun semesterTypeOf(name: String): SemesterType? =
    SemesterType.entries.firstOrNull { it.name == name }

internal fun dayOfWeekOf(value: Int): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.value == value }
