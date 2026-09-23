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
 * ### Version 3: community
 *
 * Three additive tables for the community feature — the offline-report outbox, the
 * observations snapshot cache, and the client-only dismissal list — with nothing
 * existing altered or dropped, so this too is an [AutoMigration] verified against
 * `2.json` at compile time. See [CommunityOutboxEntity] for what each is for. The
 * community tables share `attendo.db` with attendance on purpose: the outbox must
 * survive process death, and Room's WAL gives that for free. They never join across
 * the boundary — attendance queries do not mention the community tables and vice
 * versa — and the backup file's format is untouched (community state is not backed up).
 * ### Version 4: future claims
 *
 * Migration 0007's model, locally: the outbox and the observations cache each gain the
 * claim fields (observation type, event date, target slot start; the cache also keeps the
 * server-computed target end). All additive columns with defaults, so an [AutoMigration]
 * verified against `3.json` suffices — no hand-written SQL, no data transformation. Rows
 * written before the upgrade read as present observations, which is what they were.
 * ### Version 5: withdrawal and undo
 *
 * The outbox gains the server-side half of its own lifecycle: `serverObservationId` (the
 * row's id once the server accepted it, without which undo/withdraw has nothing to target)
 * and `withdrawalState` (null until the student acts; 'undo' or 'withdraw' once the server
 * confirmed, so "My Reports" can say which it was). Additive nullable columns only, so an
 * [AutoMigration] verified against `4.json` suffices — every pre-existing row reads as
 * "not withdrawn", which is what it is.
 * ### Version 6: verdict tallies
 *
 * The observations cache gains `verificationCount` and `disputeCount` — the "Accurate" /
 * "Not right" tallies the snapshot has carried all along (the active_observations view
 * computes them) but which were dropped at the cache boundary, so no card could show
 * them even after a refetch a verdict had just triggered. Additive columns with defaults
 * (0), so an [AutoMigration] verified against `5.json` suffices — a row cached before
 * the upgrade reads as "no verdicts recorded", which is what the cache can honestly say.
 * ### Version 8: the frozen outbox state
 *
 * The outbox gains `frozenAt`, stamped when the server answers a queued report with
 * `identity_frozen` — this install's identity is waiting out a pending replacement claim.
 * Until then those rows read as a state of their own ("My Reports" says the identity is
 * moving) instead of aging, attempt by attempt, into the network-failure sentence the
 * attempt cap used to render them as. Additive nullable column only, so an
 * [AutoMigration] verified against `7.json` suffices — a row written before the upgrade
 * reads as "never frozen", which is what it was.
 * ### Version 9: cloud sync columns
 *
 * The four attendance tables (courses, semesters, patterns, sessions) each gain the
 * Phase 2 sync columns: a nullable `cloudId` (the row's stable identity in the cloud —
 * the local autoGenerate Long is not one, since a restore renumbers every row), a
 * nullable `clientUpdatedAt` (device-clock edit time), a nullable `deletedAt` (tombstone),
 * and a `dirty` flag defaulting to false. All additive columns, nullable or defaulted,
 * so an [AutoMigration] verified against `8.json` suffices — every pre-existing row
 * reads as "never synced", which is what it is. Nothing reads or writes these columns
 * yet; they exist so the sync engine (Stage 2C onwards) can be built without another
 * schema change between it and the data.
 * ### Version 10: attendance sync state
 *
 * Two additive tables for the client half of Attendance Sync — the per-account pull
 * cursor and settings checkpoint ([AttendanceSyncStateEntity]), and the record of rows
 * deleted locally that the cloud has not been told about yet
 * ([AttendanceSyncTombstoneEntity]). Nothing existing is altered or dropped, so an
 * [AutoMigration] verified against `9.json` suffices. Both are empty on an upgraded
 * install, which is exactly right: a device that has never synced has read nothing and
 * owes the cloud no tombstones. Neither enters the backup file, and `AppReset` empties
 * both through `clearAllTables()` with everything else.
 * ### Version 11: attendance ownership
 *
 * One additive table, [AttendanceSyncOwnerEntity] — the single account this device's
 * attendance belongs to, recorded the first time an account syncs it. Nothing existing is
 * altered or dropped, so an [AutoMigration] verified against `10.json` suffices. Empty on
 * an upgraded install, which reads as "no account has claimed this phone's attendance yet":
 * the next account to sync claims it, exactly as an install that has never synced would.
 * Without the table the two are indistinguishable, and an install already synced by one
 * account would upload that account's whole history to the next one to sign in.
 * ### Version 12: tombstone account scoping
 *
 * [AttendanceSyncTombstoneEntity] gains an additive `userId` column with default value `""`
 * so pending tombstones are isolated per account and never uploaded to a different account.
 * An [AutoMigration] verified against `11.json` suffices.
 * ### Version 13: logged-out-edits marker
 *
 * [AttendanceSyncOwnerEntity] gains an additive nullable `loggedOutEditsForUserId`
 * column (default NULL): the uid whose unsigned writes this device still owes the
 * cloud. Signed-out attendance edits have no Linked uid of their own, so they
 * belong to the current owner if one exists; the marker is that fact written
 * down, and it is what stops ordinary signed-in dirty rows from being classified
 * as "edits made while signed out". Nothing existing is altered or dropped, so
 * an [AutoMigration] verified against `12.json` suffices. Empty on an upgraded
 * install, which reads as "no unsigned edits pending" — the everyday signed-in
 * case, and the case an install that has never been signed out of is in.
 * ### Version 14: terminated account lifecycle
 *
 * [AttendanceSyncOwnerEntity] gains an additive nullable `terminatedAt` column (default
 * NULL): when this device established, from the server, that the account named by the claim
 * has been **deleted**. An [AutoMigration] verified against `13.json` suffices — nothing
 * existing is altered or dropped, and every pre-existing claim reads as "live", which is
 * what it was. It is written by the deletion branch of the account lifecycle and by nothing
 * else: a sign-out, a network failure, a 5xx, a rate limit, an expired token and a
 * recoverable session refusal all leave it NULL. Its only reader is
 * [com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE],
 * which turns "a new uid signed in on a phone whose attendance is a deleted account's" from
 * a refusal into a new lifecycle instead of an inheritance.
 */
@Database(
    entities = [
        CourseEntity::class,
        PatternEntity::class,
        SessionEntity::class,
        SemesterEntity::class,
        CommunityOutboxEntity::class,
        CommunityObservationCacheEntity::class,
        CommunityDismissedEntity::class,
        AttendanceSyncStateEntity::class,
        AttendanceSyncTombstoneEntity::class,
        AttendanceSyncOwnerEntity::class,
    ],
    version = 14,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
    ],
)
@TypeConverters(Converters::class)
abstract class AttendoDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao

    abstract fun patternDao(): PatternDao

    abstract fun sessionDao(): SessionDao

    abstract fun semesterDao(): SemesterDao

    abstract fun communityDao(): CommunityDao

    abstract fun attendanceSyncDao(): AttendanceSyncDao

    companion object {
        const val NAME: String = "attendo.db"

        fun build(context: Context): AttendoDatabase =
            Room.databaseBuilder(context, AttendoDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
