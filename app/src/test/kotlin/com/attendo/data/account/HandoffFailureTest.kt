package com.attendo.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * The fold from what the press threw to the one reason worth showing, and the transport
 * test underneath it.
 *
 * The press makes exactly one request — the authenticated ask for the provider's
 * authorize URL; the browser does the rest — so the reachable reasons are few by
 * design. What this file pins is the discipline around them: the network is only ever
 * blamed for a transport failure, a cancelled call is rethrown instead of answered, and
 * everything else falls to the one safe sentence — never a guess at the cause. The
 * "usually a rate limit" bug was exactly such a guess, and these tests are what keeps
 * it from coming back disguised.
 *
 * The transport test earns its own boundary cases below, because "is this failure the
 * network's fault?" is the one question in this file with a wrong answer a student
 * would act on: told to check a connection that was never the problem, they retry
 * something that will fail again; not told when it *was* the problem, they retry into
 * the same wall with no idea why. So the depth bound is asserted from both sides, the
 * cause chain is exercised at each depth, and the one thing that must never decide the
 * answer — the exception's message text — is asserted against directly.
 */
class HandoffFailureTest {

    /** [depth] wrappers around [root]. Depth 0 is the root itself, un-wrapped. */
    private fun wrapped(root: Throwable, depth: Int): Throwable {
        var current = root
        repeat(depth) { current = IllegalStateException("wrapper $it", current) }
        return current
    }

    // ------------------------------------------------------- what counts as transport

    @Test
    fun `a transport failure is a network problem`() {
        // Connect errors, timeouts and DNS failures are the shapes an offline phone
        // produces, and all of them are IOExceptions.
        assertEquals(HandoffFailure.NETWORK, handoffFailureOf(IOException("connection refused")))
        assertEquals(HandoffFailure.NETWORK, handoffFailureOf(UnknownHostException("supabase.co")))
        assertEquals(HandoffFailure.NETWORK, handoffFailureOf(SocketTimeoutException("timed out")))
        assertEquals(HandoffFailure.NETWORK, handoffFailureOf(ConnectException("refused")))
    }

    @Test
    fun `a DNS failure that is not an IOException is still a network problem`() {
        // UnresolvedAddressException extends IllegalArgumentException, *not* IOException.
        // Ktor can surface an unresolved host as this, depending on the engine and the
        // path, and reading it as an unidentified failure is how an offline sign-in
        // loses its connection sentence — the exact QA finding this classification was
        // widened for.
        assertFalse(
            "The premise of this case: UnresolvedAddressException must not be an IOException.",
            UnresolvedAddressException() is IOException,
        )
        assertEquals(HandoffFailure.NETWORK, handoffFailureOf(UnresolvedAddressException()))
    }

    @Test
    fun `a wrapped transport failure is still a network problem`() {
        // Whether the error arrives bare or wrapped depends on which layer noticed it
        // first — Ktor's engine, supabase-kt's request wrapper, the auth plugin — and
        // that is not something this app can know or keep in step with a library
        // version. Walking the cause chain is what makes the verdict independent of it.
        assertEquals(
            HandoffFailure.NETWORK,
            handoffFailureOf(wrapped(UnknownHostException("supabase.co"), depth = 1)),
        )
        assertEquals(
            HandoffFailure.NETWORK,
            handoffFailureOf(wrapped(UnknownHostException("supabase.co"), depth = 4)),
        )
        assertEquals(
            HandoffFailure.NETWORK,
            handoffFailureOf(wrapped(UnresolvedAddressException(), depth = 2)),
        )
    }

    @Test
    fun `the cause chain is followed to exactly the documented depth`() {
        // The bound is a decision, not an implementation detail: past it the answer is
        // "not transport", which sends the student to the generic "please try again"
        // rather than claiming a connectivity problem the app could not confirm — the
        // same rule every other unidentifiable cause in this file follows. Asserted
        // from both sides so lowering or raising MAX_CAUSE_HOPS cannot pass unnoticed.
        assertEquals(
            "A transport failure at exactly the bound must still be found.",
            HandoffFailure.NETWORK,
            handoffFailureOf(wrapped(UnknownHostException("x"), depth = MAX_CAUSE_HOPS)),
        )
        assertEquals(
            "One hop beyond the bound must fail safe, not claim the network.",
            HandoffFailure.UNKNOWN,
            handoffFailureOf(wrapped(UnknownHostException("x"), depth = MAX_CAUSE_HOPS + 1)),
        )
    }

    @Test
    fun `a cyclic cause chain terminates without claiming the network`() {
        // initCause permits a cycle (it only refuses a cause that is the throwable
        // itself), so this is constructible, and a walk without a bound would spin here
        // forever. The assertion is the result; the *termination* is proved by this
        // test finishing at all.
        val a = IllegalStateException("a")
        val b = IllegalStateException("b")
        a.initCause(b)
        b.initCause(a)

        assertEquals(HandoffFailure.UNKNOWN, handoffFailureOf(a))
        assertEquals(HandoffFailure.UNKNOWN, handoffFailureOf(b))
    }

    @Test
    fun `a pathologically deep chain terminates without claiming the network`() {
        // Same guard as the cycle, for a chain that is merely far longer than any real
        // one: the walk must stay bounded rather than traverse a thousand causes.
        assertEquals(HandoffFailure.UNKNOWN, handoffFailureOf(wrapped(UnknownHostException("x"), depth = 1_000)))
    }

    // ------------------------------------------------ what must never count as transport

    @Test
    fun `an unexpected exception gets the safe fallback`() {
        // No browser to open, a parsing surprise, a server that answered something this
        // app does not model: one safe sentence, never a stack trace and never a guess
        // at the cause.
        assertEquals(HandoffFailure.UNKNOWN, handoffFailureOf(IllegalStateException("boom")))
    }

    @Test
    fun `an exception that merely says network is not a network failure`() {
        // The "usually a rate limit" bug, in the shape it would come back in: deciding
        // by what the message says. Nothing in the classification reads `message`, and
        // this is what says so — a failure the server answered, wearing the words, must
        // still be answered as a server-side failure.
        for (text in listOf("network error", "no connection", "offline", "UnknownHost", "ETIMEDOUT")) {
            assertEquals(
                "Message text must never decide the classification (\"$text\").",
                HandoffFailure.UNKNOWN,
                handoffFailureOf(IllegalStateException(text)),
            )
        }
    }

    @Test
    fun `a server-side failure is not a network failure`() {
        // A refusal the app received is a refusal, however it is wrapped: the request
        // travelled, so telling the student to check their connection would be wrong.
        assertEquals(
            HandoffFailure.UNKNOWN,
            handoffFailureOf(RuntimeException("invalid_grant")),
        )
        assertEquals(
            HandoffFailure.UNKNOWN,
            handoffFailureOf(wrapped(RuntimeException("invalid_grant"), depth = 2)),
        )
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `a cancelled call is rethrown, never answered`() {
        // Cancellation is coroutine bookkeeping (the screen left while the call was in
        // flight), not a verdict for the student. Swallowing it here would corrupt the
        // caller's scope; the fold rethrows it instead.
        try {
            handoffFailureOf(CancellationException("screen gone"))
            fail("handoffFailureOf must rethrow CancellationException, not classify it.")
        } catch (expected: CancellationException) {
            // the only acceptable outcome
        }
    }

    @Test
    fun `a timeout raised by the coroutine machinery is cancellation, not a network verdict`() {
        // The case that makes the rethrow-before-classify ordering load-bearing:
        // TimeoutCancellationException *is* a CancellationException, and a timeout the
        // coroutine machinery raised says nothing about the network. A fold that
        // classified first would answer it as a transport failure.
        val timeout = runBlocking {
            runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()!!
        }
        assertTrue(
            "The premise of this case: TimeoutCancellationException must be a CancellationException.",
            timeout is CancellationException,
        )
        try {
            handoffFailureOf(timeout)
            fail("A timeout is cancellation and must be rethrown, not classified as a network failure.")
        } catch (expected: CancellationException) {
            // the only acceptable outcome
        }
    }
}
