package com.attendo.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-lifecycle classification, pinned without a network, a server or a device.
 *
 * The whole safety argument for this feature is a mapping from evidence to state, and the
 * mapping is the only thing here that can be wrong in a way that loses a student's data or
 * ends their session for nothing. So every branch is asserted, and the two properties that
 * matter more than any individual branch — *nothing but `user_not_found` is a deletion*,
 * and *nothing but a deletion forbids work* — are asserted across every input the app can
 * actually produce rather than for one example each.
 */
class AuthLifecyclePolicyTest {

    // ------------------------------------------------------------------ valid

    /** AUTH 1: a valid account stays valid. */
    @Test
    fun `a returned user is valid and forbids nothing`() {
        val state = AuthLifecyclePolicy.stateOf(AccountProbe.UserReturned)

        assertEquals(AuthLifecycleState.VALID, state)
        assertFalse(state.forbidsAuthenticatedWork)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    // ------------------------------------------------------- network failures

    /** AUTH 2: a temporary network failure must not log the student out. */
    @Test
    fun `an unreachable server is unavailability, never a deletion`() {
        val state = AuthLifecyclePolicy.stateOf(AccountProbe.Unreachable)

        assertEquals(AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE, state)
        // The two properties that make "preserve everything and retry later" true.
        assertFalse(state.forbidsAuthenticatedWork)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    /**
     * A server fault is the server declining to answer, not answering "no such user".
     *
     * The stray `user_not_found` is the point of the test: a 5xx is checked *before* the
     * code, so a server error that happens to carry a deletion-shaped code cannot be read
     * as a deletion. If the order were the other way round, this input would end a
     * student's session.
     */
    @Test
    fun `a server fault is unavailability even when it carries a deletion-shaped code`() {
        val state = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(errorCode = AuthLifecyclePolicy.USER_NOT_FOUND, httpStatus = 503),
        )

        assertEquals(AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    @Test
    fun `rate limiting is unavailability, not an answer about the account`() {
        val state = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(errorCode = null, httpStatus = AuthLifecyclePolicy.TOO_MANY_REQUESTS),
        )

        assertEquals(AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    // ---------------------------------------------------- the deletion signal

    /** AUTH 4: a deleted auth user becomes SERVER_ACCOUNT_GONE. */
    @Test
    fun `user_not_found is the one server-authoritative account-gone signal`() {
        val state = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(errorCode = AuthLifecyclePolicy.USER_NOT_FOUND, httpStatus = 403),
        )

        assertEquals(AuthLifecycleState.SERVER_ACCOUNT_GONE, state)
        assertTrue(state.endsLocalAccountLifecycle)
        assertTrue(state.forbidsAuthenticatedWork)
    }

    /**
     * The status alone is not the signal — the code is.
     *
     * A 403 with no code is what a WAF, a proxy or a changed GoTrue response shape would
     * produce, and none of those is evidence that the account is gone. This asserts the
     * app does not reach for the conspicuous status when the actual evidence is missing.
     */
    @Test
    fun `a 403 without the code is recoverable, not a deletion`() {
        val state = AuthLifecyclePolicy.stateOf(AccountProbe.Rejected(errorCode = null, httpStatus = 403))

        assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    /**
     * AUTH 5, restated for the classifier: only SERVER_ACCOUNT_GONE ends the lifecycle.
     *
     * Every state is enumerated rather than sampled, so adding a state without deciding
     * whether it ends a lifecycle fails here.
     */
    @Test
    fun `only server-account-gone ends the local authenticated lifecycle`() {
        val ending = AuthLifecycleState.entries.filter { it.endsLocalAccountLifecycle }

        assertEquals(listOf(AuthLifecycleState.SERVER_ACCOUNT_GONE), ending)
    }

    @Test
    fun `only server-account-gone and no-session forbid authenticated work`() {
        val forbidding = AuthLifecycleState.entries.filter { it.forbidsAuthenticatedWork }

        assertEquals(
            listOf(AuthLifecycleState.SERVER_ACCOUNT_GONE, AuthLifecycleState.NORMAL_SIGNED_OUT),
            forbidding,
        )
    }

    // -------------------------------------------------- recoverable refusals

    /** AUTH 3: recoverable token expiry does not become ACCOUNT_DELETED. */
    @Test
    fun `an expired or revoked session is recoverable, never a deletion`() {
        for (code in AuthLifecyclePolicy.SESSION_SCOPED_CODES) {
            val state = AuthLifecyclePolicy.stateOf(AccountProbe.Rejected(errorCode = code, httpStatus = 401))

            assertEquals("$code must be recoverable", AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
            assertFalse("$code must not end the lifecycle", state.endsLocalAccountLifecycle)
        }
    }

    /**
     * A ban is terminal for this session and says nothing about deletion — the user row is
     * exactly what a ban sits on. The app must therefore not tell the student their account
     * was removed.
     */
    @Test
    fun `a banned user is recoverable, never a deletion`() {
        val state = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(errorCode = AuthLifecyclePolicy.USER_BANNED, httpStatus = 403),
        )

        assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    /**
     * An answer the app cannot interpret is not evidence of a deletion.
     *
     * The asymmetry is the justification, and it is worth stating where the test is: being
     * wrong in this direction costs one more sign-in, and being wrong in the other ends a
     * session and retires an ownership for nothing.
     */
    @Test
    fun `an unrecognised refusal is recoverable, never a deletion`() {
        val state = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(errorCode = "some_future_go_true_code", httpStatus = 400),
        )

        assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    @Test
    fun `a refusal carrying nothing at all is recoverable`() {
        val state = AuthLifecyclePolicy.stateOf(AccountProbe.Rejected(errorCode = null, httpStatus = null))

        assertEquals(AuthLifecycleState.RECOVERABLE_AUTH_FAILURE, state)
        assertFalse(state.endsLocalAccountLifecycle)
    }

    // -------------------------------------------------- the blanket property

    /**
     * The single property the whole feature rests on, asserted over every probe shape the
     * app can produce: **a deletion is reached only by `user_not_found`.**
     *
     * Written as an enumeration over the destructive state rather than as a list of
     * negative examples, so a new code or a reordered branch that quietly widened the
     * deletion signal fails here rather than in the field. The expected count is derived
     * rather than hard-coded, so the test cannot pass by enumerating nothing — which is
     * exactly how it failed the first time it was written.
     */
    @Test
    fun `no input reaches account-gone except a refusal naming user_not_found`() {
        val codes: List<String?> = listOf(
            null,
            AuthLifecyclePolicy.USER_NOT_FOUND,
            "session_not_found",
            "session_expired",
            "refresh_token_not_found",
            "refresh_token_already_used",
            "user_banned",
            "bad_jwt",
            "no_authorization",
            "unexpected_failure",
            "user_already_exists",
            "",
        )
        val statuses: List<Int?> = listOf(null, 400, 401, 403, 404, 409, 422, 429, 500, 502, 503)

        val probes: List<AccountProbe> = buildList {
            add(AccountProbe.Unreachable)
            for (code in codes) for (status in statuses) add(AccountProbe.Rejected(code, status))
        }
        val deletions = probes.filter { AuthLifecyclePolicy.stateOf(it) == AuthLifecycleState.SERVER_ACCOUNT_GONE }

        // A server fault is never a verdict on the account, whatever code rides along with
        // it; every other status leaves the code free to decide.
        fun isServerFault(status: Int?): Boolean = status != null && (status >= 500 || status == AuthLifecyclePolicy.TOO_MANY_REQUESTS)
        val statusesThatCanBeADeletion = statuses.filter { !isServerFault(it) }

        assertTrue(
            "only a refusal naming user_not_found may be a deletion, but was: $deletions",
            deletions.all { probe ->
                val rejected = probe as AccountProbe.Rejected
                rejected.errorCode == AuthLifecyclePolicy.USER_NOT_FOUND && !isServerFault(rejected.httpStatus)
            },
        )
        assertEquals(
            "the enumeration must actually exercise the deletion path: one code, " +
                "times every status a server fault does not cover",
            statusesThatCanBeADeletion.size,
            deletions.size,
        )
    }

    /**
     * A deletion named without a status is still a deletion — the code is the evidence.
     *
     * Unreachable from the app today ([com.attendo.data.account.probeOf] only ever produces a
     * coded rejection from a `RestException`, whose status is an `int`), and pinned anyway so
     * the decision is visible rather than accidental: were a code ever to arrive alone, the
     * safer reading is the code's, because discarding a `user_not_found` would leave an
     * install syncing forever as an account that does not exist.
     */
    @Test
    fun `user_not_found decides on its own when no status came with it`() {
        val state = AuthLifecyclePolicy.stateOf(
            AccountProbe.Rejected(errorCode = AuthLifecyclePolicy.USER_NOT_FOUND, httpStatus = null),
        )

        assertEquals(AuthLifecycleState.SERVER_ACCOUNT_GONE, state)
    }

    // ------------------------------------------- ownership across a deletion

    /**
     * AUTH 8 / section 3: a deletion never releases the attendance claim.
     *
     * This is the assertion behind "no data created under UID-A may ever be uploaded as
     * UID-B". Releasing the claim is what would authorise the *next* account's initial
     * push over the outgoing account's whole history, because a uid this device has never
     * synced reads as `initialPushDone = false` and an initial push takes every local row.
     */
    @Test
    fun `a deletion names the gone account and keeps the claim`() {
        val outcome = AuthLifecyclePolicy.ownerAfterAccountDeletion(ownerUserId = "uid-a", goneUserId = "uid-a")

        assertEquals(OwnerAfterDeletion.NamesGoneAccount("uid-a"), outcome)
    }

    @Test
    fun `a deletion leaves another account's claim alone`() {
        val outcome = AuthLifecyclePolicy.ownerAfterAccountDeletion(ownerUserId = "uid-c", goneUserId = "uid-a")

        assertEquals(OwnerAfterDeletion.NamesAnotherAccount("uid-c"), outcome)
    }

    @Test
    fun `a deletion with no owner has nothing to retire`() {
        val outcome = AuthLifecyclePolicy.ownerAfterAccountDeletion(ownerUserId = null, goneUserId = "uid-a")

        assertEquals(OwnerAfterDeletion.NoOwner, outcome)
    }

    /**
     * The point of the three cases together: whatever the owner row says, the deletion does
     * not produce a *release*. Nothing in [OwnerAfterDeletion] means "clear the claim", and
     * this asserts the type cannot grow one unnoticed.
     */
    @Test
    fun `no deletion outcome is a claim release`() {
        val outcomes = listOf(
            AuthLifecyclePolicy.ownerAfterAccountDeletion("uid-a", "uid-a"),
            AuthLifecyclePolicy.ownerAfterAccountDeletion("uid-c", "uid-a"),
            AuthLifecyclePolicy.ownerAfterAccountDeletion(null, "uid-a"),
        )

        assertTrue(outcomes.all { it !is OwnerAfterDeletion.NamesGoneAccount || it.userId == "uid-a" })
        // Every non-empty outcome still names an account, i.e. the row is still there.
        assertEquals(1, outcomes.count { it is OwnerAfterDeletion.NamesGoneAccount })
    }

    // ------------------------------------------- announcing the deletion once

    /** AUTH 15, first half: the first validation of a gone account announces it. */
    @Test
    fun `a gone account is announced the first time it is seen`() {
        val announcement = AuthLifecyclePolicy.announcementFor(
            state = AuthLifecycleState.SERVER_ACCOUNT_GONE,
            uid = "uid-a",
            announcedFor = null,
        )

        assertEquals(DeletionAnnouncement.Announce("uid-a"), announcement)
    }

    /**
     * AUTH 15, second half, and the requirement it encodes: *shown once per deletion, not on
     * every foreground*.
     *
     * Foregrounding re-runs the validation, so this is not a hypothetical — every return to
     * the app for as long as it lives re-establishes SERVER_ACCOUNT_GONE for the same
     * account. The record is what makes the second and the thousandth silent.
     */
    @Test
    fun `re-validating the same gone account announces nothing more`() {
        var announcedFor: String? = null
        var announcements = 0

        // Five validations of the same deleted account, as five foregrounds would produce.
        repeat(5) {
            when (val next = AuthLifecyclePolicy.announcementFor(AuthLifecycleState.SERVER_ACCOUNT_GONE, "uid-a", announcedFor)) {
                is DeletionAnnouncement.Announce -> {
                    announcedFor = next.userId
                    announcements++
                }
                DeletionAnnouncement.Clear -> announcedFor = null
                DeletionAnnouncement.None -> Unit
            }
        }

        assertEquals("one deletion, one sentence", 1, announcements)
        assertEquals("uid-a", announcedFor)
    }

    /**
     * A second deletion, much later, is announced in its turn — the record is a uid, not a
     * latch that fires once per install.
     *
     * The sequence is the real one: A is deleted, the student signs in as B (which clears
     * the record), and B is later deleted too. A boolean "already announced" would swallow
     * B's explanation entirely.
     */
    @Test
    fun `a later deletion of a different account is announced again`() {
        val afterA = AuthLifecyclePolicy.announcementFor(AuthLifecycleState.SERVER_ACCOUNT_GONE, "uid-a", null)
        val announcedFor = (afterA as DeletionAnnouncement.Announce).userId

        // B signs in successfully — the only thing that clears the record.
        val onSignIn = AuthLifecyclePolicy.announcementFor(AuthLifecycleState.VALID, "uid-b", announcedFor)
        assertEquals(DeletionAnnouncement.Clear, onSignIn)

        // B is then deleted, and B's deletion is announced on its own account.
        val afterB = AuthLifecyclePolicy.announcementFor(AuthLifecycleState.SERVER_ACCOUNT_GONE, "uid-b", null)
        assertEquals(DeletionAnnouncement.Announce("uid-b"), afterB)
    }

    /**
     * The two states that must not move the record in either direction.
     *
     * A dropped connection neither announces a deletion nor clears a real one — the first
     * would tell a student their account was removed because their train entered a tunnel,
     * and the second would let one failed validation erase the explanation for a deletion
     * that genuinely happened.
     */
    @Test
    fun `a network failure or a session refusal never touches the record`() {
        for (state in listOf(
            AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE,
            AuthLifecycleState.RECOVERABLE_AUTH_FAILURE,
            AuthLifecycleState.NORMAL_SIGNED_OUT,
        )) {
            assertEquals(
                "$state must not announce",
                DeletionAnnouncement.None,
                AuthLifecyclePolicy.announcementFor(state, "uid-a", null),
            )
            assertEquals(
                "$state must not clear a real deletion",
                DeletionAnnouncement.None,
                AuthLifecyclePolicy.announcementFor(state, "uid-a", "uid-a"),
            )
        }
    }

    /**
     * A deletion that cannot be attributed to an account is not announced.
     *
     * The shape this guards is narrow but real: the state says the account is gone while no
     * uid could be read, which would leave the screen explaining a removal without being
     * able to say whose — and, worse, would leave the record unable to suppress a repeat.
     */
    @Test
    fun `a deletion with no uid to name is not announced`() {
        val announcement = AuthLifecyclePolicy.announcementFor(
            state = AuthLifecycleState.SERVER_ACCOUNT_GONE,
            uid = null,
            announcedFor = null,
        )

        assertEquals(DeletionAnnouncement.None, announcement)
    }
}
