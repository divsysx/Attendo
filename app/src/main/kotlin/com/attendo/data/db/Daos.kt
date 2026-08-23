package com.attendo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.attendo.core.model.SemesterType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses ORDER BY archived, code")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE archived = 0 ORDER BY code")
    fun observeActive(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE id = :id")
    fun observeById(id: Long): Flow<CourseEntity?>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun byId(id: Long): CourseEntity?

    @Query("SELECT * FROM courses")
    suspend fun all(): List<CourseEntity>

    @Insert
    suspend fun insert(course: CourseEntity): Long

    /**
     * Courses written with the ids they carry, for a restore.
     *
     * Unlike [insert], the rows handed here already have their ids decided — Room binds a
     * non-zero primary key as given — so the references between restored patterns and
     * sessions still point at the right course afterwards.
     */
    @Insert
    suspend fun insertAll(courses: List<CourseEntity>): List<Long>

    @Update
    suspend fun update(course: CourseEntity)

    @Delete
    suspend fun delete(course: CourseEntity)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Every course, for the restore that replaces the lot. See `BackupRepository`. */
    @Query("DELETE FROM courses")
    suspend fun deleteAll()

    // ---- semesters ---------------------------------------------------------

    /**
     * Courses on an install that predates semesters, taken into [semesterId].
     *
     * Run once, at the point the first semester is established, rather than inside a database
     * migration. A migration cannot know which semester those courses belong to — the dates it
     * would need are in the app's settings, not in the schema — and guessing there would be a
     * silent write to attendance history. Doing it in the open, when the semester the student
     * confirmed is available, keeps the guess out of the database layer entirely.
     */
    @Query("UPDATE courses SET semesterId = :semesterId WHERE semesterId IS NULL")
    suspend fun adoptOrphans(semesterId: Long): Int
}

@Dao
interface SemesterDao {

    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    /**
     * The semester in use, if there is one.
     *
     * "Current" is the newest unarchived semester rather than the one containing today: a term
     * that has just ended is still the one being looked at until a new timetable is imported,
     * and a student on a break with no live semester would otherwise see the app forget every
     * figure it had. Archived semesters are excluded because archiving is exactly the act of
     * saying "not this one any more".
     */
    @Query("SELECT * FROM semesters WHERE archived = 0 ORDER BY startDate DESC LIMIT 1")
    fun observeCurrent(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semesters WHERE archived = 0 ORDER BY startDate DESC LIMIT 1")
    suspend fun current(): SemesterEntity?

    @Query("SELECT * FROM semesters WHERE year = :year AND type = :type")
    suspend fun byTerm(year: Int, type: SemesterType): SemesterEntity?

    @Query("SELECT * FROM semesters")
    suspend fun all(): List<SemesterEntity>

    @Insert
    suspend fun insert(semester: SemesterEntity): Long

    /** Semesters written with the ids they carry, for a restore. */
    @Insert
    suspend fun insertAll(semesters: List<SemesterEntity>): List<Long>

    @Update
    suspend fun update(semester: SemesterEntity)

    @Query("UPDATE semesters SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    /** Every semester, for the restore that replaces the lot. */
    @Query("DELETE FROM semesters")
    suspend fun deleteAll()
}

@Dao
interface PatternDao {

    @Query("SELECT * FROM patterns ORDER BY dayOfWeek, startHour")
    fun observeAll(): Flow<List<PatternEntity>>

    @Query("SELECT * FROM patterns WHERE courseId = :courseId ORDER BY dayOfWeek, startHour")
    fun observeForCourse(courseId: Long): Flow<List<PatternEntity>>

    @Query("SELECT * FROM patterns")
    suspend fun all(): List<PatternEntity>

    @Query("SELECT * FROM patterns WHERE id = :id")
    suspend fun byId(id: Long): PatternEntity?

    @Insert
    suspend fun insert(pattern: PatternEntity): Long

    @Insert
    suspend fun insertAll(patterns: List<PatternEntity>): List<Long>

    @Update
    suspend fun update(pattern: PatternEntity)

    @Delete
    suspend fun delete(pattern: PatternEntity)

    @Query("DELETE FROM patterns WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Every timetable slot, for the restore that replaces the lot. */
    @Query("DELETE FROM patterns")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY date, startHour")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to ORDER BY date, startHour")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY startHour")
    fun observeOn(date: LocalDate): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE courseId = :courseId ORDER BY date, startHour")
    fun observeForCourse(courseId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to")
    suspend fun between(from: LocalDate, to: LocalDate): List<SessionEntity>

    /** Every class ever recorded. Only a backup wants this much at once. */
    @Query("SELECT * FROM sessions")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY startHour")
    suspend fun on(date: LocalDate): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE courseId = :courseId")
    suspend fun forCourse(courseId: Long): List<SessionEntity>

    /** The row a draft would have produced, if some other write got there first. */
    @Query("SELECT * FROM sessions WHERE patternId = :patternId AND date = :date")
    suspend fun byPatternAndDate(patternId: Long, date: LocalDate): SessionEntity?

    /**
     * Generated sessions, inserted in bulk.
     *
     * [OnConflictStrategy.IGNORE] plus the unique `(patternId, date)` index is what makes
     * a concurrent double-generation harmless: the loser of the race is dropped rather
     * than duplicating a class or overwriting one the student already marked.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenerated(sessions: List<SessionEntity>): List<Long>

    /**
     * One generated session, written only if its `(patternId, date)` slot is still empty.
     *
     * Returns -1 when it was already there. The review screen leans on this: a draft is
     * written out on the first tap that needs a row id, and a second tap arriving before
     * the database flow has re-emitted must not abort the transaction.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: SessionEntity): Long

    @Insert
    suspend fun insert(session: SessionEntity): Long

    /**
     * Classes written with the ids they carry, for a restore.
     *
     * Deliberately *not* [OnConflictStrategy.IGNORE] like [insertGenerated]: during a restore
     * a `(patternId, date)` collision means the backup contained two classes in one slot,
     * and silently dropping one would restore fewer classes than the preview promised. Let
     * it abort the transaction instead.
     */
    @Insert
    suspend fun insertAll(sessions: List<SessionEntity>): List<Long>

    @Update
    suspend fun update(session: SessionEntity)

    @Update
    suspend fun updateAll(sessions: List<SessionEntity>)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Drops future *unreviewed* rows for a pattern being retired or deleted.
     *
     * Only SCHEDULED rows go: a session the student has already marked or cancelled is
     * a record of something that happened and outlives the pattern that produced it.
     */
    @Query(
        """
        DELETE FROM sessions
        WHERE patternId = :patternId AND date > :after AND status = 'SCHEDULED'
        """,
    )
    suspend fun deleteUnreviewedAfter(patternId: Long, after: LocalDate)

    /**
     * Drops every unreviewed row for a pattern that is going away entirely.
     *
     * Reviewed rows stay: they are a record of classes that happened, and the history
     * screen must not lose them just because the timetable entry behind them was deleted.
     */
    @Query("DELETE FROM sessions WHERE patternId = :patternId AND status = 'SCHEDULED'")
    suspend fun deleteUnreviewedForPattern(patternId: Long)

    @Query("SELECT MIN(date) FROM sessions")
    suspend fun earliestDate(): LocalDate?

    @Query("SELECT MAX(date) FROM sessions")
    suspend fun latestDate(): LocalDate?

    /** Every class, for the restore that replaces the lot. */
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
