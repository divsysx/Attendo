package com.attendo.ui.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The poll card's words: how long a poll has left, and what a refusal sounds like.
 *
 * Two small pure functions carry both, so both are pinned here — a poll that said
 * "closes in -3 min" or a rejection that said "rate_limited_day" to a student would be
 * the feature failing at the only moment it is listened to.
 */
class CommunityPollWordsTest {

    private val now: Instant = Instant.parse("2026-09-04T10:00:00Z")

    @Test
    fun `closesIn reads in minutes, hours, then days`() {
        assertEquals("closes in 45 min", closesIn(now.plusSeconds(45 * 60), now))
        assertEquals("closes in 2 hr", closesIn(now.plusSeconds(2 * 3600), now))
        assertEquals("closes in 2 d", closesIn(now.plusSeconds(48 * 3600), now))
    }

    @Test
    fun `a poll already past its close says closing, not a negative count`() {
        assertEquals("closing", closesIn(now.minusSeconds(60), now))
    }

    @Test
    fun `no server clock means no guess`() {
        // Without the snapshot's clock the honest label is none at all — the device
        // clock is wrong by hours for some students, and "closes in 3 hr" said with the
        // wrong clock is worse than silence.
        assertEquals("", closesIn(now.plusSeconds(3600), null))
    }

    @Test
    fun `known refusals become lines a student can act on`() {
        assertEquals("This poll just closed", pollFailureLine("poll_closed"))
        assertEquals("You're temporarily unable to take part", pollFailureLine("restricted"))
        assertEquals(
            "You're voting a lot right now. Give it a minute.",
            pollFailureLine("rate_limited_short"),
        )
        assertEquals(
            "You've asked a lot of polls today.",
            pollFailureLine("rate_limited_day"),
        )
    }

    @Test
    fun `a dead session says reopen, not a raw code`() {
        // `not_authenticated` arrives as a raised SQL exception rather than an RPC
        // envelope, so it is the one code a student is most likely to see raw. The next
        // community contact re-mints the session, which is what makes "reopen" honest.
        assertEquals(
            "You're signed out. Reopen the app and try again.",
            pollFailureLine("not_authenticated"),
        )
    }

    @Test
    fun `an unknown refusal keeps its code instead of hiding it`() {
        // The additive-only backend means tomorrow's server can send a code this build
        // has never heard of — showing it raw is honest, mapping it to a wrong friendly
        // line is not.
        val line = pollFailureLine("some_future_code")
        assertTrue(line.contains("some_future_code"))
        assertFalse(line.contains("restricted"))
    }

    @Test
    fun `own-poll and second-vote refusals become lines, not raw codes`() {
        // The chips hide themselves for the creator's own poll (myPollIds) and after a
        // vote (myVote), but a stale list can still offer them — and after an identity
        // move the ownership facts arrive only with the next refresh, so the refusal
        // line is the bridge. Raw `own_poll` at that moment was a real gap.
        assertEquals("This is your own poll", pollFailureLine("own_poll"))
        assertEquals("You already voted in this poll", pollFailureLine("already_voted"))
    }

    @Test
    fun `verdict refusals become lines too`() {
        // The verdict path used to throw its result away entirely — a tap the server
        // refused did nothing at all. The lines are the fix's other half; own_report
        // is the identity-move case: the report is this identity's, the local outbox
        // just has not been told yet.
        assertEquals("This is your own report", verdictFailureLine("own_report"))
        assertEquals("You already marked this report", verdictFailureLine("already_verified"))
        assertEquals(
            "You're marking a lot right now. Give it a minute.",
            verdictFailureLine("rate_limited_short"),
        )
        assertEquals("This report is gone", verdictFailureLine("not_found"))
        val line = verdictFailureLine("some_future_code")
        assertTrue(line.contains("some_future_code"))
    }

    @Test
    fun `the sheet cannot be asked twice`() {
        // Structural pin, same as the report sheet's: the Ask button must consult the
        // in-flight guard both in `enabled` (recomposition) and in the handler itself
        // (two clicks can land inside one frame, before any recomposition), and the
        // guard must be transient — a persisted `sending = true` restored after process
        // death would freeze a sheet whose request died with the process.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/PollSheet.kt").readText()
        assertTrue(
            "The Ask button must be disabled while a request is in flight.",
            source.contains("enabled = !sending"),
        )
        assertTrue(
            "The handler itself must consult the guard — recomposition can lag a click.",
            source.contains("if (!sending) {"),
        )
        assertTrue(
            "The in-flight guard must not survive process death.",
            source.contains("var sending by remember { mutableStateOf(false) }"),
        )
        assertFalse(
            "A persisted sending flag would freeze a restored sheet.",
            source.contains("sending by rememberSaveable"),
        )
    }

    // ------------------------------------------------------------------ plumbing

    private fun sourceFile(relative: String): java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { java.io.File(it, relative) }
            .first(java.io.File::exists)
}
