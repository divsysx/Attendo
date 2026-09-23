package com.attendo.data.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 6's two read-path contracts, pinned to source in this repo's established
 * style (see `AppResetContractTest`): both live in *structure* — which columns a
 * request asks for, which call mints the identity — so a refactor that quietly
 * drops either would ship green and break only against the real backend.
 *
 *  1. **The polls read names its columns.** Migration `0006_column_security.sql`
 *     grants `authenticated` SELECT on 12 of polls' columns — deliberately not
 *     `creator_id` or `idempotency_key` — and Postgres column privileges are
 *     all-or-explicit: `select=*` needs every column, fails with permission
 *     denied, and `openPolls()`'s own `runCatching` would turn that into a silent
 *     null. Polls would be dead on every screen with no error shown anywhere.
 *
 *  2. **Reading is a community contact (decision D1-A).** Every community read is
 *     granted to `authenticated` only, so a fresh install cannot read until it
 *     holds an anonymous identity — and the read path mints it, so a student who
 *     never reports anything still sees rooms and polls on first open. The flip
 *     side is the invariant the decision came with: nothing *outside* a community
 *     surface may mint one.
 */
class CommunityReadContractTest {

    // ------------------------------------------------- the polls column contract

    @Test
    fun `the polls read names exactly the columns migration 0006 grants`() {
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt").readText()

        // The read must go through the named list, not the table default.
        assertTrue(
            "The polls read must use Columns.list(POLL_SELECT_COLUMNS) — a bare select() " +
                "sends select=* and fails against 0006's column grant.",
            source.contains("""postgrest["polls"].select(Columns.list(POLL_SELECT_COLUMNS))"""),
        )
        assertFalse(
            "A bare select() on polls must never return — select=* needs creator_id and " +
                "idempotency_key, which the grant deliberately withholds.",
            source.contains("""postgrest["polls"].select()"""),
        )

        // And the list itself must be exactly PollRow's columns — no more (a column
        // outside the grant kills the request), no fewer (a PollRow field with no
        // column in the request decodes as default and lies).
        val declared = pollSelectColumns(source)
        assertEquals(
            setOf(
                "id", "room", "section", "subject", "class_date", "start_hour",
                "question", "status", "created_at", "closes_at",
            ),
            declared,
        )
        for (forbidden in listOf("creator_id", "idempotency_key")) {
            assertFalse(
                "$forbidden must never be requested: the column grant withholds it, so " +
                    "asking for it fails the whole read.",
                forbidden in declared,
            )
        }
    }

    @Test
    fun `the column list covers every field PollRow decodes`() {
        // The mirror of the test above, from the DTO's side: each PollRow property
        // must appear in the requested columns, or the decode silently defaults it.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt").readText()
        val declared = pollSelectColumns(source)

        val pollRowBlock = source.substringAfter("private data class PollRow(").substringBefore(")")
        val fields = Regex("""^\s*(?:val|var)\s+(\w+)""", RegexOption.MULTILINE)
            .findAll(pollRowBlock)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals("POLL_SELECT_COLUMNS must request exactly PollRow's fields.", fields, declared)
    }

    // ------------------------------------------------------ the claim wire shape

    @Test
    fun `claim parameters ride the report body only for claims`() {
        // Migration 0007 gave submit_report's three new parameters defaults, so an old
        // server still has the ten-parameter signature — and PostgREST accepts exactly
        // the old body for a present or historical report. Sending `p_observation_type`
        // unconditionally would be PGRST202 (unknown parameter) against that server and
        // break every non-claim report on an unmigrated backend. The guard is the whole
        // compatibility story; this pin is what keeps a "simplification" from removing it.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt").readText()

        val guardAt = source.indexOf(
            "if (observationType == CommunityObservationType.FUTURE) {"
        )
        assertTrue(
            "The claim parameters must be guarded by the FUTURE check.",
            guardAt >= 0,
        )
        val bodyEnd = source.indexOf(")", source.indexOf("\"submit_report\""))
        for (param in listOf("p_observation_type", "p_event_date", "p_target_start_hour")) {
            val putAt = source.indexOf("putNullable(\"$param\"", guardAt)
                .let { if (it >= 0) it else source.indexOf("put(\"$param\"", guardAt) }
            assertTrue(
                "$param must be written only inside the FUTURE guard (after $guardAt).",
                putAt > guardAt,
            )
            // And never outside the guard within the submit body.
            val earlier = source.indexOf("\"$param\"")
            assertTrue(
                "$param must not appear before the FUTURE guard in submitReport.",
                earlier >= guardAt || earlier == -1,
            )
        }
    }

    @Test
    fun `observation rows from a pre-claim server decode as present observations`() {
        // Old-backend compatibility, from the DTO's side: every claim field must be
        // nullable with a default, because a server that predates migration 0007 sends
        // rows with none of them — and a missing field that is not defaulted is a decode
        // failure, which the read path turns into a dropped row (a report disappearing
        // for no reason the student can see).
        // Cut at the class's own closing paren (line start) — the field comments inside
        // the DTO mention "(migration 0007)", and a plain substringBefore(")") would cut
        // at the comment instead.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt").readText()
        val block = source.substringAfter("private data class ObservationDto(")
            .substringBefore("\n)")
        for (field in listOf("observation_type", "event_date", "target_start_hour", "target_end_hour")) {
            val declaration = Regex("""val\s+$field[^,]*""")
                .find(block)
                ?: throw AssertionError("$field must exist on ObservationDto — the view sends it since 0007.")
            assertTrue(
                "$field must default to null on the wire — a pre-0007 server sends rows " +
                    "without it.",
                declaration.value.contains("= null"),
            )
        }
    }

    // -------------------------------------------------- the identity mint (D1-A)

    @Test
    fun `the read path mints the identity before it fetches`() {
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()

        // The gate exists, and it is a session gate: the client check first (a build
        // without credentials must never attempt a mint), the session second.
        val helperAt = source.indexOf("private suspend fun authenticatedApi()")
        assertTrue("The read path must go through authenticatedApi().", helperAt >= 0)
        val helper = source.substring(helperAt, source.indexOf("}", helperAt))
        val clientAt = helper.indexOf("val client = client ?: return null")
        val mintAt = helper.indexOf("ensureSession")
        assertTrue("authenticatedApi() must bail before the mint when no client is configured.", clientAt >= 0)
        assertTrue(
            "authenticatedApi() must ensure the anonymous session — reading is a community " +
                "contact (D1-A): a fresh install reads without first contributing.",
            mintAt > clientAt,
        )

        // Both reads fetch *through* the gate, never around it.
        val refreshGate = source.indexOf("authenticatedApi() ?: return false")
        val refreshRead = source.indexOf("api.snapshot()")
        assertTrue("refresh() must fetch through authenticatedApi().", refreshGate in 0 until refreshRead)

        val pollsGate = source.indexOf("authenticatedApi() ?: return null")
        val pollsRead = source.indexOf("api.openPolls()")
        assertTrue("openPolls() must fetch through authenticatedApi().", pollsGate in 0 until pollsRead)
    }

    @Test
    fun `an empty outbox still mints nothing`() {
        // The sync engine is woken at app start on every install — if it minted, the
        // identity would exist before the student ever saw a community card, and the
        // mint would belong to the process, not to a community contact.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunitySync.kt").readText()
        val drainAt = source.indexOf("private suspend fun drainOnce()")
        val drain = source.substring(drainAt, source.indexOf("private suspend fun sendRows"))
        val rowsAt = drain.indexOf("val rows = pendingRows()")
        val mintAt = drain.indexOf("ensureSession")
        assertTrue("The drain pass must count rows before it considers a mint.", rowsAt >= 0)
        assertTrue(
            "The drain must mint only for rows to send — an empty outbox mints nothing.",
            mintAt > rowsAt,
        )
    }

    @Test
    fun `only community surfaces can reach the community read path`() {
        // The invariant D1-A came with: the identity is minted by the first community
        // *view*, never by unrelated app usage. Two structural pins: only the identity
        // store's own package may touch the session, and only the screens that render
        // community data may drive the community ViewModel. Construction is exempt —
        // the ViewModel is constructed once at the app level so every surface shares
        // one state and one Realtime channel, and construction mints nothing: the
        // engine starts on the first onShown(), which only a community surface calls.
        //
        // Phase 2B adds one deliberate exception, recorded here: the account layer
        // (data/account) may *mint* — because connecting GitHub (the first-time
        // account path) attaches the identity to a user, and an install that never
        // touched the community feature has no user yet to attach it to. A button
        // press for an explicit identity action is the same category of contact as a
        // community view, not "unrelated app usage"; D1-A's point was that *passive*
        // usage — opening the app, marking attendance, exporting a backup — mints
        // nothing. The exception is mint-only: ending the session stays with the
        // store's own package (Clear All), and the returning-user sign-in path mints
        // nothing at all — it must work on a phone with no session, so it never
        // creates one.
        val mainSources = File(sourceFile("src/main/kotlin/com/attendo/data/community").absolutePath)
            .parentFile!!.parentFile!!.parentFile!!.parentFile!! // …/src/main/kotlin
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val communityBearingScreens = setOf(
            "RoomsScreen.kt",          // the Rooms tab's community cards
            "RoomDetailScreen.kt",     // a room's observations and polls
            "MyCommunityScreen.kt",    // the student's own reports and polls (in ui/community,
                                       // so allowed by the package rule too — listed for the record)
        )

        for (file in mainSources) {
            val text = file.readText()
            // Minting and ending: the session's two operations. Minting may happen from
            // the community package or the account layer's link action (see above);
            // ending stays in the store's own package. (AppReset may name
            // CommunityIdentityStore.FILE_NAME in its cleared-files list — listing the
            // file is not ending the session; clearForResetWith, inside the store's
            // own package, does that for it.)
            val inCommunity = file.parentFile!!.absolutePath.endsWith("data/community")
            val inAccount = file.parentFile!!.absolutePath.endsWith("data/account")
            if (text.contains("ensureSession(")) {
                assertTrue(
                    "${file.name} calls ensureSession: only data/community (community " +
                        "contact) and data/account (an explicit Sign-in press) may mint " +
                        "the anonymous identity.",
                    inCommunity || inAccount,
                )
                if (inAccount) {
                    // And the account layer mints from exactly one place: the link
                    // action itself. A second call site — an observer, an init, a state
                    // fold — would be passive minting, the thing D1-A forbids.
                    val linkAt = text.indexOf("suspend fun linkWithGitHub")
                    val mintAt = text.indexOf("ensureSession(")
                    assertTrue(
                        "AccountManager may call ensureSession only inside linkWithGitHub: " +
                            "a press of the sign-in button, never anything automatic.",
                        linkAt in 0 until mintAt,
                    )
                    assertEquals(
                        "AccountManager has exactly one reason to mint: the link action.",
                        1,
                        Regex("ensureSession\\(").findAll(text).count(),
                    )
                }
            }
            if (text.contains("signOutAndWipe(")) {
                assertTrue(
                    "${file.name} calls signOutAndWipe: only data/community may end the " +
                        "identity (Clear All, via the store's own package). The account " +
                        "layer never signs out — unlinking is not an offered action.",
                    inCommunity,
                )
            }
            if (text.contains("CommunityViewModel(") || text.contains("CommunityViewModel.")) {
                val insideFeature = file.parentFile!!.absolutePath.endsWith("ui/community")
                val isBearingScreen = file.name in communityBearingScreens
                // The app-level construction site: the one place outside a community
                // surface allowed to *construct* the shared ViewModel. It may never
                // also start the engine — an onShown() here would mint on app open.
                if (file.name == "AttendoApp.kt") {
                    assertTrue(
                        "AttendoApp.kt may construct the shared community ViewModel, but " +
                            "must never call onShown() — that would mint an identity on " +
                            "unrelated app usage (D1-A). The screens call it when they appear.",
                        !text.contains(".onShown()"),
                    )
                    continue
                }
                assertTrue(
                    "${file.name} references the community ViewModel: only the screens that " +
                        "render community data (and the app-level construction site) may.",
                    insideFeature || isBearingScreen,
                )
            }
        }

        // And the bearing screens must actually drive it: a screen that renders
        // community data but never calls onShown() would neither refresh on entry nor
        // start the shared engine — the exact "go back and come back to see the new
        // state" bug the shared ViewModel exists to end.
        for (screen in communityBearingScreens) {
            val file = mainSources.firstOrNull { it.name == screen } ?: continue
            assertTrue(
                "$screen must call communityViewModel.onShown() when it appears.",
                file.readText().contains("onShown()"),
            )
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** The value of POLL_SELECT_COLUMNS, split into its column names. */
    private fun pollSelectColumns(source: String): Set<String> {
        val match = Regex("""POLL_SELECT_COLUMNS\s*=\s*"([^"]+)"""").find(source)
        assertTrue("POLL_SELECT_COLUMNS must be declared as a quoted column list.", match != null)
        return match!!.groupValues[1].split(",").map { it.trim() }.toSet()
    }

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
