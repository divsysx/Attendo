package com.attendo.ui.community

import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityPoll
import com.attendo.core.community.CommunityPollStatus
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.CommunityRules
import com.attendo.core.community.PollOption
import com.attendo.core.community.ReportPayload
import com.attendo.data.community.CommunityRepository.OutboxUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The folds the community screens render by: one snapshot in, the Rooms tab's cards and
 * the review screen's pills out. The point of pinning these is the same as every other
 * test in the feature — the server sends rows, and this is the entire transformation
 * between rows and what a student reads, so a mistake here is a mistake on every screen
 * at once.
 */
class CommunityUiStateTest {

    private val now: Instant = Instant.parse("2026-09-04T10:00:00Z")

    private fun observation(
        id: String,
        kind: CommunityReportKind,
        room: String? = null,
        classDate: LocalDate? = null,
        startHour: Int? = null,
        status: CommunityObservationStatus = CommunityObservationStatus.REPORTED,
        createdAt: Instant = now.minusSeconds(60),
        expiresAt: Instant = now.plusSeconds(3600),
        observationType: CommunityObservationType = CommunityObservationType.PRESENT,
        targetStartHour: Int? = null,
    ) = CommunityObservation(
        id = id,
        kind = kind,
        status = status,
        room = room,
        section = null,
        subject = null,
        classDate = classDate,
        startHour = startHour,
        payload = ReportPayload(),
        note = null,
        createdAt = createdAt,
        expiresAt = expiresAt,
        observationType = observationType,
        eventDate = null,
        targetStartHour = targetStartHour,
        targetEndHour = targetStartHour?.plus(1),
    )

    @Test
    fun `expired observations are not live even when the server sent them`() {
        // The server filters expiry too, but the cache can be older than the phone it is
        // read on — the client re-judges by the snapshot's own clock before showing.
        val state = CommunityUiState(
            available = true,
            loaded = true,
            serverNow = now,
            observations = listOf(
                observation("live", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203"),
                observation(
                    "expired", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "204",
                    expiresAt = now.minusSeconds(1),
                ),
            ),
        )

        assertEquals(listOf("203"), state.live.map { it.room })
        assertEquals(listOf("203"), state.roomSummaries.map { it.room })
    }

    @Test
    fun `resolved observations are not live`() {
        val state = CommunityUiState(
            serverNow = now,
            observations = listOf(
                observation(
                    "gone", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203",
                    status = CommunityObservationStatus.DISPUTED,
                ),
            ),
        )

        assertTrue(state.live.isEmpty())
    }

    @Test
    fun `a dismissed room's card is hidden without touching the observations`() {
        val state = CommunityUiState(
            serverNow = now,
            observations = listOf(
                observation("a", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203"),
                observation("b", CommunityReportKind.ROOM_FREE_DESPITE_BUSY, room = "204"),
            ),
            dismissed = setOf("203"),
        )

        // Hidden from the Rooms tab's list…
        assertEquals(listOf("204"), state.roomSummaries.map { it.room })
        // …but still visible in the room's own detail screen — dismissal is "stop showing
        // me the card", not "pretend nobody reported it".
        assertEquals(1, state.forRoom("203").size)
    }

    @Test
    fun `a future claim never enters the room tendency`() {
        // The Rooms tab's card answers "what do students say about this room now" — a
        // claim about the 2 PM slot is not an answer to that question, and folding it in
        // would make "Students report it occupied" a statement about a moment the claim
        // never mentioned. The claim still renders where its slot can be said: the room's
        // own detail list, via forRoom.
        val state = CommunityUiState(
            available = true,
            loaded = true,
            serverNow = now,
            observations = listOf(
                observation(
                    "claim", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203",
                    observationType = CommunityObservationType.FUTURE, targetStartHour = 14,
                ),
                observation("present", CommunityReportKind.ROOM_FREE_DESPITE_BUSY, room = "204"),
            ),
        )

        assertEquals(listOf("204"), state.roomSummaries.map { it.room })
        assertEquals(listOf("claim"), state.forRoom("203").map { it.id })
    }

    @Test
    fun `forRoom matches the room name case-insensitively`() {
        val state = CommunityUiState(
            serverNow = now,
            observations = listOf(observation("a", CommunityReportKind.ROOM_OTHER, room = "Basement Lab")),
        )

        assertEquals(1, state.forRoom("basement lab").size)
    }

    @Test
    fun `pendingCount counts pending and failed but not sent or rejected`() {
        fun report(status: OutboxUiState.Status) = OutboxUiState(
            idempotencyKey = status.name,
            kind = CommunityReportKind.ROOM_OTHER,
            room = "203",
            createdAt = now,
            status = status,
            failureCode = null,
        )

        val state = CommunityUiState(
            myReports = listOf(
                report(OutboxUiState.Status.PENDING),
                report(OutboxUiState.Status.FAILED),
                report(OutboxUiState.Status.SENT),
                report(OutboxUiState.Status.REJECTED),
            ),
        )

        assertEquals(2, state.pendingCount)
    }

    @Test
    fun `roomSummaries newest first`() {
        val state = CommunityUiState(
            serverNow = now,
            observations = listOf(
                observation("old", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203", createdAt = now.minusSeconds(3600)),
                observation("new", CommunityReportKind.ROOM_FREE_DESPITE_BUSY, room = "204", createdAt = now.minusSeconds(60)),
            ),
        )

        assertEquals(listOf("204", "203"), state.roomSummaries.map { it.room })
    }

    @Test
    fun `a room whose live reports include the student's own says so`() {
        // The Rooms tab's card said "Students report it occupied" on the reporter's own
        // phone too — the fold had no notion of ownership, so a report filed seconds ago
        // read as a stranger's on every device including the one that filed it (noticed
        // on a non-teaching day, when the community section is the only thing the tab
        // shows). The join is against mySentIds: sent rows carry the server's observation
        // id, the same fact the observation card's "Your report" marker runs on.
        val state = CommunityUiState(
            serverNow = now,
            observations = listOf(
                observation("mine", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203"),
                observation("theirs", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "204"),
            ),
            mySentIds = setOf("mine"),
        )

        assertTrue(state.hasOwnReport(state.roomSummaries.single { it.room == "203" }))
        assertFalse(state.hasOwnReport(state.roomSummaries.single { it.room == "204" }))
    }

    @Test
    fun `an own report past its expiry no longer owns the room's card`() {
        // hasOwnReport reads `live`, not `observations` — a report that expired or was
        // withdrawn is off the tab entirely, so it must not keep a stranger-only card
        // saying "You" for a room the student has nothing live on.
        val state = CommunityUiState(
            serverNow = now,
            observations = listOf(
                observation("mine-expired", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203", expiresAt = now.minusSeconds(1)),
                observation("theirs-live", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "203"),
                observation("mine-live", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, room = "204"),
            ),
            mySentIds = setOf("mine-expired", "mine-live"),
        )

        assertFalse(state.hasOwnReport(state.roomSummaries.single { it.room == "203" }))
        assertTrue(state.hasOwnReport(state.roomSummaries.single { it.room == "204" }))
    }

    // ------------------------------------------------------------- poll folds

    private fun poll(
        id: String,
        room: String? = null,
        classDate: LocalDate? = null,
        status: CommunityPollStatus = CommunityPollStatus.OPEN,
        closesAt: Instant = now.plusSeconds(3600),
        myVote: Int? = null,
    ) = CommunityPoll(
        id = id,
        room = room,
        section = null,
        subject = null,
        classDate = classDate,
        startHour = null,
        question = "Is 203 free?",
        status = status,
        options = listOf(PollOption(0, "Yes", 2), PollOption(1, "No", 1)),
        totalVotes = 3,
        closesAt = closesAt,
        myVote = myVote,
    )

    @Test
    fun `closed and expired polls are not live`() {
        // The cron sweep runs every ten minutes, so the server can still be listing as
        // open a poll whose closes_at has passed — the client judges by the anchored
        // clock before showing, exactly as it does for observations.
        val state = CommunityUiState(
            serverNow = now,
            polls = listOf(
                poll("open", room = "203"),
                poll("closed", room = "204", status = CommunityPollStatus.CLOSED),
                poll("expired", room = "205", closesAt = now.minusSeconds(60)),
            ),
        )

        assertEquals(listOf("open"), state.livePolls(now).map { it.id })
    }

    @Test
    fun `pollsForRoom matches case-insensitively and only this room`() {
        val state = CommunityUiState(
            serverNow = now,
            polls = listOf(
                poll("this-room", room = "203"),
                poll("same-room-different-case", room = "LT-1"),
                poll("another-room", room = "204"),
            ),
        )

        assertEquals(listOf("this-room"), state.pollsForRoom("203", now).map { it.id })
        assertEquals(listOf("same-room-different-case"), state.pollsForRoom("lt-1", now).map { it.id })
    }

    @Test
    fun `a vote survives on the poll the student voted in`() {
        // myVote comes from the one per-user row the read policy allows; keeping it in
        // the fold is what lets the card say "your vote" instead of offering a second.
        val state = CommunityUiState(
            serverNow = now,
            polls = listOf(poll("voted", room = "203", myVote = 0)),
        )

        assertEquals(0, state.livePolls(now).single().myVote)
    }

    @Test
    fun `a poll held past its close by a failed refresh is not live and not votable`() {
        // The scenario this fold exists for. The fetch at 10:00 brought a poll closing
        // at 11:00. At 12:30 every refresh since has failed, so the list — and its
        // clock anchor — are still the 10:00 ones. The anchored clock does not care:
        // the device has ticked two and a half hours, so it reads 12:30, the poll is
        // past its close, and neither the fold nor the vote path will treat it as
        // actionable. Judged by the fetch's frozen clock instead, the same poll would
        // stay "live" for as long as the network stayed down.
        val fetchedAtWall = 1_000_000L
        val state = CommunityUiState(
            polls = listOf(poll("stale", room = "203", closesAt = now.plusSeconds(3600))),
            pollsServerNow = now,
            pollsFetchedAt = fetchedAtWall,
        )

        // At fetch time the poll was genuinely live.
        assertEquals(listOf("stale"), state.pollsForRoom("203", now).map { it.id })

        // Ninety minutes past its close, with nothing but the old snapshot:
        val late = state.pollClock(fetchedAtWall + ((2 * 3600) + 1800) * 1000L)
        assertEquals(now.plusSeconds((2 * 3600) + 1800), late)
        assertTrue(state.pollsForRoom("203", late).isEmpty())
        // And the vote path's own judgement — the guard votes are refused by.
        assertFalse(CommunityRules.isPollOpen(state.polls.single(), late!!))
    }

    @Test
    fun `pollClock without an anchor is nothing`() {
        // No successful fetch means no anchor — and no polls to judge. Null, not a
        // guess with the device clock.
        assertNull(CommunityUiState().pollClock(1_000_000L))
    }
}
