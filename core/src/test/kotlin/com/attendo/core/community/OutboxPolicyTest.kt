package com.attendo.core.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The retry arithmetic behind the community outbox. */
class OutboxPolicyTest {

    private val t0: Instant = Instant.parse("2026-09-04T10:00:00Z")

    @Test
    fun `backoff doubles from thirty seconds`() {
        assertEquals(Duration.ofSeconds(30), OutboxPolicy.delayBeforeAttempt(1))
        assertEquals(Duration.ofMinutes(1), OutboxPolicy.delayBeforeAttempt(2))
        assertEquals(Duration.ofMinutes(2), OutboxPolicy.delayBeforeAttempt(3))
        assertEquals(Duration.ofMinutes(4), OutboxPolicy.delayBeforeAttempt(4))
    }

    @Test
    fun `backoff caps at fifteen minutes`() {
        // 30s × 2^(n-1): attempt 5 is 8m, attempt 6 would be 16m → capped to 15m.
        assertEquals(Duration.ofMinutes(8), OutboxPolicy.delayBeforeAttempt(5))
        assertEquals(Duration.ofMinutes(15), OutboxPolicy.delayBeforeAttempt(6))
        assertEquals(Duration.ofMinutes(15), OutboxPolicy.delayBeforeAttempt(50))
    }

    @Test
    fun `a row retries until the fifth failure`() {
        assertTrue(OutboxPolicy.shouldRetry(0))
        assertTrue(OutboxPolicy.shouldRetry(4))
        assertFalse(OutboxPolicy.shouldRetry(5))
        assertFalse(OutboxPolicy.shouldRetry(9))
    }

    @Test
    fun `the first retry is due thirty seconds after the failure`() {
        assertEquals(
            t0.plusSeconds(30),
            OutboxPolicy.nextDueAt(t0, failedAttempts = 0)
        )
    }

    @Test
    fun `a terminal row has no due instant`() {
        assertNull(OutboxPolicy.nextDueAt(t0, failedAttempts = 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempt numbers start at one`() {
        OutboxPolicy.delayBeforeAttempt(0)
    }
}

/** Folding observations into the Rooms tab's one-line-per-room summaries. */
class CommunitySummariesTest {

    private val t0: Instant = Instant.parse("2026-09-04T10:00:00Z")
    private val t1: Instant = t0.plusSeconds(300)

    private fun obs(
        id: String, room: String?, kind: CommunityReportKind, createdAt: Instant,
    ) = CommunityObservation(
        id = id, kind = kind, status = CommunityObservationStatus.REPORTED,
        room = room, section = null, subject = null, classDate = null, startHour = null,
        payload = ReportPayload(), note = null, createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(3600),
    )

    @Test
    fun `observations about one room collapse into a single line`() {
        val summaries = CommunitySummaries.byRoom(
            listOf(
                obs("a", "203", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, t0),
                obs("b", "203", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, t1),
            )
        )
        assertEquals(1, summaries.size)
        assertEquals("203", summaries[0].room)
        assertEquals(2, summaries[0].reportCount)
        assertEquals(CommunitySummaries.Tendency.BUSY, summaries[0].tendency)
        assertEquals(t1, summaries[0].latestAt)
    }

    @Test
    fun `conflicting signals read as UNKNOWN rather than a coin flip`() {
        val summaries = CommunitySummaries.byRoom(
            listOf(
                obs("a", "204", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, t0),
                obs("b", "204", CommunityReportKind.ROOM_FREE_DESPITE_BUSY, t1),
            )
        )
        assertEquals(CommunitySummaries.Tendency.UNKNOWN, summaries[0].tendency)
    }

    @Test
    fun `rooms sort newest first`() {
        val summaries = CommunitySummaries.byRoom(
            listOf(
                obs("a", "old-room", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, t0),
                obs("b", "new-room", CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, t1),
            )
        )
        assertEquals(listOf("new-room", "old-room"), summaries.map { it.room })
    }

    @Test
    fun `observations without a room are not room summaries`() {
        assertTrue(
            CommunitySummaries.byRoom(
                listOf(obs("a", null, CommunityReportKind.CLASS_CANCELLED, t0))
            ).isEmpty()
        )
    }
}
