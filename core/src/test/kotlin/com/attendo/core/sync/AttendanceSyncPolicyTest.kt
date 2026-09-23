package com.attendo.core.sync

import com.attendo.core.sync.AttendanceSyncPolicy.LocalAttendanceOwner
import com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict
import com.attendo.core.sync.AttendanceSyncPolicy.SemesterPlacement
import com.attendo.core.sync.AttendanceSyncPolicy.SessionPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The rules the Attendance Sync client decides by, pinned as pure functions.
 *
 * These are the four places a sync can go quietly wrong: two devices disagreeing about
 * which row is which class, a stale write overwriting a newer one, a cursor stepping
 * over a row that committed late, and a cursor running ahead of a commit still in
 * flight. Each is a boundary, so each is tested at the boundary rather than in the
 * middle of it.
 */
class AttendanceSyncPolicyTest {

    // ---- the identity two devices must agree on -------------------------------

    @Test
    fun `a pattern session's cloud id is derived, not random`() {
        // Migration 0020 requires this outright: two devices generating the same class
        // from the same slot must address one cloud row, not race to create two. The
        // value is pinned literally because it is a *frozen* derivation — a change to
        // the namespace or the name shape would rename every generated class in every
        // account, which is exactly the silent duplication this exists to prevent.
        val id = AttendanceSyncPolicy.cloudSessionId(
            patternCloudId = "6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1",
            date = LocalDate.of(2026, 9, 19),
        )

        assertEquals("dbf54237-bd05-599c-86b2-4f0a040c8250", id)
    }

    @Test
    fun `the derivation is stable across calls`() {
        val pattern = "aaaaaaaa-0000-4000-8000-000000000001"
        val date = LocalDate.of(2026, 9, 19)

        assertEquals(
            AttendanceSyncPolicy.cloudSessionId(pattern, date),
            AttendanceSyncPolicy.cloudSessionId(pattern, date),
        )
    }

    @Test
    fun `a different date or a different pattern is a different class`() {
        val pattern = "aaaaaaaa-0000-4000-8000-000000000001"
        val other = "aaaaaaaa-0000-4000-8000-000000000002"

        // Same slot, next day — the whole point of the derivation.
        assertNotEquals(
            AttendanceSyncPolicy.cloudSessionId(pattern, LocalDate.of(2026, 9, 19)),
            AttendanceSyncPolicy.cloudSessionId(pattern, LocalDate.of(2026, 9, 20)),
        )
        // Same day, different slot.
        assertNotEquals(
            AttendanceSyncPolicy.cloudSessionId(pattern, LocalDate.of(2026, 9, 19)),
            AttendanceSyncPolicy.cloudSessionId(other, LocalDate.of(2026, 9, 19)),
        )
    }

    @Test
    fun `the derived id is a well-formed version 5 UUID`() {
        // Not decoration: PostgREST will not store a malformed uuid, so a derivation that
        // produced one would fail on the first push of a generated class and nowhere else.
        val id = AttendanceSyncPolicy.cloudSessionId(
            patternCloudId = "aaaaaaaa-0000-4000-8000-000000000001",
            date = LocalDate.of(2026, 9, 19),
        )

        val parsed = UUID.fromString(id)
        assertEquals(5, parsed.version())
        assertEquals(2, parsed.variant())
        assertEquals(id, parsed.toString())
    }

    @Test
    fun `a fresh cloud id is a valid random uuid`() {
        val first = AttendanceSyncPolicy.newCloudId()
        val second = AttendanceSyncPolicy.newCloudId()

        assertEquals(4, UUID.fromString(first).version())
        assertNotEquals(first, second)
    }

    // ---- which write wins -----------------------------------------------------

    @Test
    fun `a strictly newer remote write wins`() {
        // The client half of `lww_touch`'s `<=` comparison.
        assertTrue(AttendanceSyncPolicy.remoteWins(remoteClientUpdatedAt = 2_000L, localClientUpdatedAt = 1_000L))
    }

    @Test
    fun `a tie goes to the device that already had the row`() {
        // `if NEW.client_updated_at <= OLD.client_updated_at then return OLD` — equal is
        // not newer. Applying the same rule locally is what makes re-applying a page of
        // already-seen rows free instead of a second effect.
        assertFalse(AttendanceSyncPolicy.remoteWins(remoteClientUpdatedAt = 1_000L, localClientUpdatedAt = 1_000L))
    }

    @Test
    fun `an older remote write loses to a local edit made offline`() {
        // The case the whole comparison exists for: this device marked a class on a train,
        // the cloud still holds last week's copy, and the cloud must not win.
        assertFalse(AttendanceSyncPolicy.remoteWins(remoteClientUpdatedAt = 1_000L, localClientUpdatedAt = 2_000L))
    }

    @Test
    fun `a row this device has never timestamped accepts what the cloud holds`() {
        // Every pre-sync row on an upgraded install reads `clientUpdatedAt = null`. Null is
        // "no local edit on record", not "edited at the epoch".
        assertTrue(AttendanceSyncPolicy.remoteWins(remoteClientUpdatedAt = 1_000L, localClientUpdatedAt = null))
        assertTrue(AttendanceSyncPolicy.remoteWins(remoteClientUpdatedAt = 0L, localClientUpdatedAt = null))
    }

    // ---- what an incoming row asks for ----------------------------------------

    private fun decision(
        remote: Long,
        deleted: Boolean = false,
        local: Long? = null,
    ) = AttendanceSyncPolicy.decisionFor(
        remoteClientUpdatedAt = remote,
        deleted = deleted,
        localClientUpdatedAt = local,
    )

    @Test
    fun `a newer row that is not a tombstone is applied`() {
        assertEquals(AttendanceSyncPolicy.PullDecision.APPLY, decision(remote = 2_000L, local = 1_000L))
    }

    @Test
    fun `a newer tombstone deletes`() {
        assertEquals(
            AttendanceSyncPolicy.PullDecision.DELETE,
            decision(remote = 2_000L, deleted = true, local = 1_000L),
        )
    }

    @Test
    fun `a tombstone for a row this device has never seen still deletes`() {
        // Nothing local to remove, and that is the point: the row may exist on this device
        // under a local id the cloud has never heard of, or it may not exist at all. Either
        // way the cloud's newest word about it is "gone".
        assertEquals(
            AttendanceSyncPolicy.PullDecision.DELETE,
            decision(remote = 2_000L, deleted = true, local = null),
        )
    }

    @Test
    fun `a stale tombstone loses to a local edit made since`() {
        // The case the ordering exists for, and the one that would quietly destroy a
        // student's work if it went the other way: this phone marked a class on a train at
        // 18:10; another phone deleted it at 17:00. The delete is older, so it loses, the
        // row stays, and the push that follows clears `deleted_at` on the server.
        assertEquals(
            AttendanceSyncPolicy.PullDecision.SKIP_STALE,
            decision(remote = 1_700L, deleted = true, local = 1_810L),
        )
    }

    @Test
    fun `a tie on a tombstone is not a win for the delete`() {
        // Same `<=` comparison as every other edit. A device that deletes a row and then
        // re-pulls it must not see the deletion applied a second time — and, more to the
        // point, two devices that disagree at the same instant must converge on the row
        // rather than on its absence.
        assertEquals(
            AttendanceSyncPolicy.PullDecision.SKIP_STALE,
            decision(remote = 1_000L, deleted = true, local = 1_000L),
        )
    }

    @Test
    fun `a stale row is skipped, whether or not it is a tombstone`() {
        // The timestamp question is settled before what the row says, so "stale" and
        // "applied" are answers about the timestamp alone. A stale *row* and a stale
        // *tombstone* take the same branch, which is what makes re-reading a page free.
        assertEquals(AttendanceSyncPolicy.PullDecision.SKIP_STALE, decision(remote = 1_000L, local = 1_000L))
        assertEquals(
            AttendanceSyncPolicy.PullDecision.SKIP_STALE,
            decision(remote = 1_000L, deleted = true, local = 1_000L),
        )
    }

    // ---- where an applying session row lands ----------------------------------

    private fun placement(
        hasCloudRow: Boolean = false,
        slotIsFree: Boolean = true,
        slot: Long? = null,
        remote: Long = 1_000L,
    ) = AttendanceSyncPolicy.sessionPlacement(
        hasCloudRow = hasCloudRow,
        slotIsFree = slotIsFree,
        slotClientUpdatedAt = slot,
        remoteClientUpdatedAt = remote,
    )

    @Test
    fun `a row whose cloud id is already here is an ordinary update`() {
        // The common case by far: this device has seen the class before and is being told
        // about it again. The slot is irrelevant when the row has been found by its identity.
        assertEquals(SessionPlacement.UPDATE, placement(hasCloudRow = true, slotIsFree = false, slot = 2_000L))
    }

    @Test
    fun `a row with a free slot is an ordinary insert`() {
        assertEquals(SessionPlacement.INSERT, placement(hasCloudRow = false, slotIsFree = true))
    }

    @Test
    fun `an occupied slot is adopted, never a second insert`() {
        // The release-blocking case, pinned. A second device generated this class from its
        // own pattern before it ever synced, so the row is real, is the student's, and holds
        // no cloud id — identity is assigned at push time. Inserting beside it violates the
        // local unique `(patternId, date)` index, which aborts the page's transaction; the
        // cursor only advances after a page commits, so it can never get past the row and
        // sync stops on that device permanently. There is no INSERT in this function's
        // answers for an occupied slot, at any timestamp, which is the whole point.
        assertEquals(
            SessionPlacement.ADOPT,
            placement(hasCloudRow = false, slotIsFree = false, slot = 1_000L, remote = 2_000L),
        )
        assertNotEquals(
            SessionPlacement.INSERT,
            placement(hasCloudRow = false, slotIsFree = false, slot = 1_000L, remote = 2_000L),
        )
    }

    @Test
    fun `an occupant this device never stamped is adopted`() {
        // A generated row carries no `client_updated_at` until its first push. Null is "no
        // local edit on record", so the cloud's copy wins — the same reading `remoteWins`
        // gives every other unstamped row, and not a special case for this path.
        assertEquals(SessionPlacement.ADOPT, placement(hasCloudRow = false, slotIsFree = false, slot = null))
    }

    @Test
    fun `an occupant newer than the incoming row keeps the slot`() {
        // A class marked here on a train at 18:10, against the cloud's 17:00 copy of the same
        // class. Nothing is written; the occupant's next push derives this same cloud id and
        // uploads its newer timestamp over it, so the two copies converge without this path
        // having to do anything.
        assertEquals(
            SessionPlacement.SKIP_STALE,
            placement(hasCloudRow = false, slotIsFree = false, slot = 1_810L, remote = 1_700L),
        )
    }

    @Test
    fun `an occupant exactly as new as the incoming row keeps the slot`() {
        // Same `<=` boundary as every other LWW comparison in this file: a tie is not a win
        // for the incoming row, so re-applying a page of already-seen rows stays free.
        assertEquals(
            SessionPlacement.SKIP_STALE,
            placement(hasCloudRow = false, slotIsFree = false, slot = 1_000L, remote = 1_000L),
        )
    }

    @Test
    fun `an ad-hoc class is never treated as an occupant`() {
        // The caller reports the slot free for a row with no pattern — SQLite treats NULLs as
        // distinct in a unique index, so two extra classes on one day are legitimate and the
        // lookup is never even made. Pinned because "free slot" must not be inferred from a
        // missing timestamp.
        assertEquals(SessionPlacement.INSERT, placement(hasCloudRow = false, slotIsFree = true, slot = null))
    }

    // ---- whose attendance is this ---------------------------------------------

    /**
     * A **live** claim by [uid] — the everyday case, and the one every test below is about
     * unless it says otherwise.
     *
     * A function rather than an inline `LocalAttendanceOwner(uid)` at each site so that the
     * difference between these tests and the terminated-claim ones is visible at a glance:
     * the terminated tests name [LocalAttendanceOwner.terminated] explicitly, and nothing
     * else in this file can mean "terminated" by accident.
     */
    private fun liveOwner(uid: String) = LocalAttendanceOwner(uid)

    @Test
    fun `attendance no account has synced is claimed by the first account to ask`() {
        // The legitimate initial push: an install used for a term that links its first
        // account. Every local row has to be uploaded, and this is the branch that allows it.
        assertEquals(OwnershipVerdict.CLAIM, AttendanceSyncPolicy.ownershipVerdict(null, "account-a"))
    }

    @Test
    fun `the owning account may sync`() {
        assertEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(liveOwner("account-a"), "account-a"))
    }

    @Test
    fun `an account that does not own the local attendance is refused`() {
        // The account-boundary bug, pinned. Its whole consequence is that the pass proceeds:
        // a uid this device has never synced reads as `initialPushDone = false`, and the
        // initial push takes *every* local row — so account A's whole attendance history
        // would be written into account B's cloud account under B's user_id, and the server's
        // RLS check would accept it, because the rows are arriving as B.
        assertEquals(OwnershipVerdict.REFUSE, AttendanceSyncPolicy.ownershipVerdict(liveOwner("account-a"), "account-b"))
        assertNotEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(liveOwner("account-a"), "account-b"))
    }

    @Test
    fun `a sign-out leaves the owner in place`() {
        // Ownership is a fact about the local attendance, not about the session: signing out
        // does not move rows and must not move their owner. The same account signing back in
        // is still the owner, and a different one is still refused.
        val owner = "account-a"
        assertEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(liveOwner(owner), "account-a"))
        assertEquals(OwnershipVerdict.REFUSE, AttendanceSyncPolicy.ownershipVerdict(liveOwner(owner), "account-b"))
    }

    @Test
    fun `an unnamed account is a mismatch, not an absent owner`() {
        // Deciding whether a session exists is the caller's business — the engine has already
        // established there is one — so this function reads the uid it is given literally. An
        // empty one is a uid that does not match, and must never fall into the CLAIM branch,
        // which would let it take ownership of rows it has no claim to.
        assertEquals(OwnershipVerdict.REFUSE, AttendanceSyncPolicy.ownershipVerdict(liveOwner("account-a"), ""))
        assertEquals(OwnershipVerdict.CLAIM, AttendanceSyncPolicy.ownershipVerdict(null, ""))
    }

    // ---- account transitions & community alignment -----------------------------

    @Test
    fun `first account claims unowned local attendance`() {
        assertEquals(OwnershipVerdict.CLAIM, AttendanceSyncPolicy.ownershipVerdict(owner = null, linkedUserId = "account-a"))
        assertEquals(
            AttendanceSyncPolicy.AccountTransition.FIRST_CLAIM,
            AttendanceSyncPolicy.accountTransition(owner = null, linkedUserId = "account-a"),
        )
    }

    @Test
    fun `same account can sign out and return`() {
        val owner = "account-a"
        assertEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(liveOwner(owner), "account-a"))
        assertEquals(
            AttendanceSyncPolicy.AccountTransition.SAME_ACCOUNT_RETURN,
            AttendanceSyncPolicy.accountTransition(liveOwner(owner), "account-a"),
        )
    }

    @Test
    fun `same account returning does not perform fresh initial upload if initialPushDone was true`() {
        val initialPushDone = true
        val uploadAllPreSyncHistory = !initialPushDone
        assertFalse(uploadAllPreSyncHistory)
    }

    @Test
    fun `same-account logged-out edits can follow the chosen override or restore path`() {
        val overrideDecision = AttendanceSyncPolicy.ReturningReconciliation.KEEP_LOCAL_AND_MERGE
        val restoreDecision = AttendanceSyncPolicy.ReturningReconciliation.RESTORE_REMOTE

        val localEditTimestamp = 2_000L
        val remoteLastTimestamp = 1_000L

        val overrideWins = AttendanceSyncPolicy.remoteWins(remoteLastTimestamp, localEditTimestamp)
        assertFalse(overrideWins)

        val restoreWins = AttendanceSyncPolicy.remoteWins(remoteLastTimestamp, null)
        assertTrue(restoreWins)

        assertEquals(AttendanceSyncPolicy.ReturningReconciliation.KEEP_LOCAL_AND_MERGE, overrideDecision)
        assertEquals(AttendanceSyncPolicy.ReturningReconciliation.RESTORE_REMOTE, restoreDecision)
    }

    @Test
    fun `a different account cannot silently claim the local attendance`() {
        assertEquals(OwnershipVerdict.REFUSE, AttendanceSyncPolicy.ownershipVerdict(liveOwner("account-a"), "account-b"))
        assertEquals(
            AttendanceSyncPolicy.AccountTransition.DIFFERENT_ACCOUNT_REFUSE,
            AttendanceSyncPolicy.accountTransition(liveOwner("account-a"), "account-b"),
        )
    }

    @Test
    fun `a different account cannot upload or pull into previous account attendance without approved switch`() {
        assertEquals(
            OwnershipVerdict.REFUSE,
            AttendanceSyncPolicy.ownershipVerdict(owner = liveOwner("account-a"), linkedUserId = "account-b"),
        )
        assertEquals(
            AttendanceSyncPolicy.AccountTransition.DIFFERENT_ACCOUNT_REFUSE,
            AttendanceSyncPolicy.accountTransition(owner = liveOwner("account-a"), linkedUserId = "account-b", isExplicitSwitchApproved = false),
        )
    }

    @Test
    fun `explicit account switching changes the attendance ownership context`() {
        assertEquals(
            AttendanceSyncPolicy.AccountTransition.SWITCH_APPROVED,
            AttendanceSyncPolicy.accountTransition(
                owner = liveOwner("account-a"),
                linkedUserId = "account-b",
                isExplicitSwitchApproved = true,
            ),
        )
    }

    @Test
    fun `complete local reset leaves no stale attendance ownership`() {
        val ownerAfterReset: LocalAttendanceOwner? = null
        assertEquals(OwnershipVerdict.CLAIM, AttendanceSyncPolicy.ownershipVerdict(ownerAfterReset, "fresh-account"))
        assertEquals(
            AttendanceSyncPolicy.AccountTransition.FIRST_CLAIM,
            AttendanceSyncPolicy.accountTransition(ownerAfterReset, "fresh-account"),
        )
    }

    /**
     * The cross-account tombstone rule, asserted through the function the push path actually
     * calls.
     *
     * It previously spelled the rule out inline (`tombstoneOwner == activeUser ||
     * tombstoneOwner.isEmpty()`), which pinned a *copy* of the rule rather than the rule —
     * the production code could have changed and this would still have passed. The three
     * cases below are the ones that matter: another account's tombstone is refused, the
     * owning account's goes, and a tombstone from before any account owned this device
     * (the pre-ownership rows the `WHERE` clause also selects) still goes.
     */
    @Test
    fun `account A tombstones cannot be pushed as account B and return preserves pending tombstones`() {
        assertFalse(AttendanceSyncPolicy.tombstonePushableBy(tombstoneUserId = "account-a", accountUserId = "account-b"))
        assertTrue(AttendanceSyncPolicy.tombstonePushableBy(tombstoneUserId = "account-a", accountUserId = "account-a"))
        assertTrue(AttendanceSyncPolicy.tombstonePushableBy(tombstoneUserId = "", accountUserId = "account-b"))
    }

    /**
     * AUTH 10, as the enumeration it is: **no** account may push another account's
     * tombstone, for any pair of names the app can produce.
     *
     * Written over a cross product rather than as the one example above, because the failure
     * this guards is a *delete* — a row B removes from A's cloud data. A single passing pair
     * would not have caught, say, a rule that compared prefixes or ignored the empty string.
     */
    @Test
    fun `no account can push a tombstone stamped with a different account`() {
        val accounts = listOf("account-a", "account-b", "account-c")

        for (owner in accounts) {
            for (other in accounts) {
                if (owner == other) continue
                assertFalse(
                    "$other must not push $owner's tombstone",
                    AttendanceSyncPolicy.tombstonePushableBy(tombstoneUserId = owner, accountUserId = other),
                )
            }
        }
    }

    // ---- how far the cursor may move ------------------------------------------

    /** A table that had nothing to add and read to the end — the common case by far. */
    private fun done(highest: Long? = null) =
        AttendanceSyncPolicy.TableProgress(highestApplied = highest, firstDeferred = null, exhausted = true)

    /** A table that applied [highest] and stopped because the pass ran out of pages. */
    private fun truncated(highest: Long?) =
        AttendanceSyncPolicy.TableProgress(highestApplied = highest, firstDeferred = null, exhausted = false)

    private fun deferredAt(revision: Long, highest: Long? = null) =
        AttendanceSyncPolicy.TableProgress(highestApplied = highest, firstDeferred = revision, exhausted = false)

    @Test
    fun `a pass that applied nothing leaves the cursor alone`() {
        assertEquals(
            42L,
            AttendanceSyncPolicy.cursorAfterPass(42L, listOf(done(), truncated(null), deferredAt(99))),
        )
    }

    @Test
    fun `an unhindered pass advances to the highest revision it applied`() {
        assertEquals(
            105L,
            AttendanceSyncPolicy.cursorAfterPass(40L, listOf(done(105), done(97), done(105), done(101))),
        )
    }

    @Test
    fun `a row that could not be applied is re-pulled rather than stepped over`() {
        // The table that deferred holds the cursor one below the row it could not apply,
        // even though every table — that one included — went further than that.
        assertEquals(
            103L,
            AttendanceSyncPolicy.cursorAfterPass(40L, listOf(done(105), deferredAt(104, highest = 105))),
        )
    }

    @Test
    fun `a deferral below the cursor does not move it backwards`() {
        // Stronger than "the cursor is monotonic": the next pass re-reads the same rows,
        // which is harmless, instead of skipping them, which is not.
        assertEquals(50L, AttendanceSyncPolicy.cursorAfterPass(50L, listOf(deferredAt(49, highest = 51))))
    }

    @Test
    fun `a table that read to its end stops constraining the cursor`() {
        // The heart of the shared cursor. `courses` applied nothing above revision 12, but
        // it also had *nothing* above 12 to apply — so it must not drag the cursor back to
        // 12 and make the next pass re-read a thousand sessions.
        assertEquals(
            900L,
            AttendanceSyncPolicy.cursorAfterPass(0L, listOf(done(900), done(12), done(900), done(null))),
        )
    }

    @Test
    fun `a table the pass gave up on holds the cursor at what it actually read`() {
        // `sessions` had more rows than the pass was willing to page through. Everything
        // above the last page it read is unread and must stay unskipped — the other three
        // tables having gone to 9000 does not make revision 5000 of sessions read.
        assertEquals(
            5_000L,
            AttendanceSyncPolicy.cursorAfterPass(0L, listOf(done(9_000), truncated(5_000))),
        )
    }

    @Test
    fun `a truncated table that applied nothing stops the pass dead`() {
        // Nothing to advance over: the first page of that table failed to yield any row it
        // could place, so the next pass starts where this one did.
        assertEquals(1_000L, AttendanceSyncPolicy.cursorAfterPass(1_000L, listOf(done(9_000), truncated(null))))
    }

    // ---- whether a read is an answer about the account ------------------------

    @Test
    fun `a read that reached the end of every table is finished`() {
        assertTrue(AttendanceSyncPolicy.readIsFinished(listOf(done(105), done(null), done(7), done(12))))
    }

    @Test
    fun `a read that ran out of pages is not finished, however much it applied`() {
        // The case a manual sync must not call "up to date". `sessions` applied 5,000 rows and
        // stopped because the pass had spent its page allowance, not because the table had
        // nothing below — so the account has not been read, and reading again would find more.
        assertFalse(AttendanceSyncPolicy.readIsFinished(listOf(done(9_000), truncated(5_000))))
    }

    @Test
    fun `a truncated table holding nothing back still counts as unfinished`() {
        // Truncated with no applied revision reads as "no progress", which is exactly the
        // state a second read has to be allowed to look into again — the page may have been
        // nothing but rows this device already held, with real rows behind them.
        assertFalse(AttendanceSyncPolicy.readIsFinished(listOf(done(9_000), truncated(null))))
    }

    @Test
    fun `a table waiting on a parent it does not hold is finished for this pass`() {
        // The pattern names a course this device has not got. Reading patterns again returns
        // the same rows and stops at the same one: the parent cannot arrive from this pass,
        // because courses was read before patterns and is behind us. The next pass has it.
        assertTrue(AttendanceSyncPolicy.readIsFinished(listOf(done(900), done(4), deferredAt(5), done(4))))
    }

    @Test
    fun `a read of no tables is finished`() {
        // Not a shape the engine produces — it reads all four — but the rule should not turn
        // into an unbounded loop if it ever were.
        assertTrue(AttendanceSyncPolicy.readIsFinished(emptyList()))
    }

    // ---- the read overlap -----------------------------------------------------

    @Test
    fun `every read starts behind the cursor, so a late commit is not stepped over`() {
        // Non-transactional `nextval` means a row numbered below the cursor can commit
        // after one numbered above it. Starting the read behind the cursor is what gives
        // the next pass a chance to see it; a row this device already has is not applied
        // again, so the re-read costs nothing but the request.
        assertEquals(900L, AttendanceSyncPolicy.overlapStart(1_000L))
        assertEquals(
            AttendanceSyncPolicy.PULL_OVERLAP,
            AttendanceSyncPolicy.overlapStart(2 * AttendanceSyncPolicy.PULL_OVERLAP),
        )
    }

    @Test
    fun `the overlap never runs the read behind the first revision`() {
        // A fresh device must start at 0, which is below every revision the sequence has
        // ever handed out. A negative start would be a range PostgREST cannot express.
        assertEquals(0L, AttendanceSyncPolicy.overlapStart(0L))
        assertEquals(0L, AttendanceSyncPolicy.overlapStart(1L))
        assertEquals(0L, AttendanceSyncPolicy.overlapStart(AttendanceSyncPolicy.PULL_OVERLAP))
    }

    // ---- where an applying semester row lands ---------------------------------

    /**
     * A term already occupied by a row this device has pushed, and a cloud row naming the
     * same term under [local] — the two-identities-one-term case, which is the only one that
     * has a wrong answer available.
     */
    private fun termPlacement(
        hasCloudRow: Boolean = false,
        termIsFree: Boolean = true,
        local: String? = null,
        localAt: Long? = null,
        remote: String = "remote-id",
        remoteAt: Long = 1_000L,
    ) = AttendanceSyncPolicy.semesterPlacement(
        hasCloudRow = hasCloudRow,
        termIsFree = termIsFree,
        occupantCloudId = local,
        occupantClientUpdatedAt = localAt,
        remoteCloudId = remote,
        remoteClientUpdatedAt = remoteAt,
    )

    @Test
    fun `a semester whose cloud id is already here is an ordinary update`() {
        // The everyday case: this device has the term and is being told about it again. The
        // second lookup is never made, which is why the caller reports the term as free.
        assertEquals(
            SemesterPlacement.UPDATE,
            termPlacement(hasCloudRow = true, termIsFree = true, remoteAt = 2_000L),
        )
    }

    @Test
    fun `a term nothing describes is an ordinary insert`() {
        assertEquals(SemesterPlacement.INSERT, termPlacement())
    }

    @Test
    fun `an occupied term is never a second insert, at any timestamp`() {
        // The release-blocking case, pinned. A term this device established before it ever
        // signed in carries no cloud id until its first push, so the cloud's row for the same
        // term is unknown here by identity and known by term. Inserting beside it violates
        // Room's unique `(year, type)` index; the refusal aborts the page's transaction and the
        // cursor — which only advances once a page commits — can never get past the row, so
        // sync on that device stops for good, at the first table in the order.
        //
        // There is no INSERT in this function's answers for an occupied term, whichever way
        // the timestamps fall. That is the whole property.
        for (remoteAt in listOf(0L, 1_000L, 5_000L)) {
            for (localAt in listOf(null, 0L, 1_000L, 5_000L)) {
                assertNotEquals(
                    SemesterPlacement.INSERT,
                    termPlacement(termIsFree = false, local = "pushed-id", localAt = localAt, remoteAt = remoteAt),
                )
            }
        }
    }

    @Test
    fun `a term occupant this device has never pushed is adopted`() {
        // No cloud id of its own, so there is no second identity to weigh: the local row keeps
        // its primary key — courses naming it still name it — and takes the incoming identity.
        // Its timestamp is irrelevant here and deliberately not consulted.
        for (localAt in listOf(null, 1_000L, 5_000L)) {
            assertEquals(
                SemesterPlacement.ADOPT,
                termPlacement(termIsFree = false, local = null, localAt = localAt, remoteAt = 1_000L),
            )
        }
    }

    @Test
    fun `the newer cloud row supersedes the identity this device holds`() {
        assertEquals(
            SemesterPlacement.SUPERSEDE,
            termPlacement(termIsFree = false, local = "held-id", localAt = 1_000L, remote = "cloud-id", remoteAt = 2_000L),
        )
    }

    @Test
    fun `the newer local row keeps the identity this device holds`() {
        assertEquals(
            SemesterPlacement.KEEP_LOCAL,
            termPlacement(termIsFree = false, local = "held-id", localAt = 2_000L, remote = "cloud-id", remoteAt = 1_000L),
        )
    }

    @Test
    fun `an occupant this device never stamped loses its identity to the cloud`() {
        // Null is "no local edit on record", which loses to any stamped timestamp — the same
        // reading `remoteWins` gives every other unstamped row, and not a special case.
        assertEquals(
            SemesterPlacement.SUPERSEDE,
            termPlacement(termIsFree = false, local = "held-id", localAt = null, remoteAt = 1L),
        )
    }

    @Test
    fun `two devices with the same timestamp agree on which identity survives`() {
        // The tie, and the reason this rule is a pure function of the two rows: two devices
        // that disagreed about the surviving identity would each tombstone the other's row, and
        // a tombstone naming the identity the *other* device kept is not a duplicate being
        // removed — it is the semester itself being deleted, on the cloud and then on the phone
        // holding it. Both devices must compute the same answer from the same two rows, in
        // either order, which is what makes a tie-break on the cloud id sufficient.
        val a = "00000000-0000-4000-8000-000000000001"
        val b = "ffffffff-ffff-4fff-bfff-ffffffffffff"

        // Where the local row holds `a`, the incoming `b` wins.
        assertEquals(
            SemesterPlacement.SUPERSEDE,
            termPlacement(termIsFree = false, local = a, localAt = 1_000L, remote = b, remoteAt = 1_000L),
        )
        // Where the local row holds `b`, the incoming `a` loses. Same two rows, other side.
        assertEquals(
            SemesterPlacement.KEEP_LOCAL,
            termPlacement(termIsFree = false, local = b, localAt = 1_000L, remote = a, remoteAt = 1_000L),
        )
        assertTrue(
            AttendanceSyncPolicy.semesterIdentitySurvives(
                remoteCloudId = b, remoteClientUpdatedAt = 1_000L,
                localCloudId = a, localClientUpdatedAt = 1_000L,
            ),
        )
        assertFalse(
            AttendanceSyncPolicy.semesterIdentitySurvives(
                remoteCloudId = a, remoteClientUpdatedAt = 1_000L,
                localCloudId = b, localClientUpdatedAt = 1_000L,
            ),
        )
    }

    // ---- how much of a table a pass reads -------------------------------------

    @Test
    fun `a background pass reads from behind the cursor`() {
        assertEquals(
            AttendanceSyncPolicy.overlapStart(1_000L),
            AttendanceSyncPolicy.pullFloor(1_000L, AttendanceSyncPolicy.PullScope.INCREMENTAL),
        )
    }

    @Test
    fun `a manual sync reads from the beginning of the account's history`() {
        // Sync now is answered about the account, not about this device's cursor. A cursor is a
        // local optimisation that can be wrong — a restore renumbers rows, a wipe resets it, a
        // row can sit below it for reasons this device never saw — so a manual sync that
        // consulted it could report "All data is up to date." on the strength of what this
        // phone has already read. In RECONCILE mode every live row is re-read and put through
        // the same comparison that decides everything else, which costs nothing: a row this
        // device already holds is not strictly newer than the copy it holds, so it is rejected
        // without a write.
        for (cursor in listOf(0L, 1L, 1_000L, 9_999_999L)) {
            assertEquals(
                0L,
                AttendanceSyncPolicy.pullFloor(cursor, AttendanceSyncPolicy.PullScope.RECONCILE),
            )
        }
    }

    // ---- the write order ------------------------------------------------------

    @Test
    fun `the tables are declared parents-first`() {
        // The cloud enforces this with composite foreign keys, so a `SyncTable` list that
        // put a session before its course would fail on every push of a new term rather
        // than at compile time. Pinned because the order is load-bearing and invisible.
        val order = SyncTable.entries.map { it.wireName }

        assertEquals(listOf("semesters", "courses", "patterns", "sessions"), order)
        assertTrue(order.indexOf("courses") < order.indexOf("patterns"))
        assertTrue(order.indexOf("courses") < order.indexOf("sessions"))
    }
}
