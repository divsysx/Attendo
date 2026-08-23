package com.attendo.data.db

import androidx.room.TypeConverter
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.SemesterType
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Column conversions for the types `:core` is written in.
 *
 * Dates are stored as ISO-8601 text rather than epoch days for one practical reason:
 * `date BETWEEN '2026-08-01' AND '2026-08-31'` sorts and ranges correctly as a string
 * comparison, so the month queries need no arithmetic, and a `sqlite3` dump of the
 * table is readable by eye when something looks wrong.
 *
 * Enums are stored by `name`, not ordinal. Ordinals would silently reassign every
 * stored row the day someone inserts a new constant into the middle of an enum.
 */
class Converters {

    @TypeConverter
    fun fromLocalDate(value: LocalDate): String = value.toString()

    @TypeConverter
    fun toLocalDate(value: String): LocalDate = LocalDate.parse(value)

    @TypeConverter
    fun fromInstant(value: Instant): Long = value.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    /** 1 = Monday … 7 = Sunday, matching [DayOfWeek.getValue]. */
    @TypeConverter
    fun fromDayOfWeek(value: DayOfWeek): Int = value.value

    @TypeConverter
    fun toDayOfWeek(value: Int): DayOfWeek = DayOfWeek.of(value)

    @TypeConverter
    fun fromSessionKind(value: SessionKind): String = value.name

    @TypeConverter
    fun toSessionKind(value: String): SessionKind = SessionKind.valueOf(value)

    @TypeConverter
    fun fromSessionStatus(value: SessionStatus): String = value.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter
    fun fromCancellationReason(value: CancellationReason): String = value.name

    @TypeConverter
    fun toCancellationReason(value: String): CancellationReason = CancellationReason.valueOf(value)

    @TypeConverter
    fun fromSemesterType(value: SemesterType): String = value.name

    @TypeConverter
    fun toSemesterType(value: String): SemesterType = SemesterType.valueOf(value)
}
