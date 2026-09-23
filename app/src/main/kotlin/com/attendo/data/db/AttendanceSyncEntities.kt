package com.attendo.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant

/**
 * The three local tables Attendance Sync needs and the attendance tables cannot provide.
 *
 * Everything the sync engine moves is already a column on `courses`, `semesters`,
 * `patterns` and `sessions` — `cloudId`, `clientUpdatedAt`, `deletedAt`, `dirty`. What
 * cannot live there is the state that belongs to the *account* rather than to any row:
 * how far this device has read, what it has deleted and not yet said so, and which account
 * the rows on this phone are whose.
 *
 * None of them enters the backup file: a restore is a new install of the local data and
 * a fresh conversation with the cloud, so a cursor or a tombstone carried across would be
 * a claim about a history the restored database does not have. All three are emptied by
 * `AppReset` through Room's own `clearAllTables()`, with everything else — and for
 * [AttendanceSyncOwnerEntity] that is exactly right too: after a data clear there is no
 * local attendance left for an account to own.
 */

/**
 * One row per linked account: where this device has read up to, and what it last told the
 * cloud about the settings that have no rows of their own.
 *
 * Keyed by the Supabase user id rather than being a single row, so that the cursor cannot
 * follow a student from one account to another. Signing in to a second account and
 * reading from the first account's cursor would skip everything the second account holds
 * below that number — silently, and permanently.
 *
 * The `accounts` half exists because `attendance.accounts` has no Room counterpart.
 * The settings it mirrors live in [com.attendo.data.SettingsStore], a preferences file
 * with no timestamp on it, so the two facts a sync needs about them — when this device
 * last agreed with the cloud about them, and what it agreed — are kept here instead of
 * being bolted onto a store that has no business knowing sync exists.
 */
@Entity(tableName = "attendance_sync_state")
data class AttendanceSyncStateEntity(
    @PrimaryKey val userId: String,
    /**
     * The highest `attendance.<table>.revision` this device may start its next read after.
     *
     * One cursor for all four tables, which is what the shared `attendance.change_seq`
     * sequence exists to make exact.
     */
    val cursor: Long = 0L,
    val lastSyncAt: Instant? = null,
    /**
     * Whether this device has already uploaded the attendance it had before it ever synced.
     *
     * An install that links an account after a term of use has thousands of rows with no
     * cloud id and `dirty = false` — they were written by an app that had no cloud to be
     * dirty against. Pushing only dirty rows would leave every one of them out of the
     * account's backup, silently and permanently. So the first push pass for an account
     * takes *every* row; later passes take only the dirty ones.
     *
     * Set only after a push the server accepted, so an install that links on a train
     * simply tries again at the next wake-up.
     */
    val initialPushDone: Boolean = false,
    /** The `client_updated_at` this device last pushed or last accepted for the settings row. */
    val accountsClientUpdatedAt: Long? = null,
    /**
     * The settings as they stood at [accountsClientUpdatedAt], flattened.
     *
     * A fingerprint rather than a timestamp, because nothing on this phone notices a
     * setting changing — the preferences file is written by a dozen setters with no hook
     * between them. Comparing what is on the device now against what was last agreed is
     * how a change is found without threading sync through every screen that can make one.
     */
    val accountsFingerprint: String? = null,
)

/**
 * A row this device deleted, recorded before the local row was actually removed.
 *
 * Deletion in the cloud is a tombstone — migration 0020 grants no role a DELETE — but
 * locally it is a real `DELETE FROM`, because every query in the app, every screen and the
 * whole attendance engine are written against "a row that exists is a row that happened".
 * Making local deletes soft would mean adding `deletedAt IS NULL` to something like twenty
 * queries, each omission a class that silently counts towards a percentage it is not part
 * of. So the local row goes, and this table is what remembers that it did.
 *
 * There is deliberately no `DELETE` of these rows after the push succeeds: a tombstone is
 * also the answer to "was this cloud id deleted here?", which the pull path asks when a
 * session arrives whose course this device no longer has. The table grows by one row per
 * deletion the student makes, which is a few dozen rows a year — cheaper than a second
 * piece of state saying the same thing.
 */
@Entity(tableName = "attendance_sync_tombstones")
data class AttendanceSyncTombstoneEntity(
    /** `"[<userId>:]<table>:<cloudId>"` — one tombstone per row per account. */
    @PrimaryKey val key: String,
    val syncTable: String,
    val cloudId: String,
    /**
     * The row's *complete* columns, with `deleted_at` set and `client_updated_at` at the
     * moment of the delete.
     *
     * Complete rather than just the id, because a tombstone is written with the same
     * upsert as any other row: if the cloud has never seen this row — a push that failed,
     * a delete before the first sync — a payload of `{id, deleted_at}` would be an INSERT
     * missing every NOT NULL column and the server would refuse the whole batch. Carrying
     * the columns means a tombstone for a row the cloud never had simply creates it
     * already deleted, which is a truthful record and costs one row.
     */
    val payloadJson: String,
    val deletedAt: Instant,
    /**
     * When the cloud was told. Null means the tombstone is still owed; non-null means it
     * has been delivered and this row is only the local record now.
     */
    val pushedAt: Instant? = null,
    /**
     * The account this tombstone belongs to, or empty when deleted before any account
     * claimed ownership.
     */
    @ColumnInfo(defaultValue = "") val userId: String = "",
)

/** `(local id, cloud id)` for the rows that have one — the translation both directions need. */
data class CloudIdentity(
    val id: Long,
    val cloudId: String,
)

/**
 * The account this device's attendance belongs to.
 *
 * A single row, or none. Attendance is written before any account exists — a student can use
 * the app for a term and never sign in — so "whose is this?" has no answer in the rows
 * themselves, and the first account to sync them is what answers it. This table is that
 * answer written down.
 *
 * It exists because two situations are otherwise indistinguishable from the inside. An
 * install used for a term that links its *first* account must upload every local row; an
 * install one account has already synced that links a *second* must upload none of them, or
 * the first account's attendance lands in the second. Both look like "a uid this device has
 * never synced with local rows present", which is precisely the condition that makes an
 * initial push happen. See [com.attendo.core.sync.AttendanceSyncPolicy.ownershipVerdict] for
 * what is decided from it, including why a mismatch is refused rather than resolved.
 *
 * Keyed by a fixed [id] rather than by the account, which is the difference between it and
 * [AttendanceSyncStateEntity]: the cursor is one per account, because a device may legitimately
 * hold state for several. Ownership is one per *device* — the local attendance is a single
 * body of rows and can only be one account's — so the table holds one row, and the primary
 * key is what says so.
 *
 * The row also carries the two things about the claim that are not its uid: whether the
 * account it names has been established gone ([terminatedAt]), and whether unsigned edits
 * are still owed to it ([loggedOutEditsForUserId]). Both are columns here rather than tables
 * of their own for the same reason — they are facts *about the claim*, and a claim that did
 * not exist would have neither.
 */
@Entity(tableName = "attendance_sync_owner")
data class AttendanceSyncOwnerEntity(
    /** The only row this table ever holds. See [SINGLETON_ID]. */
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** The uid the local attendance belongs to. */
    val userId: String,
    /**
     * When the claim was made, for diagnosis rather than for any decision.
     *
     * A student who is refused a sync has one question — "when did this phone decide my
     * attendance was someone else's?" — and the answer is otherwise nowhere on the device.
     */
    val claimedAt: Instant,
    /**
     * When this claim's account was established **gone** by the server, or null while it
     * names an account that still exists.
     *
     * The device-side half of
     * [com.attendo.core.auth.AuthLifecycleState.SERVER_ACCOUNT_GONE]: written when
     * `POST /auth/v1/user` answers `user_not_found` for this uid, and by nothing else —
     * never by a network failure, a 5xx, a rate limit, an expired token, or a sign-out, all
     * of which keep the account alive as far as this column is concerned.
     *
     * It exists so that one question survives a process death: *is the account these rows
     * belong to still able to come back for them?* With it, the next account to sign in is a
     * new lifecycle — see
     * [com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE].
     * Without it, that account would be indistinguishable from a live second account trying
     * to use someone else's phone, and would be offered a "replace the other account's data?"
     * confirmation whose other party has been deleted.
     *
     * Not a second lifecycle state machine: one nullable column on the row that already
     * answers "whose attendance is this". A claim is at most one of live or terminated, and
     * there is no third state and no transition back — a uid the server has established is
     * gone cannot authenticate again, because Supabase does not recycle a user id.
     */
    @ColumnInfo(defaultValue = "NULL")
    val terminatedAt: Instant? = null,
    /**
     * The uid of the account whose logged-out edits this device may still have pending.
     *
     * Set only when the device has edits that were written while not linked (unsigned
     * writes or writes under a different account). This marker survives process death
     * and is cleared only after a successful push of the signed-out changes under the
     * current linked account. It is never cleared on Account Settings open, admin
     * deletion, or any other read-only path.
     */
    @ColumnInfo(defaultValue = "NULL")
    val loggedOutEditsForUserId: String? = null,
) {
    companion object {
        /**
         * The primary key of the single row.
         *
         * A constant rather than a random or auto-generated key: "at most one owner" is the
         * whole point of the table, and a fixed key makes it a constraint the database
         * enforces rather than an invariant the code has to remember.
         */
        const val SINGLETON_ID: Int = 0
    }
}

@Dao
interface AttendanceSyncDao {

    // ------------------------------------------------------------------- state

    @Query("SELECT * FROM attendance_sync_state WHERE userId = :userId")
    suspend fun state(userId: String): AttendanceSyncStateEntity?

    @Query("SELECT * FROM attendance_sync_state")
    suspend fun allStates(): List<AttendanceSyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(row: AttendanceSyncStateEntity)

    /**
     * Moves the cursor forward. Never written by anything but the pull path, and never
     * written backwards — [com.attendo.core.sync.AttendanceSyncPolicy.cursorAfter] is the
     * only thing that decides the value.
     */
    @Query("UPDATE attendance_sync_state SET cursor = :cursor, lastSyncAt = :at WHERE userId = :userId")
    suspend fun advanceCursor(userId: String, cursor: Long, at: Instant)

    @Query(
        "UPDATE attendance_sync_state SET lastSyncAt = :at " +
            "WHERE userId = :userId",
    )
    suspend fun stampSync(userId: String, at: Instant)

    @Query(
        "UPDATE attendance_sync_state SET accountsClientUpdatedAt = :clientUpdatedAt, " +
            "accountsFingerprint = :fingerprint WHERE userId = :userId",
    )
    suspend fun recordAccounts(userId: String, clientUpdatedAt: Long, fingerprint: String)

    @Query("UPDATE attendance_sync_state SET initialPushDone = 1 WHERE userId = :userId")
    suspend fun markInitialPushDone(userId: String)

    // ---------------------------------------------------------------- ownership

    /**
     * The account the local attendance belongs to, or null when none has claimed it.
     *
     * The `id` is [AttendanceSyncOwnerEntity.SINGLETON_ID], spelled out because a Room
     * annotation is read at compile time. At most one row can exist, so this is a plain read.
     */
    @Query("SELECT * FROM attendance_sync_owner WHERE id = 0")
    suspend fun owner(): AttendanceSyncOwnerEntity?

    /**
     * Records the owning account.
     *
     * `REPLACE` for the same reason the state table uses it: the row is a single fact, and a
     * second write is that fact being written again, not a second row. The store only ever
     * writes it when no owner is recorded, so a `REPLACE` here cannot overwrite a live claim
     * — ownership, once taken, is not transferable by this path.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOwner(row: AttendanceSyncOwnerEntity)

    /** Clears the owning account so subsequent accounts are not locked out or falsely matched. */
    @Query("DELETE FROM attendance_sync_owner")
    suspend fun clearOwner()

    /**
     * Marks [userId]'s claim as terminated — the account was established gone by the server.
     *
     * Three conditions, each load-bearing:
     *
     *  - `id = 0` — the one row, spelled out because a Room annotation is read at compile time.
     *  - `userId = :userId` — the claim must name the account that was actually established
     *    gone. If the row names someone else, this is a stale verdict about an account that no
     *    longer has a claim here, and it must not touch the live one.
     *  - `terminatedAt IS NULL` — write-once. A second announcement of the same deletion (the
     *    app foregrounds again before the session is cleared, or the process died between the
     *    two writes and the branch ran again) must not move a timestamp that already records
     *    when this became true.
     *
     * An `UPDATE` rather than a `REPLACE` of the row: it is the same claim, and rewriting it
     * would be a second writer of a row whose uid and claimed-at are not this method's to
     * choose. Returns the number of rows changed, so a caller can tell "marked now" from
     * "already marked or no such claim" — and, in tests, can tell a guarded no-op from a write.
     */
    @Query(
        "UPDATE attendance_sync_owner SET terminatedAt = :at " +
            "WHERE id = 0 AND userId = :userId AND terminatedAt IS NULL",
    )
    suspend fun terminateClaim(userId: String, at: Instant): Int

    /**
     * Records that unsigned edits belong to [userId], the current owner.
     *
     * A dedicated UPDATE rather than a REPLACE of the whole owner row: ownership is
     * a different fact from the marker, and rewriting the claim just to stamp a
     * pending-edits uid would be a second writer of a row that is otherwise only
     * written at claim time.
     */
    @Query("UPDATE attendance_sync_owner SET loggedOutEditsForUserId = :userId WHERE id = 0")
    suspend fun stampLoggedOutEditsForUserId(userId: String)

    /**
     * Clears the logged-out-edits marker from the owner row.
     */
    @Query("UPDATE attendance_sync_owner SET loggedOutEditsForUserId = NULL WHERE id = 0")
    suspend fun clearLoggedOutEditsMarker()

    // -------------------------------------------------------------- tombstones

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordTombstones(rows: List<AttendanceSyncTombstoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordTombstone(row: AttendanceSyncTombstoneEntity)

    /** Tombstones the cloud has not been told about yet, oldest first. */
    @Query("SELECT * FROM attendance_sync_tombstones WHERE pushedAt IS NULL AND (userId = :userId OR userId = '') ORDER BY deletedAt")
    suspend fun pendingTombstonesForUser(userId: String): List<AttendanceSyncTombstoneEntity>

    @Query("SELECT * FROM attendance_sync_tombstones WHERE pushedAt IS NULL ORDER BY deletedAt")
    suspend fun pendingTombstones(): List<AttendanceSyncTombstoneEntity>

    @Query("DELETE FROM attendance_sync_tombstones WHERE userId = :userId")
    suspend fun deleteTombstonesForUser(userId: String)

    @Query("UPDATE attendance_sync_tombstones SET pushedAt = :at WHERE `key` IN (:keys)")
    suspend fun markTombstonesPushed(keys: List<String>, at: Instant)

    /**
     * The cloud ids of rows this device has deleted, for the table named.
     *
     * Read by the pull path for exactly one question: a session has arrived whose course is
     * not on this phone — was that because the student deleted the course here? If so the
     * session is not applicable and must be let go; if not, it is a row to come back to.
     */
    @Query("SELECT cloudId FROM attendance_sync_tombstones WHERE syncTable = :table")
    suspend fun tombstonedIds(table: String): List<String>

    @Query("DELETE FROM attendance_sync_tombstones")
    suspend fun clearTombstones()
}
