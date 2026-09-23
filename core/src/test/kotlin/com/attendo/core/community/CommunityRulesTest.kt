package com.attendo.core.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Pins [CommunityRules] to the Postgres contract it mirrors
 * (`supabase/migrations/0003_rpcs.sql`, `community.validate_payload` and
 * `community.expiry_for`). If the migration changes, one of these lines is what says this
 * file has to follow.
 */
class CommunityRulesTest {

    private val now: Instant = Instant.parse("2026-09-04T10:15:00Z")
    private val today: LocalDate = LocalDate.of(2026, 9, 4)

    // ------------------------------------------------------- report validation

    @Test
    fun `a plain room report with context is accepted`() {
        assertNull(
            CommunityRules.reportValidationError(
                CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "203", classDate = null, startHour = null,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `a room kind without a room is rejected`() {
        assertEquals(
            "a room is required for room observations",
            CommunityRules.reportValidationError(
                CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = null, classDate = null, startHour = null,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `a class kind without a slot is rejected`() {
        assertEquals(
            "a class date and hour are required for class observations",
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_CANCELLED,
                room = null, classDate = null, startHour = null,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `a class kind with a full slot is accepted`() {
        assertNull(
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_CANCELLED,
                room = null, classDate = today, startHour = 10,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `class_room_changed requires a new room`() {
        assertEquals(
            "a new room is required for a room change",
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_ROOM_CHANGED,
                room = null, classDate = today, startHour = 10,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `class_room_changed with a new room is accepted`() {
        assertNull(
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_ROOM_CHANGED,
                room = "203", classDate = today, startHour = 10,
                payload = ReportPayload(newRoom = "211"), note = null,
            )
        )
    }

    @Test
    fun `class_time_changed requires a new hour`() {
        assertEquals(
            "a new start hour is required for a time change",
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_TIME_CHANGED,
                room = null, classDate = today, startHour = 10,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `class_time_changed with a new hour is accepted`() {
        assertNull(
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_TIME_CHANGED,
                room = null, classDate = today, startHour = 10,
                payload = ReportPayload(newStartHour = 11), note = null,
            )
        )
    }

    @Test
    fun `an out-of-range new hour is rejected`() {
        assertEquals(
            "new start hour must be between 9 and 17",
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_TIME_CHANGED,
                room = null, classDate = today, startHour = 10,
                payload = ReportPayload(newStartHour = 8), note = null,
            )
        )
    }

    @Test
    fun `an oversized note is rejected`() {
        assertEquals(
            "note must be at most 280 characters",
            CommunityRules.reportValidationError(
                CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "203", classDate = null, startHour = null,
                payload = ReportPayload(), note = "x".repeat(281),
            )
        )
    }

    @Test
    fun `a note at exactly the limit is accepted`() {
        assertNull(
            CommunityRules.reportValidationError(
                CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "203", classDate = null, startHour = null,
                payload = ReportPayload(), note = "x".repeat(280),
            )
        )
    }

    @Test
    fun `an oversized room is rejected`() {
        assertEquals(
            "room must be at most 60 characters",
            CommunityRules.reportValidationError(
                CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "x".repeat(61), classDate = null, startHour = null,
                payload = ReportPayload(), note = null,
            )
        )
    }

    @Test
    fun `an oversized new room is rejected`() {
        assertEquals(
            "new room must be at most 60 characters",
            CommunityRules.reportValidationError(
                CommunityReportKind.CLASS_ROOM_CHANGED,
                room = "203", classDate = today, startHour = 10,
                payload = ReportPayload(newRoom = "x".repeat(61)), note = null,
            )
        )
    }

    // --------------------------------------------------------- poll validation

    @Test
    fun `a two-option poll is accepted`() {
        assertNull(CommunityRules.pollValidationError("Is 203 free?", listOf("Yes", "No")))
    }

    @Test
    fun `a short question is rejected`() {
        assertEquals(
            "question must be 3-160 characters",
            CommunityRules.pollValidationError("Hi", listOf("Yes", "No"))
        )
    }

    @Test
    fun `a one-option poll is rejected`() {
        assertEquals(
            "a poll needs 2-4 options",
            CommunityRules.pollValidationError("Is 203 free?", listOf("Yes"))
        )
    }

    @Test
    fun `a five-option poll is rejected`() {
        assertEquals(
            "a poll needs 2-4 options",
            CommunityRules.pollValidationError("Which room?", listOf("1", "2", "3", "4", "5"))
        )
    }

    @Test
    fun `duplicate option labels are rejected`() {
        // Mirrors the server fix Phase 4 added: ambiguous tallies.
        assertEquals(
            "options must be distinct",
            CommunityRules.pollValidationError("Is 203 free?", listOf("Yes", "Yes"))
        )
    }

    @Test
    fun `a blank option label is rejected`() {
        assertEquals(
            "options must be 1-40 characters",
            CommunityRules.pollValidationError("Is 203 free?", listOf("Yes", ""))
        )
    }

    @Test
    fun `poll dates are accepted within the server's today plus or minus one`() {
        assertTrue(CommunityRules.isPollDateInRange(today, today))
        assertTrue(CommunityRules.isPollDateInRange(today, today.minusDays(1)))
        assertTrue(CommunityRules.isPollDateInRange(today, today.plusDays(1)))
    }

    @Test
    fun `poll dates beyond the server's clamp are refused`() {
        // `create_poll` accepts its today ± 1 day and nothing wider; the client says so
        // before the round-trip rather than letting the server say it after.
        assertFalse(CommunityRules.isPollDateInRange(today, today.minusDays(2)))
        assertFalse(CommunityRules.isPollDateInRange(today, today.plusDays(2)))
    }

    @Test
    fun `report dates share the server's today plus or minus one window`() {
        // `submit_report` clamps exactly as `create_poll` does; the report path must
        // preview the same window so a far-off date is refused in the sheet, not by the
        // server after an outbox round-trip.
        assertTrue(CommunityRules.isReportDateInRange(today, today))
        assertTrue(CommunityRules.isReportDateInRange(today, today.minusDays(1)))
        assertTrue(CommunityRules.isReportDateInRange(today, today.plusDays(1)))
    }

    @Test
    fun `report dates beyond the server's clamp are refused`() {
        assertFalse(CommunityRules.isReportDateInRange(today, today.minusDays(2)))
        assertFalse(CommunityRules.isReportDateInRange(today, today.plusDays(2)))
    }

    @Test
    fun `an open poll before its close is votable`() {
        assertTrue(CommunityRules.isPollOpen(poll(closesAt = now.plusSeconds(60)), now))
    }

    @Test
    fun `a poll past its close is not votable however open its status says`() {
        // The cron sweep marks polls closed on a ten-minute lag, and a client can hold
        // a fetch older than that — `status = 'open'` alone must never be the verdict.
        assertFalse(CommunityRules.isPollOpen(poll(closesAt = now.minusSeconds(1)), now))
    }

    @Test
    fun `a poll the server already closed is not votable`() {
        assertFalse(
            CommunityRules.isPollOpen(
                poll(status = CommunityPollStatus.CLOSED, closesAt = now.plusSeconds(3600)),
                now,
            )
        )
    }

    private fun poll(
        status: CommunityPollStatus = CommunityPollStatus.OPEN,
        closesAt: Instant,
    ) = CommunityPoll(
        id = "poll",
        room = "203",
        section = null,
        subject = null,
        classDate = null,
        startHour = null,
        question = "Is 203 free?",
        status = status,
        options = listOf(PollOption(0, "Yes", 0), PollOption(1, "No", 0)),
        totalVotes = 0,
        closesAt = closesAt,
    )

    // ------------------------------------------------------------------ expiry

    @Test
    fun `a class-bound report expires 20 hours into its class date, UTC`() {
        // 2026-09-04 00:00 UTC + 20h = 20:00 UTC — two hours after the 18:00 day end.
        assertEquals(
            Instant.parse("2026-09-04T20:00:00Z"),
            CommunityRules.reportExpiresAt(now, today)
        )
    }

    @Test
    fun `a room-only report lives three hours`() {
        assertEquals(
            Instant.parse("2026-09-04T13:15:00Z"),
            CommunityRules.reportExpiresAt(now, null)
        )
    }

    @Test
    fun `a class-bound poll closes at 1830 UTC on its class date`() {
        assertEquals(
            Instant.parse("2026-09-04T18:30:00Z"),
            CommunityRules.pollClosesAt(now, today)
        )
    }

    @Test
    fun `a room poll closes three hours out`() {
        assertEquals(
            Instant.parse("2026-09-04T13:15:00Z"),
            CommunityRules.pollClosesAt(now, null)
        )
    }

    @Test
    fun `a poll never closes more than 24 hours out`() {
        // A class date far in the future would otherwise exceed the cap.
        val far = today.plusDays(3)
        assertEquals(
            now.plusSeconds(24 * 3600),
            CommunityRules.pollClosesAt(now, far)
        )
    }

    @Test
    fun `a class-bound poll about the past gets thirty minutes instead of being born closed`() {
        val yesterday = today.minusDays(1)
        assertEquals(
            now.plusSeconds(30 * 60),
            CommunityRules.pollClosesAt(now, yesterday)
        )
    }

    // ---------------------------------------------------------------- visibility

    @Test
    fun `visibility is status and expiry together`() {
        fun obs(status: CommunityObservationStatus, expiresAt: Instant) = CommunityObservation(
            id = "o", kind = CommunityReportKind.ROOM_OTHER, status = status,
            room = "203", section = null, subject = null, classDate = null, startHour = null,
            payload = ReportPayload(), note = null, createdAt = now, expiresAt = expiresAt
        )
        assertTrue(CommunityRules.isObservationVisible(obs(CommunityObservationStatus.REPORTED, now.plusSeconds(60)), now))
        assertTrue(!CommunityRules.isObservationVisible(obs(CommunityObservationStatus.REPORTED, now.minusSeconds(60)), now))
        assertTrue(!CommunityRules.isObservationVisible(obs(CommunityObservationStatus.EXPIRED, now.plusSeconds(60)), now))
        assertTrue(!CommunityRules.isObservationVisible(obs(CommunityObservationStatus.DISPUTED, now.plusSeconds(60)), now))
    }

    // ----------------------------------------------------------- future claims

    // The claim block's clock: 10:15 on the fixture day, so 9 and 10 have started and
    // 11 is the first hour a claim may still be about.
    private val nowTime: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 9, 4, 10, 15)

    @Test
    fun `a claim about a later slot today is accepted`() {
        assertNull(
            CommunityRules.futureClaimValidationError(
                kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "203", today = today, now = nowTime, targetStartHour = 14,
            )
        )
    }

    @Test
    fun `a class kind cannot be a claim`() {
        // The server refuses a claim about anything but a room; so does the preview.
        assertEquals(
            "claims are about rooms",
            CommunityRules.futureClaimValidationError(
                kind = CommunityReportKind.CLASS_CANCELLED,
                room = null, today = today, now = nowTime, targetStartHour = 14,
            )
        )
    }

    @Test
    fun `a claim needs a room`() {
        assertEquals(
            "a room is required for a claim",
            CommunityRules.futureClaimValidationError(
                kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = null, today = today, now = nowTime, targetStartHour = 14,
            )
        )
    }

    @Test
    fun `a claim without an hour asks for one`() {
        // The sheet's Send stays off until an hour is picked; this is the reason it gives.
        assertEquals(
            "pick the hour the claim is about",
            CommunityRules.futureClaimValidationError(
                kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "203", today = today, now = nowTime, targetStartHour = null,
            )
        )
    }

    @Test
    fun `claim hours outside the teaching day are refused`() {
        for (hour in listOf(8, 18)) {
            assertEquals(
                "the hour must be between 9 and 17",
                CommunityRules.futureClaimValidationError(
                    kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                    room = "203", today = today, now = nowTime, targetStartHour = hour,
                )
            )
        }
    }

    @Test
    fun `a claim about a slot that already started redirects to a present report`() {
        // 10:15 now: the 10 o'clock slot is underway, and "will be occupied at 10" is no
        // longer a claim — the server's invalid_target, previewed before the round-trip.
        assertEquals(
            "that hour has already started. Report what you see instead",
            CommunityRules.futureClaimValidationError(
                kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
                room = "203", today = today, now = nowTime, targetStartHour = 10,
            )
        )
    }

    @Test
    fun `a claim dies thirty minutes after its target slot ends, UTC`() {
        // The twin of community.claim_expiry: 14–15 slot on 2026-09-04 → 15:30 UTC.
        assertEquals(
            Instant.parse("2026-09-04T15:30:00Z"),
            CommunityRules.claimExpiresAt(today, 14)
        )
        // The day's last slot (17–18) expires at 18:30 — nothing lingers into the evening.
        assertEquals(
            Instant.parse("2026-09-04T18:30:00Z"),
            CommunityRules.claimExpiresAt(today, 17)
        )
    }

    @Test
    fun `the target hour is part of a claim's identity`() {
        // Mirrors the 0007 obs_dedup_unique index: "occupied at 11" and "occupied at 14"
        // are two claims, so their dedup keys differ — while the same hour twice is one.
        fun key(target: Int?) = CommunityRules.dedupKey(
            reporterId = "r", kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
            room = "203", classDate = null, startHour = null,
            payload = ReportPayload(), atHourStart = now, targetStartHour = target,
        )
        assertTrue(key(11) != key(14))
        assertEquals(key(14), key(14))
        // And a claim never collides with the same-kind present report beside it.
        assertTrue(key(14) != key(null))
    }

    @Test
    fun `observation types round-trip and unknown types read as present`() {
        for (type in CommunityObservationType.entries.filter { it != CommunityObservationType.UNRECOGNIZED }) {
            assertEquals(type, CommunityObservationType.fromWire(type.wireName))
        }
        // A newer server's type must never crash a reader: it reads as the safest
        // interpretation, a present observation.
        assertEquals(CommunityObservationType.PRESENT, CommunityObservationType.fromWire("retroactive"))
        assertEquals(CommunityObservationType.PRESENT, CommunityObservationType.fromWire(null))
    }

    // -------------------------------------------------------------------- wire

    @Test
    fun `every report kind round-trips through its wire name`() {
        for (kind in CommunityReportKind.entries.filter { it != CommunityReportKind.UNRECOGNIZED }) {
            assertEquals(kind, CommunityReportKind.fromWire(kind.wireName))
        }
    }

    @Test
    fun `unknown wire values read as UNRECOGNIZED instead of throwing`() {
        assertEquals(CommunityReportKind.UNRECOGNIZED, CommunityReportKind.fromWire("class_teleported"))
        assertEquals(CommunityObservationStatus.UNRECOGNIZED, CommunityObservationStatus.fromWire("quarantined"))
        assertEquals(CommunityPollStatus.UNRECOGNIZED, CommunityPollStatus.fromWire("archived"))
    }

    @Test
    fun `payload json uses only the five blessed keys`() {
        val json = ReportPayload(
            newRoom = "211", newStartHour = 11, newDate = today,
            subject = "ADEC", section = "2nd Yr CSE-A",
        ).toJson()
        assertEquals(setOf("new_room", "new_start_hour", "new_date", "subject", "section"), json.keys)
    }

    @Test
    fun `payload round-trips and drops unknown server keys`() {
        val payload = ReportPayload(newRoom = "211", newStartHour = 11)
        val roundTrip = ReportPayload.fromJson(payload.toJson())
        assertEquals(payload.copy(), roundTrip)

        // A server payload with keys this build never writes: read what we know, ignore the rest.
        val fromServer = kotlinx.serialization.json.buildJsonObject {
            put("new_room", kotlinx.serialization.json.JsonPrimitive("211"))
            put("mystery_key", kotlinx.serialization.json.JsonPrimitive("anything"))
        }
        assertEquals("211", ReportPayload.fromJson(fromServer).newRoom)
    }
}
