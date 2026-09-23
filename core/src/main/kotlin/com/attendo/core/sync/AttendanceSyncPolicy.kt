package com.attendo.core.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * The attendance tables, in the order they must be pushed and applied.
 *
 * Declaration order *is* the foreign-key order: a pattern references a course, a
 * session references a course (and, softly, a pattern), so parents are written and
 * read before children. The cloud enforces this with composite foreign keys — a
 * pattern whose course is not yet on the server is rejected outright — so the order
 * is a correctness requirement, not a preference.
 *
 * [wireName] is the PostgREST table name inside the `attendance` schema.
 */
enum class SyncTable(val wireName: String) {
    SEMESTERS("semesters"),
    COURSES("courses"),
    PATTERNS("patterns"),
    SESSIONS("sessions"),
}

/**
 * Every decision the Attendance Sync client makes, as pure functions.
 *
 * The engine around this is Room and HTTP — reads, writes, retries — but none of it
 * decides anything: it applies what these functions say. Keeping the rules here means
 * the boundary conditions that matter (a stale write, a cursor that must not step over
 * a row, the identity two devices must agree on for the same class) are testable
 * without a database, a network, or a server.
 *
 * The rules mirror migration 0020 exactly; where they do, the migration is quoted so
 * the two cannot drift apart unnoticed.
 */
object AttendanceSyncPolicy {

    /**
     * How many rows one pull page asks for.
     *
     * One page per table per request, so the cost of a pass is bounded by the number of
     * tables rather than by the size of a term's attendance history.
     */
    const val PULL_PAGE_SIZE: Long = 200L

    /**
     * How many rows one push request carries.
     *
     * The first push of an install that has been used for a term is every row it has ever
     * written, which is far more than one request should hold: a body that large is slow to
     * encode, slow to upload on a phone connection, and rejected outright past the server's
     * limit — taking the whole pass with it. Chunking bounds each request instead, and the
     * cost is only that a failure part-way leaves the later chunks to the next pass.
     *
     * The chunks of one table are sent in order: a chunk of sessions naming courses in the
     * next chunk would still be refused by the composite foreign keys, and while a retry
     * would eventually converge, sending parents first avoids the failed round trip.
     */
    const val PUSH_CHUNK_SIZE: Int = 400

    /**
     * How many revisions below the stored cursor every pull re-reads.
     *
     * `attendance.change_seq` is a plain sequence and `nextval` is deliberately
     * non-transactional: transaction A can draw revision 100, transaction B draw 101, and
     * B commit first. A pull landing in that window sees 101, and a cursor advanced to 101
     * would never see 100 — a row silently missing from this device for good.
     *
     * Nothing in migration 0020 closes that window, so this is a *mitigation*: each pass
     * starts reading this many revisions behind where it finished, so a late commit is
     * picked up by the next pass instead of being stepped over. Re-reading is genuinely
     * free — a row this device already applied is not strictly newer than the copy it
     * applied, so [remoteWins] rejects it and nothing is written.
     *
     * The hazard is bounded, not eliminated: a commit delayed past this many subsequent
     * revisions still slips through. For an account whose only writers are the student's
     * own devices that is a wide margin, but true multi-device concurrency has **not**
     * been demonstrated against a live backend, and this is not a substitute for it.
     */
    const val PULL_OVERLAP: Long = 100L

    /**
     * Where a pull at [cursor] should begin reading.
     *
     * Never negative: a device that has read nothing starts at 0 (every revision is
     * greater than 0), and one that has read a little starts at the beginning rather than
     * at a negative revision.
     */
    fun overlapStart(cursor: Long): Long = (cursor - PULL_OVERLAP).coerceAtLeast(0L)

    /**
     * Where a pull should begin reading, given the stored cursor and how the pull was asked for.
     *
     * [overlapStart] is the optimisation: read from just behind where the last pass finished.
     * It is correct for a *background* pass, whose whole job is to pick up what has changed
     * since — and whose cost would otherwise grow with the size of a term's history, on every
     * wake-up, forever.
     *
     * It is not what a manual "Sync now" means. A student who presses that button is asking for
     * a reconciliation, not for a check against a cursor: the cursor is a local optimisation
     * that can be wrong (a restore renumbers rows, a wipe resets it, a row can sit below it for
     * reasons this device never saw), and answering "nothing to do" from it is answering a
     * question about this device rather than about the account. In [RECONCILE] mode the read
     * starts at the beginning of the account's history, so every live row is re-read and put
     * through the same LWW comparison that decides everything else. Re-reading is free in the
     * only sense that matters: a row this device already holds is not strictly newer than the
     * copy it holds, so it is rejected without a write.
     */
    fun pullFloor(cursor: Long, scope: PullScope): Long = when (scope) {
        PullScope.INCREMENTAL -> overlapStart(cursor)
        PullScope.RECONCILE -> 0L
    }

    /** How much of a table a pull re-reads. */
    enum class PullScope {
        /** Behind the cursor — what the background pass uses. */
        INCREMENTAL,

        /** From the beginning of the account's history — what a manual sync uses. */
        RECONCILE,
    }

    /**
     * The frozen namespace every derived session id is hashed under.
     *
     * **This value must never change.** It is the first half of the identity of every
     * generated class in every account's cloud data; changing it would rename every
     * session on the next push, and the unique index that is supposed to stop two devices
     * duplicating a class would stop catching anything. It is a random UUID chosen once
     * and written down here, which is the whole of its meaning.
     */
    private val SESSION_NAMESPACE: UUID = UUID.fromString("6f1e0c2a-9b3d-4f57-8a21-5c7d0e9f4b13")

    /**
     * The cloud id of a session generated from a recurring slot on a date.
     *
     * Migration 0020: *"Clients MUST use a deterministic id (e.g. UUIDv5 of pattern_id +
     * date) for pattern-generated sessions so two devices generate the same row; the
     * unique index below is the backstop."* This is that id.
     *
     * Two devices that generate the same class from the same slot therefore address the
     * same cloud row instead of racing to create two — which matters because the local
     * `(patternId, date)` uniqueness that stops the duplicate locally cannot travel: the
     * local pattern id is an autoincrement Long, unique to one database file, and a
     * restore renumbers it.
     *
     * @param patternCloudId the *cloud* id of the pattern, never the local Long.
     */
    fun cloudSessionId(patternCloudId: String, date: LocalDate): String =
        uuidV5(SESSION_NAMESPACE, "$patternCloudId|$date").toString()

    /** A fresh identity for a row with no deterministic derivation — an ad-hoc class, a course. */
    fun newCloudId(): String = UUID.randomUUID().toString()

    /**
     * Whether an incoming row replaces what this device holds.
     *
     * The client half of `attendance.lww_touch()`, which reads:
     * `if NEW.client_updated_at <= OLD.client_updated_at then return OLD`. Strictly
     * newer wins; a tie loses; a row this device has never seen any timestamp for loses
     * to whatever the cloud holds.
     *
     * Applying the cheaper, safer half of that rule locally is what keeps a device that
     * edited offline from being overwritten by the stale copy the cloud still has — and
     * it is the same comparison the server will make when this device finally pushes,
     * so the two never disagree about who won.
     */
    fun remoteWins(remoteClientUpdatedAt: Long, localClientUpdatedAt: Long?): Boolean =
        localClientUpdatedAt == null || remoteClientUpdatedAt > localClientUpdatedAt

    /** What a row that arrived from the cloud asks this device to do. */
    enum class PullDecision {
        /** Write it. */
        APPLY,

        /** Leave the local row exactly as it is — this device's copy is newer, or equally new. */
        SKIP_STALE,

        /** Remove the local row. */
        DELETE,
    }

    /**
     * The single rule every pulled row is put through, before anything is parsed or stored.
     *
     * The order of the two questions matters, and it is the order the server itself uses.
     * LWW comes *first*: a tombstone is an edit like any other, and one that lost on the
     * server has no business winning here. So a class this device marked on a train at 18:10
     * is not deleted by a tombstone another phone raised at 17:00 — the local edit is newer,
     * it stays, and the push that follows clears `deleted_at` on the server. A row that
     * arrives with a newer timestamp *is* deleted, and a device that has never seen the row
     * applies whatever the cloud says, because null means "no local edit on record".
     *
     * Only once the timestamp question is settled does what the row *says* matter. That
     * ordering is what makes this function the whole of the deletion story on the client:
     * there is no separate "handle tombstones" path to get out of step with the ordinary one.
     */
    fun decisionFor(
        remoteClientUpdatedAt: Long,
        deleted: Boolean,
        localClientUpdatedAt: Long?,
    ): PullDecision = when {
        !remoteWins(remoteClientUpdatedAt, localClientUpdatedAt) -> PullDecision.SKIP_STALE
        deleted -> PullDecision.DELETE
        else -> PullDecision.APPLY
    }

    /** Where an applying session row lands, once both of its local lookups have been made. */
    enum class SessionPlacement {
        /** A local row carries the incoming cloud id. The ordinary update. */
        UPDATE,

        /** Nothing occupies the slot. The ordinary insert. */
        INSERT,

        /** A local row occupies the slot with no cloud id of its own. It becomes this row. */
        ADOPT,

        /** A local row occupies the slot and is newer. Leave it; the next push converges them. */
        SKIP_STALE,
    }

    /**
     * How a session row that is being applied lands, given what is already in the slot.
     *
     * The ordinary path is [SessionPlacement.UPDATE] or [SessionPlacement.INSERT] — a row
     * already carries the incoming cloud id, or nothing does. The other two exist for the one
     * case that is neither: a row is already sitting in the `(patternId, date)` slot the
     * incoming row names, under a *different* primary key and with no cloud id of its own.
     *
     * Room's `index_sessions_patternId_date` is one half of a rule whose other half is
     * migration 0020's `sessions_pattern_date_live`: one live row per class. A client that
     * inserts instead of adopting violates the local half, and the violation is worse than a
     * rejected row — it aborts the whole page's transaction, and the cursor advances only
     * after a page commits, so it can never get past the row. Sync stops on that device and
     * never resumes. So an occupied slot is never a second insert.
     *
     * An occupied slot is not a conflict either. A class this device generated and the
     * cloud's copy of that same class *are one class*: this device holds no cloud id for its
     * own row yet, because identity is assigned at push time, and [cloudSessionId] would hand
     * it exactly the id the incoming row carries. Adopting is therefore not a merge or a
     * guess — it is recognising the row that is already here.
     *
     * @param hasCloudRow a local row already carries the incoming cloud id.
     * @param slotIsFree nothing occupies the `(patternId, date)` slot. Always true for a row
     *   with no pattern: SQLite treats NULLs as distinct in a unique index, so two ad-hoc
     *   classes in one slot are legitimate and never collide.
     * @param slotClientUpdatedAt the occupant's edit time — null both when the slot is free
     *   and when it holds a row this device generated and never stamped, which is why
     *   [slotIsFree] is a parameter of its own rather than being read off this one.
     */
    fun sessionPlacement(
        hasCloudRow: Boolean,
        slotIsFree: Boolean,
        slotClientUpdatedAt: Long?,
        remoteClientUpdatedAt: Long,
    ): SessionPlacement = when {
        hasCloudRow -> SessionPlacement.UPDATE
        slotIsFree -> SessionPlacement.INSERT
        // The occupant is the only local copy of this class there is — the lookup by cloud id
        // found nothing — so the LWW rule has to be about *it*, not about the row that is not
        // here. Same comparison as every other pulled row; no second rule for this path.
        remoteWins(remoteClientUpdatedAt, slotClientUpdatedAt) -> SessionPlacement.ADOPT
        else -> SessionPlacement.SKIP_STALE
    }

    /** Where an applying semester row lands, once both of its local lookups have been made. */
    enum class SemesterPlacement {
        /** A local row carries the incoming cloud id. The ordinary update. */
        UPDATE,

        /** No local row describes this term. The ordinary insert. */
        INSERT,

        /**
         * A local row describes this term and has **no cloud id of its own**. It becomes this
         * row: it keeps its own primary key, takes the incoming cloud id, and takes whichever
         * content is newer.
         */
        ADOPT,

        /**
         * A local row describes this term under a **different, live** cloud id, and the
         * incoming identity is the one every device keeps. The local row keeps its primary key
         * and takes the incoming id and content; the identity it used to carry is tombstoned.
         */
        SUPERSEDE,

        /**
         * A local row describes this term under a different cloud id, and the identity this
         * device holds is the one every device keeps. Nothing local changes; the incoming
         * duplicate is tombstoned.
         */
        KEEP_LOCAL,
    }

    /**
     * How a semester row that is being applied lands, given what already names its term.
     *
     * A semester's local identity is `(year, type)`, and Room enforces it with a unique index:
     * *"a semester is its year and half — 'the odd semester of 2026' names one thing"*. The
     * cloud deliberately does **not** enforce it — migration 0020 says so outright: *"the local
     * (year, kind) uniqueness is NOT enforced here — LWW reconciles instead"* — because the
     * cloud's primary key is a UUID a client chose, and two devices can each choose one for the
     * same term without ever having spoken to each other.
     *
     * So a lookup by cloud id is not enough, and treating it as enough is a crash rather than a
     * missed row: a semester the cloud holds under an id this device has never recorded lands on
     * an INSERT, the local unique index refuses it, and the refusal aborts the whole page's
     * transaction. The cursor only advances once a page commits, so it can never get past the
     * row — sync on that device stops for good, and it stops at the *first* table.
     *
     * Which local lookup answers is therefore part of the decision, exactly as it is for
     * sessions: the cloud id first, then — only when that found nothing — the term the row
     * names. An occupied term is never a second insert.
     *
     * ### Identity follows content
     *
     * When two cloud ids name one term, exactly one of them can survive: the local row is the
     * term, and it can only carry one handle. The rule is that the identity which holds the
     * newer content is the one every device keeps, computed from the two rows alone — see
     * [semesterIdentitySurvives] for why a rule that depended on which row this device happened
     * to hold would delete the semester rather than deduplicate it. A row that has never been
     * pushed has no identity to defend, so the incoming one is simply taken.
     *
     * @param hasCloudRow a local row already carries the incoming cloud id.
     * @param termIsFree no local row describes this `(year, type)`.
     * @param occupantCloudId the term occupant's cloud id, or null for a row this device has
     *   never pushed. Null is a distinct case and not "no occupant" — see [termIsFree].
     * @param occupantClientUpdatedAt the term occupant's edit time, or null for a row this
     *   device has never stamped.
     */
    fun semesterPlacement(
        hasCloudRow: Boolean,
        termIsFree: Boolean,
        occupantCloudId: String?,
        occupantClientUpdatedAt: Long?,
        remoteCloudId: String,
        remoteClientUpdatedAt: Long,
    ): SemesterPlacement = when {
        hasCloudRow -> SemesterPlacement.UPDATE
        termIsFree -> SemesterPlacement.INSERT
        // Never pushed, so there is no second identity in play: this row *is* the term's cloud
        // row, whatever either side's timestamp says about the content.
        occupantCloudId == null -> SemesterPlacement.ADOPT
        semesterIdentitySurvives(
            remoteCloudId = remoteCloudId,
            remoteClientUpdatedAt = remoteClientUpdatedAt,
            localCloudId = occupantCloudId,
            localClientUpdatedAt = occupantClientUpdatedAt,
        ) -> SemesterPlacement.SUPERSEDE

        else -> SemesterPlacement.KEEP_LOCAL
    }

    /**
     * Whether the incoming row's cloud identity is the one every device keeps for this term.
     *
     * The case this exists for: two live cloud rows describe one `(year, type)`, because two
     * devices each created the semester before either had heard of the other. Locally there can
     * only ever be one row, so exactly one of the two identities can survive — and which one it
     * is has to be the **same answer on every device**, computed from the rows alone.
     *
     * It is not enough to keep whichever identity arrived second, or whichever one this device
     * happens to hold. Two devices that disagreed about the surviving identity would each
     * tombstone the other's row, and a tombstone naming the identity the *other* device kept is
     * not a duplicate being removed — it is the semester itself being deleted, on the cloud and
     * then on the phone that held it. Worse, a device that kept its own identity while the
     * other device tombstoned it would apply that tombstone to its own row on the next pull.
     * The rule has to be a function of the two rows, not of the order they were read in.
     *
     * So: the row with the newer `client_updated_at` keeps its identity — the same comparison
     * that decides the content, which is what makes identity and content agree instead of the
     * identity changing while the values stay behind. A tie is broken by the cloud id, larger
     * winning: arbitrary, but identical everywhere, which is the only property it needs.
     *
     * A null [localClientUpdatedAt] means a row this device has never stamped, which loses to
     * any stamped timestamp — exactly as [remoteWins] says a null local timestamp does.
     */
    fun semesterIdentitySurvives(
        remoteCloudId: String,
        remoteClientUpdatedAt: Long,
        localCloudId: String,
        localClientUpdatedAt: Long?,
    ): Boolean {
        val localAt = localClientUpdatedAt ?: Long.MIN_VALUE
        return if (remoteClientUpdatedAt != localAt) {
            remoteClientUpdatedAt > localAt
        } else {
            remoteCloudId > localCloudId
        }
    }

    /**
     * The account this device's attendance belongs to, as the owner row records it.
     *
     * [terminated] is the device-side record that the account named by [userId] was
     * established **gone** — deleted from the auth server, not signed out, not expired, not
     * unreachable. It is the persisted form of
     * [com.attendo.core.auth.AuthLifecycleState.SERVER_ACCOUNT_GONE] applied to the local
     * data, and it exists because "whose attendance is on this phone?" and "is that account
     * still alive?" have different answers after a deletion: the rows are still A's, and A
     * no longer exists to hold them.
     *
     * The flag is written once and never cleared by anything but a claim. It is *not* a
     * second lifecycle state machine: it is one column on the one row that already answers
     * the ownership question, so nothing in the app has to reconcile two records of the same
     * fact.
     */
    data class LocalAttendanceOwner(
        val userId: String,
        val terminated: Boolean = false,
    )

    /** Which account this device's attendance belongs to, and what that permits. */
    enum class OwnershipVerdict {
        /** No account has claimed it yet. Claim it, then sync. */
        CLAIM,

        /**
         * The linked account is a different one from a **terminated** claim. The claim's
         * lifecycle is over, so this is a genuinely new account lifecycle: set the outgoing
         * body aside and claim for the incoming account in the same transaction.
         *
         * Distinct from [REFUSE] on purpose. See [ownershipVerdict].
         */
        QUARANTINE_NEW_LIFECYCLE,

        /** The linked account is the owning account. Sync. */
        PROCEED,

        /** The linked account is a different one. Do not sync anything, in either direction. */
        REFUSE,
    }

    /**
     * The decision for an account transition when switching or logging in.
     *
     * **FIRST_CLAIM is not "a new account after deletion".** It is only
     * `ownerUserId == null`: this phone's attendance has never been claimed, so the incoming
     * account may upload every local row. A server deletion of UID-A keeps that claim
     * (`NamesGoneAccount(A)`, marked terminated). UID-B signed in afterwards is a new
     * *authenticated* lifecycle on a phone whose attendance still belongs to A, which is
     * [NEW_LIFECYCLE_AFTER_TERMINATION] — not a first claim, and not
     * [DIFFERENT_ACCOUNT_REFUSE] either: A is gone, so there is nobody left to ask about
     * replacing, and B is not "some other live account".
     */
    enum class AccountTransition {
        /**
         * No account owns this phone's attendance (`ownerUserId == null`). Local rows will
         * be uploaded as this account's. Not the state a deletion leaves.
         */
        FIRST_CLAIM,

        /** Same account returning after sign-out. Local state is recognized as belonging to this account. */
        SAME_ACCOUNT_RETURN,

        /**
         * Different account attempting to use the device without switching.
         * Sync MUST be refused to prevent cross-account leak.
         */
        DIFFERENT_ACCOUNT_REFUSE,

        /**
         * Deliberate user-approved switch to a different account.
         * Local attendance belonging to the outgoing account is wiped, and the incoming account claims ownership.
         */
        SWITCH_APPROVED,

        /**
         * The phone's attendance is claimed by an account the server has established is
         * **gone**, and a different account has signed in.
         *
         * This is the admin-deletion lifecycle, and it is not a switch: there is no live
         * outgoing account to replace, so there is nobody to ask and nothing to approve. It
         * carries no prompt — the deletion was already announced, and the sentence the
         * student was shown said the next sign-in would be treated as a new account. This
         * value is what makes that sentence true, and
         * [ownershipVerdict]'s [OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE] is what carries it
         * out.
         */
        NEW_LIFECYCLE_AFTER_TERMINATION,
    }

    /**
     * Evaluates account transition semantics aligned with Community's account model.
     *
     * The terminated check is deliberately **before** [isExplicitSwitchApproved]: a claim
     * whose account was deleted must never be routed through the switch flow, because that
     * flow asks the student to approve replacing a live account's data — a question with no
     * true answer once the outgoing account no longer exists. Being explicitly approved is
     * therefore not enough to reach [AccountTransition.SWITCH_APPROVED] over a terminated
     * claim; the answer is [AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION] either way,
     * which is also what the engine does with it.
     */
    fun accountTransition(
        owner: LocalAttendanceOwner?,
        linkedUserId: String,
        isExplicitSwitchApproved: Boolean = false,
    ): AccountTransition = when {
        owner == null -> AccountTransition.FIRST_CLAIM
        owner.userId == linkedUserId -> AccountTransition.SAME_ACCOUNT_RETURN
        owner.terminated -> AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION
        isExplicitSwitchApproved -> AccountTransition.SWITCH_APPROVED
        else -> AccountTransition.DIFFERENT_ACCOUNT_REFUSE
    }

    /**
     * Reconciliation choices for a returning account when local edits occurred while signed out.
     */
    enum class ReturningReconciliation {
        /**
         * OVERRIDE / KEEP LOCAL:
         * Standard LWW merge path. Local newer edits made while logged out are kept and pushed.
         */
        KEEP_LOCAL_AND_MERGE,

        /**
         * RESTORE / DISCARD LOCAL:
         * Discard local signed-out changes and restore remote state from cloud.
         */
        RESTORE_REMOTE,
    }

    /**
     * Whether a pass may run for the linked account, from the account that owns the local
     * attendance.
     *
     * Attendance belongs to an account — the whole feature is "this is your backup" — but it
     * is *written* before any account exists, by a student who may never sign in at all. So
     * the owning account is not a fact the rows carry; it is recorded the first time an
     * account asks to sync them.
     *
     * That record is the only thing that separates two situations which otherwise look
     * identical from the inside: "an install used for a term, now linking its first account",
     * where uploading every local row is the entire point, and "an install one account has
     * already synced, now linking a second", where uploading every local row hands the first
     * account's attendance to the second. Without the record the second cannot be told from
     * the first, because a uid the device has never synced reads as `initialPushDone = false`
     * and takes *every* row — so the initial push and the leak are the same code path.
     *
     * [REFUSE] is a refusal to act, not a decision about what should happen. What the app
     * should *offer* a student in that state — take the local attendance into the new
     * account, leave it behind, refuse the sign-in, or something else — is unsettled; see
     * the Stage C audit's product decisions. Refusing rests on the one reading that needs no
     * product answer: it writes nothing to the cloud, deletes nothing locally, and is undone
     * by signing back in.
     *
     * An empty [linkedUserId] is not read as "no account" — deciding whether a session exists
     * is the caller's business, and it has already done so. It is simply a uid that does not
     * match the owner.
     *
     * ### A terminated claim: [QUARANTINE_NEW_LIFECYCLE], not [REFUSE]
     *
     * A newly authenticated account after the *previous* account was deleted is not the
     * empty-owner case and not the live-mismatch case either. It is a third case, and it is
     * the one the owner row's `terminated` flag exists to name:
     *
     *  - **[REFUSE] would be safe but wrong.** The leak is closed, and the student is left
     *    with a sync that never completes and a prompt asking them to approve replacing an
     *    account that no longer exists — a question with no true answer, about data whose
     *    owner cannot be reached to answer it.
     *  - **[CLAIM] would be unsafe.** B's initial push takes *every* local row, so A's
     *    attendance would be uploaded as B's — the leak this whole function exists to stop.
     *  - **[QUARANTINE_NEW_LIFECYCLE] is the one that is both.** The incoming account is
     *    treated as a genuinely new lifecycle: A's body is set aside (never uploaded under
     *    B), and B claims the emptied phone and starts its own history. See
     *    [com.attendo.data.sync.AttendanceSyncStore.releaseTerminatedClaim] for what "set
     *    aside" does to the rows.
     *
     * Termination is keyed on the *claim*, never on the incoming account: a terminated claim
     * for A is quarantined for **any** uid that is not A. There is no uid that may inherit it,
     * because a claim is one device's single body of attendance and its owner is gone — not
     * because B in particular is suspect.
     *
     * The `owner.userId == linkedUserId` arm is checked first and is unreachable after a
     * deletion (a uid the server has established is gone cannot authenticate again; Supabase
     * does not recycle a user id). It stays first because it is the accurate reading if it is
     * ever reached — the same uid *is* the same account, and no cross-account move is
     * possible in that direction — and because a terminated flag must not be able to make a
     * claim unusable for the one account it actually belongs to.
     */
    fun ownershipVerdict(owner: LocalAttendanceOwner?, linkedUserId: String): OwnershipVerdict = when {
        owner == null -> OwnershipVerdict.CLAIM
        owner.userId == linkedUserId -> OwnershipVerdict.PROCEED
        owner.terminated -> OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE
        else -> OwnershipVerdict.REFUSE
    }

    /**
     * Whether a pending tombstone recorded for [tombstoneUserId] may be pushed by
     * [accountUserId].
     *
     * Deletions are the sharpest edge of the account boundary, because a tombstone is a
     * *write* that carries a cloud id: pushing one account's deletion under another account
     * would reach into that other account's rows and remove one. So the rule is stated
     * here, as a function, rather than living only in the `WHERE` clause of the query that
     * happens to read them — the query is the database's guard, this is the one the sync
     * path applies to the rows it has in hand, and the tests pin this one.
     *
     * An empty [tombstoneUserId] is an *unowned* tombstone — recorded before any account
     * claimed this device's attendance — and is pushable by whoever owns it now. In
     * practice this case is unreachable for rows that have a cloud id (identity is assigned
     * at push time, and claiming precedes pushing, so a row with a cloud id belonged to an
     * account by the time it could be deleted), but the rule states it rather than relying
     * on that argument holding forever.
     *
     * The load-bearing case is the one the account boundary turns on:
     * `tombstonePushableBy("A", "B")` is **false**. A deleted account's tombstones are
     * already scoped to it by [com.attendo.data.db.AttendanceSyncTombstoneEntity.userId],
     * and this is the assertion that says so out loud.
     */
    fun tombstonePushableBy(tombstoneUserId: String, accountUserId: String): Boolean =
        tombstoneUserId.isEmpty() || tombstoneUserId == accountUserId

    /**
     * Whether the owner-row marker names [linkedUserId] as the account whose
     * unsigned edits this device still holds.
     *
     * Null is not "edits for everyone": a device with no pending unsigned work,
     * or one whose owner was never claimed, must not surface
     * [AccountTransition.SAME_ACCOUNT_RETURN] as a reconcile prompt. A marker
     * for a different uid is likewise not this account's — account B signing in
     * on a phone whose unsigned edits belong to A is a switch, not a return.
     */
    fun loggedOutEditsBelongTo(marker: String?, linkedUserId: String): Boolean =
        marker != null && marker == linkedUserId

    /**
     * What one table's read came to, in the terms the cursor decision needs.
     *
     * [firstDeferred] is the first revision whose row could not be applied — a pattern
     * whose course this device does not have yet — and it is a wall: the cursor may not
     * pass it, or the row would never be read again. [exhausted] says the read reached the
     * end of that table's changes, which is what makes the table stop constraining the
     * cursor at all.
     */
    data class TableProgress(
        val highestApplied: Long?,
        val firstDeferred: Long?,
        val exhausted: Boolean,
    )

    /**
     * Where the next pull starts, given what a pass over *every* table managed to apply.
     *
     * One cursor covers four tables sharing one sequence, and that is the whole difficulty:
     * a device that read `semesters` up to revision 500 has not thereby read `courses` up to
     * 500, and advancing the shared cursor on the strength of one table would step over
     * every row the other tables hold below it — silently, and with nothing left to come
     * back to. So the cursor may only move to a revision every table has covered.
     *
     * Each table's own limit is the lower of two things:
     *
     *  * the revision one below its first deferred row, if it had one;
     *  * otherwise, if it read to the end, no limit at all — it has no more rows to offer;
     *  * otherwise the highest revision it applied, because the pass stopped early there
     *    and cannot vouch for anything above it.
     *
     * The cursor is the smallest of those, and it never moves backwards: a pass that could
     * not get past where the last one finished re-reads the same rows, which is safe,
     * rather than skipping them, which is not.
     */
    fun cursorAfterPass(current: Long, tables: List<TableProgress>): Long {
        if (tables.isEmpty()) return current
        val limit = tables.minOf { table ->
            table.firstDeferred?.minus(1L)
                ?: if (table.exhausted) Long.MAX_VALUE else table.highestApplied ?: current
        }
        val highest = tables.mapNotNull { it.highestApplied }.maxOrNull() ?: return current
        return maxOf(current, minOf(limit, highest))
    }

    /**
     * Why a table's read stopped, and therefore whether reading it again could tell this
     * device anything it does not already hold.
     *
     * This exists because a pass has two different reasons to stop, and only one of them is
     * an answer about the table:
     *
     *  * **Read to the end.** There is nothing below; a second read returns nothing. Settled.
     *  * **Stopped at a deferred row.** A child whose parent this device does not hold. A
     *    second read returns the same rows and stops at the same one — the parent is not
     *    something this pass can produce, because the tables are read parent-first and the
     *    parent's own read has already been through. Settled *for this pass*, and picked up
     *    by the next one, which no longer has the excuse.
     *  * **Ran out of pages.** The allowance in
     *    [com.attendo.data.sync.AttendanceSyncEngine] stopped the read with rows still below
     *    it, and no answer has been reached about the rest of the table. **Not** settled.
     *
     * The distinction is what keeps a manual sync honest. A student pressing Sync now is told
     * the account is up to date; if the pass stopped because it had read its page allowance
     * rather than because it had read the table, that sentence is about the prefix of the
     * account that happened to fit in one pass, which is not the question they asked. The
     * caller reads an unfinished table as "read again", and only reports the account settled
     * once a read comes back with every table settled.
     *
     * An empty list is finished: there is no table that stopped early.
     */
    fun readIsFinished(tables: List<TableProgress>): Boolean =
        tables.all { it.exhausted || it.firstDeferred != null }

    /**
     * RFC 4122 version 5 (SHA-1, name-based) UUID.
     *
     * `java.util.UUID.nameUUIDFromBytes` is version 3 (MD5) and hashes the name alone, with
     * no namespace — neither of which is what migration 0020 asks for, so the derivation is
     * written out here rather than borrowed.
     */
    private fun uuidV5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespace.mostSignificantBits.toBytes())
        digest.update(namespace.leastSignificantBits.toBytes())
        digest.update(name.toByteArray(StandardCharsets.UTF_8))
        val hash = digest.digest()

        // Version 5 in the high nibble of byte 6, RFC 4122 variant in the top two bits of byte 8.
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()

        var most = 0L
        var least = 0L
        for (index in 0..7) most = (most shl 8) or (hash[index].toLong() and 0xFF)
        for (index in 8..15) least = (least shl 8) or (hash[index].toLong() and 0xFF)
        return UUID(most, least)
    }

    private fun Long.toBytes(): ByteArray = ByteArray(8) { index ->
        (this ushr (56 - index * 8)).toByte()
    }
}
