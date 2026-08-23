package com.attendo.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's only database.
 *
 * Four tables: courses, the recurring patterns that belong to them, the dated sessions those
 * patterns produce, and the semesters courses are grouped into. There is deliberately no fifth
 * table for exceptions — a cancelled or shortened class is a state on the session row itself,
 * which keeps every attendance query one table wide (see [SessionEntity]).
 *
 * The room timetable is *not* in here. It ships as a read-only CSV asset, is identical
 * for every user, and is replaced wholesale each semester; copying it into SQLite would
 * add a migration for data that has no user edits in it.
 *
 * `exportSchema` is on and the JSON lands in `app/schemas/` (configured via KSP in the
 * build file), so the next schema change has something to diff a migration against.
 *
 * ### Version 2: semesters
 *
 * Two changes, both additive: a `semesters` table, and a nullable `semesterId` on `courses`.
 * Nothing existing is altered or dropped, so this is an [AutoMigration] — there is no
 * hand-written SQL to get wrong, and Room verifies the generated migration against the
 * checked-in `1.json` at compile time. Every course on an upgraded install therefore starts
 * with `semesterId = NULL`, which is a real state the model accounts for and not a defect: the
 * first semester the student confirms adopts them, because a migration cannot know their term
 * without guessing at attendance history. See [CourseDao.adoptOrphans].
 */
@Database(
    entities = [
        CourseEntity::class,
        PatternEntity::class,
        SessionEntity::class,
        SemesterEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AttendoDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao

    abstract fun patternDao(): PatternDao

    abstract fun sessionDao(): SessionDao

    abstract fun semesterDao(): SemesterDao

    companion object {
        const val NAME: String = "attendo.db"

        fun build(context: Context): AttendoDatabase =
            Room.databaseBuilder(context, AttendoDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
