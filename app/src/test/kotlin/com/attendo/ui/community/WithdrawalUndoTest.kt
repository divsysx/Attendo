package com.attendo.ui.community

import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityPoll
import com.attendo.core.community.CommunityPollStatus
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.CommunityRules
import com.attendo.core.community.PollOption
import com.attendo.core.community.ReportPayload
import com.attendo.data.community.CommunityRepository.OutboxUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The client half of Parts 4–8: the outbox state machine, the own-report rule,
 * "My Reports"/"My Polls" states, and the fresh-undo window.
 *
 * Behavior tests pin the [CommunityUiState] folds and [CommunityRules] arithmetic;
 * contract tests (this repo's source-reading style, see `CommunityResilienceTest`)
 * pin the structural guarantees that live in code shape, not in a pure function:
 * the first send must not wait for backoff, the outbox must be observed rather
 * than polled, and a withdrawal must be recorded only from the server's `ok`.
 */
class WithdrawalUndoTest {

    private val now: Instant = Instant.parse("2026-09-08T10:00:00Z")

    // ------------------------------------------------------- Part 4: the send

    @Test
    fun `the first attempt never waits for backoff`() {
        val sync = source("src/main/kotlin/com/attendo/data/community/CommunitySync.kt")
        val sendRows = sync.substring(sync.indexOf("private suspend fun sendRows"))
        // The guard that makes attempt zero due immediately: backoff applies only
        // once failedAttempts > 0. Without it, every first send waits 30 seconds —
        // the original "Waiting to send" bug.
        assertTrue(
            "sendRows must gate backoff on failedAttempts > 0, or the first send waits 30s.",
            sendRows.contains("if (row.failedAttempts > 0)"),
        )
        val drainLoop = sync.substring(sync.indexOf("while (true)"))
        assertTrue(
            "the drain's self-rescheduling loop must ignore rows that never failed, " +
                "or it wakes every 30s for nothing.",
            drainLoop.contains("pendingRows().filter { it.failedAttempts > 0 }"),
        )
    }

    @Test
    fun `the outbox is observed, not polled`() {
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        assertTrue(
            "The repository must expose the outbox as a Flow for the ViewModel to observe.",
            repo.contains("fun outboxChanges()"),
        )
        val dao = source("src/main/kotlin/com/attendo/data/db/CommunityEntities.kt")
        assertTrue(
            "The DAO must offer allOutboxFlow() — Room invalidation is the event source.",
            dao.contains("fun allOutboxFlow(): Flow<List<CommunityOutboxEntity>>"),
        )
        val vm = source("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt")
        assertTrue(
            "The ViewModel must collect outboxChanges() so state changes reach the screen live.",
            vm.contains("repository.outboxChanges().collect"),
        )
    }

    @Test
    fun `the server's observation id is stamped on acceptance`() {
        val sync = source("src/main/kotlin/com/attendo/data/community/CommunitySync.kt")
        val ok = sync.substring(sync.indexOf("is RemoteReportApi.SubmitResult.Ok"))
        assertTrue(
            "The Ok branch must stamp the server's id — undo/withdraw has no target without it.",
            ok.contains("markOutboxServerId"),
        )
    }

    // --------------------------------------------- Part 5: the own-report rule

    @Test
    fun `own reports are not verifiable`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            serverNow = now,
            observations = listOf(
                observation("mine", room = "203"),
                observation("theirs", room = "204"),
            ),
            mySentIds = setOf("mine"),
        )

        assertFalse("My own report must not offer verdict buttons.", state.isVerifiable(state.observations[0]))
        assertTrue("Someone else's report must offer verdict buttons.", state.isVerifiable(state.observations[1]))
    }

    @Test
    fun `terminal observations are not verifiable either`() {
        val state = CommunityUiState(available = true, loaded = true, serverNow = now)
        listOf(
            CommunityObservationStatus.REJECTED,
            CommunityObservationStatus.EXPIRED,
            CommunityObservationStatus.WITHDRAWN,
            CommunityObservationStatus.UNRECOGNIZED,
        ).forEach { status ->
            assertFalse(
                "A $status observation is not actionable.",
                state.isVerifiable(observation("x", room = "203").copy(status = status)),
            )
        }
    }

    @Test
    fun `an already-given verdict is not verifiable either`() {
        val state = CommunityUiState(available = true, loaded = true, serverNow = now)
        // The server refuses a second verdict (already_verified); the card must not
        // offer the taps in the first place — the observation twin of myVote disabling
        // the poll chips.
        assertFalse(
            "A report I already marked must not offer verdict buttons.",
            state.isVerifiable(observation("x", room = "203").copy(myVerdict = true)),
        )
        assertFalse(
            "A report I already disputed must not offer verdict buttons.",
            state.isVerifiable(observation("x", room = "203").copy(myVerdict = false)),
        )
        assertTrue(
            "A report I have not weighed in on is still verifiable.",
            state.isVerifiable(observation("x", room = "203")),
        )
    }

    @Test
    fun `the own verdict is read, cached, and rendered`() {
        // The read: the snapshot must fetch the caller's own verifications row
        // (RLS read-own, exactly like poll_votes) and copy it onto the model.
        val api = source("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt")
        assertTrue(
            "snapshot() must read the caller's own verifications.",
            api.contains("postgrest[\"verifications\"].select()"),
        )
        assertTrue(
            "the own verdict must ride the model out of the snapshot.",
            api.contains("copy(myVerdict = myVerdicts[dto.id])"),
        )
        // The cache: the verdict must survive the cache boundary both ways, or the
        // marker vanishes offline and the buttons return — the two-device test's
        // "no update at all" was exactly a boundary like this one dropping a field.
        val entities = source("src/main/kotlin/com/attendo/data/db/CommunityEntities.kt")
        assertTrue(
            "the cache entity must carry myVerdict.",
            entities.contains("val myVerdict: Boolean? = null,"),
        )
        assertTrue(
            "the cache-to-model mapper must pass myVerdict through.",
            entities.contains("myVerdict = myVerdict,"),
        )
        // The render: an already-given verdict reads as a marker, not as nothing —
        // the disappearance of buttons alone would look like a bug.
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        assertTrue(
            "the card must say what the student chose instead of offering dead buttons.",
            cards.contains("You marked this accurate") && cards.contains("You marked this not right"),
        )
    }

    @Test
    fun `own polls are not votable`() {
        val state = CommunityUiState(
            available = true, loaded = true, serverNow = now,
            myPollIds = setOf("mine"),
        )
        // The server refuses the creator's vote (own_poll); the card must not offer
        // it in the first place — the poll twin of the own-report rule.
        assertFalse("My own poll must not offer vote chips.", state.isVotable(poll(id = "mine"), now))
        assertTrue("Someone else's poll is votable.", state.isVotable(poll(id = "theirs"), now))
    }

    @Test
    fun `voted polls are not votable either`() {
        val state = CommunityUiState(available = true, loaded = true, serverNow = now)
        // The server never sends creator ids on the read path; myPolls (the owner
        // RPC) is where the client learns its own poll ids, and myVote != null
        // still blocks a revote in someone else's poll.
        val voted = poll(id = "p1").copy(myVote = 0)
        assertFalse("A poll I voted in is not votable again.", state.isVotable(voted, now))
        assertTrue("A poll I have not voted in is votable.", state.isVotable(poll(id = "p2"), now))
    }

    @Test
    fun `the server refuses the creator's vote in their own poll`() {
        // Part 5/9 parity: the own-poll guard must live in the RPC, not only in
        // the UI (an attacker with a raw client skips the app entirely).
        val migration = source("supabase/migrations/0010_poll_parity.sql")
        val castVote = migration.substring(migration.indexOf("community.cast_vote("))
        assertTrue(
            "cast_vote must reject the creator's vote server-side.",
            castVote.contains("poll.creator_id = profile.user_id") &&
                castVote.contains("'own_poll'"),
        )
    }

    @Test
    fun `the ui knows its own polls and the card says so`() {
        // The UI half of the own-poll rule: myPollIds feeds isVotable, and PollCard
        // renders the owner's copy read-only with a label — found missing during
        // production verification 2026-09-09 when tapping an own-poll chip surfaced
        // the raw server code ("Couldn't do that (own_poll)").
        val vm = source("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt")
        assertTrue(
            "myPollIds must be derived from the my_polls fetch, like mySentIds from reports.",
            vm.contains("myPollIds = (mine ?: _state.value.myPolls)?.mapTo(mutableSetOf()) { it.id }"),
        )
        // ownPoll is the card's way in to the owner-facing row — the only place a
        // poll's creation time exists (public reads carry no created_at), and what
        // the inline Undo/Withdraw below is judged against.
        assertTrue(
            "CommunityUiState must offer ownPoll — the poll twin of ownReport.",
            vm.contains("fun ownPoll(poll: CommunityPoll): CommunityPoll?"),
        )
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        val pollCard = cards.substring(cards.indexOf("fun PollCard"))
        assertTrue(
            "PollCard must take an own-poll context and hide the chips without it.",
            pollCard.contains("own: CommunityPoll? = null") &&
                pollCard.contains("poll.myVote == null && own == null -> ChipChoice"),
        )
        assertTrue(
            "The own-poll card must explain itself with a label, or it reads as a bug.",
            pollCard.contains("if (own != null) \"Your poll\" else null"),
        )
        // The two-device follow-up 2026-09-09: a poll just asked deserves the same
        // one-tap regret as a report just filed — Undo inside the window, Withdraw
        // after it, in place on the card.
        assertTrue(
            "The own-poll card must offer the same window-judged Undo/Withdraw pair " +
                "the observation card does.",
            pollCard.contains("CommunityRules.undoDeadline") &&
                pollCard.contains("onUndo(poll.id)") &&
                pollCard.contains("onWithdraw(poll.id)"),
        )
    }

    // --------------------------------- the identity-move gaps (2026-09-11 test)

    @Test
    fun `a verdict refusal says something instead of doing nothing`() {
        // The two-device test's "tapping does not do anything": verify() used to hand
        // its result to a callback that ignored everything but success, so an
        // own_report / already_verified / frozen refusal vanished silently and the
        // buttons stayed. The handler must surface refusals like vote() does.
        val vm = source("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt")
        val verify = vm.substring(vm.indexOf("fun verify("))
        assertTrue(
            "verify() must show a refused verdict's line, not discard it.",
            verify.contains("verdictFailureLine(result.code)"),
        )
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        assertTrue(
            "verdictFailureLine must exist with the own-report and already-verified lines.",
            cards.contains("internal fun verdictFailureLine") &&
                cards.contains("\"own_report\" -> \"This is your own report\"") &&
                cards.contains("\"already_verified\" -> \"You already marked this report\""),
        )
        // And the poll line's own gaps: own_poll and already_voted had no lines at
        // all, so they surfaced as raw codes.
        assertTrue(
            "pollFailureLine must cover own_poll and already_voted.",
            cards.contains("\"own_poll\" -> \"This is your own poll\"") &&
                cards.contains("\"already_voted\" -> \"You already voted in this poll\""),
        )
    }

    @Test
    fun `the reconcile adds the server's rows, not just deletes its own`() {
        // The identity-move bug's other half: when a transfer or replacement
        // completes on the other phone, this phone's outbox never learns of the
        // moved identity's reports — they render as strangers' reports with buttons
        // the server refuses with own_report. The reconcile that runs on every
        // refresh must add live server rows the outbox does not know, as sent rows
        // carrying their server id, with a deterministic rehydrated- key that can
        // never enter the send path (sent rows are never read by the drain).
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        val reconcile = repo.substring(repo.indexOf("private suspend fun reconcileSentReportsWith"))
        assertTrue(
            "The reconcile must rehydrate live server rows missing from the outbox.",
            reconcile.contains("idempotencyKey = \"rehydrated-${'$'}{report.id}\"") &&
                reconcile.contains("sentAt = report.createdAt") &&
                reconcile.contains("serverObservationId = report.id"),
        )
        assertTrue(
            "Only live rows may be rehydrated — the 24h prune stays the horizon for dead history.",
            reconcile.contains("report.expiresAt.isAfter(at)"),
        )
        // The key must be visibly synthetic and deterministic: REPLACE semantics make
        // a re-run idempotent, and a UUID-shaped key would hide which rows these are.
        assertTrue(
            "Rehydrated rows must carry the deterministic rehydrated- key prefix.",
            reconcile.contains("rehydrated-"),
        )
    }

    @Test
    fun `my polls offer undo inside the window like reports do`() {
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        assertTrue("The repository must offer undoPoll alongside undoReport.", repo.contains("fun undoPoll"))
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        val row = cards.substring(cards.indexOf("fun MyPollRow"))
        assertTrue(
            "MyPollRow must gate its Undo on the same server-clock window as MyReportRow.",
            row.contains("CommunityRules.undoDeadline") && row.contains("onUndo"),
        )
        val server = source("supabase/migrations/0009_withdrawal_undo_rpcs.sql")
        val myPolls = server.substring(server.indexOf("create or replace function community.my_polls()"))
        assertTrue(
            "my_polls must return created_at and undo_available_until so the client can judge the window.",
            myPolls.contains("'created_at', p.created_at") &&
                myPolls.contains("'undo_available_until'"),
        )
    }

    // ---------------------------------------------------- Part 7: undo window

    @Test
    fun `the undo window is 30 seconds and mirrored in one place`() {
        // Narrowed from 60 in 0012, at the user's call, both sides at once: the cards
        // offer Undo for the first half-minute and Withdraw after it, and the server
        // must honor exactly as long as the client offers — a server window longer
        // than the client's would mean Withdraw buttons that get refused, shorter
        // would mean Undo buttons that get refused.
        assertEquals(30, CommunityRules.FRESH_UNDO_SECONDS)
        // The server's side is one place too: 0012's fresh_undo_window().
        val migration = source("supabase/migrations/0012_live_pulse.sql")
        val window = migration.substring(migration.indexOf("community.fresh_undo_window()"))
        assertTrue(
            "The server window must stay 30 seconds or the client's Undo offer lies.",
            window.contains("interval '30 seconds'"),
        )
    }

    @Test
    fun `undo is offered while the window is open and gone after`() {
        val sentAt = now.minusSeconds(10)
        assertNotNull(CommunityRules.undoDeadline(sentAt, now))
        assertTrue("10s after send, undo is still available.", CommunityRules.undoDeadline(sentAt, now)!! > now)
        val sentAtOld = now.minusSeconds(120)
        assertTrue("120s after send, the window is gone.", CommunityRules.undoDeadline(sentAtOld, now)!! <= now)
    }

    @Test
    fun `no server clock means no undo offer`() {
        // Advertising a server-enforced window without a clock to judge it by would
        // promise something the client cannot check.
        assertNull(CommunityRules.undoDeadline(now, null))
    }

    // ------------------------------------------ Parts 6/8: the state machine

    @Test
    fun `a withdrawn row reads as taken back, per its kind`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            serverNow = now,
            myReports = listOf(
                outboxRow(key = "u", withdrawalKind = "undo"),
                outboxRow(key = "w", withdrawalKind = "withdraw"),
            ),
        )
        assertEquals(OutboxUiState.Status.WITHDRAWN, state.myReports[0].status)
        assertEquals(OutboxUiState.Status.WITHDRAWN, state.myReports[1].status)
        assertEquals("undo", state.myReports[0].withdrawalKind)
        assertEquals("withdraw", state.myReports[1].withdrawalKind)
    }

    @Test
    fun `withdrawn reports do not count as pending`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            myReports = listOf(outboxRow(key = "w", withdrawalKind = "withdraw")),
        )
        assertEquals(0, state.pendingCount)
    }

    @Test
    fun `my polls render their statuses`() {
        val state = CommunityUiState(
            available = true,
            loaded = true,
            myPolls = listOf(
                poll(id = "open"),
                poll(id = "gone").copy(status = CommunityPollStatus.WITHDRAWN, withdrawalKind = "undo"),
            ),
        )
        assertEquals(CommunityPollStatus.OPEN, state.myPolls!![0].status)
        assertEquals(CommunityPollStatus.WITHDRAWN, state.myPolls!![1].status)
        assertEquals("undo", state.myPolls!![1].withdrawalKind)
    }

    @Test
    fun `a withdrawal is recorded only from the server's ok`() {
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        val internal = repo.substring(repo.indexOf("private suspend fun withdrawReportInternal"))
        val okBranch = internal.substring(internal.indexOf("is RemoteReportApi.SubmitResult.Ok"))
        assertTrue(
            "The Ok branch — and only it — may stamp withdrawalKind on the row.",
            okBranch.contains("markOutboxWithdrawn") &&
                internal.indexOf("markOutboxWithdrawn", internal.indexOf("Rejected")) < 0,
        )
        // And a refused withdrawal never touches the row.
        val rejected = internal.substring(internal.indexOf("is RemoteReportApi.SubmitResult.Rejected"))
        assertFalse(
            "A refused withdrawal must not be recorded as one.",
            rejected.contains("markOutboxWithdrawn"),
        )
    }

    @Test
    fun `undo before the server knows the row is refused, not recorded`() {
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        val internal = repo.substring(repo.indexOf("private suspend fun withdrawReportInternal"))
        assertTrue(
            "Undo needs the server's observation id; without it the honest answer is REFUSED.",
            internal.contains("row.serverObservationId ?: return WithdrawOutcome.REFUSED"),
        )
    }

    @Test
    fun `my reports and polls live on their own screen, not in settings`() {
        // The restructure found during production verification 2026-09-09: two
        // stateful row sections had outgrown Settings. Settings opens the screen;
        // the screen owns the rows.
        val settings = source("src/main/kotlin/com/attendo/ui/settings/SettingsScreen.kt")
        assertFalse(
            "Settings must not inline the community rows — it opens the dedicated screen.",
            settings.contains("MyReportRow") || settings.contains("MyPollRow"),
        )
        assertTrue(
            "Settings must hand off to the dedicated screen.",
            settings.contains("onOpenMyCommunity"),
        )
        val screen = source("src/main/kotlin/com/attendo/ui/community/MyCommunityScreen.kt")
        assertTrue(
            "The dedicated screen must render both sections.",
            screen.contains("MyReportRow") && screen.contains("MyPollRow"),
        )
    }

    @Test
    fun `own reports can be taken back on the card, not only in settings`() {
        // The friction fix from live-refresh verification 2026-09-09: a student who
        // just reported something sees "Undo"/"Withdraw" where their own
        // observation card would otherwise show verdict buttons — no trip through
        // Settings to undo a one-line report.
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        assertTrue(
            "Both observation cards must accept the own-report context and callbacks.",
            cards.contains("own: CommunityRepository.OutboxUiState? = null") &&
                cards.contains("onUndo: (idempotencyKey: String) -> Unit") &&
                cards.contains("onWithdraw: (idempotencyKey: String) -> Unit"),
        )
        val actions = cards.substring(cards.indexOf("private fun OwnReportActions"))
        assertTrue(
            "The inline actions must use the one undo window, and swap Withdraw in after it.",
            actions.contains("CommunityRules.undoDeadline") &&
                actions.contains("\"Undo\"") &&
                actions.contains("\"Withdraw\""),
        )
        // The wiring: every surface that renders observations passes the own-report
        // context through, or the buttons exist but never light up. (The day review
        // no longer renders community cards — reports live in the Rooms tab only.)
        listOf(
            "src/main/kotlin/com/attendo/ui/rooms/RoomDetailScreen.kt",
        ).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path must pass own/onUndo/onWithdraw into its observation cards.",
                screen.contains("own = community.ownReport(observation)") &&
                    screen.contains("communityViewModel.undoReport(it)") &&
                    screen.contains("communityViewModel.withdrawReport(it)"),
            )
        }
    }

    @Test
    fun `a disappearance is news too - the pulse table`() {
        // The two-device test 2026-09-09 found the hole: reports appeared live but
        // withdrawals did not. Realtime delivers an UPDATE only to subscribers who
        // can SELECT the new row, and the read policies (0002) make a withdrawn
        // observation, a withdrawn or closed poll, and a rejected report invisible
        // to everyone but their owner — so the changes that make content disappear
        // arrived as silence on every other phone. 0012's pulse table is the fix,
        // and this pin holds its three load-bearing pieces in place.
        val migration = source("supabase/migrations/0012_live_pulse.sql")
        assertTrue(
            "Both public tables must pulse on insert and update — a trigger is what " +
                "makes it impossible for a future RPC to forget.",
            migration.contains("after insert or update on community.observations") &&
                migration.contains("after insert or update on community.polls"),
        )
        assertTrue(
            "The pulse table must join the publication, or nothing is delivered at all.",
            migration.contains("alter publication supabase_realtime add table community.activity_pulses"),
        )
        assertTrue(
            "The pulse rows must be readable by authenticated (RLS: using (true)) and " +
                "carry nothing but table name and timestamp — no ids, no payloads, " +
                "no identities.",
            migration.contains("using (true)") &&
                migration.contains("grant select (table_name, at) on community.activity_pulses to authenticated"),
        )
        assertTrue(
            "The client roles must hold nothing but the column grant — a new table " +
                "must not inherit Supabase's default blanket privileges.",
            migration.contains("revoke all on community.activity_pulses from anon, authenticated"),
        )
    }

    @Test
    fun `my polls says nothing while the first fetch is out`() {
        // Found 2026-09-11: "My Polls" showed "Couldn't load — check your connection."
        // on every first visit to the screen, on perfectly good connections, because
        // a null list was read as *failed* while it still meant *waiting* — My Reports
        // paints instantly from the outbox cache, so the polls section alone looked
        // broken for as long as the fetch took. The settled flag separates the two,
        // and the failure branch carries its own retry because the top status row
        // stays silent when only the poll fetch failed.
        val screen = source("src/main/kotlin/com/attendo/ui/community/MyCommunityScreen.kt")
        assertTrue(
            "The connection line must wait for the fetch to have answered.",
            screen.contains("myPolls == null -> if (community.myPollsSettled)"),
        )
        assertTrue(
            "A failed poll fetch needs its own way out when the snapshot succeeded.",
            screen.contains("TextButton(onClick = communityViewModel::refresh)") &&
                screen.contains("Retry"),
        )
        val vm = source("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt")
        assertTrue(
            "Every completed refresh pass must settle the polls section.",
            vm.contains("myPollsSettled = true,"),
        )
        // The RPC's other half: a refused call (dead session) must degrade to
        // "keep what you had", never to an empty list the screen would read as
        // "None yet" — the student's polls do not vanish because the session died.
        val api = source("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt")
        val myPollsFn = api.substring(api.indexOf("suspend fun myPolls()"))
        assertTrue(
            "A refused my_polls call must return null, not emptyList.",
            myPollsFn.contains("if (!obj.bool(\"ok\")) return null"),
        )
    }

    @Test
    fun `an unsent report stands in for the card it will become`() {
        // The two-device test 2026-09-11: a report filed offline on the room page left
        // that page unchanged — the room's list joins the server's observations by
        // serverObservationId, which an unsent row does not have, so the student's own
        // report was invisible exactly where they had filed it. The fix renders the
        // outbox row itself as a stand-in card: pending, failed, and frozen rows about
        // this room, keyed by their idempotency key so they animate away when accepted.
        val vm = source("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt")
        val unsent = vm.substring(vm.indexOf("fun unsentForRoom"))
        assertTrue(
            "Unsent rows are the ones without a server id — the sent ones are already observations.",
            unsent.contains("report.serverObservationId == null"),
        )
        assertTrue(
            "Only this room's rows belong on this room's page.",
            unsent.contains("ignoreCase = true"),
        )
        assertTrue(
            "Declined rows never become anything — they must not stand in for a card.",
            unsent.contains("Status.PENDING") && unsent.contains("Status.FAILED") &&
                unsent.contains("Status.FROZEN") && !unsent.contains("Status.REJECTED"),
        )
        val screen = source("src/main/kotlin/com/attendo/ui/rooms/RoomDetailScreen.kt")
        assertTrue(
            "The room page must render its unsent rows as cards.",
            screen.contains("community.unsentForRoom(state.room)") &&
                screen.contains("PendingReportCard("),
        )
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        assertTrue(
            "The stand-in card and My Reports must share one status vocabulary.",
            cards.contains("internal fun outboxStatusLine(report: CommunityRepository.OutboxUiState): String"),
        )
    }

    @Test
    fun `a duplicate refusal adopts the server's row instead of declining`() {
        // The user's 2026-09-11 report: after an identity move, a re-filed report sat at
        // "waiting to send", then landed as "declined — already reported" — but the
        // report IS on the server, live and votable. duplicate_report means the server
        // holds this exact report under this identity; the local row's only missing
        // piece is the server's id. The sync engine must look the collider up in the
        // owner-facing read and stamp the row sent, so undo, withdraw, and the
        // own-report rules all work against the row that actually exists.
        val sync = source("src/main/kotlin/com/attendo/data/community/CommunitySync.kt")
        val rejection = sync.substringAfter("is RemoteReportApi.SubmitResult.Rejected ->")
            .substringBefore("RemoteReportApi.SubmitResult.Unreachable ->")
        assertTrue(
            "duplicate_report must be attempted as a reconciliation before any decline.",
            rejection.contains("result.code == \"duplicate_report\" && reconcileDuplicate(row, api)"),
        )
        val reconcile = sync.substring(sync.indexOf("private suspend fun reconcileDuplicate"))
        assertTrue(
            "The collider search must exclude withdrawn rows — the partial dedup index " +
                "frees their slot, so they can never be the collision.",
            reconcile.contains("CommunityObservationStatus.WITHDRAWN"),
        )
        assertTrue(
            "The match must cover the dedup key the server refused on.",
            reconcile.contains("o.kind == row.kind") && reconcile.contains("o.room == row.room") &&
                reconcile.contains("o.classDate == row.classDate") && reconcile.contains("o.startHour == row.startHour"),
        )
        assertTrue(
            "A successful match must stamp both the send and the server's id.",
            reconcile.contains("dao.markOutboxSent(row.idempotencyKey, now())") &&
                reconcile.contains("dao.markOutboxServerId(row.idempotencyKey, collider.id)"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun observation(
        id: String,
        room: String?,
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

    private fun poll(id: String) = CommunityPoll(
        id = id,
        room = "203",
        section = null,
        subject = null,
        classDate = null,
        startHour = null,
        question = "Is the room free?",
        status = CommunityPollStatus.OPEN,
        options = listOf(PollOption(0, "Yes", 0), PollOption(1, "No", 0)),
        totalVotes = 0,
        closesAt = now.plusSeconds(3600),
    )

    private fun outboxRow(
        key: String,
        withdrawalKind: String? = null,
    ) = OutboxUiState(
        idempotencyKey = key,
        kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
        room = "203",
        createdAt = now,
        status = if (withdrawalKind != null) OutboxUiState.Status.WITHDRAWN else OutboxUiState.Status.SENT,
        failureCode = null,
        withdrawalKind = withdrawalKind,
        serverObservationId = if (withdrawalKind != null) "srv-$key" else null,
        sentAt = now,
    )

    private fun source(relative: String): String {
        val file = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
        return file.readText()
    }
}
