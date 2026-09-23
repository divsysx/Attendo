package com.attendo.data.account

import com.attendo.core.auth.AccountProbe
import com.attendo.core.auth.AuthLifecyclePolicy
import com.attendo.core.auth.AuthLifecycleState
import com.attendo.core.auth.DeletionAnnouncement
import com.attendo.core.auth.OwnerAfterDeletion
import com.attendo.core.sync.AttendanceSyncPolicy
import com.attendo.core.sync.AttendanceSyncPolicy.AccountTransition
import com.attendo.core.sync.AttendanceSyncPolicy.LocalAttendanceOwner
import com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthSessionMissingException
import io.github.jan.supabase.exceptions.RestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * The two boundaries a deleted account has to cross, pinned where they actually are.
 *
 * [com.attendo.core.auth.AuthLifecyclePolicy] is pure and tested in `:core`. What it cannot
 * test is the half that lives here:
 *
 *  1. **The bridge from the library to the policy.** The policy rules on an [AccountProbe];
 *     the app produces one from whatever supabase-kt threw. If that mapping is wrong — an
 *     IOException read as a refusal, a wrapped cause not followed, a `RestException` reaching
 *     the policy with its status dropped — the policy is correct and the app still ends a
 *     student's session on a dropped connection. So this file constructs the *real* exception
 *     types and asserts what they become.
 *
 *  2. **The UID-A → deleted → UID-B transition**, end to end over the real ownership rules.
 *     The security invariant is that no row created under A may ever be uploaded or
 *     authenticated as B, and the whole of it comes down to a handful of decisions that are
 *     each one line of policy. This walks the exact sequence — A owns and has synced, A is
 *     deleted, B signs in — and asserts every one of them, because the failure mode is a
 *     silent cross-account *write* and nothing else would notice.
 *
 * Neither half needs a device, a network or a database: the exceptions are ordinary
 * constructible objects and the ownership rules are pure functions.
 */
class AccountLifecycleBoundaryTest {

    // ============================================================ the bridge

    /**
     * The literal the app rules on is the library's own — asserted, not assumed.
     *
     * `AuthLifecyclePolicy.USER_NOT_FOUND` is a string constant in `:core`, deliberately:
     * the policy must not depend on a Supabase type to stay reusable. The cost of that
     * decision is that a library upgrade could rename the code and the constant would go
     * quietly stale — every account would classify as "recoverable", the deletion handling
     * would stop firing, and nothing would fail. This is the assertion that fails instead.
     */
    @Test
    fun `the policy's codes are the library's own wire values`() {
        assertEquals(AuthLifecyclePolicy.USER_NOT_FOUND, AuthErrorCode.UserNotFound.value)
        assertEquals(AuthLifecyclePolicy.USER_BANNED, AuthErrorCode.UserBanned.value)
        assertEquals(AuthErrorCode.SessionNotFound.value, "session_not_found")
        assertEquals(AuthErrorCode.SessionExpired.value, "session_expired")
        assertEquals(AuthErrorCode.RefreshTokenNotFound.value, "refresh_token_not_found")
        assertEquals(AuthErrorCode.RefreshTokenAlreadyUsed.value, "refresh_token_already_used")

        // And every session-scoped code the policy names is one the library can actually
        // produce — a code that only exists in our set is a rule about nothing.
        val libraryCodes = setOf(
            AuthErrorCode.SessionNotFound.value,
            AuthErrorCode.SessionExpired.value,
            AuthErrorCode.RefreshTokenNotFound.value,
            AuthErrorCode.RefreshTokenAlreadyUsed.value,
        )
        assertEquals(libraryCodes, AuthLifecyclePolicy.SESSION_SCOPED_CODES)
    }

    /** AUTH 4, through the exception the library actually throws. */
    @Test
    fun `a deleted user reaches account-gone through the real exception`() {
        val thrown = AuthRestException(AuthErrorCode.UserNotFound.value, "User not found", 403)

        assertEquals(
            AccountProbe.Rejected(AuthLifecyclePolicy.USER_NOT_FOUND, 403),
            probeOf(thrown),
        )
        assertEquals(AuthLifecycleState.SERVER_ACCOUNT_GONE, AuthLifecyclePolicy.stateOf(probeOf(thrown)))
    }

    /** AUTH 3, through the real exception. */
    @Test
    fun `an expired session reaches a recoverable failure, never a deletion`() {
        val thrown = AuthRestException(AuthErrorCode.SessionExpired.value, "Session expired", 401)

        val state = AuthLifecyclePolicy.stateOf(probeOf(thrown))

        assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
        assertFalse(state.endsLocalAccountLifecycle)
        assertFalse(state.forbidsAuthenticatedWork)
    }

    /**
     * The base-type path matters as much as the typed one.
     *
     * `AuthRestException` extends `RestException`, so `probeOf` would still classify it
     * correctly if the typed branch were deleted — but a `RestException` that is *not* an
     * auth one reaches the policy only through that second branch, and it is the branch that
     * carries the status code. A 5xx arriving here as a bare `RestException` must still read
     * as the server declining to answer.
     */
    @Test
    fun `a bare rest exception still carries its code and status to the policy`() {
        val gone = RestException(AuthLifecyclePolicy.USER_NOT_FOUND, "User not found", 403, "")
        assertEquals(AuthLifecycleState.SERVER_ACCOUNT_GONE, AuthLifecyclePolicy.stateOf(probeOf(gone)))

        val broken = RestException(AuthLifecyclePolicy.USER_NOT_FOUND, "Service unavailable", 503, "")
        assertEquals(
            AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE,
            AuthLifecyclePolicy.stateOf(probeOf(broken)),
        )
    }

    /** AUTH 2, through the exception a phone on a train actually produces. */
    @Test
    fun `a transport failure reaches unavailability and preserves everything`() {
        for (thrown in listOf(
            IOException("connection reset"),
            UnknownHostException("no dns"),
            // The shape a wrapper produces — a transport failure one hop down. Reading only
            // the outermost type is how this would be lost.
            IllegalStateException("request failed", IOException("unreachable")),
        )) {
            val probe = probeOf(thrown)
            val state = AuthLifecyclePolicy.stateOf(probe)

            assertEquals("${thrown::class.simpleName} must be unreachable", AccountProbe.Unreachable, probe)
            assertEquals(AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE, state)
            // Both of these are the whole of "preserve local state and retry later".
            assertFalse(state.forbidsAuthenticatedWork)
            assertFalse(state.endsLocalAccountLifecycle)
        }
    }

    /**
     * A failure the app cannot identify is not a deletion.
     *
     * A parse error, a serialization failure, a bug — none of them is an answer from the
     * server about the account, and the app must reach the general "sign in again" path
     * rather than telling a student their account was removed.
     */
    @Test
    fun `an unrecognised failure is a recoverable refusal, never a deletion`() {
        for (thrown in listOf(
            IllegalStateException("could not parse the response body"),
            IllegalArgumentException("unexpected shape"),
            RuntimeException("something else"),
        )) {
            val state = AuthLifecyclePolicy.stateOf(probeOf(thrown))

            assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
            assertFalse(state.endsLocalAccountLifecycle)
        }
    }

    /**
     * Cancellation is checked before classification in [validateAccount], and this pins the
     * backstop: even if a cancelled call reached [probeOf], it cannot become a deletion.
     */
    @Test
    fun `a cancelled request never becomes a deletion`() {
        val state = AuthLifecyclePolicy.stateOf(probeOf(CancellationException("screen went away")))

        assertNotEquals(AuthLifecycleState.SERVER_ACCOUNT_GONE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    /**
     * AUTH 2/3/4 as the single property they are: **no throwable the app can produce reaches
     * [AuthLifecycleState.SERVER_ACCOUNT_GONE] except one naming `user_not_found`.**
     *
     * Asserted over a table of real exception objects rather than the policy's own inputs, so
     * the bridge and the rule are checked together — which is the only place a wrong answer
     * is actually reachable.
     */
    @Test
    fun `only a refusal naming user_not_found ends the lifecycle, whatever was thrown`() {
        val throwables: List<Throwable> = listOf(
            IOException("offline"),
            UnknownHostException("no dns"),
            IllegalStateException("wrapped", IOException("offline")),
            CancellationException("cancelled"),
            IllegalStateException("unparseable"),
            RestException("session_expired", "expired", 401, ""),
            RestException("session_not_found", "gone", 401, ""),
            RestException("refresh_token_not_found", "gone", 401, ""),
            RestException("refresh_token_already_used", "spent", 401, ""),
            RestException("user_banned", "banned", 403, ""),
            RestException("bad_jwt", "bad", 401, ""),
            RestException("", "", 400, ""),
            RestException("internal_server_error", "oops", 500, ""),
            RestException("user_not_found", "oops", 503, ""),
            AuthRestException("session_expired", "expired", 401),
            AuthRestException("user_banned", "banned", 403),
            AuthSessionMissingException(401),
        )

        val ending = throwables.filter { AuthLifecyclePolicy.stateOf(probeOf(it)).endsLocalAccountLifecycle }

        assertEquals(
            "Nothing in this table may end a student's lifecycle — every entry is either a " +
                "transport failure, a server fault, a session refusal or an unrecognised " +
                "failure, and none of them is evidence the account is gone.",
            emptyList<Throwable>(),
            ending,
        )
    }

    /**
     * The library's own exception whose **message says "deleted"** — and which is still not
     * evidence that the account is gone.
     *
     * `AuthSessionMissingException` extends `AuthRestException` and hard-codes
     * `session_not_found` with the description *"Session not found. This can happen if the
     * user was logged out or deleted."* It is the single most dangerous object in this
     * system: a message-only classifier would read it as a deletion and sign the student out
     * with an explanation that is wrong for the ordinary case (they were logged out
     * elsewhere, or their session was revoked) and unproven even in the deleted one.
     *
     * The classification is right for a reason worth naming: `probeOf` reads the *type* and
     * then the `error_code`, and never the description. This test exists so that a future
     * "helpful" improvement that greps the message fails here first. Its real cost is
     * disclosed in the limitations: a deletion discovered only through a failed session
     * refresh — rather than through the user fetch — is classified recoverable and explained
     * as nothing at all.
     */
    @Test
    fun `the library's session-missing exception is not a deletion, however its message reads`() {
        val thrown = AuthSessionMissingException(401)

        // The trap, stated: the text is suggestive and is deliberately never read. The
        // library puts it in `message`; `description` is the mechanical "Auth API error: …".
        assertTrue(thrown.message?.contains("deleted") == true)

        val state = AuthLifecyclePolicy.stateOf(probeOf(thrown))

        assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
        assertFalse(state.endsLocalAccountLifecycle)
        assertFalse(state.forbidsAuthenticatedWork)
    }

    /**
     * AUTH 6 / section D: **the states the app cannot resolve are the states that move nothing.**
     *
     * `session_not_found` is the sharpest case. A refresh can fail with it before any account
     * check runs, and the library then clears the stored session itself — so Attendo sees no
     * session at all and reaches [AuthLifecycleState.NORMAL_SIGNED_OUT], not
     * [AuthLifecycleState.RECOVERABLE_AUTH_FAILURE]. A revoked session and a deleted account
     * are indistinguishable on that path, and the app deliberately does not guess from the
     * exception's message: the one exception whose message says "deleted" is the one that must
     * not be believed.
     *
     * The reason that is a safe place to be, asserted rather than asserted-in-prose:
     *
     *  * None of these states ends the local lifecycle, so the attendance claim survives any
     *    of them — live, naming the account that may or may not still exist.
     *  * Because the claim stays live, a different account arriving during the ambiguity meets
     *    the ordinary refusal. That is the property that matters: the ambiguity delays the
     *    *announcement*, and never opens a direction for data to move.
     *
     * The gap is closed by the next authoritative probe, which is the earliest moment it can
     * be — until a session exists there is nothing for the server to answer. See
     * [AuthLifecycleState.NORMAL_SIGNED_OUT] for the limitation stated in full.
     */
    @Test
    fun `an unresolved session state leaves ownership alone and refuses a new account`() {
        val accountA = "uid-a"
        val accountB = "uid-b"

        // Every state the app can be in without positive evidence of a deletion.
        val unresolved = listOf(
            AuthLifecyclePolicy.stateOf(probeOf(AuthSessionMissingException(401))),
            AuthLifecyclePolicy.stateOf(probeOf(CancellationException("the screen went away"))),
            AuthLifecyclePolicy.stateOf(AccountProbe.Unreachable),
            AuthLifecyclePolicy.stateOf(AccountProbe.Rejected("session_expired", 401)),
            AuthLifecyclePolicy.stateOf(AccountProbe.Rejected("user_banned", 403)),
        )

        for (state in unresolved) {
            assertFalse(
                "$state must not end the local lifecycle — the claim it would terminate is " +
                    "the one the next sign-in has to be refused against.",
                state.endsLocalAccountLifecycle,
            )

            // The claim is therefore still live and still A's, and B is refused in both
            // directions. This is the whole of "the ambiguous state cannot be exploited":
            // the rows cannot be uploaded as B, and A's cloud attendance cannot be pulled
            // into a database A owns, until the server has actually said A is gone.
            val claim = LocalAttendanceOwner(accountA)
            assertEquals(state.name, OwnershipVerdict.REFUSE, AttendanceSyncPolicy.ownershipVerdict(claim, accountB))
            assertEquals(
                state.name,
                AccountTransition.DIFFERENT_ACCOUNT_REFUSE,
                AttendanceSyncPolicy.accountTransition(claim, accountB),
            )
        }
    }

    // ==================================== UID-A → deleted → UID-B, end to end

    /**
     * The regression the whole feature exists for, walked one decision at a time.
     *
     * The order below is the real order, and each step is asserted against the function the
     * production path calls. If any single one of them regresses, a student's attendance
     * moves between accounts — which is why this is one test that reads as a story rather
     * than six that each check a predicate in isolation.
     *
     * The story has one more act than it used to, and that act is the whole of Auth Lifecycle
     * Hardening: a deletion does not just *retain* A's claim, it marks it **terminated**, and
     * B arriving over a terminated claim is a new lifecycle rather than the switch prompt.
     */
    @Test
    fun `a deleted account's local attendance can never become the next account's`() {
        val accountA = "uid-a"
        val accountB = "uid-b"

        // 1. A owns this device's attendance and has finished its initial push.
        var owner: LocalAttendanceOwner? = LocalAttendanceOwner(accountA)
        assertEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(owner, accountA))
        assertEquals(AccountTransition.SAME_ACCOUNT_RETURN, AttendanceSyncPolicy.accountTransition(owner, accountA))

        // 2. The server establishes that A no longer exists.
        val gone = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(AuthLifecyclePolicy.USER_NOT_FOUND, 403),
        )
        assertEquals(AuthLifecycleState.SERVER_ACCOUNT_GONE, gone)
        assertTrue("a gone account may not sync", gone.forbidsAuthenticatedWork)

        // 3. The deletion looks at the ownership row — and deliberately leaves the claim in
        //    place. This is the step that keeps the boundary closed; clearing it here is what
        //    would hand B an initial push over every row A created.
        assertEquals(
            OwnerAfterDeletion.NamesGoneAccount(accountA),
            AuthLifecyclePolicy.ownerAfterAccountDeletion(owner?.userId, goneUserId = accountA),
        )

        // 4. What it does write, once, is the fact that the claim can never be resumed:
        //    the account it names was established gone. This is the persisted state a process
        //    death cannot lose, and the only input to every decision below.
        owner = LocalAttendanceOwner(accountA, terminated = true)

        // 5. The student signs in again and Supabase resolves a *different* user.
        assertNotEquals(accountA, accountB)

        // 6. B is a new lifecycle, and it is NOT the switch prompt. A terminated claim is not
        //    "someone else's live account" — there is nobody left to ask, and the sentence the
        //    student was shown for the deletion said the next sign-in would be a new account.
        assertEquals(OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE, AttendanceSyncPolicy.ownershipVerdict(owner, accountB))
        assertEquals(
            AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION,
            AttendanceSyncPolicy.accountTransition(owner, accountB),
        )

        // 7. A's tombstones are not B's to send, in any state. This is the sharpest edge in
        //    the feature: every other leak copies a row, and this one *deletes* one from A's
        //    cloud data.
        assertFalse(AttendanceSyncPolicy.tombstonePushableBy(tombstoneUserId = accountA, accountUserId = accountB))

        // 8. That transition is not an inheritance. Before the release, B may not sync A's
        //    rows in either direction — the verdict is a quarantine, not a proceed.
        assertNotEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(owner, accountB))
        assertNotEquals(OwnershipVerdict.CLAIM, AttendanceSyncPolicy.ownershipVerdict(owner, accountB))

        // 9. After the release the device is B's: the rows are gone (set aside first), the
        //    claim names B, and it is live rather than terminated — so B proceeds and its
        //    initial push is over its own body, not A's.
        owner = LocalAttendanceOwner(accountB)
        assertEquals(OwnershipVerdict.PROCEED, AttendanceSyncPolicy.ownershipVerdict(owner, accountB))
        assertEquals(AccountTransition.SAME_ACCOUNT_RETURN, AttendanceSyncPolicy.accountTransition(owner, accountB))
        assertFalse("B's claim is live, not terminated", owner.terminated)
    }

    /**
     * AUTH 9 + 13 + 21: after A is deleted, B is a *new lifecycle* — and that is a third
     * outcome, not one of the two that already existed.
     *
     *  - Not FIRST_CLAIM: `ownerUserId == null` means the phone has never been claimed, which
     *    is what makes an upload of every local row correct there. A deletion leaves a claim,
     *    so reading it as one is the leak.
     *  - Not DIFFERENT_ACCOUNT_REFUSE: that is the prompt about a *live* other account, and it
     *    is unanswerable once that account has been deleted — the student would be asked to
     *    approve replacing data whose owner no longer exists, on behalf of a ghost.
     *  - NEW_LIFECYCLE_AFTER_TERMINATION: the phone is handed to B as a new account, after the
     *    rows are set aside.
     */
    @Test
    fun `B after a deletion is a new lifecycle, not a first claim and not a switch`() {
        val accountB = "uid-b"

        // With no owner (a reset, or an install that never had one) B claims. That is the
        // only FIRST_CLAIM edge — not what a deletion leaves.
        assertEquals(AccountTransition.FIRST_CLAIM, AttendanceSyncPolicy.accountTransition(null, accountB))
        assertEquals(OwnershipVerdict.CLAIM, AttendanceSyncPolicy.ownershipVerdict(null, accountB))

        // With A's claim retained — what a deletion leaves — B is a new lifecycle over a phone
        // that still holds A's rows.
        val afterDeletion = AuthLifecyclePolicy.ownerAfterAccountDeletion("uid-a", "uid-a")
        val goneUserId = (afterDeletion as OwnerAfterDeletion.NamesGoneAccount).userId
        val terminated = LocalAttendanceOwner(goneUserId, terminated = true)

        assertEquals(
            AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION,
            AttendanceSyncPolicy.accountTransition(terminated, accountB),
        )
        assertNotEquals(
            "A deletion must not read as B's return to its own account.",
            AccountTransition.SAME_ACCOUNT_RETURN,
            AttendanceSyncPolicy.accountTransition(terminated, accountB),
        )
        assertNotEquals(
            "A deletion must not read as a first claim: B's initial push would take A's rows.",
            AccountTransition.FIRST_CLAIM,
            AttendanceSyncPolicy.accountTransition(terminated, accountB),
        )
        assertNotEquals(
            "A deleted account is not a live account to ask about replacing.",
            AccountTransition.DIFFERENT_ACCOUNT_REFUSE,
            AttendanceSyncPolicy.accountTransition(terminated, accountB),
        )

        // And it is not a *pending* question either: the termination is the answer, so nothing
        // in this path is waiting on a student's confirmation.
        assertEquals(
            OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE,
            AttendanceSyncPolicy.ownershipVerdict(terminated, accountB),
        )
    }

    /**
     * AUTH 21, as the enumeration it is: **no** input can route a deleted account's claim
     * through the switch flow.
     *
     * The switch flow's entire contract is the confirmation it puts in front of the student —
     * "this phone's attendance belongs to someone else; replace it?" — and every word of that
     * is false once the someone else has been deleted. So the explicit-approval flag, which is
     * the *only* thing that reaches SWITCH_APPROVED for a live mismatch, must not reach it
     * here. Asserted over the cross product rather than the one case because the failure is
     * whichever branch ordering a later edit happens to introduce.
     */
    @Test
    fun `an admin deletion can never route through the normal account switch`() {
        val terminated = LocalAttendanceOwner("uid-a", terminated = true)

        for (incoming in listOf("uid-b", "uid-c", "uid-a")) {
            for (approved in listOf(false, true)) {
                val transition = AttendanceSyncPolicy.accountTransition(
                    owner = terminated,
                    linkedUserId = incoming,
                    isExplicitSwitchApproved = approved,
                )
                assertNotEquals(
                    "An approval flag cannot turn a terminated claim into a switch (incoming=$incoming, approved=$approved).",
                    AccountTransition.SWITCH_APPROVED,
                    transition,
                )
                assertNotEquals(
                    "A terminated claim is never the mismatch prompt (incoming=$incoming, approved=$approved).",
                    AccountTransition.DIFFERENT_ACCOUNT_REFUSE,
                    transition,
                )
            }
        }
    }

    /**
     * AUTH 14 and 20: the explicit switch still works, and it is still the only thing that
     * moves a *live* claim.
     *
     * Asserted beside the deletion cases because the requirement is that the deletion reuses
     * this path's machinery rather than growing a second wipe — so the switch must remain
     * reachable exactly as it was for the case it was written for, two live accounts on one
     * phone.
     */
    @Test
    fun `only an explicit approval moves a live claim to another account`() {
        val accountA = "uid-a"
        val accountB = "uid-b"
        val live = LocalAttendanceOwner(accountA)

        // Not approved, whatever the reason for the mismatch — a different device, a shared
        // phone, a second account. The rule does not care why.
        for (reason in listOf("different device", "shared phone", "second account")) {
            assertEquals(
                reason,
                AccountTransition.DIFFERENT_ACCOUNT_REFUSE,
                AttendanceSyncPolicy.accountTransition(live, accountB),
            )
            assertEquals(reason, OwnershipVerdict.REFUSE, AttendanceSyncPolicy.ownershipVerdict(live, accountB))
        }

        // Approved is the one input that changes it, and it changes it to the approved state.
        assertEquals(
            AccountTransition.SWITCH_APPROVED,
            AttendanceSyncPolicy.accountTransition(live, accountB, isExplicitSwitchApproved = true),
        )
    }

    // ============================================ announcing the deletion once

    /**
     * AUTH 15 at the app's boundary: a foreground loop re-validates the same gone account and
     * produces exactly one announcement.
     *
     * This drives the same function `AccountManager.applyLifecycle` drives, with the same
     * carried state, so what it pins is the manager's actual behaviour and not a description
     * of it.
     */
    @Test
    fun `foregrounding a deleted account announces the removal exactly once`() {
        var announcedFor: String? = null
        var announced = 0

        // Ten foregrounds' worth of validation, as `MainActivity.onStart` produces them.
        repeat(10) {
            when (val next = AuthLifecyclePolicy.announcementFor(AuthLifecycleState.SERVER_ACCOUNT_GONE, "uid-a", announcedFor)) {
                is DeletionAnnouncement.Announce -> {
                    announcedFor = next.userId
                    announced++
                }
                DeletionAnnouncement.Clear -> announcedFor = null
                DeletionAnnouncement.None -> Unit
            }
        }

        assertEquals(1, announced)
    }
}
