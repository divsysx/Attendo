package com.attendo.data.sync

import androidx.room.withTransaction
import com.attendo.core.sync.AttendanceSyncPolicy
import com.attendo.core.sync.AttendanceSyncPolicy.LocalAttendanceOwner
import com.attendo.core.sync.AttendanceSyncPolicy.PullDecision
import com.attendo.core.sync.AttendanceSyncPolicy.SemesterPlacement
import com.attendo.core.sync.AttendanceSyncPolicy.SessionPlacement
import com.attendo.core.sync.SyncTable
import com.attendo.data.AppSettings
import com.attendo.data.db.AttendanceSyncOwnerEntity
import com.attendo.data.db.AttendanceSyncStateEntity
import com.attendo.data.db.AttendanceSyncTombstoneEntity
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.CourseEntity
import com.attendo.data.db.PatternEntity
import com.attendo.data.db.SemesterEntity
import com.attendo.data.db.SessionEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Everything Attendance Sync does to the database — the local half of the engine, and the
 * half that holds every judgement the sync makes about what a row means.
 *
 * The engine above this decides *when* to talk to the cloud. This decides what the local
 * state is before it is sent, and what a row that arrives is allowed to change. Keeping
 * the two apart is what lets the interesting behaviour — which write wins, what a deletion
 * becomes, what a pulled row may overwrite — be read here without a server in view.
 *
 * ### What is authoritative
 *
 * Locally: everything. This is a backup and a convergence point, not a lease. A student who
 * never syncs has a fully working app, and one whose sync is failing has lost nothing: every
 * rule below either writes what the cloud holds or writes nothing at all, and no rule
 * anywhere deletes attendance the cloud merely failed to mention. The only deletion this
 * file performs is one the cloud explicitly asked for, by tombstone, or one the student made
 * here and [recordDeleted] was told about first.
 *
 * ### Pull before push
 *
 * Always, and it is the engine's job to keep that order. A device that takes on what the
 * cloud holds before it uploads what it holds has already converged before it writes, so the
 * push cannot present a stale row as the current one — the server's `lww_touch` would refuse
 * that anyway, but arriving already correct is better than being corrected.
 */
class AttendanceSyncStore(
    private val database: AttendoDatabase,
    /**
     * The clock a tombstone raised by a *pull* is stamped with.
     *
     * Applying a page is the one place this class decides a deletion rather than being told
     * about one — reconciling two cloud identities that name a single local semester — and a
     * deletion has to be stamped when it is made. Injected rather than read inside so a test can
     * pin the instant, and so the whole store keeps the one clock the engine already has.
     */
    private val now: () -> Instant = Instant::now,
) {

    private val syncDao = database.attendanceSyncDao()
    private val semesterDao = database.semesterDao()
    private val courseDao = database.courseDao()
    private val patternDao = database.patternDao()
    private val sessionDao = database.sessionDao()

    // ------------------------------------------------------------------ state

    /**
     * This account's sync state, created at zero if the account has never synced.
     *
     * A cursor of 0 is below every revision the shared sequence has ever handed out, so a
     * fresh account reads the whole history. That is exactly what "a new phone signs in"
     * means, and it is also why the state is keyed by account: a cursor that followed the
     * student from one account to another would skip everything the second account holds
     * below that number, silently and permanently.
     */
    suspend fun ensureState(userId: String): AttendanceSyncStateEntity {
        syncDao.state(userId)?.let { return it }
        val fresh = AttendanceSyncStateEntity(userId = userId)
        syncDao.putState(fresh)
        return fresh
    }

    suspend fun stateOrNull(userId: String): AttendanceSyncStateEntity? = syncDao.state(userId)

    /**
     * Moves the cursor, after the rows it covers have been committed.
     *
     * Deliberately a separate write from the application it follows. Applying a page and
     * advancing past it in one transaction would mean a crash during the apply left the
     * cursor claiming rows that were never written — a gap with nothing to come back to. In
     * this order the failure mode is a page applied twice, which costs a request and nothing
     * else: [AttendanceSyncPolicy.remoteWins] rejects a row this device has already applied,
     * so a second application is not a second effect.
     */
    suspend fun advanceCursor(userId: String, cursor: Long, at: Instant) {
        val current = stateOrNull(userId)?.cursor ?: 0L
        syncDao.advanceCursor(userId, maxOf(cursor, current), at)
    }

    suspend fun stampSync(userId: String, at: Instant) = syncDao.stampSync(userId, at)

    /** The cloud ids of rows this device has deleted, which the pull path asks about. */
    suspend fun tombstonedIds(table: SyncTable): Set<String> =
        syncDao.tombstonedIds(table.wireName).toSet()

    // -------------------------------------------------------------- ownership

    /**
     * The account this device's attendance belongs to, or null when none has claimed it.
     *
     * Not derivable from [stateOrNull]: the state table holds one row per account this
     * device has *ever* synced, so it can say which accounts have been here but not which
     * one the rows are whose. This is a different fact, and the only one that separates
     * "this install is linking its first account" from "this install is linking a second".
     * See [AttendanceSyncPolicy.ownershipVerdict].
     *
     * Returns the whole claim rather than just the uid because the row carries one more fact
     * that changes what the answer means: whether the account it names has been established
     * *gone*. A live claim and a terminated one both hold the same rows and are not the same
     * situation — see [LocalAttendanceOwner].
     */
    suspend fun attendanceOwner(): LocalAttendanceOwner? = syncDao.owner()?.let {
        LocalAttendanceOwner(userId = it.userId, terminated = it.terminatedAt != null)
    }

    /**
     * Records [userId] as the account the local attendance belongs to.
     *
     * Called only when [attendanceOwner] answered null — the first account to ask for a sync
     * on an install whose attendance has never belonged to one. Writing it *before* the push
     * it authorises, rather than after that push succeeds, is the deliberate direction: an
     * upload that landed and then failed to record its owner would leave the next account
     * free to claim rows that are the first account's, which is the leak the record exists
     * to prevent. The cost of the other order is the opposite and much smaller — an install
     * bound to an account whose backup has not happened yet, which signing back in fixes.
     *
     * Never overwrites a live claim: no caller passes a uid it has not first found to be
     * either the current owner or no owner at all. A `REPLACE` here is also what clears a
     * terminated flag — a fresh claim is a fresh lifecycle, and the new row is built with
     * `terminatedAt` at its default of null. That is the only write in the app that can
     * un-terminate a claim, and it can only ever be reached with a newly emptied phone: see
     * [releaseTerminatedClaim].
     */
    suspend fun claimAttendance(userId: String, at: Instant): Unit =
        syncDao.putOwner(AttendanceSyncOwnerEntity(userId = userId, claimedAt = at))

    /**
     * Marks the current claim as naming an account the server has established is **gone**.
     *
     * The only writer of `terminatedAt`, and the persisted half of
     * [com.attendo.core.auth.AuthLifecycleState.SERVER_ACCOUNT_GONE]. It touches nothing else:
     * the rows stay where they are, still the gone account's, because the next account must
     * not inherit them (see [AttendanceSyncPolicy.ownershipVerdict]).
     *
     * Guarded and idempotent — the DAO's `UPDATE` requires the row to name [goneUserId] and to
     * not already be terminated, so a second call after a process death (the deletion is
     * re-established on the next validation) changes nothing and is not an error. Returns
     * whether this call was the one that marked it, which is the only observable difference
     * between the first and the hundredth.
     *
     * A claim that names some *other* account is left alone deliberately. A deletion is
     * evidence about one uid; if the row names a different one, this device has already moved
     * on — a switch that happened after the deletion was announced — and terminating on the
     * strength of a verdict about an account that no longer owns anything would be reading
     * evidence for the wrong claim.
     */
    suspend fun terminateAttendanceClaim(goneUserId: String, at: Instant): Boolean =
        syncDao.terminateClaim(goneUserId, at) > 0

    /**
     * Ends the terminated lifecycle: sets the gone account's body aside and lets [newUserId]
     * claim the phone as a genuinely new account.
     *
     * Called for [AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE] — a
     * different account has signed in on a phone whose attendance belongs to an account the
     * server has established is gone.
     *
     * ### Why the body is set aside before it is cleared
     *
     * It cannot stay: the claim is about to be spent, and every local row would be uploaded
     * as the new account's on its first push — a term of one student's attendance in another
     * student's backup. Setting the rows aside first is the difference between that and a
     * deletion: the gone account was removed by somebody else, with no confirmation from the
     * student and no chance to export first, so the copy is the only thing standing between
     * an administrative action and losing a term's marks. It is written before anything is
     * cleared, so a failure to write it leaves the phone exactly as it was and the next pass
     * tries again — the safe direction, and the reason [preserve] is called here rather than
     * after.
     *
     * The rows themselves then go through [switchAttendanceOwner], the same single
     * transaction a confirmed switch uses: clear the four attendance tables, clear the
     * tombstones, claim for the new account. Nothing new is invented for this path, which is
     * what keeps "the local attendance was emptied" true for exactly one implementation.
     *
     * ### What the new account gets
     *
     * A phone with no attendance on it and no claim but its own. That is
     * [AttendanceSyncPolicy.AccountTransition.FIRST_CLAIM] in everything but the name — and
     * it is reached here rather than by clearing the owner row, because clearing the row is
     * indistinguishable from "never claimed", which is the state whose initial push uploads
     * whatever happens to be local. This releases the claim only after there is nothing left
     * for it to protect.
     *
     * Not reachable for a claim that is merely someone else's: see
     * [AttendanceSyncPolicy.OwnershipVerdict.REFUSE], which is the live-mismatch case and
     * still asks.
     *
     * ### Where the ordering is enforced
     *
     * Not here. This is the name the terminated lifecycle reaches the transition by, and it
     * is a name rather than an implementation because the ordering that makes it safe belongs
     * to [switchAttendanceOwner] — the one method that clears local attendance, which reads
     * the claim itself and sets the body aside before it does. Keeping the guarantee at that
     * single boundary rather than in this wrapper is what stops a caller that arrives some
     * other way — an approval prompt built before the deletion, say — from clearing a
     * terminated claim's body with no copy taken.
     *
     * @param preserve the copy of the outgoing body, injected rather than written here so
     *   this class stays Room-only — see
     *   [com.attendo.data.sync.AttendanceSyncEngine.preserveRemovedAccountAttendance].
     */
    suspend fun releaseTerminatedClaim(
        newUserId: String,
        at: Instant,
        preserve: suspend () -> Unit,
    ) = switchAttendanceOwner(newUserId, at, preserve)

    /**
     * Replaces local attendance and ownership with [newUserId].
     *
     * Used when an explicit account switch is performed, and — through
     * [releaseTerminatedClaim] — when the claim's account has been established gone.
     * Parallels Community's account switch: the outgoing account's unpushed tombstones and
     * state are cleaned up, all local attendance tables are cleared so that the new account's
     * cloud attendance can be pulled cleanly, and the new account is recorded as the owner.
     *
     * ### The preservation invariant is enforced here, not at the calling screen
     *
     * This is the one method in the app that clears local attendance, and it decides for
     * itself whether the body must be set aside first, by reading the claim immediately
     * before the transaction:
     *
     *  * **A live claim** is an account switch the student approved. The rows being replaced
     *    belong to an account that still exists and was replaced on purpose, by the one person
     *    entitled to replace them. Nothing is copied.
     *  * **A terminated claim** means the account that owned the body was deleted by somebody
     *    else, with no confirmation and no chance to export first. [preserve] is called, and
     *    the copy is the only thing standing between that administrative action and a lost
     *    term.
     *
     * The read belongs here rather than at the call site because a call site's answer can be
     * stale, and the way it goes stale is the whole reason this guard exists: an approval
     * prompt is built from a claim read when the prompt was *shown*, the deletion can land
     * while it is on screen, and the press that follows would otherwise clear a terminated
     * account's body with no copy taken. Reading the claim at the boundary means no caller can
     * skip the copy by having read it earlier, whether it meant to or not.
     *
     * [preserve] is called *before* the transaction and outside it: a failure to write the
     * copy throws before anything has been cleared, which leaves the phone exactly as it was —
     * still the gone account's, still refused for [newUserId] — and the next pass arrives at
     * the same quarantine verdict and tries again. The reverse order would clear first and
     * lose the body, so the ordering is the data-safety property. See
     * [com.attendo.data.AttendanceQuarantineStore].
     *
     * The claim is read twice — once to decide whether to copy, and once inside the
     * transaction, before any write. The second read is not a repetition of the first: it
     * catches the flag arriving in the gap between them, in which case nothing has been
     * written yet and returning leaves the body intact for the pass to quarantine properly.
     * Without it that interleaving would clear a terminated claim with no copy, which is the
     * one outcome this method exists to make unreachable.
     */
    suspend fun switchAttendanceOwner(
        newUserId: String,
        at: Instant,
        preserve: suspend () -> Unit,
    ) {
        val terminatedBefore = attendanceOwner()?.terminated == true
        if (terminatedBefore) preserve()
        database.withTransaction {
            // Read before any write: if the claim became terminated after the read above,
            // this transition is the quarantine's to perform, not the caller's, and leaving
            // now writes nothing at all.
            if (!terminatedBefore && attendanceOwner()?.terminated == true) return@withTransaction
            // Clear local attendance rows so the new account's data does not mix with the old
            database.sessionDao().deleteAll()
            database.patternDao().deleteAll()
            database.courseDao().deleteAll()
            database.semesterDao().deleteAll()
            // Clear old tombstones
            syncDao.clearTombstones()
            // Claim for new account
            claimAttendance(newUserId, at)
        }
    }

    /**
     * Whether local attendance tables contain any unpushed edits (dirty rows) or unpushed tombstones.
     *
     * Used to detect whether a returning account modified attendance while signed out.
     */
    suspend fun hasLoggedOutEdits(userId: String): Boolean {
        if (sessionDao.dirty().isNotEmpty()) return true
        if (patternDao.dirty().isNotEmpty()) return true
        if (courseDao.dirty().isNotEmpty()) return true
        if (semesterDao.dirty().isNotEmpty()) return true
        if (syncDao.pendingTombstonesForUser(userId).isNotEmpty()) return true
        return false
    }

    /**
     * Discards local attendance and resets sync cursor for [userId] so the remote state can be cleanly restored.
     *
     * ### Why this refuses a terminated claim, where [switchAttendanceOwner] copies
     *
     * It is destructive in the same way — all four attendance tables go — so it carries a
     * guard for the same reason: its prompt is built from a claim read when the prompt was
     * shown, and a deletion landing while the student is still deciding must not turn "restore
     * my backup" into the loss of a removed account's term.
     *
     * The guard is not the same shape, though, and the difference is not a preference. There
     * is nothing here to restore: the account is gone, so the cloud copy the student asked for
     * does not exist. And clearing the rows anyway would not merely be pointless — it would
     * destroy the body a second time. The copy that must outlive it is written by the
     * quarantine path, which sets the body aside and clears it in the same breath as claiming
     * the phone for the next account. Clearing here first would leave that path to preserve an
     * already-empty database over the only good copy, so "restore" would end as the deletion
     * of the thing it was protecting.
     *
     * So the answer is neither a copy nor a clear: it is to write nothing and leave the body
     * where it is, for the lifecycle that owns it. Returns nothing, because a refusal and a
     * completed restore are the same answer to every caller — the pass that follows decides
     * what happens next from the claim, not from this.
     */
    suspend fun restoreRemote(userId: String) = database.withTransaction {
        // Read before any write. A terminated claim's body is the quarantine's to move, and
        // it moves it only alongside the claim that replaces it.
        if (attendanceOwner()?.terminated == true) return@withTransaction
        database.sessionDao().deleteAll()
        database.patternDao().deleteAll()
        database.courseDao().deleteAll()
        database.semesterDao().deleteAll()
        syncDao.deleteTombstonesForUser(userId)
        val state = ensureState(userId)
        syncDao.putState(state.copy(cursor = 0L, initialPushDone = true))
    }

    // ------------------------------------------------------------------- push

    /** One tombstone the cloud has not been told about, as a request body. */
    data class PendingTombstone(val table: SyncTable, val key: String, val body: JsonObject)

    /**
     * One row on its way out: the body to send, and what is needed to acknowledge it.
     *
     * The two travel together because the engine sends them in chunks, and a chunk that
     * lands has to be matched back to the rows it was built from. Carrying the row's local
     * id and the timestamp it was stamped with alongside its body means the acknowledgement
     * needs nothing looked up and cannot be confused between two chunks of one table.
     */
    data class Outgoing(val body: JsonObject, val id: Long, val clientUpdatedAt: Long)

    /** What this device and the cloud last agreed the settings row was. */
    data class AccountsAgreed(val clientUpdatedAt: Long, val fingerprint: String)

    /**
     * Everything this device owes the cloud.
     *
     * [bodies] is keyed by table and must be sent in [SyncTable] declaration order: the
     * cloud's composite foreign keys reject a pattern whose course has not arrived yet, so
     * the order is a correctness requirement rather than a preference. Tombstones are a flat
     * list because each carries its own table, and go after the rows.
     */
    data class PushBatch(
        val bodies: Map<SyncTable, List<Outgoing>>,
        val tombstones: List<PendingTombstone>,
        val accounts: JsonObject?,
        val accountsFingerprint: String?,
        val accountsClientUpdatedAt: Long?,
        /** True when this is the pass that uploads a pre-sync install's whole history. */
        val initial: Boolean,
    )

    /**
     * Builds the upload, giving a cloud identity to any row that does not have one yet.
     *
     * Identity is assigned here, at the first push, rather than when the row is written —
     * which is what `CourseEntity.cloudId`'s own documentation says ("assigned when the row
     * is first pushed") and what keeps a row nobody ever uploaded from claiming an identity
     * it never used.
     *
     * Two passes, because a row's request body names its parents. Pass one gives every row
     * across all four tables the id it needs; pass two can then resolve any reference,
     * including ones being resolved for the first time on a first push.
     *
     * All inside one transaction, so a crash mid-pass leaves either every identity or none.
     * Writing the ids back *before* the request is deliberate: if the push then fails, the
     * ids are still the rows' identities, and the retry is an upsert of the same rows rather
     * than a second, different set.
     *
     * This method assumes the caller has already established that [userId] is the account the
     * local attendance belongs to — see [attendanceOwner]. It cannot check that itself
     * without turning a question about the account into a question about the rows: the whole
     * reason `initial` exists is that "a uid this device has never synced" legitimately means
     * "upload everything" for an install's *first* account and must mean "upload nothing" for
     * its second. The two are the same condition, and only the caller knows which one it is
     * looking at.
     */
    suspend fun preparePush(
        userId: String,
        settings: AppSettings,
        at: Instant,
    ): PushBatch = database.withTransaction {
        val state = ensureState(userId)
        val initial = !state.initialPushDone
        val stamp = at.toEpochMilli()

        // -- pass one: identity, parents before children -----------------------
        val semesters = assignIdentity(
            rows = if (initial) semesterDao.all() else semesterDao.dirty(),
            cloudId = { it.cloudId },
            clientUpdatedAt = { it.clientUpdatedAt },
            stamp = stamp,
            copyWith = { row, id, editedAt -> row.copy(cloudId = id, clientUpdatedAt = editedAt) },
            save = { semesterDao.update(it) },
        )
        val courses = assignIdentity(
            rows = if (initial) courseDao.all() else courseDao.dirty(),
            cloudId = { it.cloudId },
            clientUpdatedAt = { it.clientUpdatedAt },
            stamp = stamp,
            copyWith = { row, id, editedAt -> row.copy(cloudId = id, clientUpdatedAt = editedAt) },
            save = { courseDao.update(it) },
        )
        val patterns = assignIdentity(
            rows = if (initial) patternDao.all() else patternDao.dirty(),
            cloudId = { it.cloudId },
            clientUpdatedAt = { it.clientUpdatedAt },
            stamp = stamp,
            copyWith = { row, id, editedAt -> row.copy(cloudId = id, clientUpdatedAt = editedAt) },
            save = { patternDao.update(it) },
        )

        // Sessions are the one table whose identity is *derived* where it can be. A class
        // the generator produced from a recurring slot gets the same cloud id on every
        // device, so two phones that generate the same lecture address one row instead of
        // racing to make two — migration 0020 requires this outright, because the local
        // `(patternId, date)` uniqueness that stops the duplicate here cannot travel: the
        // local pattern id is an autoincrement Long, unique to one database file.
        //
        // The pattern's own cloud id has to exist first, which pass one has just guaranteed.
        // Reading it back from the table rather than from the batch is what also covers a
        // session whose pattern was uploaded by an earlier pass and is not in this one.
        val patternCloudIds = patternDao.cloudIdentities().associate { it.id to it.cloudId }
        val staleSessions = if (initial) sessionDao.all() else sessionDao.dirty()
        val sessions = ArrayList<SessionEntity>(staleSessions.size)
        for (row in staleSessions) {
            val derived = row.cloudId
                ?: row.patternId?.let { patternCloudIds[it] }
                    ?.let { patternCloudId -> AttendanceSyncPolicy.cloudSessionId(patternCloudId, row.date) }
            // An ad-hoc class has nothing to derive from and gets a random id. It is one
            // device's extra, which is exactly why the cloud's unique index exempts a
            // session with no pattern from the one-class-per-slot rule.
            val cloudId = derived ?: AttendanceSyncPolicy.newCloudId()
            val editedAt = row.clientUpdatedAt ?: stamp
            if (row.cloudId == cloudId && row.clientUpdatedAt == editedAt) {
                sessions += row
            } else {
                sessions += row.copy(cloudId = cloudId, clientUpdatedAt = editedAt).also { sessionDao.update(it) }
            }
        }

        // -- pass two: the request bodies --------------------------------------
        val semesterIds = semesterDao.cloudIdentities().associate { it.id to it.cloudId }
        val courseIds = courseDao.cloudIdentities().associate { it.id to it.cloudId }
        val sessionIds = sessionDao.cloudIdentities().associate { it.id to it.cloudId }

        val bodies = SyncTable.entries.associateWith { table ->
            when (table) {
                SyncTable.SEMESTERS -> semesters.mapNotNull { row ->
                    val editedAt = row.clientUpdatedAt ?: return@mapNotNull null
                    if (row.cloudId == null) return@mapNotNull null
                    Outgoing(wireBodyOf(row.toRow(userId)), row.id, editedAt)
                }

                SyncTable.COURSES -> courses.mapNotNull { row ->
                    val editedAt = row.clientUpdatedAt ?: return@mapNotNull null
                    if (row.cloudId == null) return@mapNotNull null
                    Outgoing(
                        wireBodyOf(row.toRow(userId, semesterCloudId = row.semesterId?.let { semesterIds[it] })),
                        row.id,
                        editedAt,
                    )
                }

                SyncTable.PATTERNS -> patterns.mapNotNull { row ->
                    val editedAt = row.clientUpdatedAt ?: return@mapNotNull null
                    if (row.cloudId == null) return@mapNotNull null
                    // No course cloud id means the course was deleted between the read and
                    // here. Leaving the pattern out is the only truthful thing to do; it
                    // keeps its dirty flag and goes out on the pass that follows the delete.
                    val courseCloudId = courseIds[row.courseId] ?: return@mapNotNull null
                    Outgoing(wireBodyOf(row.toRow(userId, courseCloudId)), row.id, editedAt)
                }

                SyncTable.SESSIONS -> sessions.mapNotNull { row ->
                    val editedAt = row.clientUpdatedAt ?: return@mapNotNull null
                    if (row.cloudId == null) return@mapNotNull null
                    val courseCloudId = courseIds[row.courseId] ?: return@mapNotNull null
                    Outgoing(
                        wireBodyOf(
                            row.toRow(
                                userId = userId,
                                courseCloudId = courseCloudId,
                                patternCloudId = row.patternId?.let { patternCloudIds[it] },
                                movedToCloudId = row.movedToSessionId?.let { sessionIds[it] },
                                movedFromCloudId = row.movedFromSessionId?.let { sessionIds[it] },
                            ),
                        ),
                        row.id,
                        editedAt,
                    )
                }
            }
        }

        // -- what the cloud has not been told about deletions ------------------
        // Tombstones are isolated by account: only tombstones belonging to this user (or
        // unassigned tombstones from before ownership) are sent. Pending tombstones belonging
        // to a different account are never sent under this user.
        //
        // The query already scopes this in SQL. [AttendanceSyncPolicy.tombstonePushableBy] is
        // applied to the rows as well, deliberately: this is the one boundary where a mistake
        // is a cross-account *write*, so the rule is stated in a form the tests can pin rather
        // than only in a `WHERE` clause. The two cannot disagree — the filter never rejects a
        // row the query returned for any reason other than the rule itself.
        val tombstones = syncDao.pendingTombstonesForUser(userId)
            .filter { AttendanceSyncPolicy.tombstonePushableBy(it.userId, userId) }
            .mapNotNull { row ->
                val table = SyncTable.entries.firstOrNull { it.wireName == row.syncTable } ?: return@mapNotNull null
                val body = runCatching { wireJson.parseToJsonElement(row.payloadJson) as? JsonObject }.getOrNull()
                    ?: return@mapNotNull null
                // `user_id` is stamped here rather than when the tombstone was recorded: nothing
                // on the delete path knows the account, and it is a push-time fact in exactly the
                // way `revision` is. The row's own columns are already in the payload; see
                // [AttendanceSyncTombstoneEntity.payloadJson].
                PendingTombstone(table, row.key, JsonObject(body + ("user_id" to JsonPrimitive(userId))))
            }

        // -- the settings row --------------------------------------------------
        // Nothing on this phone notices a setting changing — the preferences file is written
        // by a dozen setters with no hook between them — so a change is found by comparing
        // what is on the device now against what was last agreed, rather than by being told.
        // See [AttendanceSyncStateEntity.accountsFingerprint].
        val accountsRow = settings.toRow(userId, stamp)
        val fingerprint = accountsRow.fingerprint()
        val accountsDue = state.accountsFingerprint != fingerprint

        PushBatch(
            bodies = bodies,
            tombstones = tombstones,
            accounts = if (accountsDue) wireBodyOf(accountsRow) else null,
            accountsFingerprint = if (accountsDue) fingerprint else null,
            accountsClientUpdatedAt = if (accountsDue) stamp else null,
            initial = initial,
        )
    }

    /**
     * Records that the server accepted part of a batch, and only ever called with what it
     * actually accepted.
     *
     * Partial, because a push is several requests — four tables, chunked, plus tombstones —
     * and the ones that landed must not be re-sent because a later one did not. A request
     * that failed leaves exactly its own rows dirty, so the next pass retries those and
     * nothing else.
     *
     * `dirty` is cleared per row rather than per table, and only where the row still holds
     * the timestamp it was uploaded at. An edit made by the student while the upload was in
     * flight sets the flag again, and an unconditional clear would lose that edit's upload —
     * silently, until something else happened to touch the row.
     *
     * A tombstone is *not* deleted once delivered. It is also the answer to "was this cloud
     * id deleted here?", which the pull path asks when a session arrives whose course this
     * device no longer has; see [tombstonedIds]. Its `pushedAt` is what stops it being sent
     * twice, which is all it needs to do.
     */
    suspend fun markPushed(
        userId: String,
        accepted: Map<SyncTable, List<Outgoing>>,
        acceptedTombstones: List<String>,
        accountsAgreed: AccountsAgreed?,
        initialDone: Boolean,
        at: Instant,
    ) = database.withTransaction {
        for ((table, rows) in accepted) {
            for (row in rows) {
                when (table) {
                    SyncTable.SEMESTERS -> semesterDao.clearDirtyIfUnchanged(row.id, row.clientUpdatedAt)
                    SyncTable.COURSES -> courseDao.clearDirtyIfUnchanged(row.id, row.clientUpdatedAt)
                    SyncTable.PATTERNS -> patternDao.clearDirtyIfUnchanged(row.id, row.clientUpdatedAt)
                    SyncTable.SESSIONS -> sessionDao.clearDirtyIfUnchanged(row.id, row.clientUpdatedAt)
                }
            }
        }
        if (acceptedTombstones.isNotEmpty()) {
            syncDao.markTombstonesPushed(acceptedTombstones, at)
        }
        accountsAgreed?.let {
            syncDao.recordAccounts(userId, it.clientUpdatedAt, it.fingerprint)
        }
        if (initialDone) syncDao.markInitialPushDone(userId)
    }

    // ------------------------------------------------------------------- pull

    /** What applying a page of rows came to: what to advance over, and what to come back to. */
    data class Applied(val revisions: List<Long>, val firstDeferred: Long?)

    /**
     * Applies a page of `attendance.semesters`.
     *
     * Every `apply…` below follows the same three rules, and together they are the whole
     * contract between the cloud and this database:
     *
     *  1. **A tombstone deletes.** A row whose `deleted_at` is set removes the local row and
     *     is not written. There is no soft delete locally; see [AttendanceSyncTombstoneEntity]
     *     for why.
     *  2. **The newer `client_updated_at` wins** — [AttendanceSyncPolicy.remoteWins], the same
     *     comparison the server's `lww_touch` makes. A row edited here while offline is left
     *     alone, still dirty, and uploads on the next push.
     *  3. **A row that cannot be placed is deferred, not guessed.** A pattern whose course
     *     this device does not have cannot be stored at all — the local foreign key would
     *     refuse it — so it is left out and its revision reported, and the cursor stops below
     *     it so the next pass reads it again.
     *
     * A row whose *values* are unreadable is a fourth case, handled differently: an unknown
     * status, an unparseable date, a term ending before it starts. Those are values the
     * server's own constraints forbid, so meeting one means the row is not what this client
     * thinks the table is. It is skipped and the cursor moves past it, because retrying it
     * forever would stall every row behind it — and guessing a default instead would write a
     * wrong class into a percentage, which is the failure this app exists to prevent.
     *
     * ### Why this one table needs a second lookup
     *
     * A semester's identity is `(year, type)` locally and a UUID in the cloud, and the two are
     * not the same key — so a row can be unknown by cloud id and still describe a term this
     * device holds. That is not an edge case: a term the student established on this phone
     * before they ever signed in carries no cloud id at all until it is first pushed, and a
     * term two phones each established offline carries two. Migration 0020 leaves the cloud
     * permissive on purpose — *"LWW reconciles instead"* — which makes the reconciliation this
     * function's job rather than the schema's.
     *
     * Getting it wrong is not one lost row. Room's `index_semesters_year_type` refuses the
     * insert, the refusal aborts this page's transaction, and the cursor only advances once a
     * page commits — so the pass can never get past the row, and sync on that device stops for
     * good, at the first table in the order. See [AttendanceSyncPolicy.semesterPlacement].
     */
    suspend fun applySemesters(rows: List<SemesterRow>): Applied = database.withTransaction {
        val applied = mutableListOf<Long>()
        // Cloud identities that lose the reconciliation, tombstoned as part of the same commit.
        // Recording them here rather than deleting anything is the whole deletion story for this
        // table: migration 0020 grants no client a SQL delete, and a duplicate's row has to be
        // removed from the *cloud* — where the other devices and the web client will see it.
        val superseded = mutableListOf<SemesterEntity>()

        for (row in rows.sortedBy { it.revision }) {
            // Unreadable `client_updated_at` — a NOT NULL column the server always renders.
            // Skipped, and the cursor moves past it, per the note above.
            val updatedAt = millisOrNull(row.clientUpdatedAt)
            if (updatedAt == null) {
                applied += row.revision
                continue
            }
            val existing = semesterDao.byCloudId(row.id)
            when (AttendanceSyncPolicy.decisionFor(updatedAt, row.deletedAt != null, existing?.clientUpdatedAt)) {
                PullDecision.SKIP_STALE -> applied += row.revision

                PullDecision.DELETE -> {
                    existing?.let { semesterDao.deleteById(it.id) }
                    applied += row.revision
                }

                PullDecision.APPLY -> {
                    // The row as this device would store it under the incoming identity. A row
                    // this client cannot read faithfully is let go and the cursor moves past it
                    // — the alternative is a page that can never commit.
                    val incoming = row.toEntity(existing, updatedAt)
                    if (incoming == null) {
                        applied += row.revision
                        continue
                    }
                    // The second lookup, made only when the first found nothing: a row that
                    // already resolved by cloud id has nothing to ask the term about.
                    val occupant = if (existing != null) null else semesterDao.byTerm(incoming.year, incoming.type)
                    when (
                        AttendanceSyncPolicy.semesterPlacement(
                            hasCloudRow = existing != null,
                            termIsFree = existing == null && occupant == null,
                            occupantCloudId = occupant?.cloudId,
                            occupantClientUpdatedAt = occupant?.clientUpdatedAt,
                            remoteCloudId = row.id,
                            remoteClientUpdatedAt = updatedAt,
                        )
                    ) {
                        // A local row already carries this cloud id. The ordinary update, and the
                        // only arm reachable for a row this device has seen before.
                        SemesterPlacement.UPDATE -> semesterDao.update(incoming)

                        // Nothing describes this term. The ordinary insert.
                        SemesterPlacement.INSERT -> semesterDao.insert(incoming)

                        // Never pushed, so there is no second identity in play: the local row
                        // takes the incoming one. Its own values survive only if they are
                        // strictly newer, and then it owes the cloud a push — under the id it
                        // has just been given, so the two phones meet on one row rather than
                        // each keeping a semester of their own.
                        SemesterPlacement.ADOPT -> {
                            val here = requireNotNull(occupant)
                            val localWins = !AttendanceSyncPolicy.remoteWins(updatedAt, here.clientUpdatedAt)
                            val merged = if (localWins) here else incoming
                            semesterDao.update(
                                merged.copy(id = here.id, cloudId = incoming.cloudId, dirty = localWins),
                            )
                        }

                        // Two live identities for one term, and the incoming one is the one every
                        // device keeps: the local row keeps its primary key — so courses naming
                        // it still name it — and takes the incoming id and content. The identity
                        // it used to carry loses, and is tombstoned below.
                        SemesterPlacement.SUPERSEDE -> {
                            val here = requireNotNull(occupant)
                            semesterDao.update(incoming.copy(id = here.id))
                            superseded += here
                        }

                        // The identity this device holds holds the newer content, so it stands,
                        // and the incoming row is a duplicate of it. Nothing local changes; the
                        // duplicate's identity is tombstoned so the cloud converges too.
                        SemesterPlacement.KEEP_LOCAL -> superseded += incoming
                    }
                    applied += row.revision
                }
            }
        }

        if (superseded.isNotEmpty()) {
            // Stamped from this device's clock at this moment, like any other deletion: the
            // duplicate is being deleted *now*, and the server's `lww_touch` would refuse a
            // tombstone carrying the timestamp the row already had.
            //
            // Scoped to the account the incoming rows name rather than to the ownership row.
            // The row on the wire says whose it is, and this pass is reading that account's
            // data — so the deletion belongs to it even if the local claim is absent or names
            // someone else. A tombstone that fell back to the claim in that state would be an
            // unscoped one, which any account may push: the next account to sign in on this
            // phone would delete a stranger's semester.
            recordTombstonesFor(
                DeletedRows(semesters = superseded),
                now(),
                deletingFor = rows.firstNotNullOfOrNull { it.userId.takeIf(String::isNotEmpty) },
            )
        }
        Applied(applied, null)
    }

    suspend fun applyCourses(rows: List<CourseRow>): Applied = database.withTransaction {
        val semesterIds = semesterDao.cloudIdentities().associate { it.cloudId to it.id }
        val applied = mutableListOf<Long>()
        for (row in rows.sortedBy { it.revision }) {
            val existing = courseDao.byCloudId(row.id)
            val updatedAt = millisOrNull(row.clientUpdatedAt)
            if (updatedAt == null) {
                applied += row.revision
                continue
            }
            when (AttendanceSyncPolicy.decisionFor(updatedAt, row.deletedAt != null, existing?.clientUpdatedAt)) {
                PullDecision.SKIP_STALE -> applied += row.revision

                PullDecision.DELETE -> {
                    // The local foreign keys cascade to this course's patterns and sessions,
                    // which is the local half of what the deleting device pushed tombstones
                    // for. Room enables foreign key enforcement, so the cascade is real.
                    existing?.let { courseDao.deleteById(it.id) }
                    applied += row.revision
                }

                PullDecision.APPLY -> {
                    val entity = row.toEntity(
                        existing = existing,
                        clientUpdatedAtMillis = updatedAt,
                        semesterId = row.semesterId?.let { semesterIds[it] },
                    )
                    if (existing == null) courseDao.insert(entity) else courseDao.update(entity)
                    applied += row.revision
                }
            }
        }
        Applied(applied, null)
    }

    suspend fun applyPatterns(rows: List<PatternRow>): Applied = database.withTransaction {
        val courseIds = courseDao.cloudIdentities().associate { it.cloudId to it.id }
        val deletedCourses = tombstonedIds(SyncTable.COURSES)
        val applied = mutableListOf<Long>()
        var deferred: Long? = null
        for (row in rows.sortedBy { it.revision }) {
            val existing = patternDao.byCloudId(row.id)
            val updatedAt = millisOrNull(row.clientUpdatedAt)
            if (updatedAt == null) {
                applied += row.revision
                continue
            }
            when (AttendanceSyncPolicy.decisionFor(updatedAt, row.deletedAt != null, existing?.clientUpdatedAt)) {
                PullDecision.SKIP_STALE -> applied += row.revision

                PullDecision.DELETE -> {
                    existing?.let { patternDao.deleteById(it.id) }
                    applied += row.revision
                }

                PullDecision.APPLY -> {
                    val courseId = courseIds[row.courseId]
                    if (courseId == null) {
                        // The course is not here. If this device deleted it, the pattern is
                        // not applicable and waiting would stall sync forever; otherwise it
                        // is a row to come back to, and the cursor stops below it.
                        if (row.courseId in deletedCourses) applied += row.revision
                        else if (deferred == null) deferred = row.revision
                        continue
                    }
                    val entity = row.toEntity(existing, updatedAt, courseId)
                    if (entity == null) {
                        // Unreadable values, per the note on [applySemesters]: let the row go and
                        // move past it. Dropping the revision instead would leave the cursor
                        // unable to pass the row, which stalls every row behind it forever.
                        applied += row.revision
                        continue
                    }
                    if (existing == null) patternDao.insert(entity) else patternDao.update(entity)
                    applied += row.revision
                }
            }
        }
        Applied(applied, deferred)
    }

    suspend fun applySessions(rows: List<SessionRow>): Applied = database.withTransaction {
        val courseIds = courseDao.cloudIdentities().associate { it.cloudId to it.id }
        val patternIds = patternDao.cloudIdentities().associate { it.cloudId to it.id }
        val sessionIds = sessionDao.cloudIdentities().associate { it.cloudId to it.id }
        val deletedCourses = tombstonedIds(SyncTable.COURSES)
        val applied = mutableListOf<Long>()
        var deferred: Long? = null
        for (row in rows.sortedBy { it.revision }) {
            val existing = sessionDao.byCloudId(row.id)
            val updatedAt = millisOrNull(row.clientUpdatedAt)
            if (updatedAt == null) {
                applied += row.revision
                continue
            }
            when (AttendanceSyncPolicy.decisionFor(updatedAt, row.deletedAt != null, existing?.clientUpdatedAt)) {
                PullDecision.SKIP_STALE -> applied += row.revision

                PullDecision.DELETE -> {
                    existing?.let { sessionDao.deleteById(it.id) }
                    applied += row.revision
                }

                PullDecision.APPLY -> {
                    val courseId = courseIds[row.courseId]
                    if (courseId == null) {
                        // The course is not here. If this device deleted it, the class is not
                        // applicable and waiting would stall sync forever; otherwise it is a
                        // row to come back to, and the cursor stops below it.
                        if (row.courseId in deletedCourses) applied += row.revision
                        else if (deferred == null) deferred = row.revision
                        continue
                    }
                    val entity = row.toEntity(
                        existing = existing,
                        clientUpdatedAtMillis = updatedAt,
                        courseId = courseId,
                        patternId = row.patternId?.let { patternIds[it] },
                        movedToSessionId = row.movedToSessionId?.let { sessionIds[it] },
                        movedFromSessionId = row.movedFromSessionId?.let { sessionIds[it] },
                    )
                    if (entity == null) {
                        // See the same case in [applyPatterns]: the row is let go and the cursor
                        // moves past it, rather than the page being made unpassable.
                        applied += row.revision
                        continue
                    }
                    // The row the cloud id names is only one of the two places this class can
                    // already be. `(patternId, date)` is unique locally and this device
                    // generates its own rows from its own patterns long before it ever syncs,
                    // so the slot is often occupied by a row the cloud has never heard of —
                    // which is the ordinary state of a second device, not an edge case. A
                    // plain insert there is a constraint violation, and a violation here is
                    // not one lost row: it aborts this page's transaction, and the cursor
                    // advances only once a page commits, so it can never get past the row.
                    // Sync on that device stops for good. See [sessionPlacement].
                    val occupant = if (existing != null) {
                        null
                    } else {
                        entity.patternId?.let { patternId -> sessionDao.byPatternAndDate(patternId, entity.date) }
                    }
                    when (
                        AttendanceSyncPolicy.sessionPlacement(
                            hasCloudRow = existing != null,
                            slotIsFree = occupant == null,
                            slotClientUpdatedAt = occupant?.clientUpdatedAt,
                            remoteClientUpdatedAt = updatedAt,
                        )
                    ) {
                        SessionPlacement.UPDATE -> sessionDao.update(entity)
                        SessionPlacement.INSERT -> sessionDao.insert(entity)

                        // The occupant is newer, so it stands. Nothing is written, and nothing
                        // needs to be: the occupancy is not a permanent split. On its next push
                        // the occupant derives exactly this cloud id — `cloudSessionId` of its
                        // own pattern — and uploads under it with its own, newer timestamp,
                        // which the server's `lww_touch` accepts. The two copies converge on
                        // the occupant's values with no help from here.
                        SessionPlacement.SKIP_STALE -> Unit

                        // The occupant becomes this row: it keeps its own primary key, so
                        // anything on this device referencing it still resolves, and it takes
                        // the incoming cloud id — which its next push would have derived for it
                        // anyway. A slot occupant that somehow already had a cloud id keeps
                        // that identity instead: adopting the incoming one would strand a row
                        // the cloud holds under an id nothing here would ever name again, and
                        // identity outlives any single pass.
                        SessionPlacement.ADOPT -> occupant?.let { here ->
                            sessionDao.update(entity.copy(id = here.id, cloudId = here.cloudId ?: entity.cloudId))
                        }
                    }
                    applied += row.revision
                }
            }
        }
        Applied(applied, deferred)
    }

    /**
     * The settings a pulled accounts row describes, or null when it must not be applied.
     *
     * Null covers four different answers with one shape: the row is a tombstone, the row is
     * unreadable, the row is not newer than what this device already agreed, or this device's
     * settings have changed since it last agreed and are waiting to be pushed. In every one of
     * them the local settings are left exactly as they are, which is the only safe reading of
     * each.
     *
     * The tombstone case is the one that looks like it could not happen. It cannot happen
     * *from here* — this client never soft-deletes its settings, and the row is keyed by the
     * auth user with `on delete cascade`, so a deleted account removes it outright. It can
     * happen from the server: `deleted_at` is a column an operator can set, and the server
     * keeping a row it has marked deleted is exactly what the four revisioned tables are read
     * through [AttendanceSyncPolicy.decisionFor] for. A row marked deleted is a deletion, not
     * content, so it is not applied — the local settings stand, which is the same answer every
     * other branch here gives. Reading it as content instead would let a deleted settings row
     * overwrite a live one, which is the failure the tombstone model exists to prevent.
     */
    suspend fun settingsFromAccount(userId: String, row: AccountRow, current: AppSettings): AppSettings? {
        if (row.deletedAt != null) return null
        val remoteAt = millisOrNull(row.clientUpdatedAt) ?: return null
        val state = ensureState(userId)
        if (state.accountsClientUpdatedAt != null && remoteAt <= state.accountsClientUpdatedAt) return null
        val settings = row.toSettings(current) ?: return null
        // A local change that has not been uploaded yet must not be overwritten by the copy
        // the cloud still holds. Same rule as [AttendanceSyncPolicy.remoteWins], on a row
        // whose "local timestamp" is the fingerprint of what is on the device right now.
        val agreed = state.accountsFingerprint
        if (agreed != null && current.toRow(userId, 0L).fingerprint() != agreed) return null
        return settings
    }

    /**
     * Records that the cloud's accounts row is now what this device holds.
     *
     * Called only when it was actually applied. A row that was read and deliberately left
     * out — because the local settings are newer, or because the row was unreadable — records
     * nothing, so the same row is re-decided on the next pass. That is one extra read rather
     * than a wrong checkpoint: writing down an agreement this device did not make is exactly
     * the kind of quiet lie that loses a student's settings later.
     */
    suspend fun recordAccountsApplied(userId: String, row: AccountRow) {
        val at = millisOrNull(row.clientUpdatedAt) ?: return
        syncDao.recordAccounts(userId, clientUpdatedAt = at, fingerprint = row.fingerprint())
    }

    // -------------------------------------------------------------- deletions

    /** The rows a delete is about to remove locally, handed over before they go. */
    data class DeletedRows(
        val semesters: List<SemesterEntity> = emptyList(),
        val courses: List<CourseEntity> = emptyList(),
        val patterns: List<PatternEntity> = emptyList(),
        val sessions: List<SessionEntity> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = semesters.isEmpty() && courses.isEmpty() && patterns.isEmpty() && sessions.isEmpty()
    }

    /**
     * Records tombstones for rows that are about to be hard-deleted locally.
     *
     * Called with the entities still in hand, because a tombstone carries their columns —
     * see [AttendanceSyncTombstoneEntity.payloadJson]. `client_updated_at` is stamped at the
     * *moment of the delete* rather than copied from the row: deleting is an edit that
     * happened now, and a tombstone carrying the row's last-edit time would lose to a device
     * that had touched it since, leaving it alive in the cloud for good.
     *
     * A row with no cloud id is skipped outright. It has never been pushed — identity is
     * assigned at push time — so there is nothing in the cloud to delete, and a tombstone
     * for it would manufacture a deleted row the server never had.
     */
    suspend fun recordDeleted(rows: DeletedRows, at: Instant) = database.withTransaction {
        recordTombstonesFor(rows, at)
    }

    /**
     * The body of [recordDeleted], without a transaction of its own.
     *
     * Split out so the pull path can record a tombstone inside the transaction it is already
     * in — see [applySemesters], which removes a duplicated cloud identity as part of applying a
     * page. Wrapping that in a second [database.withTransaction] would be harmless but would
     * hide the fact that the tombstone and the write it belongs to commit together or not at
     * all, which is the property that matters: a tombstone committed on its own could outlive a
     * page that was rolled back, and a page committed alone would leave the duplicate alive.
     *
     * @param deletingFor the account the deletion belongs to, when the caller knows it better
     *   than the ownership row does. The pull path does: the row being removed names its own
     *   account on the wire, whereas the owner row is a claim this device made and could be
     *   absent or stale. Left null for a local delete, where the owner *is* the answer — the
     *   deletion was made by whoever this phone's attendance belongs to. Null falls back to the
     *   owner, and an empty result is the documented "deleted before any account claimed it"
     *   scope that any account may push.
     */
    private suspend fun recordTombstonesFor(rows: DeletedRows, at: Instant, deletingFor: String? = null) {
        if (rows.isEmpty) return
        val stamp = at.toEpochMilli()
        val stampText = Instant.ofEpochMilli(stamp).toString()
        // The uid, not the whole claim: a tombstone is scoped to the account whose row it
        // deletes, and a terminated claim's deletions are still that gone account's — nobody
        // else may push them, and [AttendanceSyncPolicy.tombstonePushableBy] refuses them for
        // every other uid on the strength of this field alone.
        val owner = deletingFor?.takeIf { it.isNotEmpty() } ?: attendanceOwner()?.userId.orEmpty()

        // A row being deleted may be another deleted row's reference — a session's
        // `movedTo`, a pattern's course — so the ids of the rows in hand are merged into
        // each table's map rather than looked for on disk, where they are about to vanish.
        val semesterIds = semesterDao.cloudIdentities().associate { it.id to it.cloudId } +
            rows.semesters.mapNotNull { row -> row.cloudId?.let { row.id to it } }
        val courseIds = courseDao.cloudIdentities().associate { it.id to it.cloudId } +
            rows.courses.mapNotNull { row -> row.cloudId?.let { row.id to it } }
        val patternIds = patternDao.cloudIdentities().associate { it.id to it.cloudId } +
            rows.patterns.mapNotNull { row -> row.cloudId?.let { row.id to it } }
        val sessionIds = sessionDao.cloudIdentities().associate { it.id to it.cloudId } +
            rows.sessions.mapNotNull { row -> row.cloudId?.let { row.id to it } }

        val tombstones = mutableListOf<AttendanceSyncTombstoneEntity>()

        fun record(table: SyncTable, cloudId: String, body: JsonObject) {
            val prefix = if (owner.isNotEmpty()) "$owner:" else ""
            tombstones += AttendanceSyncTombstoneEntity(
                key = "$prefix${table.wireName}:$cloudId",
                syncTable = table.wireName,
                cloudId = cloudId,
                payloadJson = body.toString(),
                deletedAt = Instant.ofEpochMilli(stamp),
                userId = owner,
            )
        }

        for (row in rows.semesters) {
            val cloudId = row.cloudId ?: continue
            record(
                SyncTable.SEMESTERS,
                cloudId,
                wireBodyOf(
                    row.copy(clientUpdatedAt = stamp).toRow(USER_ID_AT_PUSH)
                        .copy(clientUpdatedAt = stampText, deletedAt = stampText),
                ),
            )
        }
        for (row in rows.courses) {
            val cloudId = row.cloudId ?: continue
            record(
                SyncTable.COURSES,
                cloudId,
                wireBodyOf(
                    row.copy(clientUpdatedAt = stamp)
                        .toRow(USER_ID_AT_PUSH, semesterCloudId = row.semesterId?.let { semesterIds[it] })
                        .copy(clientUpdatedAt = stampText, deletedAt = stampText),
                ),
            )
        }
        for (row in rows.patterns) {
            val cloudId = row.cloudId ?: continue
            val courseCloudId = courseIds[row.courseId] ?: continue
            record(
                SyncTable.PATTERNS,
                cloudId,
                wireBodyOf(
                    row.copy(clientUpdatedAt = stamp).toRow(USER_ID_AT_PUSH, courseCloudId)
                        .copy(clientUpdatedAt = stampText, deletedAt = stampText),
                ),
            )
        }
        for (row in rows.sessions) {
            val cloudId = row.cloudId ?: continue
            val courseCloudId = courseIds[row.courseId] ?: continue
            record(
                SyncTable.SESSIONS,
                cloudId,
                wireBodyOf(
                    row.copy(clientUpdatedAt = stamp)
                        .toRow(
                            userId = USER_ID_AT_PUSH,
                            courseCloudId = courseCloudId,
                            patternCloudId = row.patternId?.let { patternIds[it] },
                            movedToCloudId = row.movedToSessionId?.let { sessionIds[it] },
                            movedFromCloudId = row.movedFromSessionId?.let { sessionIds[it] },
                        )
                        .copy(clientUpdatedAt = stampText, deletedAt = stampText),
                ),
            )
        }

        syncDao.recordTombstones(tombstones)
    }

    // ----------------------------------------------------------------- shared

    /**
     * Gives rows an identity and a timestamp, writing back only the ones that needed one.
     *
     * Shared by the three tables whose id is simply fresh — sessions are the exception, since
     * theirs is derived, and are stamped in [preparePush] itself.
     */
    private suspend fun <T> assignIdentity(
        rows: List<T>,
        cloudId: (T) -> String?,
        clientUpdatedAt: (T) -> Long?,
        stamp: Long,
        copyWith: (T, String, Long) -> T,
        save: suspend (T) -> Unit,
    ): List<T> {
        val out = ArrayList<T>(rows.size)
        for (row in rows) {
            val id = cloudId(row)
            val editedAt = clientUpdatedAt(row)
            if (id != null && editedAt != null) {
                out += row
                continue
            }
            val next = copyWith(row, id ?: AttendanceSyncPolicy.newCloudId(), editedAt ?: stamp)
            save(next)
            out += next
        }
        return out
    }

    private companion object {
        /**
         * The account is stamped onto a tombstone's payload at push time, not at delete time:
         * nothing on the delete path knows it, and it is a push-time fact in exactly the way
         * `revision` is. See [preparePush].
         */
        const val USER_ID_AT_PUSH = ""
    }
}
