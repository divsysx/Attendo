package com.attendo.core.community

import java.time.Duration
import java.time.Instant

/**
 * When and whether a pending community submission gets retried.
 *
 * The outbox row itself lives in Room in the app module; this is the arithmetic only, so
 * it can be tested without Android: how long to wait after the Nth failed attempt, and
 * when a row stops being retried and becomes the student's to delete or resubmit.
 *
 * Backoff grows exponentially from [BASE_DELAY] up to [MAX_DELAY] so a network that is down
 * for an hour is not hammered every ten seconds, and the cap keeps the schedule honest on
 * the way back up. The attempt cap is a hard stop rather than an unbounded retry, because
 * a submission that has failed five times is something the student should see and decide
 * about, not something the app should quietly keep trying for the rest of the semester.
 */
object OutboxPolicy {

    const val MAX_ATTEMPTS: Int = 5

    private val BASE_DELAY: Duration = Duration.ofSeconds(30)
    private val MAX_DELAY: Duration = Duration.ofMinutes(15)

    /**
     * How long to wait before attempt [nextAttempt] (1-based: the first retry is attempt 1).
     * 30s, 1m, 2m, 4m, capped at 15m.
     */
    fun delayBeforeAttempt(nextAttempt: Int): Duration {
        require(nextAttempt >= 1) { "attempt numbers start at 1" }
        val shift = (nextAttempt - 1).coerceAtMost(20) // 2^20 s ≈ 12 days; the cap bites first
        val exponential = BASE_DELAY.multipliedBy(1L shl shift)
        return if (exponential > MAX_DELAY) MAX_DELAY else exponential
    }

    /** True while the row still earns automatic retries. */
    fun shouldRetry(failedAttempts: Int): Boolean = failedAttempts < MAX_ATTEMPTS

    /**
     * The instant a row becomes due, given when it last failed. Null when the row is
     * terminal — past the cap — and waiting longer will not help.
     */
    fun nextDueAt(lastFailureAt: Instant, failedAttempts: Int): Instant? =
        if (!shouldRetry(failedAttempts)) null
        else lastFailureAt.plus(delayBeforeAttempt(failedAttempts + 1))
}
