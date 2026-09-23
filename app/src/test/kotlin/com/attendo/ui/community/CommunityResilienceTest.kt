package com.attendo.ui.community

import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.ReportPayload
import com.attendo.data.community.CommunityRepository.OutboxUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * What the app does when Supabase is unreachable, paused, or simply absent: nothing
 * breaks, nothing lies, nothing is lost.
 *
 * The first half is behavior — the [CommunityUiState] folds that decide what a community
 * section says when the last fetch failed. The second half is contract, in this repo's
 * established style of reading the real source (see `AppResetContractTest`): the
 * resilience properties that live in structure rather than in a pure function — a failed
 * refresh must not clear the cache, a restart must drain the outbox, a dead realtime feed
 * must not crash the process — are pinned to the code that implements them, so a refactor
 * that quietly drops one fails this suite instead of shipping the regression.
 */
class CommunityResilienceTest {

    private val now: Instant = Instant.parse("2026-09-04T10:00:00Z")

    private fun observation(
        id: String,
        room: String? = "203",
    ) = CommunityObservation(
        id = id,
        kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
        status = CommunityObservationStatus.REPORTED,
        room = room,
        section = null,
        subject = null,
        classDate = null,
        startHour = null,
        payload = ReportPayload(),
        note = null,
        createdAt = now.minusSeconds(60),
        expiresAt = now.plusSeconds(3600),
    )

    // ------------------------------------------------- what a section says

    @Test
    fun `a failed fetch with no cache is unavailable, not invisible`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            stale = true,
            serverNow = now,
        )

        assertEquals(CommunityUiState.Status.UNAVAILABLE, state.status)
    }

    @Test
    fun `a failed fetch with cached data is stale, and the data still renders`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            stale = true,
            serverNow = now,
            observations = listOf(observation("cached")),
        )

        // The honest word…
        assertEquals(CommunityUiState.Status.STALE, state.status)
        // …and the data it is said about: the cache is what renders, fetch or no fetch.
        assertEquals(1, state.roomSummaries.size)
    }

    @Test
    fun `a successful fetch is fresh`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            stale = false,
            serverNow = now,
            observations = listOf(observation("fresh")),
        )

        assertEquals(CommunityUiState.Status.FRESH, state.status)
    }

    @Test
    fun `pending reports outlive a fetch failure`() {
        // The outbox is a Room table the refresh never touches: a report queued while
        // offline is neither sent nor lost while Supabase is down, and Settings keeps
        // showing it as waiting.
        val state = CommunityUiState(
            available = true,
            loaded = true,
            stale = true,
            myReports = listOf(
                OutboxUiState(
                    idempotencyKey = "queued-while-offline",
                    kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                    room = "203",
                    createdAt = now,
                    status = OutboxUiState.Status.PENDING,
                    failureCode = null,
                ),
            ),
        )

        assertEquals(1, state.pendingCount)
    }

    // ------------------------------------------------- structural contracts

    @Test
    fun `a failed refresh leaves the cache alone`() {
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()

        // The null-check must bail *before* the transaction that clears the cache — if a
        // reorder ever put the clear first, every unreachable-Supabase moment would blank
        // the Rooms tab instead of merely ageing it.
        val bailAt = source.indexOf("?: return false")
        val clearAt = source.indexOf("clearCachedObservations()")
        assertTrue("refresh() must bail when the snapshot fetch fails.", bailAt >= 0)
        assertTrue("refresh() must replace the cache inside a transaction.", clearAt > bailAt)
    }

    @Test
    fun `the sync engine retries on app start, connectivity regain, and backoff`() {
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunitySync.kt").readText()

        // App start: a restart with rows pending and the network already up gets no
        // onAvailable, so start() itself must kick a drain.
        val startAt = source.indexOf("fun start()")
        val kickAt = source.indexOf("drain()", startAt)
        assertTrue("start() must drain once — a restart with pending rows cannot wait for a network flap.", kickAt > startAt)

        // Connectivity regain: the callback fires the drain.
        assertTrue(
            "The connectivity callback must drain when the network returns.",
            source.contains("override fun onAvailable(network: Network) {\n                drain()"),
        )

        // Backoff: a pass that cannot send yet reschedules itself rather than spinning
        // or giving up. (The delays themselves are unit-tested in OutboxPolicyTest.)
        assertTrue(
            "The drain loop must sleep between backoff-scheduled passes.",
            source.contains("delay(wait)"),
        )
    }

    @Test
    fun `a dead realtime feed never takes the process down`() {
        // Two layers, both deliberate: the flow itself swallows upstream errors, and the
        // collector is armoured so even a throw while *building* the channel cannot crash
        // the app. Either alone is survivable; the pair is what makes it a non-event.
        val flow = sourceFile("src/main/kotlin/com/attendo/data/community/RealtimeObservations.kt").readText()
        assertTrue(
            "The realtime flow must catch its own errors.",
            flow.contains(".catch {"),
        )

        val viewModel = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        val collectAt = viewModel.indexOf("realtime.changes().collect")
        assertTrue("The ViewModel must collect the realtime feed.", collectAt >= 0)
        val armourAt = viewModel.indexOf("runCatching {")
        assertTrue(
            "The realtime collection must be armoured — a throw there is process death.",
            armourAt in 0 until collectAt,
        )
    }

    @Test
    fun `the realtime channel is actually subscribed`() {
        // The bug this pins: postgresChangeFlow only registers a listener — nothing
        // reaches the server until the channel is subscribed, so the original wiring
        // collected a perfectly built flow that never connected, never joined, and
        // never fired. Every screen refreshed only on entry, which read to students
        // as "live updates don't work". subscribe() is the one call that opens the
        // websocket and joins with the session's token; without this pin, the same
        // silent omission can ship again.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/RealtimeObservations.kt").readText()
        assertTrue(
            "The realtime flow must call channel.subscribe() — an unsubscribed channel " +
                "delivers nothing.",
            source.contains("channel.subscribe()"),
        )
        val subscribeAt = source.indexOf("channel.subscribe()")
        val flowsAt = source.indexOf("postgresChangeFlow")
        assertTrue(
            "The pulse flow must be created before subscribe() — a postgres_change " +
                "joins the payload when its flow is created, and the library throws on " +
                "flows created after the join.",
            flowsAt in 0 until subscribeAt,
        )
        assertTrue(
            "The channel must ride the pulse table (0012): direct observation/poll " +
                "events are lost exactly when content disappears, because Realtime " +
                "delivers an UPDATE only to subscribers who can see the *new* row — " +
                "and a withdrawn or closed row is invisible to everyone but its owner. " +
                "The pulse rows are content-free and always visible.",
            source.indexOf("table = PULSES") in 0 until subscribeAt &&
                source.indexOf("const val PULSES = \"activity_pulses\"") >= 0,
        )
    }

    @Test
    fun `a dead realtime feed is rebuilt, not mourned`() {
        // The channel is app-level now: no screen entry will rebuild it, so the
        // ViewModel must resubscribe itself on a slow drumbeat — otherwise a feed that
        // failed at app start (no network yet) stays dead for the whole session.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        assertTrue(
            "The realtime collector must loop — one dead channel must not mute the " +
                "feed for the whole app session.",
            source.contains("while (isActive)"),
        )
        assertTrue(
            "The resubscribe must pause between attempts, not spin.",
            source.contains("delay(REALTIME_RESUBSCRIBE_MS)"),
        )
    }

    @Test
    fun `the community feature cannot touch attendance state`() {
        // The independence guarantee, pinned structurally: the attendance-side stores
        // never learn the community feature exists. A future edit that reaches from the
        // community layer into attendance data would have to import it, and this stops it.
        // Comments are stripped first — a KDoc mention of a class is documentation, and
        // this test is about code.
        for (file in communitySources()) {
            val code = file.readLines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            for (forbidden in listOf(
                "com.attendo.data.AttendanceRepository",
                "com.attendo.data.BackupRepository",
                "com.attendo.core.backup",
            )) {
                assertTrue(
                    "${file.name} must not reference $forbidden: Supabase being down must " +
                        "be unable to touch attendance, backups, or their code paths.",
                    !code.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `community observations never reach the availability math`() {
        // The other half of the boundary: just as community code must not reach into
        // attendance data, community data must not reach into the official availability
        // math — a claim about 2 PM is other students' expectation, and "Room 203 is
        // occupied" in the free/busy list because of it would be the timetable lying.
        // The ViewModels that compute availability are the seam: community state joins
        // only at the screens, below the list, so a future edit that folds one into the
        // other would have to make these files mention community at all.
        for (vm in listOf(
            "src/main/kotlin/com/attendo/ui/rooms/RoomsViewModel.kt",
            "src/main/kotlin/com/attendo/ui/rooms/RoomDetailViewModel.kt",
        )) {
            val code = sourceFile(vm).readLines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            assertTrue(
                "$vm computes official availability and must never reference community " +
                    "state — claims and reports are advice beside the list, never inputs " +
                    "to it.",
                !code.contains("ommunity"),
            )
        }
    }

    @Test
    fun `a failed poll fetch keeps the polls on screen`() {
        // Polls have no disk cache, so the only thing standing between a flaky network
        // and the question a student is reading is this fold keeping the last fetch's
        // list when the new one cannot be had.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        assertTrue(
            "refresh() must keep the previous polls when the fetch fails.",
            source.contains("polls = pollsSnapshot?.polls ?: _state.value.polls"),
        )
    }

    @Test
    fun `a vote past the close is refused before the network`() {
        // Whatever a card shows, the vote path itself re-judges closes_at by the
        // anchored clock and refuses locally — the server would refuse it too, but the
        // guarantee has to hold on the client, including while an old snapshot is all
        // the screen has.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        val guardAt = source.indexOf("isPollOpen")
        val sendAt = source.indexOf("repository.castVote")
        assertTrue("vote() must judge the poll against the anchored clock.", guardAt >= 0)
        assertTrue(
            "The closes_at guard must run before the vote is sent.",
            guardAt in 0 until sendAt,
        )
    }

    @Test
    fun `polls are judged by a clock that keeps moving after the fetch`() {
        // The anchored clock is the whole fix for the stale-snapshot case: the anchor
        // (server now at fetch, device wall at fetch) is read as server-now plus the
        // device's ticks since, never as the fetch's frozen instant.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        assertTrue(
            "pollClock must advance the fetch's server-now by the device's ticks.",
            source.contains("server.plusMillis(wallNow - fetchedAt)"),
        )
    }

    @Test
    fun `a declined row's Retry genuinely revives it`() {
        // The Retry a declined card offers must reach the row. pendingOutbox() excludes
        // rejected rows, so a retry that only walks the pending set is a silent no-op for
        // exactly the rows the button exists for — the refusals that clear with time: a
        // dead session re-mints on the next contact, and the PGRST202 shape mismatch that
        // broke every basic report cleared with the app update that fixed null parameters.
        // The reset keeps the row and its idempotency key, so the re-send is free of
        // double effects.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()
        val retryAt = source.indexOf("fun retryNow(")
        assertTrue("retryNow must exist.", retryAt >= 0)
        val retry = source.substring(retryAt, source.indexOf("\n    }", retryAt))
        assertTrue(
            "retryNow must search allOutbox() — the pending set cannot see a declined row.",
            retry.contains("allOutbox()"),
        )
        assertTrue(
            "retryNow must reset the refusal before draining, or the drain skips the row.",
            retry.contains("resetOutboxForRetry"),
        )
        val dao = sourceFile("src/main/kotlin/com/attendo/data/db/CommunityEntities.kt").readText()
        val resetAt = dao.indexOf("suspend fun resetOutboxForRetry")
        assertTrue("The DAO must own a reset for the manual retry.", resetAt >= 0)
        val resetQuery = dao.substring(dao.lastIndexOf("@Query", resetAt), resetAt)
        assertTrue(
            "The reset must clear the refusal code itself.",
            resetQuery.contains("rejectionCode = null"),
        )
    }

    // --------------------------------------- liveness (the shared engine)

    @Test
    fun `the engine starts on the first community view, not on construction`() {
        // The shared ViewModel is constructed at the app level so one state and one
        // Realtime channel serve every surface — which is exactly why construction must
        // be inert. An init block that fetched would mint the anonymous identity on
        // app open (D1-A violation: the mint belongs to the first community *view*),
        // and would spend the connection budget before anyone asked a community
        // question. onShown() is the only starter.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        assertTrue(
            "The engine must have an onShown() starter — lazy start is what lets the " +
                "ViewModel live at the app level without minting on app open.",
            source.contains("fun onShown()"),
        )
        val classBody = source.substringAfter("class CommunityViewModel(")
        val initAt = classBody.indexOf("\n    init {")
        assertTrue(
            "CommunityViewModel must not have an init block — the engine starts in " +
                "onShown(), on the first community view.",
            initAt == -1,
        )
    }

    @Test
    fun `a realtime nudge refetches almost immediately`() {
        // Two seconds, not the old ten: the live channel's whole point is that a screen
        // watching a room shows its new state at once. Ten seconds read as "refresh is
        // not auto" — the student went back and came back instead of waiting for a
        // nudge that was arriving but being sat on. Two seconds still collapses any
        // burst of events into one fetch per window.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        assertTrue(
            "REFETCH_FLOOR_MS must stay at 2 seconds — the realtime floor is the " +
                "app's live-refresh latency.",
            source.contains("REFETCH_FLOOR_MS = 2_000L"),
        )
    }

    @Test
    fun `a nudge that lands mid-fetch is coalesced, never dropped`() {
        // The two-device test found the drop-guard: a realtime pulse arriving while
        // the vote's or verify's own callback fetch was in flight was lost — and a
        // pulse carries no content, so the change it announced never reached the
        // screen until some *later* event happened to bump it. The guard must
        // coalesce (one more full pass at the end), and only clear the refreshing
        // flag when the loop actually ends — a coalesced pass that cleared it early
        // would race the next nudge back into the same drop.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt").readText()
        assertTrue(
            "A mid-fetch refresh() must set the coalescing flag, not return silently.",
            source.contains("refreshAgain = true"),
        )
        assertFalse(
            "The old drop-guard must be gone — dropping is the bug this pins.",
            source.contains("if (_state.value.refreshing) return"),
        )
        val loopAt = source.indexOf("do {")
        val clearAt = source.indexOf("refreshing = false")
        assertTrue(
            "The refreshing flag must clear only after the coalescing loop ends.",
            loopAt in 0 until clearAt,
        )
    }

    @Test
    fun `one-shot RPCs retry once when the network blips`() {
        // verify/vote/create/withdraw are one-shot: no outbox, so a transient blip
        // surfaced as "Couldn't reach the server" for an action that a single replay
        // would have landed. Every one of them is idempotent server-side
        // (already_voted / already_verified / already_withdrawn / the poll idempotency
        // key), which is what makes one silent retry safe — and the poll key must be
        // minted *before* the retry wrapper, or a replay would ask twice instead of
        // re-asking once.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()
        assertTrue(
            "The repository must route one-shot RPCs through rpcWithTransientRetry.",
            source.contains("private suspend fun rpcWithTransientRetry("),
        )
        val keyAt = source.indexOf("val idempotencyKey = UUID.randomUUID().toString()")
        val retryCreateAt = source.indexOf("rpcWithTransientRetry", source.indexOf("fun createPoll("))
        assertTrue(
            "createPoll's idempotency key must be minted before the retry wrapper, so " +
                "the replay re-asks the same poll instead of making a second one.",
            keyAt in 0 until retryCreateAt,
        )
        for (call in listOf("verify(", "castVote(", "withdrawReportInternal(", "pollWithdrawInternal(")) {
            val funAt = source.indexOf("fun $call")
            val body = source.substring(funAt, source.indexOf("\n    }", funAt))
            assertTrue(
                "The $call path must retry through rpcWithTransientRetry — a blip must " +
                    "not surface as a user-visible failure for an idempotent action.",
                body.contains("rpcWithTransientRetry"),
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun communitySources(): List<File> =
        File(sourceFile("src/main/kotlin/com/attendo/data/community").absolutePath)
            .walkTopDown()
            .filter { it.isFile }
            .toList() +
            File(sourceFile("src/main/kotlin/com/attendo/ui/community").absolutePath)
                .walkTopDown()
                .filter { it.isFile }
                .toList()

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
