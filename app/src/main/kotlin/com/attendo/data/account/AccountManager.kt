package com.attendo.data.account

import com.attendo.core.auth.AccountProbe
import com.attendo.core.auth.AuthLifecyclePolicy
import com.attendo.core.auth.AuthLifecycleState
import com.attendo.core.auth.DeletionAnnouncement
import com.attendo.data.community.CommunityClient
import com.attendo.data.community.CommunityIdentityStore
import com.attendo.data.community.CommunityRepository
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.authenticatedSupabaseApi
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.parseSessionFromFragment
import io.github.jan.supabase.auth.providers.Github
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/**
 * The install's account, read off the one Supabase session this app has.
 *
 * There is no second account system here, deliberately. The community feature already
 * holds a Supabase session — anonymous at first, persisted in the community identity
 * file, one per install — and Phase 2's account *is* that session. Two clients would
 * mean two sessions, two sessions would eventually mean two UIDs, and a second UID
 * would orphan the reporter this install already is; that is the whole reason this
 * class wraps [CommunityClient] instead of owning a client.
 *
 * There are exactly two ways a session becomes an account, and they are deliberately
 * not one method:
 *
 *  * **Linking** ([linkWithGitHub]) — the *first-time* path. The current user —
 *    anonymous, this install's own UID — attaches a GitHub identity to itself, so the
 *    UID never changes and every row keyed to it (reports, polls, reputation) stays
 *    exactly where it is. Nothing is migrated and nothing is re-filed; the reporter
 *    this install already is simply gains a way to sign back in.
 *
 *  * **Signing in** ([signInToExistingAccount]) — the *returning* path, for a phone
 *    that has no session (after Sign out, an app-data clear, or on another device) or
 *    holds a different, anonymous one. GitHub's identity is resolved by the server to
 *    the account it already belongs to — the same UID, the same reporter history — and
 *    that session replaces whatever this phone held. The press itself does nothing but
 *    open the browser: the outgoing identity's cleanup happens later, in the callback's
 *    import window, and only after the sign-in has succeeded (see
 *    [importCallbackSession]) — a cancelled or failed round-trip leaves everything
 *    exactly as it was.
 *
 * The two share the browser round-trip — both end at the `com.attendo://login-callback`
 * deep link, which MainActivity hands to [importCallbackSession] on this same manager —
 * but they ask the server two different things: linking attaches an identity to the
 * current user, signing in authenticates an existing one. Collapsing them into one
 * "sign in" call is how an install ends up with a second UID and an orphaned reporter,
 * which is why the previous stage shipped linking only.
 *
 * The anonymous session is created the way it always was — by
 * [CommunityIdentityStore.ensureSession], on first community contact — and this class
 * only *reads* the session's status until the student presses one of the two buttons.
 */
class AccountManager(
    private val client: CommunityClient,
    private val identityStore: CommunityIdentityStore,
    private val openInBrowser: (String) -> Unit,
    private val discardLocalCommunityRows: suspend () -> Unit,
    private val retireOutgoingIdentity: suspend (String) -> CommunityRepository.RetirementOutcome,
    private val withSendsBlocked: suspend (suspend () -> Unit) -> Unit,
    /**
     * Records, on this device, that [goneUserId]'s account was established gone — the
     * persisted half of the deletion lifecycle. See
     * [com.attendo.data.sync.AttendanceSyncEngine.terminateAttendanceClaim], which is what
     * the container wires here.
     *
     * Injected like the other two reaches into storage, and for the same reason: this class
     * is the account lifecycle and stays platform-free and storage-free, so what a deletion
     * *means* for the local attendance is decided here while how it is recorded belongs to
     * the object that owns those rows. Called only from the one branch that means "the
     * server established this account is gone", under the same guard as the announcement
     * itself — so it can no more be reached by a network failure or a recoverable refusal
     * than `clearSession` can.
     *
     * Unlike [discardLocalCommunityRows] this is not wrapped by the caller in the send gate,
     * because it is neither a Community write nor a send: one UPDATE on this install's own
     * owner row. See the call site.
     */
    private val terminateLocalAttendanceClaim: suspend (String) -> Unit,
    private val scope: CoroutineScope,
) {

    /**
     * The account, live. Derived from `auth.sessionStatus`, so everything that can move
     * it — an anonymous sign-in, the deep link returning from GitHub, a sign-out during
     * Clear All — moves this with it, with no second copy of the truth to fall stale.
     */
    val state: StateFlow<AccountState> = client.client.auth.sessionStatus
        .map(::accountStateOf)
        .stateIn(scope, SharingStarted.Eagerly, AccountState.NoSession)

    /**
     * Whether the account [state] names still exists, as established from the server.
     *
     * [state] cannot answer this and must not be asked to. A Supabase access token keeps
     * verifying after the user behind it is deleted — the signature is still good and
     * `exp` is still in the future — so an install whose account an administrator removed
     * reads as [AccountState.Linked] and syncs, indefinitely, against an account that is
     * gone. This is the second question, asked of the server rather than of the session,
     * and it is asked in exactly one place: [validateAccount].
     *
     * Starts at [AuthLifecycleState.NORMAL_SIGNED_OUT] — the honest reading of "no check
     * has been run yet" is that no authenticated lifecycle is established, and nothing is
     * permitted to act on this value until the first validation has run. Consumers that
     * gate work on it (see `AttendanceSyncEngine`) call [validateAccount] themselves
     * rather than trusting a stored answer, so a stale value is never the basis for
     * ending anyone's session.
     */
    private val lifecycleState = MutableStateFlow(AuthLifecycleState.NORMAL_SIGNED_OUT)

    /** [lifecycleState], exposed so the app's one reading of "is this account still there?" is this one. */
    val lifecycle: StateFlow<AuthLifecycleState> = lifecycleState.asStateFlow()

    /**
     * True once the server has established that this install's account no longer exists.
     *
     * A state rather than an event, deliberately. The sentence it drives is the reason a
     * student was signed out and why the app will treat their next sign-in as a new
     * account, and a one-shot event would be delivered only if somebody happened to be
     * collecting at the instant the check ran — which, on a validation triggered from the
     * application scope, is usually nobody. Staying true until a *new* sign-in resolves it
     * also gives the requirement "shown once per deletion, not on every foreground" for
     * free: the flag is set once, and repeated validations of the same deleted account
     * cannot set it again.
     *
     * Cleared only by a successful validation of a different account, which is the one
     * thing that makes it no longer true.
     */
    private val removedAccountState = MutableStateFlow(false)

    /** [removedAccountState], for the Account screen's explanation. */
    val accountRemoved: StateFlow<Boolean> = removedAccountState.asStateFlow()

    /**
     * Serialises validation so two callers cannot probe at once, and so the
     * announce-once bookkeeping below has a single writer.
     */
    private val validationGate = Mutex()

    /** The deleted account already announced, so one deletion produces one sentence. */
    private var announcedDeletionFor: String? = null

    /** What the browser round-trip concluded on its own, without a second press. */
    private val handoffNotices = MutableSharedFlow<HandoffNotice>(extraBufferCapacity = 1)

    /**
     * Asks the server whether the account this install's session names still exists, and
     * applies the answer.
     *
     * **The question.** `auth.retrieveUser(accessToken)` is a `GET /auth/v1/user` carrying
     * the access token as its bearer. GoTrue verifies the token first and only then loads
     * the user named by its `sub` claim, so an answer of `user_not_found` means the server
     * reached its own user table and found nothing there. That is server-authoritative
     * evidence about the *account*, and it is deliberately not derived from anything local:
     * not from `exp` (a deleted account's token is still cryptographically valid), not from
     * the session store (it still holds the session), not from `onAuthStateChange` (nothing
     * fires — the account was deleted by someone else, and the app is not told), and not
     * from any cached user object (that is the thing that would still be there).
     *
     * **The classification.** The probe is folded by
     * [AuthLifecyclePolicy.stateOf], which is pure and lives in `:core` — see its
     * documentation for the line between "the server said this account is gone" and "we
     * could not ask", and for why only `user_not_found` crosses it. Nothing here re-decides
     * that; this method's whole job is to produce an [AccountProbe] and apply the verdict.
     *
     * **The consequence.** A [AuthLifecycleState.SERVER_ACCOUNT_GONE] verdict ends this
     * install's *local* authenticated lifecycle promptly: the session is removed locally
     * (no server call — the account is gone and a logout request would be answered by
     * nothing), the flag the Account screen reads is raised, and authenticated sync stops.
     * No attendance row, no tombstone and no settings value is deleted — the ownership
     * claim keeps the rows as the gone account's, and is *marked terminated* so that the
     * next account to sign in is a new lifecycle rather than an inheritance; see
     * [AuthLifecyclePolicy.ownerAfterAccountDeletion] for why the claim is kept rather
     * than released, and what releasing it would have leaked. Community outbox, cache
     * and dismissals for the gone identity *are* discarded, under the same send gate the
     * sign-in swap uses, so parked reports cannot leave as a freshly minted anonymous
     * identity after the session is cleared. That is the existing signed-out Community
     * path (a fresh anonymous identity on next Community contact), not a second
     * identity-retirement system and not a wipe of attendance.
     *
     * Nothing else is ended. A [AuthLifecycleState.RECOVERABLE_AUTH_FAILURE] or
     * [AuthLifecycleState.NETWORK_OR_SERVER_UNAVAILABLE] verdict is recorded and returns —
     * the local session is left exactly as it was, because an unreachable server is not
     * evidence about an account and must never be turned into one.
     */
    suspend fun validateAccount(): AuthLifecycleState = withContext(Dispatchers.IO) {
        validationGate.withLock {
            val auth = client.client.auth
            // The live status first, the store second. The store read is what makes this
            // correct on a cold start (the status is still initializing while the file
            // already names the account) and during a callback, when the live status is
            // transiently unauthenticated but the session on disk is real.
            val session = (auth.sessionStatus.value as? SessionStatus.Authenticated)?.session
                ?: runCatching { auth.sessionManager.loadSession() }.getOrNull()

            val state = if (session == null) {
                // No session to ask about. Not a failure and not a deletion — the student
                // signed out, or never signed in. Asking the server here would be asking
                // it about nobody.
                AuthLifecycleState.NORMAL_SIGNED_OUT
            } else {
                val probe = runCatching {
                    auth.retrieveUser(session.accessToken)
                    AccountProbe.UserReturned
                }.getOrElse { error ->
                    // Cancellation is the screen going away, never a verdict.
                    if (error is CancellationException) throw error
                    probeOf(error)
                }
                AuthLifecyclePolicy.stateOf(probe).also {
                    // Temporary diagnostic. The verdict is what the whole pass turns on, and
                    // until now the probe's answer was not recorded anywhere: a lifecycle
                    // state that forbids work and one that permits it left identical traces.
                    // Presence only — the token is never named, and neither is its value.
                    android.util.Log.d(
                        "AttendanceSyncDiag",
                        "operation: validateAccount, probe: $probe, lifecycle verdict: $it " +
                            "(forbidsAuthenticatedWork: ${it.forbidsAuthenticatedWork}), " +
                            "tokenPresent: true, account userId: ${session.user?.id ?: "none"}",
                    )
                }
            }

            applyLifecycle(state, session?.user?.id)
            state
        }
    }

    /**
     * Records [state] and, for the one state that ends a lifecycle, ends it — once.
     *
     * The announce-once guard is keyed on the uid rather than being a bare boolean so that
     * the flag honestly means "this account's deletion has been dealt with": repeated
     * validations of the same gone account are no-ops, and a genuinely new account (which
     * validates as [AuthLifecycleState.VALID]) clears the record so a *second* deletion,
     * years later, is announced in its turn.
     */
    private suspend fun applyLifecycle(state: AuthLifecycleState, uid: String?) {
        lifecycleState.value = state

        // The rule itself is pure and lives in :core, so "announced once per deletion, and
        // never for anything that is not a deletion" is asserted as a sequence of decisions
        // in a JVM test rather than as the absence of a line in this coroutine. Everything
        // here only carries it out — and the session is cleared *inside* the one branch that
        // means "the server established this account is gone", so nothing can reach
        // clearSession by falling through a case a later edit added.
        when (val announcement = AuthLifecyclePolicy.announcementFor(state, uid, announcedDeletionFor)) {
            DeletionAnnouncement.None -> Unit

            DeletionAnnouncement.Clear -> {
                // A new, valid account is the one thing that makes the previous deletion's
                // sentence no longer true.
                announcedDeletionFor = null
                removedAccountState.value = false
            }

            is DeletionAnnouncement.Announce -> {
                announcedDeletionFor = announcement.userId
                removedAccountState.value = true

                // First, before the session goes: write down that the account this phone's
                // attendance belongs to no longer exists. It is the one durable consequence of
                // the deletion, and it has to be durable — the answer it feeds is needed
                // *after* a process death, when a new uid signs in and this app has to know
                // that the claim it can see is a leftover rather than a live second account.
                // The alternative reading of that claim is the "replace the other account's
                // data?" confirmation, asked on behalf of an account that has been deleted.
                //
                // Before the session is cleared, and outside the community send gate, because
                // it is neither a Community write nor a send: it is one UPDATE on this
                // install's own owner row, guarded on the uid that was established gone and on
                // the flag not already being set, so it is idempotent and cannot touch a claim
                // that names someone else. `runCatching` because it must not be able to stop
                // the session from being cleared: if the write fails, the claim stays live and
                // the next account to sign in meets the ordinary refusal and its confirmation
                // — a question with a stale premise, and safe, since a refusal moves no data
                // in either direction. The student is signed out either way.
                //
                // This is not the wipe the switch performs. The rows stay exactly where they
                // are; what changes is what the app knows about who can come back for them.
                // See [AuthLifecyclePolicy.ownerAfterAccountDeletion] and
                // [AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE].
                runCatching { terminateLocalAttendanceClaim(announcement.userId) }

                // Local only, and deliberately so: the account is gone, so a logout request
                // would either fail or be answered by nothing, and the point is to stop this
                // device acting as the deleted account rather than to tell the server
                // anything it does not already know. Held under the same send gate the
                // sign-in swap uses, so a drain cannot send A's parked outbox as a freshly
                // minted anonymous identity after the session is cleared. Discard first
                // (local Room only — it does not need the session), then `clearSession`,
                // which removes the stored session and moves the live status to
                // NotAuthenticated, which is what makes `state` follow on its own.
                //
                // This ends the *session*, not the account's attendance: no attendance
                // row, no tombstone and no ownership claim is deleted. Community
                // outbox/cache/dismissals for the gone identity are discarded by the
                // existing [discardLocalCommunityRows] helper — the same three-table
                // clear sign-in already uses — so they cannot be filed under whoever
                // [CommunityIdentityStore.ensureSession] mints next. Retirement of A's
                // server-side Community identity is not attempted: A is gone, so the
                // RPC would be answered by nothing.
                withSendsBlocked {
                    discardLocalCommunityRows()
                    runCatching { client.client.auth.clearSession() }
                }
            }
        }
    }

    /**
     * The verdicts that arrive as the OAuth callback — shared by both flows, because
     * the callback is one deep link no matter which button opened the browser. The
     * student answered "no" on GitHub's consent screen, or the browser came back with
     * an error instead of a session. There is no code to run at that moment — the
     * session simply did not change — so the only thing to do with these is say so,
     * and the Account screen listens here for the sentence.
     */
    val notices: SharedFlow<HandoffNotice> = handoffNotices.asSharedFlow()

    /**
     * The one request the link press makes: an authenticated GET on the *existing*
     * client's auth plugin — the same request wrapper the library's own link call uses
     * internally. It is not a second client: it borrows the client's HTTP stack, which
     * sends the publishable key as a header, and attaches this session's access token
     * as the bearer. Those two headers are exactly what the linking endpoint demands
     * and exactly what a browser URL cannot carry — an apikey-less request is answered
     * "No API key found in request", and a bearer-less one "This endpoint requires a
     * valid Bearer token". The library marks this wrapper internal; opting in is the
     * price of working around 3.0.3's `linkIdentity`, which cannot ask this endpoint
     * anything without dying on its redirect. The sign-in flow never touches it: the
     * sign-in endpoint is a plain page a browser can open alone.
     */
    @OptIn(SupabaseInternal::class)
    private val linkApi = client.client.authenticatedSupabaseApi(client.client.auth)

    /** GitHub's consent screen was answered with "no". Not an error, and not the network's fault. */
    fun onAuthCancelled() {
        handoffNotices.tryEmit(HandoffNotice.CANCELLED)
    }

    /**
     * The browser returned with an error instead of a session. The fragment carries the
     * server's own `error_code` when it has one, and exactly one of those codes says
     * something a student can act on: [IDENTITY_ALREADY_EXISTS], the GitHub account
     * already being linked to another user — the code this install's own reinstall
     * produces, because the old link outlives both the app-data clear and the
     * dashboard's deletion of the user it belonged to (see [handoffNoticeOf]). Every
     * other code, and no code at all, falls to the safe one-line refusal.
     */
    fun onAuthRefused(errorCode: String? = null) {
        handoffNotices.tryEmit(handoffNoticeOf(errorCode))
    }

    /**
     * The session the OAuth callback brought back, imported here rather than handed to
     * the library's own deeplink helper — for both flows, because the callback is one
     * deep link: a link returns this install's own user with its new identity, a
     * sign-in returns the account's user, and both end the same way.
     *
     * `handleDeeplinks` parses the fragment and then does the rest fire-and-forget in
     * the library's own scope: fetch the user, import the session — with no error
     * handling anywhere, so a user fetch that fails (and right after the browser
     * handoff is when the network is least trusted) simply never imports, and the
     * account sits un-signed-in with nothing said. The same three library calls, made
     * here in the app's own scope, turn that silent dead end into either a session —
     * which [state] picks up the instant the import emits it, no polling, no screen
     * re-entry — or a notice saying the sign-in did not finish. The fetch is also the
     * step where connectivity is most likely to be the cause — it happens right after
     * the browser handoff — so the notice it earns is classified by
     * [handoffNoticeOfFailure] rather than assuming: a transport failure is answered
     * with the connection sentence, and only a failure that is genuinely not the
     * network's gets the generic one.
     */
    fun importCallbackSession(fragment: String) {
        scope.launch {
            runCatching {
                val auth = client.client.auth
                val session = auth.parseSessionFromFragment(fragment)
                val user = auth.retrieveUser(session.accessToken)
                // The import window — the one moment this phone holds both identities
                // at once: the callback's session for the account is parsed and its
                // user fetched (the sign-in positively succeeded), while the session
                // the client still runs on is the outgoing one. The swap and the
                // import are one gated sequence: from the first line inside
                // withSendsBlocked to the last, no outbox drain may send, re-check, or
                // mint anything, so no row can leave this phone under the wrong
                // identity and no identity can be minted mid-swap.
                withSendsBlocked {
                    retireTheOutgoingIdentityIfTheUserChanges(auth, user.id)
                    auth.importSession(session.copy(user = user), source = SessionSource.External)
                }
            }.onFailure { error ->
                // Which of the import's two sentences this failure earned — and the
                // cancellation check happens inside, before anything is classified.
                handoffNotices.tryEmit(handoffNoticeOfFailure(error))
            }
        }
    }

    /**
     * The returning sign-in's cleanup, in the one window it is possible — and the one
     * window it is allowed.
     *
     * **When.** After the callback's user is fetched (the student genuinely
     * authenticated as the account) and before its session is imported (this phone
     * still acts as the outgoing identity — which is the only thing that authorises
     * deleting that identity's rows). Nothing here runs at the sign-in press: a
     * cancelled consent screen, a closed browser, a network drop or a failed import
     * never reaches this method, so the outgoing identity and everything it owns —
     * local and server — survive any failed attempt exactly as they were.
     *
     * **What.** Only when the user actually changes: the previous uid is read from the
     * live session, falling back to the session store's own file — a pure storage
     * read (no minting, no network, no status change) that makes the cold-start
     * callback decide correctly (the process can die in the browser; the live status
     * is still initializing while the file still names the identity being replaced)
     * and keeps a transiently unauthenticated live status during a *link* callback —
     * when the user does not change — from being misread as a sign-in. A link can
     * never trip this path on its own: linking attaches an identity to the current
     * user, so both reads name the same uid the callback returns.
     *
     * **In which order.** Server first, then local: the retirement RPC runs as the
     * outgoing identity, deleting its grants, profile and every identity-owned row in
     * one server-side transaction and leaving a tombstone that refuses any straggler
     * write; then the local rows go (outbox, cache, dismissals — rows that cannot
     * follow the identity switch and must not be filed under the account); then the
     * caller imports the account's session. All of it under the send gate.
     *
     * **And what failure means.** A retirement the server definitively *declined*
     * (`destination_not_established` — the GitHub login is brand new to Attendo;
     * `not_anonymous` — the outgoing session is an account, and accounts are
     * permanent) deletes nothing server-side but is still an answer: the local
     * cleanup and the import proceed, and the outgoing identity's server rows are
     * orphaned — a disclosed limitation, never a silent merge. A retirement with *no
     * answer* is the one case this method cannot survive: it throws, the whole import
     * aborts, and everything stays as it was — the outgoing identity fully intact,
     * its rows still local, the account still one press away. The RPC is idempotent,
     * so the student's retry is always safe.
     */
    private suspend fun retireTheOutgoingIdentityIfTheUserChanges(auth: Auth, incomingUserId: String) {
        val previousUserId = (auth.sessionStatus.value as? SessionStatus.Authenticated)
            ?.session?.user?.id
            ?: runCatching { auth.sessionManager.loadSession()?.user?.id }.getOrNull()
        if (previousUserId == null || previousUserId == incomingUserId) return
        when (val outcome = retireOutgoingIdentity(incomingUserId)) {
            // Retired: the server rows are gone. Declined: the server definitively
            // deleted nothing and never will — both are answers, and both leave the
            // local cleanup to do its work.
            CommunityRepository.RetirementOutcome.Retired,
            is CommunityRepository.RetirementOutcome.Declined -> Unit
            // Unreachable is not an answer: whether the retirement ran is unknown, and
            // the import must not proceed on unknown. Throwing fails the import, which
            // is the safe direction — nothing was discarded, nothing was imported, and
            // the idempotent RPC makes the retry free.
            CommunityRepository.RetirementOutcome.Unreachable ->
                error("The outgoing identity's retirement could not be confirmed.")
        }
        discardLocalCommunityRows()
    }

    /**
     * "Sign out", pressed.
     *
     * One call on the existing client's auth: the library's `signOut`, whose LOCAL
     * scope ends this session server-side and then removes it from the one session
     * store — [state] turns [AccountState.NoSession] on its own. That is all
     * sign-out is here, on purpose: it is not the reset action, not a community
     * identity rotation, and not anything that deletes a row anywhere. The account and
     * everything it owns stay on the server; what changes is only that this phone no
     * longer holds the session for them — and [signInToExistingAccount] is the way
     * back, which is what makes sign-out safe to offer.
     *
     * A sign-out that cannot reach the server fails honestly: the library keeps the
     * local session when the logout call errors, and so does this — the student is
     * told it did not happen rather than left half-signed-out.
     */
    suspend fun signOut(): SignOutcome = withContext(Dispatchers.IO) {
        runCatching { client.client.auth.signOut() }.fold(
            onSuccess = { SignOutcome.SignedOut },
            onFailure = { error ->
                if (error is CancellationException) throw error
                SignOutcome.Failed
            },
        )
    }

    /**
     * "Connect GitHub" — the first-time account path — pressed.
     *
     * Ensures the anonymous session exists first — linking attaches an identity to a
     * user, so there must be one, and an install that never touched the community
     * feature has none yet. That is [CommunityIdentityStore.ensureSession], the same
     * mint the community feature uses, never a second sign-in path.
     *
     * Then one authenticated request, [linkApi], asks the identity-linking endpoint
     * where GitHub's consent screen lives — with [SKIP_HTTP_REDIRECT_PARAM], so the
     * endpoint answers `200 {"url": …}` instead of the 302 it is otherwise designed to
     * return — and the provider URL that comes back
     * (`github.com/login/oauth/authorize?…`, which needs none of our credentials) is
     * what [openInBrowser] opens. The round-trip ends at the
     * `com.attendo://login-callback` deep link, which MainActivity hands to
     * [importCallbackSession] on this same manager, and [state] turns
     * [AccountState.Linked] on its own — with the *same* UID it had as anonymous,
     * because attaching an identity creates nothing.
     *
     * Why the detour through a request, instead of opening the linking URL in the
     * browser the way the library's own link call intends: that endpoint demands the
     * apikey and this session's bearer token, both of which travel as headers on the
     * library's requests and neither of which a browser URL can carry — opened
     * directly, it is answered "No API key found in request". And the library's own
     * call is worse: in 3.0.3 it requests the same URL *without*
     * [SKIP_HTTP_REDIRECT_PARAM], so the endpoint answers 302; the library's
     * `isSuccess` is 2xx-only, its OkHttp engine does not follow redirects, and every
     * attempt dies as a 302-flavoured exception before the browser ever opens. The
     * JSON answer is the one shape that works: the provider URL it returns is the only
     * URL in this flow that was ever meant for a browser.
     */
    suspend fun linkWithGitHub(): HandoffOutcome = withContext(Dispatchers.IO) {
        val auth = client.client.auth
        identityStore.ensureSession(client)
            ?: return@withContext HandoffOutcome.Failed(HandoffFailure.NETWORK)
        runCatching {
            val authorizeUrl = auth.getOAuthUrl(Github, url = LINK_AUTHORIZE_PATH) {
                queryParams[SKIP_HTTP_REDIRECT_PARAM] = "true"
            }
            val providerUrl = linkApi.rawRequest(authorizeUrl) {}
                .bodyAsText()
                .let(Json::parseToJsonElement)
                .jsonObject["url"]?.jsonPrimitive?.contentOrNull
                ?: error("The link authorize answer carried no URL.")
            openInBrowser(providerUrl)
        }.fold(
            onSuccess = { HandoffOutcome.Started },
            onFailure = { error ->
                if (error is CancellationException) throw error
                HandoffOutcome.Failed(handoffFailureOf(error))
            },
        )
    }

    /**
     * "Sign in to an existing account" — the returning-user path — pressed, after the
     * screen's confirmation.
     *
     * The plain sign-in endpoint, nothing else: `getOAuthUrl(Github)` with every
     * default builds `{supabaseUrl}/auth/v1/authorize?provider=github&redirect_to=…`,
     * where the redirect is the client's configured deep link
     * (`com.attendo://login-callback` — on Android the library builds it from the same
     * scheme/host settings this client has always declared). The browser is the only
     * participant that ever touches that URL: it needs no apikey and no session, so
     * there is no request to make and none is made. GitHub answers, the server
     * resolves the GitHub identity to the user it already belongs to — the same UID,
     * the same reporter history, verified on the staging project across a fresh
     * session — and the tokens come back through the *same* deep link and the *same*
     * [importCallbackSession] the link flow uses. A GitHub identity that has never
     * been used with Attendo creates a new account instead; that is the server's
     * behaviour and cannot be switched off per request, so the confirmation says so.
     *
     * Why not the library's own sign-in call: on Android it does exactly this — builds
     * the same URL with the same defaults and opens it in the browser (its
     * `startExternalAuth` is `openExternalUrl(getUrl(redirectUrl))` and nothing more),
     * so it would save nothing and would cost the one thing this flow needs that it
     * cannot offer: an outcome the app answers itself instead of the library's
     * fire-and-forget session import.
     *
     * Why no identity is minted first, the way [linkWithGitHub] mints one: signing in
     * is the one account action that must work with *no* session — after Sign out, an
     * app-data clear, on a fresh device — and an install in that state has nothing to
     * attach an identity to. `ensureSession` here would mint a throwaway anonymous
     * user on the server for no purpose at all.
     *
     * And why the press discards nothing, deletes nothing, and changes nothing: the
     * round-trip can end without a sign-in. The student can refuse GitHub's consent
     * screen, close the browser, lose the network, or come back to an error — and in
     * every one of those endings the identity this phone holds must still be there,
     * whole and usable, exactly as it was. The outgoing identity's cleanup — its
     * server rows retired, its local rows discarded — happens in the callback's
     * import window instead ([retireTheOutgoingIdentityIfTheUserChanges]), after the
     * sign-in has positively succeeded and before the account's session lands. The
     * confirmation the student answers is what promises that ordering: the signed-out
     * activity is discarded *after* the sign-in succeeds, not by attempting it.
     */
    suspend fun signInToExistingAccount(): HandoffOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val authorizeUrl = client.client.auth.getOAuthUrl(Github)
            openInBrowser(authorizeUrl)
        }.fold(
            onSuccess = { HandoffOutcome.Started },
            onFailure = { error ->
                if (error is CancellationException) throw error
                HandoffOutcome.Failed(handoffFailureOf(error))
            },
        )
    }

    companion object {
        /**
         * The identity-linking authorize endpoint — the URL path that attaches an OAuth
         * identity to the *current* user, which is the first-time account path and only
         * that. The returning-user path uses the plain `authorize` default instead:
         * the same string here would attach nothing and sign in no one.
         */
        const val LINK_AUTHORIZE_PATH: String = "user/identities/authorize"

        /**
         * The flag that makes the linking endpoint answer with `200 {"url": …}` — the
         * provider's authorize URL — instead of its designed 302 redirect, which the
         * library's 2xx-only response check reads as a failure. The URL in that answer
         * needs none of our credentials, so it is the one the browser can open.
         */
        const val SKIP_HTTP_REDIRECT_PARAM: String = "skip_http_redirect"

        /**
         * GoTrue's `error_code` for "this GitHub account is already linked to another
         * user" — the one error the callback can carry that a student can act on, and
         * the one this install's own history produces: deleting the auth user from the
         * Supabase dashboard soft-deletes it (the identity rows are kept on purpose),
         * so the GitHub link survives both that deletion and any app-data clear, and
         * the next link attempt with the same GitHub account is refused with exactly
         * this code. Expected Supabase behaviour, not an Attendo bug — which is why it
         * is surfaced as its own sentence (one that points at the sign-in button, since
         * signing in is the way back into that earlier account) rather than suppressed
         * or retried.
         */
        const val IDENTITY_ALREADY_EXISTS: String = "identity_already_exists"
    }
}

/** What one press of a GitHub button can conclude before the browser takes over. */
sealed interface HandoffOutcome {
    /** The browser is opening on GitHub's consent page. The rest arrives via deep link. */
    data object Started : HandoffOutcome

    /** The attempt did not start. The reason is one a student can act on. */
    data class Failed(val reason: HandoffFailure) : HandoffOutcome
}

/** What "Sign out", pressed, can conclude. */
sealed interface SignOutcome {
    /** The session ended server-side and left the one session store. */
    data object SignedOut : SignOutcome

    /** The logout call failed. The session is still here, and still signed in. */
    data object Failed : SignOutcome
}

/**
 * Why a handoff did not complete. The link press makes exactly one request — the
 * authenticated ask for GitHub's authorize URL — and the sign-in press makes none at
 * all, so the reachable reasons are few, and each maps to exactly one sentence in the
 * Account screen. A server "no" (a session that expired between the mint and the ask,
 * a provider switched off) falls to the safe fallback rather than a guess: the "usually
 * a rate limit" bug was exactly such a guess.
 */
enum class HandoffFailure {
    /**
     * The request could not travel: the phone has no usable network, or Supabase was
     * not reachable — to mint the anonymous session or to ask for the link URL. The
     * verdict is [isTransportFailure]'s, decided from the exception's *type* and never
     * from its message, so this is claimed only for a failure that is genuinely a
     * transport one; everything a server actually answered falls to [UNKNOWN].
     */
    NETWORK,

    /** The browser could not be opened, or anything else unexpected. The safe fallback. */
    UNKNOWN,
}

/** What the OAuth callback concluded on its own. */
enum class HandoffNotice {
    /** The student said "no" on GitHub's consent screen. */
    CANCELLED,

    /** The browser returned with an error instead of a session. */
    REFUSED,

    /**
     * The server refused the *link* because that GitHub account is already connected
     * to another user — including a user this very install used to be, since the old
     * link survives an app-data clear and the dashboard's deletion of its user. The
     * sign-in flow can never produce this: it attaches nothing.
     */
    IDENTITY_TAKEN,

    /** The callback carried a session, but it could not be imported. */
    IMPORT_FAILED,

    /**
     * The callback carried a session, but importing it failed in transport — the
     * phone lost its network between the browser handoff and the user fetch, which is
     * the one moment in this flow where that is most likely. Its own notice rather
     * than [IMPORT_FAILED] because the student can act on it: the sign-in itself
     * succeeded at GitHub, nothing was discarded, and retrying once there is a
     * connection is all it takes. Decided by [handoffNoticeOfFailure], by exception
     * type alone — a callback the server *answered* can never land here.
     */
    NETWORK,
}

/**
 * How many cause hops [isTransportFailure] follows before giving up.
 *
 * A decision, not an implementation detail. Past this many hops the answer is "not a
 * transport failure", which sends the student to the generic "please try again"
 * sentence rather than claiming a connectivity problem the app could not actually
 * confirm — the same rule [handoffFailureOf] follows for every cause it cannot
 * identify. Real chains here are one or two deep; the bound exists so a pathological
 * or cyclic one terminates instead of spinning, and it is `internal` so the tests can
 * pin it from both sides.
 */
internal const val MAX_CAUSE_HOPS: Int = 8

/**
 * Whether [error] is a transport failure — the network could not carry the request —
 * as opposed to anything a server answered or the app itself got wrong.
 *
 * Two things make this worth more than the `is IOException` check it replaces:
 *
 *  * **The cause chain is walked.** Whether a transport error arrives bare or wrapped
 *    depends on which layer noticed it first — Ktor's engine, supabase-kt's request
 *    wrapper, the auth plugin — and that is not something this app should have to
 *    guess, or keep in step with a library version. Following `cause` answers the
 *    question the same way whoever wrapped it; asking after the *wrapper's* type
 *    instead would be the "usually a rate limit" guess in a new costume.
 *  * **[UnresolvedAddressException] is not an [IOException].** It extends
 *    `IllegalArgumentException`, so a DNS failure that surfaces as one — which Ktor
 *    can do, depending on the engine and the path — would otherwise read as an
 *    unidentified failure and lose its connection sentence.
 *
 * **Never by message text.** No `message` is read, matched or searched: an unrelated
 * failure that happens to say "network" is not a network failure, and a transport
 * failure that says nothing is still one. The exception's type is the whole test.
 */
internal fun isTransportFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    var hops = 0
    while (current != null && hops <= MAX_CAUSE_HOPS) {
        if (current is IOException || current is UnresolvedAddressException) return true
        val next = current.cause
        // A cause that points back at itself would spin forever; stop instead.
        if (next === current) break
        current = next
        hops++
    }
    return false
}

/**
 * What a failed user fetch establishes about the account — the bridge from the exception
 * the library threw to the evidence [AuthLifecyclePolicy] rules on.
 *
 * The order of the branches is the safety argument, and it is the same one
 * [isTransportFailure] and [handoffFailureOf] already make: the *type* decides, never the
 * message. A transport failure is checked before anything is read off the exception, so a
 * dropped connection cannot be talked into looking like a server verdict by the text of
 * whatever wrapped it.
 *
 * A [RestException] is an answer — the server replied and this is what it said — and its
 * `error` field is GoTrue's own `error_code` (the typed [AuthRestException.errorCode] is
 * preferred where the library recognised it, since that is the enum the vocabulary is
 * checked against). Its status travels with it, because a 5xx is the server declining to
 * answer rather than answering "no".
 *
 * Anything else — a parse failure, a shape this app does not recognise — is deliberately
 * **not** read as evidence about the account. It is reported as a rejection with no code
 * and no status, which [AuthLifecyclePolicy.stateOf] folds to a recoverable failure: the
 * session may be finished, and the account is emphatically not declared gone. Reading an
 * unknown failure as a deletion is the one mistake this whole path exists to avoid.
 */
internal fun probeOf(error: Throwable): AccountProbe = when {
    error is AuthRestException ->
        AccountProbe.Rejected(error.errorCode?.value ?: error.error, error.statusCode)

    error is RestException -> AccountProbe.Rejected(error.error, error.statusCode)

    isTransportFailure(error) -> AccountProbe.Unreachable

    else -> AccountProbe.Rejected(errorCode = null, httpStatus = null)
}

/**
 * The fold from whatever the press threw to the one reason worth showing. Kept
 * top-level and pure, the same way [accountStateOf] is, so the mapping is pinned by
 * tests without a client or a device.
 */
internal fun handoffFailureOf(error: Throwable): HandoffFailure {
    // A cancelled call is cancellation — rethrow it, never answer it. Checked before
    // anything is classified, which is also what keeps a TimeoutCancellationException
    // out of NETWORK: it *is* a CancellationException, and a timeout the coroutine
    // machinery raised is not the transport's verdict on the network.
    if (error is CancellationException) throw error
    // Transport failures are never the student's server saying no; everything else
    // gets one safe sentence. The exact cause is not the student's problem, and
    // guessing at it is how the "usually a rate limit" bug shipped.
    return if (isTransportFailure(error)) HandoffFailure.NETWORK else HandoffFailure.UNKNOWN
}

/**
 * The notice a failed import earned. Kept top-level and pure, like [handoffFailureOf],
 * so the mapping is pinned by tests without a client or a device.
 *
 * Cancellation is checked first and rethrown, for the same reason the failure fold
 * does it: an import cancelled is the screen going away, not a verdict for the
 * student. After that there is one question — did the import fail in transport, or for
 * some other reason (a fragment the server answered with an error, a session the
 * library refused, a retirement that could not be confirmed)? Only the first earns the
 * connection sentence; every other reason keeps the generic one it has always had.
 */
internal fun handoffNoticeOfFailure(error: Throwable): HandoffNotice {
    if (error is CancellationException) throw error
    return if (isTransportFailure(error)) HandoffNotice.NETWORK else HandoffNotice.IMPORT_FAILED
}

/**
 * The notice an error callback deserves, from the `error_code` it carried. Kept
 * top-level and pure, like [handoffFailureOf], so the mapping is pinned by tests
 * without a client or a device. Exactly one code earns its own sentence — the one that
 * names a state the student can recognise ("my old install") — and everything else,
 * known or unknown, gets the same safe refusal: an unrecognised code is not the
 * student's problem, and guessing at it is the "usually a rate limit" bug again.
 */
internal fun handoffNoticeOf(errorCode: String?): HandoffNotice = when (errorCode) {
    AccountManager.IDENTITY_ALREADY_EXISTS -> HandoffNotice.IDENTITY_TAKEN
    else -> HandoffNotice.REFUSED
}

/**
 * The `error_code` an OAuth error callback carried, if any. The fragment is
 * `error=…&error_description=…&error_code=…`; only the code parameter is read — never
 * a description, which is prose and can mention anything — and only because one value
 * of it is worth its own sentence. A fragment without the parameter, or with an empty
 * one, answers null: the safe refusal, never a guess at what the server meant.
 */
internal fun errorCodeIn(fragment: String): String? = fragment
    .split("&")
    .firstOrNull { it.startsWith("error_code=") }
    ?.removePrefix("error_code=")
    ?.takeIf { it.isNotEmpty() }

/**
 * The account a session status describes. A pure fold, kept top-level so the mapping
 * from supabase-kt's vocabulary to ours is pinned by tests without a client or a
 * device: an authenticated user with no OAuth identities is the anonymous reporter,
 * one with identities is a linked account, and anything else is no session.
 */
internal fun accountStateOf(status: SessionStatus): AccountState = when (status) {
    is SessionStatus.Authenticated -> {
        val user = status.session.user
        val uid = user?.id ?: ""
        // An anonymous user has no OAuth identities; linking is what adds the first one.
        val linked = !user?.identities.isNullOrEmpty()
        when {
            linked -> AccountState.Linked(uid)
            uid.isNotEmpty() -> AccountState.Anonymous(uid)
            // A session whose user object never arrived: authenticated, but to nothing
            // we can name. Read as no session rather than guessed at.
            else -> AccountState.NoSession
        }
    }
    // NotAuthenticated, Initializing, RefreshFailure: initializing and a refresh
    // failure both pass, and the honest reading of each is "no usable session right
    // now" — the community write path already re-signs-in on exactly that reading.
    else -> AccountState.NoSession
}

/** Where the install stands with its account. */
sealed interface AccountState {
    /** No session on this install yet — it has never touched the community feature. */
    data object NoSession : AccountState

    /** The anonymous reporter: a random ID created on this phone, linkable but not linked. */
    data class Anonymous(val uid: String) : AccountState

    /**
     * A permanent account: either the same UID as before with a GitHub identity
     * attached (the link path — nothing was migrated, nothing re-filed), or an
     * existing account's UID restored to a phone that had lost or never held its
     * session (the sign-in path — its reporter history came back with it).
     */
    data class Linked(val uid: String) : AccountState
}
