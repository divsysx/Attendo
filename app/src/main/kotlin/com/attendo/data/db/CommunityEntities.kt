package com.attendo.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityReportKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * The community feature's three Room tables — all additive, arriving in database
 * version 3, none referenced by anything attendance-side.
 *
 * What each is *for*:
 *  * [CommunityOutboxEntity] — reports composed offline, waiting for the network.
 *    The durable half of local-first: a row survives the process, so a submission the
 *    student saw confirmed is either delivered exactly once or surfaced as failed —
 *    never silently dropped. The idempotency key is generated at enqueue time and
 *    reused verbatim on every retry, which is what makes "exactly once" reachable at
 *    all over an unreliable link.
 *  * [CommunityObservationCacheEntity] — the last snapshot of live observations, so
 *    the Rooms tab has something to show before the first fetch completes and when the
 *    network is gone. Cache, not source of truth: every refresh replaces it wholesale.
 *  * [CommunityDismissedEntity] — rooms the student swiped away. Client-only: the
 *    server never learns what was dismissed.
 *
 * None of this enters the backup file (BackupSnapshot is untouched by design), and the
 * identity prefs file never enters Android's own backup (see backup_rules.xml) — so a
 * restore onto another device starts a fresh identity with an empty outbox, which is
 * exactly what one-student-one-reporter requires.
 */
@Entity(tableName = "community_outbox")
data class CommunityOutboxEntity(
    /** Generated at enqueue; reused verbatim on every retry (the exactly-once key). */
    @PrimaryKey val idempotencyKey: String,
    val kind: CommunityReportKind,
    val room: String?,
    val section: String?,
    val subject: String?,
    val classDate: LocalDate?,
    val startHour: Int?,
    /** The payload as the server expects it: the five blessed keys, JSON-encoded. */
    val payloadJson: String,
    val note: String?,
    /**
     * The claim fields (migration 0007's model): a FUTURE row carries its target slot
     * explicitly; every other row leaves them null and never sends them, which is what
     * keeps an old server usable for everything but claims.
     *
     * The column default is the wire name 'present' so the 3→4 AutoMigration can
     * backfill: every pre-claim row *was* a present observation (class rows' historical
     * type is a server-side read concern — locally they render the same either way).
     */
    @ColumnInfo(defaultValue = "present")
    val observationType: CommunityObservationType = CommunityObservationType.PRESENT,
    val eventDate: LocalDate? = null,
    val targetStartHour: Int? = null,
    /** When the student hit submit — local clock, display only. */
    val createdAt: Instant,
    /** 0 until the first send is attempted; terminal at [com.attendo.core.community.OutboxPolicy.MAX_ATTEMPTS]. */
    val failedAttempts: Int = 0,
    val lastFailureAt: Instant? = null,
    /** Set once the server accepted the report; kept briefly as the "sent" state, then pruned. */
    val sentAt: Instant? = null,
    /** The server's rejection code, when it rejected the submission outright. */
    val rejectionCode: String? = null,
    /**
     * The observation's server id, stamped at the moment the server accepted the row —
     * the handle undo/withdraw targets, and the join to whatever the owner-facing read
     * RPCs say about it later. Null until first acceptance (version 5).
     */
    @ColumnInfo(defaultValue = "null")
    val serverObservationId: String? = null,
    /**
     * The server-confirmed end state of a taken-back report: 'undo' (inside the fresh
     * window, no reputation change) or 'withdraw' (after it). Null means not taken back.
     * Set only from a server `ok` envelope — never optimistically, so a withdrawal the
     * server refused never reads as one that happened (version 5).
     */
    @ColumnInfo(defaultValue = "null")
    val withdrawalKind: String? = null,
    /**
     * Set when the server last answered this row with identity_frozen — this install's
     * identity is waiting out a pending replacement claim. A state, not a verdict on the
     * report: the row keeps its place in the pending set and its retry schedule, and sends
     * when the claim resolves either way (completed, or died and lifted the freeze).
     * Cleared by the next answer that is anything else — accepted, or refused for another
     * reason — while an unreachable attempt after it leaves it alone, because the last
     * server answer is still the truest thing known (version 8).
     */
    @ColumnInfo(defaultValue = "null")
    val frozenAt: Instant? = null,
)

@Entity(tableName = "community_observations_cache")
data class CommunityObservationCacheEntity(
    /** The server's observation id — cache rows are replaced by id. */
    @PrimaryKey val id: String,
    val kind: CommunityReportKind,
    val status: CommunityObservationStatus,
    val room: String?,
    val section: String?,
    val subject: String?,
    val classDate: LocalDate?,
    val startHour: Int?,
    val payloadJson: String,
    val note: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    /** The claim fields, so a cached claim still renders as a claim offline. */
    @ColumnInfo(defaultValue = "present")
    val observationType: CommunityObservationType = CommunityObservationType.PRESENT,
    val eventDate: LocalDate? = null,
    val targetStartHour: Int? = null,
    val targetEndHour: Int? = null,
    /** The snapshot's server time — the clock the cache's expiries are judged against. */
    val snapshotServerNow: Instant,
    /**
     * The verdict tallies the snapshot carried: how many students said "Accurate" and
     * "Not right". The active_observations view computes them live, and a verdict bumps
     * the observation's activity_at (migration 0011) so every phone refetches — but
     * until these columns existed the counts were fetched and then thrown away at the
     * cache boundary, and a card had nowhere to put them even after the refetch.
     * Defaults, so the 5→6 AutoMigration backfills pre-existing rows as "no verdicts
     * recorded yet", which is the honest reading of a row cached before the columns.
     */
    @ColumnInfo(defaultValue = "0")
    val verificationCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val disputeCount: Int = 0,
    /**
     * The caller's own verdict on this observation, when they have given one —
     * read from their own `verifications` row alongside the snapshot, cached
     * with it, and rendered as "You marked this accurate" in place of buttons
     * the server would refuse with `already_verified`. Null (the default) is
     * also what a 6→7 AutoMigration backfills for pre-existing rows: "no
     * verdict recorded yet", which is honest — the next refresh carries the
     * truth.
     */
    val myVerdict: Boolean? = null,
)

@Entity(tableName = "community_dismissed")
data class CommunityDismissedEntity(
    /** What was dismissed: a room name in the Rooms tab. */
    @PrimaryKey val key: String,
    val dismissedAt: Instant,
)

@Dao
interface CommunityDao {

    // ------------------------------------------------------------------ outbox

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(row: CommunityOutboxEntity)

    @Query("select * from community_outbox where sentAt is null and rejectionCode is null order by createdAt")
    suspend fun pendingOutbox(): List<CommunityOutboxEntity>

    /**
     * Whether a row a drain pass read earlier is still in the pending set. The pass
     * reads its rows once and then sends them one by one, but the table can change
     * under that list mid-pass — signing in to an account discards the outgoing
     * identity's rows in one transaction — and a row discarded that way must not leave
     * the phone under the session that lands next. Same pending predicate as
     * [pendingOutbox], so the two can never disagree about what "pending" means.
     */
    @Query("select count(*) from community_outbox where idempotencyKey = :key and sentAt is null and rejectionCode is null")
    suspend fun outboxPendingCount(key: String): Int

    @Query("select * from community_outbox order by createdAt desc")
    suspend fun allOutbox(): List<CommunityOutboxEntity>

    /**
     * The outbox as a live Flow — "My Reports" observes this rather than pulling, so a
     * submission's state changes (queued → sent/failed) reach the screen as they happen
     * instead of on the next manual reload. Room invalidation is the trigger; the state
     * machine's honest picture is the content.
     */
    @Query("select * from community_outbox order by createdAt desc")
    fun allOutboxFlow(): Flow<List<CommunityOutboxEntity>>

    @Query("update community_outbox set failedAttempts = :attempts, lastFailureAt = :at where idempotencyKey = :key")
    suspend fun markOutboxFailure(key: String, attempts: Int, at: Instant)

    /**
     * The identity_frozen twin of [markOutboxFailure]: same counters, but the row is also
     * stamped frozen — the state "My Reports" renders as the identity-move sentence instead
     * of "waiting", and never as the network-failure sentence the attempt cap would
     * otherwise age it into. The row stays in the pending set either way.
     */
    @Query(
        "update community_outbox set failedAttempts = :attempts, lastFailureAt = :at, frozenAt = :at " +
            "where idempotencyKey = :key"
    )
    suspend fun markOutboxFrozen(key: String, attempts: Int, at: Instant)

    @Query("update community_outbox set sentAt = :at, frozenAt = null where idempotencyKey = :key")
    suspend fun markOutboxSent(key: String, at: Instant)

    /** Stamps the server's observation id onto an accepted row, once it is known. */
    @Query(
        "update community_outbox set serverObservationId = :observationId " +
            "where idempotencyKey = :key"
    )
    suspend fun markOutboxServerId(key: String, observationId: String)

    @Query("update community_outbox set rejectionCode = :code, frozenAt = null where idempotencyKey = :key")
    suspend fun markOutboxRejected(key: String, code: String)

    /**
     * Records a server-confirmed undo or withdrawal on its outbox row. The kind is
     * exactly what the server's envelope confirmed — never a client guess — so "My
     * Reports" cannot claim a withdrawal happened that the server refused. The sent
     * stamp stays: the row *was* sent, and "My Reports" says both things — sent, then
     * taken back — which is one honest sentence, not two states pretending to be one.
     */
    @Query(
        "update community_outbox set withdrawalKind = :kind " +
            "where idempotencyKey = :key"
    )
    suspend fun markOutboxWithdrawn(key: String, kind: String)

    /**
     * A manual Retry: clears a row's terminal refusal and backoff counters, keeping the
     * row and its idempotency key. Some refusals clear with time — a dead session re-mints
     * on the next contact, a client/server shape mismatch clears with an app update — and
     * the Retry a declined card offers must be able to revive the row for exactly those.
     * The frozen stamp goes with them: a student who taps Retry after the identity move
     * resolved is asking for a fresh answer, not the cached one.
     */
    @Query(
        "update community_outbox set rejectionCode = null, failedAttempts = 0, lastFailureAt = null, " +
            "frozenAt = null where idempotencyKey = :key"
    )
    suspend fun resetOutboxForRetry(key: String)

    @Query("delete from community_outbox where idempotencyKey = :key")
    suspend fun deleteOutbox(key: String)

    /** Sent/rejected rows older than a day are history nobody needs. */
    @Query("delete from community_outbox where (sentAt is not null or rejectionCode is not null) and createdAt < :before")
    suspend fun pruneOutbox(before: Instant)

    @Query("delete from community_outbox")
    suspend fun clearOutbox()

    // ------------------------------------------------------------------- cache

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedObservations(rows: List<CommunityObservationCacheEntity>)

    @Query("delete from community_observations_cache")
    suspend fun clearCachedObservations()

    @Query("select * from community_observations_cache")
    suspend fun cachedObservations(): List<CommunityObservationCacheEntity>

    // --------------------------------------------------------------- dismissed

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(row: CommunityDismissedEntity)

    @Query("delete from community_dismissed where `key` = :key")
    suspend fun undismiss(key: String)

    @Query("select * from community_dismissed")
    suspend fun dismissed(): List<CommunityDismissedEntity>

    @Query("delete from community_dismissed")
    suspend fun clearDismissed()
}

// ---- mapping ---------------------------------------------------------------

/** Cache row back to the domain model; unreadable rows read as null, never throw. */
fun CommunityObservationCacheEntity.toModel(): com.attendo.core.community.CommunityObservation? =
    runCatching {
        com.attendo.core.community.CommunityObservation(
            id = id,
            kind = kind,
            status = status,
            room = room,
            section = section,
            subject = subject,
            classDate = classDate,
            startHour = startHour,
            payload = com.attendo.core.community.ReportPayload.fromJson(
                runCatching {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(payloadJson) as? kotlinx.serialization.json.JsonObject
                }.getOrNull()
            ),
            note = note,
            createdAt = createdAt,
            expiresAt = expiresAt,
            observationType = observationType,
            eventDate = eventDate,
            targetStartHour = targetStartHour,
            targetEndHour = targetEndHour,
            verificationCount = verificationCount,
            disputeCount = disputeCount,
            myVerdict = myVerdict,
        )
    }.getOrNull()
