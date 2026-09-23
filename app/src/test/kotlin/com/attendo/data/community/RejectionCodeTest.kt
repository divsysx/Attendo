package com.attendo.data.community

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a server refusal's reason is recovered from the shape it arrived in.
 *
 * The RPCs' own refusals arrive as HTTP 200 `{ok, code}` envelopes and are mapped by
 * `RemoteReportApi`'s call sites. What reaches [rejectionCode] is a `RestException`'s
 * message — supabase-kt builds it as the server's own message first, then code/hint/URL
 * lines (see `PostgrestRestException`), and some layers hand the JSON envelope over
 * whole. These pin all three shapes plus the empty one, because a mis-parsed reason
 * becomes a raw non-code string in front of a student.
 */
class RejectionCodeTest {

    @Test
    fun `a RestException message yields its first line, the server's own reason`() {
        // The shape PostgrestRestException builds for a raised SQL exception such as
        // `raise exception 'not_authenticated' using errcode = '28000'`.
        assertEquals(
            "not_authenticated",
            rejectionCode("not_authenticated\nCode: 28000\nURL: https://example.supabase.co\nHttp Method: POST"),
        )
    }

    @Test
    fun `a structured JSON envelope is unwrapped to its message`() {
        // Some layers (proxies, other server versions) leave the envelope unparsed. The
        // reason lives in "message" — the same field PostgREST documents for errors.
        assertEquals(
            "not_authenticated",
            rejectionCode("""{"code":"28000","message":"not_authenticated","hint":null,"details":null}"""),
        )
    }

    @Test
    fun `an unknown reason passes through as itself`() {
        // Same honesty rule as the failure-line helpers: a reason this build has never
        // met is shown raw, never guessed at.
        assertEquals("some_future_failure", rejectionCode("some_future_failure"))
    }

    @Test
    fun `no message at all becomes the generic rejected code`() {
        assertEquals("rejected", rejectionCode(null))
        assertEquals("rejected", rejectionCode(""))
        assertEquals("rejected", rejectionCode("   \nCode: 28000"))
    }
}
