package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * When it is time to ask the network about updates.
 *
 * The interval is the whole defence against an app that phones home on every cold start,
 * so the boundary cases are pinned: never having checked, exactly one interval, one
 * interval plus a moment, and a manual check that bypasses all of it by design (that last
 * one is structural — [UpdateCheckPolicy.isDue] is simply not consulted on that path).
 */
class UpdateCheckPolicyTest {

    private val policy = UpdateCheckPolicy()
    private val now: Instant = Instant.parse("2026-09-01T10:00:00Z")

    @Test
    fun `never having checked means a check is due`() {
        // The first run of the app is a check that is due — otherwise a fresh install
        // would wait a day before ever hearing about the release it was born behind on.
        assertTrue(policy.isDue(lastChecked = null, now = now))
    }

    @Test
    fun `a check one hour ago is not due again`() {
        val lastChecked = now.minus(Duration.ofHours(1))

        assertFalse(policy.isDue(lastChecked, now))
    }

    @Test
    fun `a check 23 hours ago is not due again`() {
        val lastChecked = now.minus(Duration.ofHours(23))

        assertFalse(policy.isDue(lastChecked, now))
    }

    @Test
    fun `a check 25 hours ago is due again`() {
        val lastChecked = now.minus(Duration.ofHours(25))

        assertTrue(policy.isDue(lastChecked, now))
    }

    @Test
    fun `exactly one interval later is due`() {
        // The interval is a floor, not a window: a check at 10:00 yesterday is due at
        // 10:00 today, not 10:00:01.
        val lastChecked = now.minus(UpdateCheckPolicy.DEFAULT_INTERVAL)

        assertTrue(policy.isDue(lastChecked, now))
    }

    @Test
    fun `the default interval is about a day`() {
        // "Approximately 24 hours" is the promise; a regression that made this minutes
        // would be the app hammering GitHub on every open, and one that made it weeks
        // would strand students behind a fixed bug.
        assertEquals(Duration.ofHours(24), UpdateCheckPolicy.DEFAULT_INTERVAL)
    }

    @Test
    fun `a failed check is not this policy's business - only a timestamp is`() {
        // The policy sees an Instant and nothing else. Whether a check *succeeded* is the
        // caller's discipline (UpdateCheckStore records only completed checks), which is
        // why this test exists: to say out loud that a failed check must never be handed
        // to this method as though it had happened.
        assertTrue(policy.isDue(null, now))
    }
}
