package com.attendo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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

/**
 * The stored shapes, kept deliberately separate from the `:core` models.
 *
 * `:core` must stay Android-free, so its models cannot carry Room annotations. Rather
 * than push the domain types into the database's vocabulary — [Percent] and [UnitMask]
 * are inline value classes, which Room has no column for — each entity stores plain
 * columns and converts at the boundary. The mapping functions at the bottom of this
 * file are the only place that knows both languages.
 *
 * That boundary earns its keep: it is where a `Percent` becomes an integer number of
 * basis points and a `UnitMask` becomes an `Int` of bits, both losslessly, so nothing
 * about the exact-arithmetic guarantee in the engine depends on the database.
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val code: String,
    /** [Percent] as basis points — an integer, so the stored target is exact. */
    val targetBasisPoints: Int,
    val colorArgb: Int,
    val archived: Boolean,
    /**
     * The semester this course belongs to, or null on a row written before semesters existed.
     *
     * Deliberately *not* a foreign key, for the same reason [SessionEntity.patternId] is not:
     * a cascade from one semester row to a whole term's attendance history is precisely the
     * accident this app must be unable to have. Removing a semester's data is an explicit,
     * confirmed, multi-step operation in the repository — never a side effect of deleting a
     * row. A dangling id is harmless by comparison: the course simply belongs to no semester's
     * figures, which is the same state a pre-semester install starts in.
     *
     * And deliberately not indexed. An index here would be free to query and expensive to
     * migrate: adding one makes Room's generated migration drop and rebuild `courses` rather
     * than add a column, which puts a term's attendance through a table copy that only works
     * because foreign keys happen to be off inside a migration. A table holding one semester's
     * six subjects has nothing to gain from an index to justify that.
     */
    val semesterId: Long? = null,
    /**
     * Cloud sync columns (Phase 2). Null on every row until the install links an
     * account — accountless installs never touch them, and nothing reads them yet.
     *
     * `cloudId` is the row's stable identity in `attendance.courses` (a UUID string,
     * assigned when the row is first pushed). The local autoGenerate `id` is *not*
     * that identity: a restore renumbers every row, so a Long is only unique inside
     * one database file.
     */
    val cloudId: String? = null,
    /** When this row was last edited locally — the device clock, in epoch millis. */
    val clientUpdatedAt: Long? = null,
    /** Set (epoch millis) when the row is deleted in the cloud sense — a tombstone. */
    val deletedAt: Long? = null,
    /**
     * True when local edits exist that the cloud has not confirmed yet. Carries a SQL
     * default, not just a Kotlin one: an auto-migration adds a NOT NULL column with
     * `ALTER TABLE … ADD COLUMN`, which SQLite only allows when a DEFAULT is given.
     */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

/**
 * One semester.
 *
 * `(year, type)` is unique because a semester *is* its year and half — "the odd semester of
 * 2026" names one thing, and two imports describing it are two readings of the same semester
 * rather than two semesters. Enforcing that here means a restore carrying the same term twice
 * is rejected rather than quietly producing two of it for courses to be split between.
 */
@Entity(
    tableName = "semesters",
    indices = [Index(value = ["year", "type"], unique = true)],
)
data class SemesterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val year: Int,
    val type: SemesterType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** True once the semester has been put away. Its courses and classes are all still here. */
    val archived: Boolean,
    /** Cloud sync columns (Phase 2) — see [CourseEntity.cloudId] for the shape. */
    val cloudId: String? = null,
    val clientUpdatedAt: Long? = null,
    val deletedAt: Long? = null,
    /** True when local edits exist that the cloud has not confirmed yet (SQL default 0 — [CourseEntity.dirty]). */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

/**
 * A recurring weekly slot.
 *
 * Deleting a course takes its patterns with it ([ForeignKey.CASCADE]); patterns have no
 * meaning without one. Sessions are handled the same way — see [SessionEntity].
 */
@Entity(
    tableName = "patterns",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("courseId")],
)
data class PatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val dayOfWeek: DayOfWeek,
    val startHour: Int,
    val units: Int,
    val kind: SessionKind,
    val room: String?,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    /** Cloud sync columns (Phase 2) — see [CourseEntity.cloudId] for the shape. */
    val cloudId: String? = null,
    val clientUpdatedAt: Long? = null,
    val deletedAt: Long? = null,
    /** True when local edits exist that the cloud has not confirmed yet (SQL default 0 — [CourseEntity.dirty]). */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

/**
 * One dated class occurrence.
 *
 * The `(patternId, date)` index is `unique`, which is the database half of the
 * generator's idempotency rule: even if generation is triggered twice concurrently —
 * app open racing a date change — the second insert is rejected rather than producing a
 * duplicate class. SQLite treats NULLs as distinct in a unique index, so the ad-hoc
 * rows (`patternId IS NULL`) are unaffected, which is exactly right: two extra classes
 * on the same day are legitimate.
 *
 * [patternId] is a plain column rather than a foreign key on purpose. A retired pattern
 * must stay deletable without erasing the history it produced, and more importantly a
 * session's identity does not depend on its pattern still existing.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("courseId"),
        Index("date"),
        Index(value = ["patternId", "date"], unique = true),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val patternId: Long?,
    val date: LocalDate,
    val startHour: Int,
    val unitsPlanned: Int,
    /** [UnitMask.bits] — which hours were attended. */
    val unitsMaskBits: Int,
    val status: SessionStatus,
    val cancellationReason: CancellationReason?,
    val kind: SessionKind,
    val room: String?,
    val note: String?,
    val approvedAt: Instant?,
    val lastEditedAt: Instant?,
    val movedToSessionId: Long?,
    val movedFromSessionId: Long?,
    /** Cloud sync columns (Phase 2) — see [CourseEntity.cloudId] for the shape. */
    val cloudId: String? = null,
    val clientUpdatedAt: Long? = null,
    val deletedAt: Long? = null,
    /** True when local edits exist that the cloud has not confirmed yet (SQL default 0 — [CourseEntity.dirty]). */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
)

// ---- mapping ---------------------------------------------------------------

fun CourseEntity.toModel(): Course = Course(
    id = id,
    name = name,
    code = code,
    targetPercent = Percent.ofBasisPoints(targetBasisPoints),
    colorArgb = colorArgb,
    archived = archived,
    semesterId = semesterId,
)

fun Course.toEntity(): CourseEntity = CourseEntity(
    id = id,
    name = name,
    code = code,
    targetBasisPoints = targetPercent.basisPoints,
    colorArgb = colorArgb,
    archived = archived,
    semesterId = semesterId,
)

fun SemesterEntity.toModel(): Semester = Semester(
    id = id,
    year = year,
    type = type,
    startDate = startDate,
    endDate = endDate,
    archived = archived,
)

fun Semester.toEntity(): SemesterEntity = SemesterEntity(
    id = id,
    year = year,
    type = type,
    startDate = startDate,
    endDate = endDate,
    archived = archived,
)

fun PatternEntity.toModel(): SessionPattern = SessionPattern(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startHour = startHour,
    units = units,
    kind = kind,
    room = room,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo,
)

fun SessionPattern.toEntity(): PatternEntity = PatternEntity(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startHour = startHour,
    units = units,
    kind = kind,
    room = room,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo,
)

fun SessionEntity.toModel(): ClassSession = ClassSession(
    id = id,
    courseId = courseId,
    patternId = patternId,
    date = date,
    startHour = startHour,
    unitsPlanned = unitsPlanned,
    unitsMask = UnitMask(unitsMaskBits),
    status = status,
    cancellationReason = cancellationReason,
    kind = kind,
    room = room,
    note = note,
    approvedAt = approvedAt,
    lastEditedAt = lastEditedAt,
    movedToSessionId = movedToSessionId,
    movedFromSessionId = movedFromSessionId,
)

fun ClassSession.toEntity(): SessionEntity = SessionEntity(
    id = id,
    courseId = courseId,
    patternId = patternId,
    date = date,
    startHour = startHour,
    unitsPlanned = unitsPlanned,
    // Clamped on the way out as well as in the model. The mask is the one field where a
    // stale bit would inflate a percentage, so it is narrowed at every boundary.
    unitsMaskBits = unitsMask.clampedTo(unitsPlanned).bits,
    status = status,
    cancellationReason = cancellationReason,
    kind = kind,
    room = room,
    note = note,
    approvedAt = approvedAt,
    lastEditedAt = lastEditedAt,
    movedToSessionId = movedToSessionId,
    movedFromSessionId = movedFromSessionId,
)
