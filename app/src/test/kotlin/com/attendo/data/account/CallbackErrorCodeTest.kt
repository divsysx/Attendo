package com.attendo.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The error_code an OAuth failure carries, and the notice it deserves.
 *
 * The callback fragment on a failed link is
 * `#error=…&error_description=…&error_code=…`, and GoTrue puts real, stable codes in
 * the last of those. One of them — `identity_already_exists` — describes a state the
 * student can recognise (most often their own earlier install, whose GitHub link
 * outlives both an app-data clear and the dashboard's deletion of its user), so it
 * earns its own sentence. This file pins that the code is read from the right
 * parameter, that exactly that one code is singled out, and that everything else —
 * known codes, unknown codes, no code at all — falls to the same safe refusal, never
 * a guess at what the server might have meant.
 */
class CallbackErrorCodeTest {

    @Test
    fun `an identity_already_exists refusal is named for what it is`() {
        val fragment = "error=server_error" +
            "&error_description=Identity+is+already+linked+to+another+user" +
            "&error_code=identity_already_exists"

        assertEquals(AccountManager.IDENTITY_ALREADY_EXISTS, errorCodeIn(fragment))
        assertEquals(HandoffNotice.IDENTITY_TAKEN, handoffNoticeOf(errorCodeIn(fragment)))
    }

    @Test
    fun `any other code, and no code at all, stay on the safe refusal`() {
        // The safe fallback is the discipline the "usually a rate limit" bug taught:
        // an unrecognised code is not the student's problem, and guessing at it is
        // how that bug shipped. One code is named; everything else is one sentence.
        assertEquals(HandoffNotice.REFUSED, handoffNoticeOf("server_error"))
        assertEquals(HandoffNotice.REFUSED, handoffNoticeOf("some_future_code_we_do_not_know"))
        assertEquals(HandoffNotice.REFUSED, handoffNoticeOf(null))
        assertNull(errorCodeIn("error=server_error&error_description=Something+broke"))
        assertNull(errorCodeIn(""))
    }

    @Test
    fun `the code is read only from its own parameter`() {
        // The description is prose and can mention anything — including a code that
        // is not the verdict. Only the error_code parameter counts.
        val fragment = "error=server_error" +
            "&error_description=identity_already_exists+occurred+earlier" +
            "&error_code=oauth_callback_failure"

        assertEquals("oauth_callback_failure", errorCodeIn(fragment))
        assertEquals(HandoffNotice.REFUSED, handoffNoticeOf(errorCodeIn(fragment)))
    }

    @Test
    fun `the code is read wherever the parameter sits`() {
        // Parameter order in the fragment is the server's choice, not ours.
        assertEquals(
            AccountManager.IDENTITY_ALREADY_EXISTS,
            errorCodeIn("error_code=identity_already_exists&error=server_error"),
        )
    }

    @Test
    fun `an empty code parameter answers null, not an empty verdict`() {
        assertNull(errorCodeIn("error=server_error&error_code="))
    }
}
