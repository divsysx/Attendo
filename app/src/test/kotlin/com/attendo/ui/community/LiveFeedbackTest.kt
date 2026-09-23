package com.attendo.ui.community

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four fixes from the two-device test on 2026-09-10, each pinned where it lives:
 *
 *  1. **The re-file wall** — a report filed, taken back, and filed again was declined
 *     (duplicate_report): the dedup indexes covered withdrawn rows too. 0013 makes
 *     them partial; a withdrawn row frees its slot, a rejected one keeps it.
 *  2. **Verdict feedback that updated nothing** — "Not right" refetched a snapshot
 *     whose counts were dropped at the cache boundary, and the card had no line to
 *     render them in even if they had arrived.
 *  3. **The frozen swap** — Undo became Withdraw only when some other change happened
 *     to trigger a refetch, because the cards judged the 30-second window against the
 *     snapshot's frozen clock. The anchored ticking clock makes the swap the card's
 *     own job, on time, with no network involved.
 *  4. **The silent rejoin** — after airplane mode the library rejoined the channel,
 *     but Realtime never replays missed changes, so nothing refetched until some
 *     *future* change landed. The channel's status flow is the only rejoin signal,
 *     and every SUBSCRIBED now carries the same nudge the first join did.
 *
 * Contract tests in this repo's source-reading style (see `CommunityResilienceTest`)
 * because every guarantee here is a shape, not a pure function.
 */
class LiveFeedbackTest {

    // ------------------------------------------------- 1. the re-file wall

    @Test
    fun `a withdrawn report frees its dedup slot`() {
        val migration = source("supabase/migrations/0013_refile_after_withdrawal.sql")
        assertTrue(
            "Both dedup indexes must be partial — a withdrawn row must not block the " +
                "re-file of the same content for the rest of the hour.",
            migration.contains("drop index if exists community.obs_dedup_unique") &&
                migration.contains("drop index if exists community.poll_dedup_unique"),
        )
        // The predicate itself, on both: withdrawn frees, everything else holds.
        val obsIndex = migration.substring(migration.indexOf("create unique index obs_dedup_unique"))
        val pollIndex = migration.substring(migration.indexOf("create unique index poll_dedup_unique"))
        assertTrue(
            "The observations index must exclude withdrawn rows and nothing else — a " +
                "rejected report keeps its slot, by the audit's decision.",
            obsIndex.contains("where status <> 'withdrawn'"),
        )
        assertTrue(
            "The polls index must free a withdrawn poll's slot the same way.",
            pollIndex.contains("where status <> 'withdrawn'"),
        )
    }

    @Test
    fun `the poll rate limit was raised, the others audited and kept`() {
        val migration = source("supabase/migrations/0013_refile_after_withdrawal.sql")
        val createPoll = migration.substring(migration.indexOf("community.create_poll("))
        assertTrue(
            "create_poll's limit must be 10/day — 5/day was the one limit an engaged " +
                "student could honestly hit.",
            createPoll.contains("check_rate_limit('poll_create', 10, interval '1 day')"),
        )
        // The audit's kept rules stay in 0003, unchanged: report 10/h + 60/d,
        // verify 60/h, vote 60/h (0011's copy). If one of those moves, this pin
        // forces the move to be a deliberate edit, not a drift.
        val rpcs = source("supabase/migrations/0003_rpcs.sql")
        assertTrue(
            "The report burst limit stays 10/hour unless deliberately changed.",
            rpcs.contains("check_rate_limit('report', 10, interval '1 hour')") &&
                rpcs.contains("check_rate_limit('report', 60, interval '1 day')"),
        )
        assertTrue(
            "The verify limit stays 60/hour — the PK already makes repeats impossible.",
            rpcs.contains("check_rate_limit('verify', 60, interval '1 hour')"),
        )
    }

    // ------------------------------------- 2. verdict feedback that shows up

    @Test
    fun `verdict tallies survive the cache boundary`() {
        // The bug's first half: refresh() mapped the snapshot into cache entities and
        // dropped the counts on the floor — the two new columns are load-bearing.
        val entities = source("src/main/kotlin/com/attendo/data/db/CommunityEntities.kt")
        assertTrue(
            "The cache entity must carry the verdict tallies, or no refetch can ever " +
                "show them.",
            entities.contains("val verificationCount: Int = 0") &&
                entities.contains("val disputeCount: Int = 0"),
        )
        val toModel = entities.substring(entities.indexOf("fun CommunityObservationCacheEntity.toModel"))
        assertTrue(
            "The cache-to-model mapping must carry the tallies back out.",
            toModel.contains("verificationCount = verificationCount") &&
                toModel.contains("disputeCount = disputeCount"),
        )
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        val refresh = repo.substring(repo.indexOf("suspend fun refresh()"))
        assertTrue(
            "refresh() must write the tallies into the cache — dropping them here was " +
                "the 'no update at all' the two-device test saw.",
            refresh.contains("verificationCount = o.verificationCount") &&
                refresh.contains("disputeCount = o.disputeCount"),
        )
    }

    @Test
    fun `the cards say what the room said`() {
        // The bug's second half: even with counts in hand, the cards had no line for
        // them — a single "Not right" changed nothing visible, because a status
        // transition needs two more voices than that. (The class-change card is gone
        // with its screen; the observation card is the one surface left.)
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        val observationCard = cards.substring(cards.indexOf("fun ObservationCard"))
        assertTrue(
            "the observation card must render the tallies in the buttons' own words.",
            observationCard.contains("verificationCount} accurate") &&
                observationCard.contains("disputeCount} not right"),
        )
        // Zero stays silent — a report with no verdicts yet has nothing to say.
        assertTrue(
            "The tallies must be gated on > 0, so an unverified report stays clean.",
            observationCard.contains("if (observation.verificationCount > 0)") &&
                observationCard.contains("if (observation.disputeCount > 0)"),
        )
    }

    // ------------------------------------------------- 3. the frozen swap

    @Test
    fun `the undo window is judged by a clock that moves`() {
        val cards = source("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt")
        // The one engine, ticking at second resolution: the window is 30 seconds, and
        // a minute tick would swap Undo to Withdraw up to 29 seconds late.
        val engine = cards.substring(cards.indexOf("private fun rememberAnchoredClock"))
        assertTrue(
            "The anchored clock must tick every second — the 30-second window cannot " +
                "be judged by a clock that moves once a minute.",
            engine.contains("delay(1_000)"),
        )
        assertTrue(
            "Both clocks must run on the one anchored engine.",
            cards.contains("fun rememberSnapshotClock(state: CommunityUiState): Instant? =") &&
                cards.contains("rememberAnchoredClock(state.pollsServerNow ?: state.serverNow, state.pollsFetchedAt)"),
        )
        // The pair that anchors the observations' clock: server now, and the wall
        // time it was captured. Moved on success alone, like the polls' anchor.
        val vm = source("src/main/kotlin/com/attendo/ui/community/CommunityViewModel.kt")
        assertTrue(
            "The ViewModel must pair the snapshot clock with its wall-clock capture.",
            vm.contains("val snapshotFetchedAt: Long? = null") &&
                vm.contains("val snapshotFetchedAt = if (ok) wallClock() else null"),
        )
    }

    @Test
    fun `every own-card surface uses the ticking clock`() {
        // The swap must be live on every surface that offers Undo — the fix is only
        // real where the frozen clock was actually replaced. (The day review no longer
        // renders community cards: reports and polls live in the Rooms tab only.)
        val room = source("src/main/kotlin/com/attendo/ui/rooms/RoomDetailScreen.kt")
        val mine = source("src/main/kotlin/com/attendo/ui/community/MyCommunityScreen.kt")
        assertTrue(
            "Room detail must pass the ticking clock to its observation cards.",
            room.contains("val snapshotClock = rememberSnapshotClock(community)") &&
                room.contains("serverNow = snapshotClock"),
        )
        assertTrue(
            "My community must judge both rows' windows by the ticking clock.",
            mine.contains("val snapshotClock = rememberSnapshotClock(community)") &&
                mine.lineSequence().count { it.contains("serverNow = snapshotClock") } == 2,
        )
    }

    // ----------------------------------------------- 4. the silent rejoin

    @Test
    fun `a rejoined channel refetches what it missed`() {
        val flow = source("src/main/kotlin/com/attendo/data/community/RealtimeObservations.kt")
        // The status collector must exist, and must arm before subscribe() — the same
        // ordering rule the pulse flow obeys, for the same reason.
        val statusAt = flow.indexOf("channel.status.collect")
        val subscribeAt = flow.indexOf("channel.subscribe()")
        assertTrue(
            "The channel's status flow must be collected — it is the only signal a " +
                "rejoin happened, and Realtime never replays what was missed.",
            statusAt >= 0,
        )
        assertTrue(
            "The status collector must arm before subscribe(), or the join's own " +
                "SUBSCRIBED emission is missed.",
            statusAt in 0 until subscribeAt,
        )
        val collector = flow.substring(statusAt)
        assertTrue(
            "Every SUBSCRIBED transition — first join and every rejoin — must carry " +
                "the fetch-once nudge.",
            collector.contains("if (status == RealtimeChannel.Status.SUBSCRIBED) send(Change.ACTIVATED)"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String {
        val file = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
        return file.readText()
    }
}
