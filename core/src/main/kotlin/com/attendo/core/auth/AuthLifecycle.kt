package com.attendo.core.auth

/**
 * Where this install stands with the account its stored session names.
 *
 * ### Why this is not [com.attendo.data.account.AccountState]
 *
 * That type reads the *local* session: it can say "this phone holds a session, and the
 * user object in it carries these identities". It cannot say whether the account that
 * session names still exists, because a Supabase access token keeps verifying after the
 * user behind it has been deleted — the signature is still good, the `exp` claim is still
 * in the future, and nothing local has changed. An install in that state reads as
 * `Linked` and syncs, indefinitely, against an account that is gone.
 *
 * So this is a second question, asked of the server rather than of the session, and the
 * two are layered rather than competing: [com.attendo.data.account.AccountState] answers
 * "is there a session?", this answers "*is the account that session names still there?*",
 * and the app's one authoritative reading is the pair. There is deliberately no third
 * machine — every surface that needs the answer reads it from the one place that asks.
 *
 * ### The states, and why there are five
 *
 * They are the five endings a server-authoritative check can have, and they exist because
 * collapsing any two of them produces a specific, shipped bug:
 *
 *  * [VALID] — the server returned the user. Sync, sign-in and everything else proceed.
 *  * [RECOVERABLE_AUTH_FAILURE] — the server answered, and refused *this session*
 *    (expired, revoked, the refresh token already spent). The account may well be alive;
 *    what is gone is the session. Signing in again is the way back, so this must never be
 *    reported as a deletion.
 *  * [NETWORK_OR_SERVER_UNAVAILABLE] — no answer at all. The phone has no usable network,
 *    or Supabase is down or rate-limiting. This is the state that must never look like a
 *    deletion: everything local is preserved and the check is simply tried again later.
 *  * [SERVER_ACCOUNT_GONE] — the server was reached, the token was presented, and it
 *    answered that there is no such user. This is the only state that may terminate the
 *    authenticated lifecycle, and it is entered only on that positive evidence.
 *  * [NORMAL_SIGNED_OUT] — there is no session to ask about. Not a failure, and not a
 *    deletion: the student signed out, or never signed in.
 */
enum class AuthLifecycleState {

    /** The server confirmed the account this session names still exists. */
    VALID,

    /**
     * The server refused this session, but said nothing about the account.
     *
     * Expired, revoked elsewhere, a refresh token already spent — all of them end this
     * session and none of them is a deletion. The student signs in again and is back in
     * the same account with the same UID.
     */
    RECOVERABLE_AUTH_FAILURE,

    /**
     * Nothing was established, because nothing answered.
     *
     * The whole point of this state is that it is *not* [SERVER_ACCOUNT_GONE]. Local data
     * is preserved untouched and the check runs again at the next boundary.
     */
    NETWORK_OR_SERVER_UNAVAILABLE,

    /**
     * The server was reached and answered that this account no longer exists.
     *
     * The one state that ends the local authenticated lifecycle. See
     * [AuthLifecyclePolicy.stateOf] for the exact evidence required to enter it.
     */
    SERVER_ACCOUNT_GONE,

    /**
     * There is no session. Nothing to validate, and nothing was deleted.
     *
     * ### The one path this state cannot distinguish, and why that is safe
     *
     * A session refresh can fail with `session_not_found` *before* any account check runs.
     * supabase-kt then clears the stored session on its own, so by the time Attendo looks,
     * the session is already gone, [com.attendo.data.account.AccountState] reads `NoSession`,
     * and the lifecycle check reaches this state rather than
     * [RECOVERABLE_AUTH_FAILURE]. In that path the app genuinely cannot tell a revoked
     * session from a deleted account: the library exposes no distinct signal for it, and the
     * classification deliberately never reads exception message text — the one exception
     * whose message says "deleted" is the one that must not be believed. This is a real
     * limitation and it is stated rather than papered over with a heuristic.
     *
     * Three things make it safe, and they are the reason no heuristic was invented to close
     * it:
     *
     *  1. **This state ends nothing.** [endsLocalAccountLifecycle] is false here, so the
     *     local attendance claim is left exactly as it stands — live, naming the account that
     *     may or may not still exist. No row, tombstone or ownership claim is touched, and no
     *     data moves in either direction.
     *  2. **The ambiguity cannot be exploited on the way out.** Because the claim stays live,
     *     a *different* account signing in before the deletion is established meets
     *     [com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict.REFUSE] — the ordinary
     *     mismatch — so nothing is uploaded or pulled under the ambiguous reading. The only
     *     way through is an account that owns the claim, which is the account the claim names.
     *  3. **The next authoritative probe settles it.** The next sign-in mints a fresh session,
     *     and the next `retrieveUser` classifies it: `user_not_found` establishes
     *     [SERVER_ACCOUNT_GONE] and the termination, and anything else establishes
     *     [VALID]. That is the earliest moment the fact *can* be established, since until then
     *     there is no session to present and therefore nothing for the server to answer.
     *
     * What the student sees in the gap is a signed-out app, which is true; what they are not
     * told is that their account was removed, which is unproven. The cost of the ambiguity is
     * that a deletion discovered this way is announced at the next sign-in rather than at the
     * moment it happened — a delay, not a wrong answer, and never a crossed account boundary.
     */
    NORMAL_SIGNED_OUT,
    ;

    /**
     * Whether this state forbids authenticated work outright — pushing, pulling, or any
     * other request made as the account.
     *
     * Deliberately true for only two states. [RECOVERABLE_AUTH_FAILURE] and
     * [NETWORK_OR_SERVER_UNAVAILABLE] both leave the door open: a pass attempted under
     * either fails on its own terms if it really cannot proceed, and treating them as
     * prohibitions would turn a dropped connection into a sync that never resumes. The
     * requirement this encodes is narrow and load-bearing — *only* server-authoritative
     * evidence of a deletion may stop authenticated work and retire ownership.
     */
    val forbidsAuthenticatedWork: Boolean
        get() = this == SERVER_ACCOUNT_GONE || this == NORMAL_SIGNED_OUT

    /**
     * Whether the local authenticated lifecycle must be ended now.
     *
     * True only for [SERVER_ACCOUNT_GONE]. A recoverable failure ends the *session* — the
     * library does that on its own — but the lifecycle, and everything the student has on
     * this phone, is not this state's to end.
     */
    val endsLocalAccountLifecycle: Boolean
        get() = this == SERVER_ACCOUNT_GONE
}

/**
 * What one server-authoritative account check came back with.
 *
 * The three shapes are exhaustive over "answered yes", "answered no", and "did not
 * answer", which is what makes [AuthLifecyclePolicy.stateOf] a total function and keeps
 * the transport question — "did anything arrive?" — from being confused with the
 * semantic one — "what did the server mean?".
 */
sealed interface AccountProbe {

    /** The server returned the user for this token. The account exists. */
    data object UserReturned : AccountProbe

    /**
     * The server answered and refused the token.
     *
     * [errorCode] is GoTrue's own `error_code` — the machine-readable half of the answer,
     * and the only half read. [httpStatus] is carried because a 5xx is a server problem
     * rather than a verdict on the account, and because it is the one thing that still
     * distinguishes "refused" from "broken" if a code ever arrives without one.
     */
    data class Rejected(val errorCode: String?, val httpStatus: Int?) : AccountProbe

    /** Nothing answered: no network, a timeout, or a transport failure. */
    data object Unreachable : AccountProbe
}

/**
 * Every decision the account-lifecycle check makes, as pure functions.
 *
 * The engine around this is HTTP and a session store, but none of it decides anything: it
 * produces an [AccountProbe] and applies what these functions say. Keeping the rules here
 * means the boundary that matters — the line between "the server told us this account is
 * gone" and "we could not ask" — is testable without a network, a server, or a device, and
 * means the same rules can be applied by a client that is not this one.
 *
 * The vocabulary is deliberately platform-free. Nothing here names a Supabase type, a
 * Kotlin type from `:app`, or anything Android: the codes are the server's own strings and
 * the states are Attendo's. A web client implementing the same lifecycle asks the same
 * question, gets the same codes back, and reaches the same states through this table.
 */
object AuthLifecyclePolicy {

    /**
     * GoTrue's `error_code` for "the JWT was accepted, and the user it names does not
     * exist".
     *
     * **This is the whole of the deletion signal.** `/auth/v1/user` verifies the token
     * first and only then loads the user by the token's `sub` claim, so an answer of
     * `user_not_found` means the server reached its own user table and found nothing —
     * not that the token was stale, not that the session lapsed, and not that the request
     * failed. It is therefore the only code that establishes
     * [AuthLifecycleState.SERVER_ACCOUNT_GONE].
     *
     * (Verified against supabase-kt 3.0.3's own `AuthErrorCode.UserNotFound`.)
     */
    const val USER_NOT_FOUND: String = "user_not_found"

    /**
     * The codes that mean "*this session* is finished" without saying anything about the
     * account.
     *
     * `session_not_found` and `refresh_token_not_found` are what a revoked session or a
     * spent refresh token produce; `session_expired` and `refresh_token_already_used` are
     * the token lifecycle's own endings. Every one of them is answered by signing in
     * again, which returns the *same* account — so none of them may be reported as a
     * deletion, however tempting the shape of the answer looks.
     */
    val SESSION_SCOPED_CODES: Set<String> = setOf(
        "session_not_found",
        "session_expired",
        "refresh_token_not_found",
        "refresh_token_already_used",
    )

    /**
     * `user_banned`: the account exists and is refused.
     *
     * Not a deletion — the user row is right there, which is exactly what the ban sits on
     * — and not recoverable by the student either. It is read as
     * [AuthLifecycleState.RECOVERABLE_AUTH_FAILURE] rather than given a state of its own
     * because the app's response is the same one a revoked session gets: end this session,
     * say nothing about deletion, keep everything local.
     */
    const val USER_BANNED: String = "user_banned"

    /**
     * The state an [AccountProbe] establishes.
     *
     * The order of the questions is the whole of the safety argument, and it is why this
     * reads top-down rather than as a lookup on the code:
     *
     *  1. **Did anything answer?** An unreachable probe is
     *     [AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE] before any code is consulted,
     *     so a phone on a train cannot be walked into a deletion by a timeout, a DNS
     *     failure, or an exception that happens to carry a suggestive message.
     *  2. **Was the answer a server fault?** A 5xx or a 429 is the server saying "not
     *     now", not "no such user", and is read as unavailable.
     *  3. **Did the server name the user as missing?** Only `user_not_found` reaches
     *     [AuthLifecycleState.SERVER_ACCOUNT_GONE].
     *  4. **Was the refusal session-scoped?** Those are
     *     [AuthLifecycleState.RECOVERABLE_AUTH_FAILURE].
     *  5. **Everything else** — an unrecognised code, a bare 401, a 400 — is also
     *     recoverable. An answer this app cannot interpret is not evidence of a deletion,
     *     and the cost of being wrong is asymmetric: reading a deletion that was not one
     *     ends a student's session and retires their ownership for nothing, while reading
     *     a recoverable failure that was really a deletion costs one more sign-in.
     */
    fun stateOf(probe: AccountProbe): AuthLifecycleState = when (probe) {
        AccountProbe.UserReturned -> AuthLifecycleState.VALID

        AccountProbe.Unreachable -> AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE

        is AccountProbe.Rejected -> when {
            // The server could not answer the question — it is not answering it in the
            // negative. Checked before the code so that a 5xx carrying a stray code, or a
            // rate-limited response shaped like a refusal, still reads as unavailable.
            probe.httpStatus != null && probe.httpStatus >= 500 -> AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE
            probe.httpStatus == TOO_MANY_REQUESTS -> AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE

            probe.errorCode == USER_NOT_FOUND -> AuthLifecycleState.SERVER_ACCOUNT_GONE

            probe.errorCode in SESSION_SCOPED_CODES -> AuthLifecycleState.RECOVERABLE_AUTH_FAILURE
            probe.errorCode == USER_BANNED -> AuthLifecycleState.RECOVERABLE_AUTH_FAILURE

            else -> AuthLifecycleState.RECOVERABLE_AUTH_FAILURE
        }
    }

    /** HTTP 429, `Too Many Requests` — the server declining to answer right now. */
    const val TOO_MANY_REQUESTS: Int = 429

    /**
     * What the local attendance ownership must become once [goneUserId]'s account has been
     * established as gone.
     *
     * ### Why the answer is "keep it"
     *
     * The obvious reading — "the owner is deleted, so clear the owner row" — is the one
     * move this must never take, and the reason is in the push path rather than in the
     * ownership table. `AttendanceSyncStore.preparePush` decides what to upload from
     * `initialPushDone` on the *incoming* account's state row, and a uid this device has
     * never synced reads as `false` — the condition that makes a first push take
     * **every local row**. So releasing the claim hands the next account an initial push
     * over the outgoing account's entire attendance history. That is precisely the leak
     * [OwnershipVerdict] exists to prevent, and clearing the row would open it.
     *
     * Keeping the claim closes it from the other side: the next account is a *different*
     * uid, so the ordinary mismatch rule refuses it in both directions and nothing moves
     * until the student has been asked. The way out is the one that already exists —
     * the explicit, confirmed account switch, which clears the local attendance, clears
     * the tombstones and claims the new account, in one transaction. A deletion therefore
     * invents no new cleanup and destroys nothing on its own.
     *
     * ### What the deletion path does write
     *
     * The claim stays, and it is **marked terminated** —
     * [com.attendo.data.sync.AttendanceSyncStore.terminateAttendanceClaim]. One column on the
     * row that already answers "whose attendance is this", recording that the answer can no
     * longer change by the owner coming back. It is not a release and not a wipe: the rows
     * stay exactly where they are, still A's.
     *
     * The distinction it buys is the one this function's name cannot express on its own.
     * *Retained and live* means "a different account is trying to use someone else's phone —
     * ask before replacing". *Retained and terminated* means "the account this phone was
     * backing up no longer exists, so whoever signs in next is starting fresh". Both keep the
     * body; only the second may end in the body being set aside rather than replaced by an
     * approval, and only the second is a lifecycle that has already ended. See
     * [com.attendo.core.sync.AttendanceSyncPolicy.ownershipVerdict], whose
     * `QUARANTINE_NEW_LIFECYCLE` is that second state.
     *
     * [goneUserId] is not consulted beyond naming the row in the answer, because "which uid
     * does the row name" does not depend on it. It is a parameter so the caller's intent is
     * explicit at the call site and so this can be asserted directly.
     *
     * Production deletion does not call this and then write the result back: the Announce
     * path marks the claim terminated by uid and leaves the row otherwise untouched —
     * Community rows are discarded and the session is cleared; the attendance rows are left
     * as they were. This function names that retained state so tests can assert it; it is not
     * the mutation the deletion path applies.
     */
    fun ownerAfterAccountDeletion(ownerUserId: String?, goneUserId: String): OwnerAfterDeletion = when {
        ownerUserId == null -> OwnerAfterDeletion.NoOwner
        ownerUserId == goneUserId -> OwnerAfterDeletion.NamesGoneAccount(ownerUserId)
        else -> OwnerAfterDeletion.NamesAnotherAccount(ownerUserId)
    }

    /**
     * What a finished validation does to the "your account was removed" record.
     *
     * The requirement is that the explanation is shown **once per deletion**, and not again
     * every time the student returns to the app — so the rule cannot be "announce whenever
     * the state is [AuthLifecycleState.SERVER_ACCOUNT_GONE]", because that state is
     * re-established on every foreground for as long as the app runs. It is instead keyed on
     * the uid the deletion was announced for: a second validation of the same gone account
     * finds [announcedFor] already equal to it and announces nothing.
     *
     * The record is cleared by a validation that comes back [AuthLifecycleState.VALID],
     * which is the one thing that makes the sentence untrue — a different account, signed
     * into successfully. [AuthLifecycleState.RECOVERABLE_AUTH_FAILURE] and
     * [AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE] deliberately leave it alone in both
     * directions: they neither announce anything (they are not evidence of a deletion) nor
     * clear the record (they are not evidence the deletion stopped being true), which is
     * what keeps a dropped connection from resurrecting the message or erasing it.
     *
     * [uid] is null when no session could be read at all; a deletion cannot be attributed to
     * anyone then, so it is not announced.
     */
    fun announcementFor(
        state: AuthLifecycleState,
        uid: String?,
        announcedFor: String?,
    ): DeletionAnnouncement = when {
        state == AuthLifecycleState.VALID -> DeletionAnnouncement.Clear
        !state.endsLocalAccountLifecycle -> DeletionAnnouncement.None
        uid == null -> DeletionAnnouncement.None
        announcedFor == uid -> DeletionAnnouncement.None
        else -> DeletionAnnouncement.Announce(uid)
    }
}

/**
 * The three things a validation can do to the deletion record — see
 * [AuthLifecyclePolicy.announcementFor].
 *
 * A value rather than an instruction pair ("set the flag", "clear the flag") so the
 * once-per-deletion rule is assertable as a sequence of calls in a test rather than as the
 * absence of a line in a coroutine.
 */
sealed interface DeletionAnnouncement {

    /** Nothing changes: not a deletion, or a deletion already announced for this account. */
    data object None : DeletionAnnouncement

    /** Announce [userId]'s deletion, and remember that it has been announced. */
    data class Announce(val userId: String) : DeletionAnnouncement

    /** The record is no longer true — a valid account answered — so forget it. */
    data object Clear : DeletionAnnouncement
}

/**
 * What the ownership table holds, read against an account the server has established is
 * gone.
 *
 * In both non-empty cases the claim is **retained**. This type exists so that "we looked
 * at the owner row and deliberately left it alone" is a value the tests can assert, rather
 * than an absence of code that reads the same as having forgotten about it.
 *
 * Retained is not unchanged: the deletion path marks the claim terminated as well. See
 * [AuthLifecyclePolicy.ownerAfterAccountDeletion].
 */
sealed interface OwnerAfterDeletion {

    /** No account owns the local attendance. Nothing to do; the next account claims it. */
    data object NoOwner : OwnerAfterDeletion

    /**
     * The claim names the deleted account. It is kept — and marked terminated — so the rows
     * stay that account's rather than being handed to whoever signs in next. A later sign-in
     * is a new lifecycle, not an inheritance; see
     * [com.attendo.core.sync.AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE].
     */
    data class NamesGoneAccount(val userId: String) : OwnerAfterDeletion

    /**
     * The claim names some other account — a switch that already happened, or a session
     * that was replaced. This deletion says nothing about it, so it is left exactly as it
     * was.
     */
    data class NamesAnotherAccount(val userId: String) : OwnerAfterDeletion
}
