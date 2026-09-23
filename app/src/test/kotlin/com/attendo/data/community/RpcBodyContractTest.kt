package com.attendo.data.community

import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.ReportPayload
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/**
 * The RPC wire contract, pinned against the JSON the server actually receives.
 *
 * PostgREST 16 resolves a function only when the request body carries every non-DEFAULT
 * parameter; an absent one is PGRST202 — the HTTP 404 production logged while every
 * basic room report (no section, subject, class date or start hour) silently omitted
 * four of them, and the outbox recorded the transport's answer as a decline. Null
 * context fields must ride the body as JSON null, never as missing keys. These tests
 * call the real builders, so what they assert is the serialized request body itself.
 */
class RpcBodyContractTest {

    @Test
    fun `a basic room report carries every non-default parameter, nulls included`() {
        // The exact shape that failed in production: a room, a kind, a note — and no
        // section, subject, class date or start hour.
        val body = buildSubmitReportBody(
            kind = CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
            room = "217",
            section = null,
            subject = null,
            classDate = null,
            startHour = null,
            payload = ReportPayload(),
            note = "Testing",
            idempotencyKey = "idem-key",
            clientVersionCode = 2,
            observationType = CommunityObservationType.PRESENT,
            eventDate = null,
            targetStartHour = null,
        )

        assertEquals(
            setOf(
                "p_kind", "p_room", "p_section", "p_subject", "p_class_date",
                "p_start_hour", "p_payload", "p_note", "p_idempotency_key",
                "p_client_version_code",
            ),
            body.keys,
        )
        for (key in listOf("p_section", "p_subject", "p_class_date", "p_start_hour")) {
            assertEquals("$key must ride the body as JSON null, not an absent key.", JsonNull, body[key])
        }
        assertEquals("room_occupied_despite_free", body["p_kind"]!!.jsonPrimitive.content)
        assertEquals("217", body["p_room"]!!.jsonPrimitive.content)
        assertEquals("Testing", body["p_note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a class-level report carries its null room and note explicitly`() {
        // p_room and p_note are non-default parameters too: a class-kind report with no
        // room omitted p_room before the fix and hit the same 404.
        val body = buildSubmitReportBody(
            kind = CommunityReportKind.CLASS_CANCELLED,
            room = null,
            section = null,
            subject = "Physics",
            classDate = LocalDate.of(2026, 9, 8),
            startHour = 14,
            payload = ReportPayload(),
            note = null,
            idempotencyKey = "idem-key",
            clientVersionCode = 2,
            observationType = CommunityObservationType.PRESENT,
            eventDate = null,
            targetStartHour = null,
        )

        assertEquals(JsonNull, body["p_room"])
        assertEquals(JsonNull, body["p_note"])
        assertEquals("2026-09-08", body["p_class_date"]!!.jsonPrimitive.content)
        assertEquals(14, body["p_start_hour"]!!.jsonPrimitive.int)
    }

    @Test
    fun `claim parameters ride the body only for claims`() {
        // The default migration 0007 gave the three claim parameters is what allows them
        // to be absent: an unmigrated ten-parameter server still accepts this body. A
        // present report must not carry them (PGRST202 against the old server), a claim
        // must carry all three.
        val present = buildSubmitReportBody(
            kind = CommunityReportKind.ROOM_OTHER,
            room = "217",
            section = null, subject = null, classDate = null, startHour = null,
            payload = ReportPayload(), note = null,
            idempotencyKey = "idem-key", clientVersionCode = 2,
            observationType = CommunityObservationType.PRESENT,
            eventDate = null, targetStartHour = null,
        )
        for (key in listOf("p_observation_type", "p_event_date", "p_target_start_hour")) {
            assertFalse("$key must be absent for a present report (old-server compatibility).", key in present)
        }

        val claim = buildSubmitReportBody(
            kind = CommunityReportKind.ROOM_OTHER,
            room = "217",
            section = null, subject = null, classDate = null, startHour = null,
            payload = ReportPayload(), note = null,
            idempotencyKey = "idem-key", clientVersionCode = 2,
            observationType = CommunityObservationType.FUTURE,
            eventDate = LocalDate.of(2026, 9, 9),
            targetStartHour = 14,
        )
        assertEquals("future", claim["p_observation_type"]!!.jsonPrimitive.content)
        assertEquals("2026-09-09", claim["p_event_date"]!!.jsonPrimitive.content)
        assertEquals(14, claim["p_target_start_hour"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a poll body carries every non-default parameter, nulls included`() {
        val body = buildCreatePollBody(
            room = null,
            section = null,
            subject = null,
            classDate = null,
            startHour = null,
            question = "Is 217 free?",
            options = listOf("yes", "no"),
            idempotencyKey = "idem-key",
            clientVersionCode = 2,
        )

        assertEquals(
            setOf(
                "p_room", "p_section", "p_subject", "p_class_date", "p_start_hour",
                "p_question", "p_options", "p_idempotency_key", "p_client_version_code",
            ),
            body.keys,
        )
        for (key in listOf("p_room", "p_section", "p_subject", "p_class_date", "p_start_hour")) {
            assertEquals("$key must ride the body as JSON null, not an absent key.", JsonNull, body[key])
        }

        val withRoom = buildCreatePollBody(
            room = "217",
            section = "A",
            subject = "Physics",
            classDate = LocalDate.of(2026, 9, 8),
            startHour = 14,
            question = "Is 217 free?",
            options = listOf("yes", "no"),
            idempotencyKey = "idem-key",
            clientVersionCode = 2,
        )
        assertEquals("217", withRoom["p_room"]!!.jsonPrimitive.content)
        assertEquals("A", withRoom["p_section"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a claim body carries the transfer code and exactly one key`() {
        // claim_reporting_identity (0015) takes a single non-default parameter, so the
        // body is a single key — and the code must ride the body, never a header or a
        // URL, either of which would put a live transfer code into a log. The other
        // three transfer RPCs (begin, approve, abort) take no parameters at all, so
        // their empty bodies have nothing to pin here.
        val body = buildClaimIdentityBody("XTY-9fQ2-staging-mint", replaceClaimant = false)

        assertEquals(setOf("p_transfer_code"), body.keys)
        assertEquals(
            "XTY-9fQ2-staging-mint",
            body["p_transfer_code"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a replacement claim adds exactly one key, the flag, and only when confirmed`() {
        // Migration 0017 gave claim_reporting_identity a second parameter with a
        // default, so it must ride the body only on the destructive path: an
        // ordinary claim against a server that predates replacement (or simply a
        // non-replacing one) must keep the single-key shape above.
        val replace = buildClaimIdentityBody("XTY-9fQ2-staging-mint", replaceClaimant = true)

        assertEquals(setOf("p_transfer_code", "p_replace_claimant"), replace.keys)
        assertEquals(
            "XTY-9fQ2-staging-mint",
            replace["p_transfer_code"]!!.jsonPrimitive.content,
        )
        assertEquals(true, replace["p_replace_claimant"]!!.jsonPrimitive.boolean)
    }
}
