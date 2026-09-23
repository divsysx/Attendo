package com.attendo.data.account

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * The notice a failed OAuth callback import earns.
 *
 * The import is the one step of the GitHub round-trip that happens after the browser
 * has already handed control back — right when the network is least trustworthy — and
 * it used to answer every failure with the same generic sentence. What this file pins
 * is the split: a failure in transport is answered with the connection sentence, and
 * everything else keeps the generic one. A failure the server *answered* must never be
 * told to check a connection that was never the problem, and a cancelled import is not
 * a verdict for the student at all.
 *
 * The classification itself is [isTransportFailure]'s, pinned at its own boundaries in
 * [HandoffFailureTest]; this file covers the fold on top of it.
 */
class ImportFailureTest {

    @Test
    fun `an import that failed in transport is answered as a network problem`() {
        assertEquals(
            HandoffNotice.NETWORK,
            handoffNoticeOfFailure(UnknownHostException("supabase.co")),
        )
        assertEquals(HandoffNotice.NETWORK, handoffNoticeOfFailure(IOException("connection reset")))
        assertEquals(HandoffNotice.NETWORK, handoffNoticeOfFailure(UnresolvedAddressException()))
        // Wrapped, the way a library layer may hand it back.
        assertEquals(
            HandoffNotice.NETWORK,
            handoffNoticeOfFailure(IllegalStateException("request failed", UnknownHostException("x"))),
        )
    }

    @Test
    fun `an import that failed for any other reason keeps the generic answer`() {
        // A fragment the library would not parse, a session the server refused, a
        // retirement that could not be confirmed: the request travelled or the answer
        // was a refusal, so the connection is not the thing to check.
        assertEquals(
            HandoffNotice.IMPORT_FAILED,
            handoffNoticeOfFailure(IllegalStateException("The link authorize answer carried no URL.")),
        )
        assertEquals(
            HandoffNotice.IMPORT_FAILED,
            handoffNoticeOfFailure(RuntimeException("invalid_grant")),
        )
        assertEquals(
            HandoffNotice.IMPORT_FAILED,
            handoffNoticeOfFailure(IllegalStateException("The outgoing identity's retirement could not be confirmed.")),
        )
    }

    @Test
    fun `a cancelled import is rethrown, never answered`() {
        // The import runs in the app's scope and the screen can go away mid-flight.
        // Cancellation is that, not a verdict — and rethrowing it is what keeps the
        // caller's scope intact. Checked before classification, so a cancellation can
        // never be dressed as a transport failure.
        try {
            handoffNoticeOfFailure(CancellationException("screen gone"))
            fail("handoffNoticeOfFailure must rethrow CancellationException, not classify it.")
        } catch (expected: CancellationException) {
            // the only acceptable outcome
        }
    }
}
