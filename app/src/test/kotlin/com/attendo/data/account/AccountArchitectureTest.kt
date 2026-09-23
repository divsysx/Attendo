package com.attendo.data.account

import com.attendo.data.AppReset
import com.attendo.data.community.CommunityIdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one-session architecture, pinned as source.
 *
 * Phase 2's account is the community session with a GitHub identity attached — never a
 * second client, a second session store, or a second uid (see [AccountManager]). The
 * behaviour tests cover what that state means; this file covers what it is built from,
 * the same way [com.attendo.data.community.CommunityIdentityBackupGuardTest] pins the
 * identity-never-in-a-backup invariant. A future edit that "simplifies" account setup by
 * creating its own Supabase client — its own session, its own uid, and an orphaned
 * reporter — fails here instead of shipping.
 *
 * The real OAuth browser round-trip cannot run in a JVM test; what *can* be pinned is
 * everything around it, for **both** flows the account offers: the link path asks the
 * identity-linking endpoint through the existing client (never a second client, never
 * a secret key) and opens the provider URL it answers with, while the sign-in path
 * asks the plain authorize endpoint (a page the browser opens alone) and destroys
 * nothing at the press — the outgoing identity's cleanup happens in the callback's
 * import window, after the sign-in has succeeded, under the drain engine's send gate.
 * The deep link returns to that same client either way. The end-to-end flow needs one
 * manual pass on a device — see the Stage 2B report for the exact steps.
 */
class AccountArchitectureTest {

    /** The repo's main source tree, found by walking up from wherever Gradle runs the test. */
    private val mainSources: List<File> =
        repoRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun repoRoot(): File =
    generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .map { File(it, "app/src/main/kotlin") }
        .first(File::exists)

    private fun filesMentioning(needle: String): List<File> =
        mainSources.filter { it.readText().contains(needle) }

    // ------------------------------------------------------------ the one client

    @Test
    fun `the app builds exactly one Supabase client`() {
        val builders = filesMentioning("createSupabaseClient(")

        assertEquals(
            "Exactly one SupabaseClient may exist per process — the community one, which " +
                "the account layer shares. Found builders in: ${builders.map { it.name }}. " +
                "A second client means a second session, and a second session eventually " +
                "means a second identity, which orphans the reporter this install already is.",
            listOf("CommunityClient.kt"),
            builders.map { it.name },
        )
    }

    @Test
    fun `the one session manager is the community identity store`() {
        val managers = filesMentioning("SettingsSessionManager(")

        assertEquals(
            "The community identity file is the app's only auth session store; the account " +
                "layer must reuse it, not add a sibling. Found in: ${managers.map { it.name }}.",
            listOf("CommunityClient.kt"),
            managers.map { it.name },
        )
        // And it is the same dedicated file the community feature always used.
        assertTrue(
            sourceFile("app/src/main/kotlin/com/attendo/data/community/CommunityClient.kt").readText()
                .contains("CommunityIdentityStore.FILE_NAME"),
        )
    }

    @Test
    fun `the account manager persists nothing of its own`() {
        // The account's persistence is the session store, full stop. Any file of its own
        // — preferences, a cache, a database table — would be a second place the account
        // lives, and a second place a reset or a backup rule could miss.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()

        for (door in listOf("getSharedPreferences", "SharedPreferencesSettings", "SettingsSessionManager", "File(")) {
            assertFalse(
                "AccountManager must not persist anything itself (found \"$door\"): the " +
                    "session store is the account's only storage, so there is no second " +
                    "copy to go stale, leak into a backup, or survive a reset.",
                source.contains(door),
            )
        }
    }

    // ------------------------------------------------------------ the link path

    @Test
    fun `linking asks the identity-linking endpoint for a URL the browser can open`() {
        // The linking endpoint demands the apikey and the session's bearer token — both
        // travel as headers on the library's requests, and neither can ride a URL the
        // browser opens (opened bare, the endpoint answers "No API key found in
        // request"). So the press asks the endpoint itself, through the existing
        // client's authenticated request wrapper, with `skip_http_redirect` — the flag
        // that turns the endpoint's designed 302 (which the library's 2xx-only check
        // reads as failure) into `200 {"url": …}` — and opens the *provider* URL that
        // comes back, which needs none of our credentials. It must never use the
        // library's `linkIdentity` (in 3.0.3 it makes this same request *without* the
        // flag and dies on the 302), and never `signInWith` — the library's call would
        // hand the session import to its own fire-and-forget deeplink handling, which
        // is exactly what this app replaced with its own explicit import.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()

        assertTrue(
            "The link path must build its URL with getOAuthUrl on the existing client's " +
                "auth — the pure builder the library documents for linking.",
            source.contains("getOAuthUrl(Github, url = LINK_AUTHORIZE_PATH)"),
        )
        assertTrue(
            "The link path must target the identity-linking endpoint, exactly.",
            source.contains("""const val LINK_AUTHORIZE_PATH: String = "user/identities/authorize""""),
        )
        assertTrue(
            "The ask must carry skip_http_redirect=true — without it the endpoint answers " +
                "with the 302 the library's response check rejects.",
            source.contains("""queryParams[SKIP_HTTP_REDIRECT_PARAM] = "true"""") &&
                source.contains("""const val SKIP_HTTP_REDIRECT_PARAM: String = "skip_http_redirect""""),
        )
        assertTrue(
            "The ask must go through the existing client's authenticated request wrapper — " +
                "the wrapper that carries the apikey header and this session's bearer token.",
            source.contains("authenticatedSupabaseApi(client.client.auth)"),
        )
        assertTrue(
            "The browser must open the provider URL the endpoint answered with — the one " +
                "URL in the flow that carries no credentials.",
            source.contains("openInBrowser(providerUrl)"),
        )
        assertFalse(
            "The library's linkIdentity call must not come back: in 3.0.3 it makes the " +
                "authorize request without skip_http_redirect and fails on the redirect " +
                "the endpoint returns.",
            source.contains("linkIdentity("),
        )
        assertFalse(
            "The library's signInWith must not come back either: on Android it opens " +
                "the same authorize URL the sign-in path builds by hand, but its session " +
                "import is the fire-and-forget deeplink handling this app deliberately " +
                "replaced — and it could not run the sign-in discard first.",
            source.contains("signInWith("),
        )
        assertFalse(
            "The link path must not spell the plain authorize endpoint: that is the " +
                "sign-in path's endpoint, and mixing them is how a link becomes a " +
                "second user.",
            source.contains("""url = "authorize""""),
        )
    }

    @Test
    fun `signing in asks the plain authorize endpoint, mints nothing, and destroys nothing at the press`() {
        // The returning-user path. The plain authorize endpoint — `getOAuthUrl(Github)`
        // with every default — is a page the browser opens alone (it needs no apikey
        // and no session), and the server resolves the GitHub identity to the account
        // it already belongs to. Three things must never creep into this method:
        // LINK_AUTHORIZE_PATH (attaching an identity to *this* user is the other flow's
        // business), linkApi (there is no request to authenticate), and ensureSession
        // (signing in must work on a phone with no session — after Sign out, an
        // app-data clear, on another device — so minting a throwaway anonymous user
        // first would be a new uid created for nothing).
        //
        // And the press must be side-effect free, because the round-trip can end
        // without a sign-in: the student can refuse GitHub's consent screen, close the
        // browser, or lose the network, and in every one of those endings the identity
        // this phone holds must still be there, whole and usable. The old press-time
        // discard violated exactly that — it deleted the outgoing identity's rows
        // before anyone knew whether a sign-in would happen at all. The cleanup now
        // lives in the callback's import window, and nothing destructive may run
        // before the browser has even opened.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()
        val signIn = methodBodyOf(source, "signInToExistingAccount")

        assertTrue(
            "The sign-in path must build the plain authorize URL with every default — " +
                "provider github, the client's configured deep link as the redirect.",
            signIn.contains("client.client.auth.getOAuthUrl(Github)"),
        )
        for (forbidden in listOf("LINK_AUTHORIZE_PATH", "linkApi", "ensureSession", "signInWith(", "linkIdentity(")) {
            assertFalse(
                "The sign-in path must not contain \"$forbidden\": that is the link " +
                    "flow's machinery, or the library's fire-and-forget — signing in " +
                    "is a different operation and must stay one.",
                signIn.contains(forbidden),
            )
        }
        for (destructive in listOf("discardLocalCommunityRows()", "retireOutgoingIdentity(", "withSendsBlocked(")) {
            assertFalse(
                "The sign-in press must not run \"$destructive\": the round-trip can " +
                    "end without a sign-in, and a cancelled or failed attempt must " +
                    "leave the identity this phone holds exactly as it was. The " +
                    "cleanup belongs to the import window, after success is proven.",
                signIn.contains(destructive),
            )
        }
    }

    @Test
    fun `the sign-in cleanup is the community repository's, wired once each`() {
        // The account layer does not reach into Room or the community schema itself —
        // the same rule that keeps it away from attendance and away from its own
        // persistence. The cleanup is a community-storage operation in both halves:
        // the local rows (the discard, clearing exactly the three tables every other
        // identity change clears, in one transaction) and the server-side retirement
        // (one RPC, migration 0021's, whose deletions are scoped to the caller's own
        // uid server-side). The container wires each into the account manager exactly
        // once, and the send gate that sequences them comes from the drain engine
        // itself — the one object whose sends it blocks.
        val repository = sourceFile("app/src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()
        val discard = methodBodyOf(repository, "discardLocalCommunityRows")

        for (clear in listOf("clearCachedObservations()", "clearOutbox()", "clearDismissed()")) {
            assertTrue(
                "The discard must clear \"$clear\": the outgoing identity's view of the " +
                    "community goes with its outbox, the same three clears every other " +
                    "identity change makes.",
                discard.contains(clear),
            )
        }
        assertTrue(
            "The three clears are one transaction or they are three windows for a drain " +
                "to read a half-cleared outbox.",
            discard.contains("withTransaction"),
        )
        val retirement = methodBodyOf(repository, "retireOutgoingIdentity")
        assertTrue(
            "The retirement must be one RPC call on the existing client — never a " +
                "second client, never a direct table delete the client role cannot " +
                "and must not perform.",
            retirement.contains("retireReportingIdentity(destinationUserId)"),
        )
        val wiring = sourceFile("app/src/main/kotlin/com/attendo/AttendoApplication.kt").readText()
        for (wired in listOf("community\\.discardLocalCommunityRows\\(\\)", "community\\.retireOutgoingIdentity\\(", "communitySync\\.withSendsBlocked\\(")) {
            assertEquals(
                "The container wires \"$wired\" into the account manager exactly once — " +
                    "a second call site would be a second identity change nobody " +
                    "confirmed.",
                1,
                Regex(wired).findAll(wiring).count(),
            )
        }
    }

    @Test
    fun `the callback import swaps identities inside the send gate`() {
        // The import window is the one moment this phone holds both identities: the
        // callback's session for the account is parsed and its user fetched, while the
        // session the client still runs on is the outgoing one. The swap (retire the
        // outgoing identity, discard its local rows) and the session import must run
        // as ONE gated sequence — withSendsBlocked around both — so no outbox drain
        // can send a row under a session that is being replaced, and no drain can
        // mint a throwaway identity while the replacement is in flight. A swap
        // outside the gate would reopen the exact misfile the cleanup exists to
        // prevent: a row re-checked as pending, then sent after the account's session
        // landed, filed under the account.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()
        val body = methodBodyOf(source, "importCallbackSession")

        val userAt = body.indexOf("retrieveUser(session.accessToken)")
        val gateAt = body.indexOf("withSendsBlocked {")
        val swapAt = body.indexOf("retireTheOutgoingIdentityIfTheUserChanges(auth, user.id)")
        val importAt =
            body.indexOf("importSession(session.copy(user = user), source = SessionSource.External)")
        assertTrue(
            "The import must fetch the callback's user before deciding anything about it.",
            userAt in 0 until swapAt,
        )
        assertTrue(
            "The swap and the import must share one gate: the gate opens before the " +
                "swap begins.",
            gateAt in 0 until swapAt,
        )
        assertTrue(
            "The swap must run before importSession — the outgoing identity's session " +
                "is the only thing that authorises retiring it.",
            swapAt in 0 until importAt,
        )
        assertTrue(
            "The gate must still be held when the session is imported — closing it " +
                "early would let a drain send a row under the account's session " +
                "before the local rows it must not send are even discarded.",
            importAt > gateAt,
        )
    }

    @Test
    fun `the import-time cleanup fires only when the user actually changes`() {
        // The guard is the whole safety story of the cleanup. A *link* callback
        // returns the same user this phone holds — linking attaches an identity to the
        // current user, changing nothing — so its rows must survive the import
        // untouched, and a duplicate callback for the account already held must not
        // wipe that account's own pending rows either. Only a genuinely different user
        // (the sign-in callback) triggers the retirement and the discard.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()
        val helper = methodBodyOf(source, "retireTheOutgoingIdentityIfTheUserChanges")

        // The previous user is read from the live session first…
        assertTrue(
            "The cleanup must read the user this phone holds from the live session.",
            helper.contains("sessionStatus.value as? SessionStatus.Authenticated"),
        )
        // …and falls back to the session store's own file when there is no live one —
        // a pure storage read (no minting, no network, no status change), and the only
        // thing that makes the cold-start callback decide correctly: the process can
        // die in the browser, and the live status is still initializing when the
        // callback cold-starts the app, while the file still names the identity being
        // replaced.
        assertTrue(
            "The cleanup must fall back to the persisted session — loadSession on the " +
                "one session manager, never a second store or a minting call.",
            helper.contains("sessionManager.loadSession()"),
        )
        for (forbidden in listOf("ensureSession", "signInAnonymously")) {
            assertFalse(
                "The cleanup must never mint: a peek that could create an identity is " +
                    "the passive minting the community contract forbids.",
                helper.contains(forbidden),
            )
        }
        // And the cleanup is guarded by the difference itself, never by which flow
        // opened the browser — the callback cannot know that, and guessing is how the
        // link flow would lose rows it must keep.
        assertTrue(
            "The cleanup must fire on the user difference alone.",
            helper.contains("previousUserId == null || previousUserId == incomingUserId"),
        )
        // The server half runs before the local half: retiring the outgoing identity
        // is what removes its rows server-side, and doing it first means a retirement
        // that cannot be confirmed never discards anything locally either.
        assertTrue(
            "The retirement must precede the local discard — an unconfirmed " +
                "retirement must leave every local row exactly where it was.",
            helper.indexOf("retireOutgoingIdentity(incomingUserId)") in
                0 until helper.indexOf("discardLocalCommunityRows()"),
        )
        assertTrue(
            "And the local half must be the same community-repository discard every " +
                "other identity change uses — never a second clearing path with its " +
                "own idea of the tables.",
            helper.contains("discardLocalCommunityRows()"),
        )
    }

    @Test
    fun `an unknown retirement aborts the import before anything is discarded`() {
        // The one outcome the sign-in cannot proceed on is no outcome at all: if the
        // retirement RPC gave no answer, whether the outgoing identity's server rows
        // were deleted is unknown. Proceeding would abandon them with no way to reach
        // that identity again; discarding locally would destroy rows whose owner's
        // retirement was never confirmed. The only safe move is to fail the whole
        // import — nothing discarded, nothing imported, the outgoing identity fully
        // intact — which is what throwing from inside the gated swap does, and the
        // RPC's idempotency makes the student's retry free.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()
        val helper = methodBodyOf(source, "retireTheOutgoingIdentityIfTheUserChanges")

        val unreachableAt = helper.indexOf("RetirementOutcome.Unreachable")
        assertTrue(
            "The unreachable outcome must be named and handled explicitly.",
            unreachableAt >= 0,
        )
        val throwAt = helper.indexOf("error(", unreachableAt)
        val discardAt = helper.indexOf("discardLocalCommunityRows()")
        assertTrue(
            "An unknown retirement must throw — aborting the import is the safe " +
                "failure, never a guess that it probably worked.",
            throwAt in unreachableAt until discardAt,
        )
        // And a *definite* answer never throws: retired and declined both fall
        // through to the local discard, because retrying a definite answer can only
        // return the same one.
        assertTrue(
            "Retired and Declined must both be answered without failing the import.",
            helper.contains("RetirementOutcome.Retired,") &&
                helper.contains("RetirementOutcome.Declined -> Unit"),
        )
    }

    @Test
    fun `no app source or build file mentions a secret or service-role key`() {
        // The only key the app may hold is the publishable one — public by design —
        // and it enters in exactly one place: local.properties (never committed) into
        // BuildConfig. A service-role or secret key anywhere in the app would be a
        // credential it has no business holding: either bypasses every RLS policy the
        // community schema has, and the linking ask must never need one — it rides the
        // client, which already holds the publishable key.
        val sources = mainSources + listOf(sourceFile("app/build.gradle.kts"))

        for (file in sources) {
            for (needle in listOf("service_role", "serviceRole", "sb_secret", "SUPABASE_SERVICE")) {
                assertFalse(
                    "No app source may mention \"$needle\" (found in ${file.name}): the " +
                        "publishable key is the only credential this app holds.",
                    file.readText().contains(needle),
                )
            }
        }
    }

    @Test
    fun `the account screen is reached from the nav graph and rendered nowhere else`() {
        // One entry point, on purpose: the account is the install's identity, and a
        // second place rendering it would be a second place to drift out of sync with
        // it. Settings shows one compact row; the nav graph wires it; the dedicated
        // screen is the only composable that renders account state. (The screen file
        // itself is skipped — it declares the composable.)
        val callers = filesMentioning("AccountSettingsScreen(")
            .filter { it.name != "AccountSettingsScreen.kt" }
        assertEquals(listOf("AttendoApp.kt"), callers.map { it.name })
    }

    @Test
    fun `Settings keeps the account to one compact entry`() {
        // The full account controls — the sign-in button, the sign-out button, the
        // confirmation — belong to the dedicated screen. Settings may show a row that
        // navigates there, and nothing more.
        val settings = sourceFile("app/src/main/kotlin/com/attendo/ui/settings/SettingsScreen.kt").readText()

        assertTrue(
            "Settings must keep its compact Account row — the one way into the " +
                "account screen.",
            settings.contains("onOpenAccount"),
        )
        assertFalse(
            "The connect-GitHub button belongs to the Account screen, not to Settings.",
            settings.contains("Create or connect an account"),
        )
        assertFalse(
            "The sign-in button belongs to the Account screen, not to Settings.",
            settings.contains("Sign in to an existing account"),
        )
        assertFalse(
            "The sign-out button belongs to the Account screen, not to Settings.",
            settings.contains("Sign out"),
        )
    }

    // ------------------------------------------------------------ the deep link

    @Test
    fun `the login callback returns to the one client`() {
        val source = sourceFile("app/src/main/kotlin/com/attendo/MainActivity.kt").readText()

        assertTrue(
            "MainActivity must hand the OAuth callback's fragment to the account " +
                "manager — the wrapper around the one client whose session manager " +
                "holds the one session — and gate it on that client existing.",
            source.contains("importCallbackSession(fragment)") &&
                source.contains("container.accountManager") &&
                source.contains("container.communityClient"),
        )
        assertFalse(
            "MainActivity must not build its own client to receive the callback.",
            source.contains("createSupabaseClient"),
        )
        assertFalse(
            "The library's fire-and-forget deeplink import must not come back: it " +
                "launches the user fetch in its own scope with no error handling, so " +
                "a fetch that fails leaves the account silently un-signed-in with " +
                "nothing said.",
            source.contains("handleDeeplinks"),
        )
    }

    @Test
    fun `the callback session is imported explicitly, and a failed import is answered`() {
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()

        assertTrue(
            "The callback import must make the library's own three calls — parse the " +
                "fragment, fetch the user, import the completed session — in the app's " +
                "scope, so the account turns Linked the moment the session lands and a " +
                "failure becomes a notice instead of silence.",
            source.contains("parseSessionFromFragment(fragment)") &&
                source.contains("retrieveUser(session.accessToken)") &&
                source.contains("importSession(session.copy(user = user), source = SessionSource.External)"),
        )
    }

    @Test
    fun `an error callback is answered, never handed to the library`() {
        // The library's callback parser demands a token and throws on anything else —
        // on the main thread, where nothing of ours could catch it. A student denying
        // GitHub's consent screen comes back as #error=access_denied, and that intent
        // must reach the account layer (a sentence), not the parser (a crash). The
        // token check is the gate: only a callback carrying a session may go in.
        val source = sourceFile("app/src/main/kotlin/com/attendo/MainActivity.kt").readText()

        assertTrue(
            "MainActivity must check the callback's fragment for access_token before " +
                "importing anything — every other shape must stay out of the library's " +
                "parser.",
            source.contains("\"access_token=\" in fragment"),
        )
        assertTrue(
            "A denied consent (#error=access_denied) must be routed to the account " +
                "layer as a cancellation, not parsed as a session.",
            source.contains("\"error=access_denied\" in fragment") &&
                source.contains("onAuthCancelled()"),
        )
        assertTrue(
            "An error callback of any other kind must be routed to the account layer " +
                "as a refusal, carrying the server's error_code with it — one code " +
                "(identity_already_exists) says something a student can act on.",
            source.contains("onAuthRefused(errorCodeIn(fragment))"),
        )
    }

    @Test
    fun `the manifest declares exactly the login-callback deep link`() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "The browser can only return from GitHub if the manifest declares " +
                "com.attendo://login-callback as a VIEW intent-filter.",
            manifest.contains("""<data android:scheme="com.attendo" android:host="login-callback" />"""),
        )
        // singleTask: the callback lands in onNewIntent of the one running instance,
        // not on a second MainActivity stacked on top of the app.
        assertTrue(manifest.contains("""android:launchMode="singleTask""""))
        // Exactly one deep-link intent-filter: the callback. (The manifest's other
        // <data> element is the mailto *query* declaration for the feedback flow, which
        // is not an intent-filter and not navigation.) Anything else here is unrelated
        // navigation this stage was told not to add.
        val deepLinkFilters = Regex("""<intent-filter>.*?</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .filter { it.value.contains("""android:scheme="com.attendo"""") }
            .count()
        assertEquals(1, deepLinkFilters)
    }

    @Test
    fun `the client's configured callback is the one the manifest declares`() {
        val client = sourceFile("app/src/main/kotlin/com/attendo/data/community/CommunityClient.kt").readText()

        assertTrue(client.contains("""scheme = "com.attendo""""))
        assertTrue(client.contains("""host = "login-callback""""))
    }

    // ------------------------------------------------------------- reset safety

    @Test
    fun `Clear All ends the account session`() {
        // The account session is the community session in the same file, so the reset
        // that has always wiped that file wipes the account with it — and this pins
        // that no *separate* account file was ever added for a reset to miss.
        assertTrue(
            "Clear All must wipe the session file (${CommunityIdentityStore.FILE_NAME}); " +
                "an account that survived its own reset would be a reporter left alive " +
                "on a phone with no recollection of reporting.",
            AppReset.clearedPreferenceFiles.contains(CommunityIdentityStore.FILE_NAME),
        )
    }

    // ------------------------------------------------------------ sign-out safety

    @Test
    fun `signing out is one call on the one client, and touches nothing else`() {
        // Sign out is only sign out. It ends the session server-side and clears it
        // from the one session store; it must not delete data, reset the community
        // identity, or grow into the reset action — those are separate operations
        // with separate confirmations, and the account layer reaching for any of
        // them would blur exactly the line the sign-out copy promises is uncrossed.
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()

        assertTrue(
            "Sign out must be the library's signOut on the existing client's auth — " +
                "the one call that ends the session server-side and removes it from " +
                "the one session store.",
            source.contains("client.client.auth.signOut()"),
        )
        for (door in listOf("AppReset", "clearForReset", "signOutAndWipe", "AttendanceRepository", "AttendoDatabase")) {
            assertFalse(
                "AccountManager must not reach for \"$door\": sign out ends a session. " +
                    "It is not account deletion, not a community identity reset, and " +
                    "not Clear All — those stay separate, each with its own asking.",
                source.contains(door),
            )
        }
    }

    /**
     * AUTH 6 / AUTH 7: a server-authoritative deletion ends the *session* under the Community
     * send gate, and touches nothing of attendance.
     *
     * The Announce branch is the one place a deletion is carried out, and it is the existing
     * identity-change machinery reused rather than a second retirement system: the same send
     * gate the sign-in swap holds, the same three-table Community discard, and a local-only
     * `clearSession` (the account is gone, so a logout request would be answered by nothing).
     * Attendance, tombstones and the ownership claim are not mentioned — releasing the claim
     * is what would hand the next account an initial push over the gone account's rows.
     * Server-side Community retirement is not attempted either: A is gone, so the RPC would
     * be answered by nothing.
     *
     * AUTH 7 is the other half of the same method: the None and Clear branches, which is
     * what a network failure, a recoverable session refusal, or a later valid sign-in
     * actually produce, discard nothing, wipe nothing, and do not clear the session.
     */
    @Test
    fun `a deleted account discards community rows under the send gate and leaves attendance alone`() {
        val source = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()
        val body = methodBodyOf(source, "applyLifecycle")

        val announceAt = body.indexOf("is DeletionAnnouncement.Announce")
        assertTrue("applyLifecycle must have an Announce branch — that is the deletion.", announceAt >= 0)

        val announce = body.substring(announceAt)
        val gateAt = announce.indexOf("withSendsBlocked {")
        val discardAt = announce.indexOf("discardLocalCommunityRows()")
        val clearAt = announce.indexOf("clearSession()")
        assertTrue(
            "The deletion must take the same send gate the sign-in swap uses, so a drain " +
                "cannot send the gone identity's parked outbox as whoever is minted next.",
            gateAt >= 0,
        )
        assertTrue(
            "The gone identity's Community outbox, cache and dismissals must be discarded " +
                "by the existing helper — not a second clearing path.",
            discardAt >= 0,
        )
        assertTrue(
            "The session must be cleared locally. The account is gone, so a server logout " +
                "would be answered by nothing.",
            clearAt >= 0,
        )
        assertTrue(
            "Discard first (local Room, does not need the session), then clearSession — " +
                "the other order would mint an anonymous identity underneath a drain that " +
                "still held the gone account's outbox.",
            discardAt in 0 until clearAt && gateAt in 0 until discardAt,
        )
        assertFalse(
            "A deletion must not call retireOutgoingIdentity: A is gone, so the RPC would " +
                "be unanswered, and retirement remains the sign-in swap's job.",
            announce.contains("retireOutgoingIdentity("),
        )
        for (attendance in listOf("deleteAll", "clearTombstones", "switchAttendanceOwner", "claimAttendance")) {
            assertFalse(
                "A deletion must not reach for \"$attendance\": attendance stays on the " +
                    "phone, quarantined under the gone account's claim. Inventing a wipe here " +
                    "is the data-loss the lifecycle was written not to perform; the rows move " +
                    "only later, and only once the outgoing body has been set aside — see " +
                    "AttendanceSyncStore.releaseTerminatedClaim.",
                announce.contains(attendance),
            )
        }

        // ...but it does write one thing, and the guard above must not be read as forbidding
        // it. The claim is *marked*, never erased, and marked here rather than anywhere the
        // verdict is applied: this branch is the only place that knows the uid was
        // established gone by the server, and a flag set from anywhere else would be
        // inferring a deletion from something that is not one.
        assertTrue(
            "The deletion must mark this install's attendance claim as terminated — the " +
                "persisted half of SERVER_ACCOUNT_GONE, and the only thing that survives " +
                "the process death between the deletion and the next sign-in.",
            announce.contains("terminateLocalAttendanceClaim(announcement.userId)"),
        )
        assertTrue(
            "The mark must be runCatching-wrapped: it can never be allowed to stop the " +
                "session from being cleared. If it fails, the claim stays live and the next " +
                "account meets the ordinary refusal — a stale question, and a safe one, " +
                "since a refusal moves no data in either direction. The student is signed " +
                "out either way.",
            announce.contains("runCatching { terminateLocalAttendanceClaim("),
        )
        assertTrue(
            "The mark must precede the send gate. Behind it, a drain holding the gone " +
                "identity's outbox would be what stands between the student and being signed " +
                "out — and the mark is one UPDATE on this install's own row, neither a " +
                "Community write nor a send.",
            body.indexOf("terminateLocalAttendanceClaim(") in 0 until gateAt,
        )

        val clear = methodBodyOfBranch(body, "DeletionAnnouncement.Clear")
        for (destructive in listOf(
            "withSendsBlocked",
            "discardLocalCommunityRows",
            "clearSession",
            "retireOutgoingIdentity",
            "deleteAll",
            "clearTombstones",
            "switchAttendanceOwner",
            "claimAttendance",
        )) {
            assertFalse(
                "AUTH 7: a Clear (a later valid account) must not run \"$destructive\". " +
                    "Clearing the announcement record is all a successful sign-in does here.",
                clear.contains(destructive),
            )
        }
        assertTrue(
            "AUTH 7: a None (network, recoverable auth, signed-out) is a no-op.",
            body.contains("DeletionAnnouncement.None -> Unit"),
        )
    }

    /**
     * Account existence is asked in one place. Attendance Sync consults the answer at the
     * start of a pass; Community Sync does not grow a second check of its own — after a
     * gated discard the outbox is empty, and a later Community contact mints a fresh
     * anonymous identity the way signed-out Community already does.
     */
    @Test
    fun `account existence is asked only at the account manager`() {
        val manager = sourceFile("app/src/main/kotlin/com/attendo/data/account/AccountManager.kt").readText()
        assertTrue(
            "validateAccount is the one method that asks the server whether the account still exists.",
            manager.contains("suspend fun validateAccount()"),
        )
        assertTrue(
            "The ask is retrieveUser on the live access token — GET /auth/v1/user — not a " +
                "local session presence check and not JWT expiry.",
            methodBodyOf(manager, "validateAccount").contains("retrieveUser(session.accessToken)"),
        )

        val communitySync = sourceFile("app/src/main/kotlin/com/attendo/data/community/CommunitySync.kt").readText()
        assertFalse(
            "CommunitySync must not call validateAccount: scattering the account-existence " +
                "check is the competing state machine the lifecycle was written not to grow. " +
                "The send gate plus the gated discard is what keeps a drain from sending as " +
                "the gone identity, without a second classifier. The KDoc may name " +
                "AccountManager.validateAccount as a caller of the send gate — that is the " +
                "account manager asking, not a second classifier here. The call form is " +
                "what this forbids.",
            communitySync.contains("validateAccount("),
        )

        val engine = sourceFile("app/src/main/kotlin/com/attendo/data/sync/AttendanceSyncEngine.kt").readText()
        val pass = methodBodyOf(engine, "pass")
        assertTrue(
            "Attendance Sync consults the centralized check at the start of a pass, and " +
                "stops only when the verdict forbids authenticated work. The verdict may be " +
                "held in a local and then asked — the shape the diagnostics use — so the " +
                "requirement is that the call is made and that its answer is what the guard " +
                "reads, not that the two are written on one line.",
            pass.contains("validateAccount().forbidsAuthenticatedWork") ||
                (pass.contains("validateAccount()") && pass.contains("lifecycle.forbidsAuthenticatedWork")),
        )

        // ...exactly once, and only in the pass. `runPass` used to repeat the same block, which
        // cost a second GET /auth/v1/user on every sync — a full round trip to re-ask a
        // question the pass had just answered, on the path that runs most often. Correctness
        // does not depend on the count (both probes return the same verdict), so this is
        // pinned as the shape it should keep: one gate per pass, owned by the two callers that
        // already hold one.
        // AUTH 7: and the verdict is a *stop*, not a note. Both gate owners must return before
        // `runPass`, because the whole requirement is that no authenticated attendance work
        // happens once the server has said the account is gone — a pass that logged the verdict
        // and continued would sync the deleted account's rows under a session that no longer
        // has an account behind it.
        for (gateOwner in listOf("pass", "syncNow")) {
            val owner = methodBodyOf(engine, gateOwner)
            val guardAt = owner.indexOf("forbidsAuthenticatedWork")
            // `runPass(` rather than `runPass()`: the two gate owners differ in the pull scope
            // they hand it (a background pass reads from the cursor, Sync now reads from the
            // beginning), so the call carries an argument. What is pinned is unchanged — the
            // call is here, and the refusal is above it.
            val runAt = owner.indexOf("runPass(")
            assertTrue("$gateOwner must ask the question.", guardAt >= 0)
            assertTrue(
                "AUTH 7: $gateOwner must stop on a forbidding verdict rather than noting it.",
                owner.contains("return@withLock SyncPassResult.AccountGone"),
            )
            assertTrue(
                "AUTH 7: in $gateOwner the refusal must come before the pass — asked " +
                    "afterwards it would be a report on work already done.",
                runAt >= 0 && guardAt in 0 until runAt,
            )
        }

        assertFalse(
            "One probe per pass, not two. `runPass` used to repeat the same block the gate " +
                "owner had already run, which cost a second `GET /auth/v1/user` — a full " +
                "round trip to re-ask a question answered microseconds earlier, on the path " +
                "that runs on every wake-up. The gate is the *caller's* job: `pass` and " +
                "`syncNow` each ask once, under the same passGate `runPass` runs inside, so " +
                "the answer cannot be overtaken by the only thing that changes it.",
            methodBodyOf(engine, "runPass").contains("validateAccount("),
        )
    }

    /**
     * AUTH 13 / 21: the terminated lifecycle is released from two named routes, and neither
     * of them is what makes it safe.
     *
     * [AttendanceSyncPolicy.OwnershipVerdict.QUARANTINE_NEW_LIFECYCLE] is the only verdict that
     * sets a gone account's body aside, and the engine's `when` is the only place a verdict is
     * turned into a write. Pinned here rather than in the store's own contract because the
     * risk is not the write — it is a second caller deciding to release on some *other*
     * evidence, which would be the "infer a deletion" failure the lifecycle forbids.
     *
     * There is one such second caller, and it is not a second piece of evidence: Account
     * Settings' approved-switch press, which re-derives the transition from the claim and takes
     * this route when the answer turns out to be
     * [AttendanceSyncPolicy.AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION] — the outgoing
     * account having been deleted while the prompt was on screen. Two routes, one store method,
     * and the store reads the claim itself — so the number below is a statement about where the
     * lifecycle is *entered*, not about what keeps it safe.
     */
    @Test
    fun `the terminated lifecycle is released from the quarantine verdict alone`() {
        val engine = sourceFile("app/src/main/kotlin/com/attendo/data/sync/AttendanceSyncEngine.kt")
        val releaseSites = engine
            .readText()
            .lines()
            .withIndex()
            .filter { (_, line) -> line.contains("releaseTerminatedClaim(") && !line.contains("suspend fun") }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }

        assertEquals(
            "releaseTerminatedClaim may be reached from exactly two routes: the " +
                "QUARANTINE_NEW_LIFECYCLE branch, and the engine's own route for the approved " +
                "press that the claim turned out to be terminated under. Every other verdict " +
                "has a safe answer that moves no data. Found: $releaseSites",
            2,
            releaseSites.size,
        )
        assertTrue(
            "One route must be the verdict's, for the linked account. Found: $releaseSites",
            releaseSites.any { it.contains("releaseTerminatedClaim(userId") },
        )
        assertTrue(
            "One route must be the press's, for the incoming account, and it must be a call " +
                "into the store rather than a second implementation of clearing. " +
                "Found: $releaseSites",
            releaseSites.any { it.contains("store.releaseTerminatedClaim(newUserId") },
        )

        // What makes both routes safe is not which branch arrived — it is that the store reads
        // the claim at the transition and takes the copy itself. Asserted here as well as in
        // the store's own contract because this is the test that would notice the guard being
        // dropped while the two call sites above stayed put.
        val store = sourceFile("app/src/main/kotlin/com/attendo/data/sync/AttendanceSyncStore.kt").readText()
        val switch = methodBodyOf(store, "switchAttendanceOwner")
        assertTrue(
            "The store's switch must set the body aside before the transaction that clears " +
                "it, so that both routes — and any route added later — inherit the ordering " +
                "rather than being trusted to reproduce it.",
            switch.indexOf("preserve()") in 0 until switch.indexOf("withTransaction"),
        )
        assertTrue(
            "The copy must be conditional on the claim being terminated: an approved switch " +
                "over a live account keeps nothing of the account the student replaced.",
            switch.indexOf("terminated") in 0 until switch.indexOf("preserve()"),
        )
        assertFalse(
            "The copy must not be swallowed — a failed write has to stop the clear, or the " +
                "rows would go without the copy that justifies their going.",
            switch.contains("runCatching"),
        )
    }

    @Test
    fun `the identity wipe belongs to the reset path alone`() {
        // signOutAndWipe is the nuclear version of ending a session: server sign-out
        // *plus* wiping the identity file. The account's sign-out must never grow
        // into it — it clears the session through the library, and the file (with
        // everything else) is the reset's to wipe, not the account screen's.
        val accountSources = mainSources
            .filter { it.path.contains("/data/account/") }
            .map { it.readText() }
        for (source in accountSources) {
            assertFalse(
                "The account layer must not call signOutAndWipe: wiping the identity " +
                    "file belongs to the reset path, not to signing out.",
                source.contains("signOutAndWipe"),
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)

    /**
     * The source of one method, from its `fun` line to the next member after it. The
     * flow pins above are about *which* flow does what — the link path asks the
     * linking endpoint, the sign-in path the plain one — so they must scope their
     * assertions to that flow's method, not to the whole file.
     */
    private fun methodBodyOf(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("$name must exist — the flow it implements is not optional.", start >= 0)
        val rest = source.substring(start)
        val end = listOf(
            rest.indexOf("\n    suspend fun "),
            rest.indexOf("\n    private suspend fun "),
            rest.indexOf("\n    fun "),
            rest.indexOf("\n    companion object {"),
            rest.indexOf("\n    private val "),
        ).filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * The source of one `when` branch inside [methodBody], from the arm's header to the
     * next arm or the closing of the `when`. Used to pin that Clear and None do not grow
     * the Announce branch's cleanup.
     */
    private fun methodBodyOfBranch(methodBody: String, armHeader: String): String {
        val start = methodBody.indexOf(armHeader)
        assertTrue("$armHeader must exist in the method under test.", start >= 0)
        val rest = methodBody.substring(start)
        val end = listOf(
            rest.indexOf("\n            is DeletionAnnouncement."),
            rest.indexOf("\n            DeletionAnnouncement."),
            rest.indexOf("\n        }"),
        ).filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
