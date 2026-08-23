package com.attendo.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The integrity check.
 *
 * Two properties, and they pull in opposite directions. The hash must be **blind to
 * formatting**, or a backup that has been through a JSON formatter — or written by a build whose
 * serialiser orders keys differently — would fail verification while meaning exactly the same
 * thing. And it must be **sensitive to data**, down to a single digit, because the whole point
 * is to catch a file that lost a block on its way through a chat app.
 */
class BackupChecksumTest {

    // ---- blind to formatting ------------------------------------------------

    @Test
    fun `key order does not change the hash`() {
        val one = parse("""{"a": 1, "b": 2, "c": 3}""")
        val other = parse("""{"c": 3, "a": 1, "b": 2}""")

        assertEquals(BackupChecksum.of(one), BackupChecksum.of(other))
    }

    @Test
    fun `whitespace and indentation do not change the hash`() {
        val compact = parse("""{"courses":[{"ref":"c1","name":"ADEC"}],"n":2}""")
        val spread = parse(
            """
            {
                "courses" : [
                    { "name" : "ADEC" , "ref" : "c1" }
                ] ,
                "n" : 2
            }
            """.trimIndent(),
        )

        assertEquals(BackupChecksum.of(compact), BackupChecksum.of(spread))
    }

    @Test
    fun `nested objects are canonicalised at every depth`() {
        val one = parse("""{"outer": {"inner": {"z": 1, "a": 2}, "list": [{"q": 1, "p": 2}]}}""")
        val other = parse("""{"outer": {"list": [{"p": 2, "q": 1}], "inner": {"a": 2, "z": 1}}}""")

        assertEquals(BackupChecksum.of(one), BackupChecksum.of(other))
    }

    @Test
    fun `an exported backup verifies after being reformatted`() {
        val payload = payloadOf(encoded(pretty = true))
        val reformatted = payloadOf(encoded(pretty = false))

        assertEquals(BackupChecksum.of(payload), BackupChecksum.of(reformatted))
    }

    // ---- sensitive to data --------------------------------------------------

    @Test
    fun `changing a string changes the hash`() {
        assertNotEquals(
            BackupChecksum.of(parse("""{"name": "ADEC"}""")),
            BackupChecksum.of(parse("""{"name": "ADEd"}""")),
        )
    }

    @Test
    fun `changing one digit changes the hash`() {
        assertNotEquals(
            BackupChecksum.of(parse("""{"basisPoints": 7500}""")),
            BackupChecksum.of(parse("""{"basisPoints": 7501}""")),
        )
    }

    @Test
    fun `dropping an entry from an array changes the hash`() {
        assertNotEquals(
            BackupChecksum.of(parse("""{"sessions": [1, 2, 3]}""")),
            BackupChecksum.of(parse("""{"sessions": [1, 2]}""")),
        )
    }

    @Test
    fun `array order changes the hash, because order is data`() {
        // Unlike key order: which hours were attended is a list, and reordering it would be a
        // different record rather than the same one written differently.
        assertNotEquals(
            BackupChecksum.of(parse("""{"attendedUnits": [0, 1]}""")),
            BackupChecksum.of(parse("""{"attendedUnits": [1, 0]}""")),
        )
    }

    @Test
    fun `a missing field changes the hash`() {
        assertNotEquals(
            BackupChecksum.of(parse("""{"a": 1, "b": 2}""")),
            BackupChecksum.of(parse("""{"a": 1}""")),
        )
    }

    @Test
    fun `a number and the same digits as a string hash differently`() {
        assertNotEquals(
            BackupChecksum.of(parse("""{"n": 7500}""")),
            BackupChecksum.of(parse("""{"n": "7500"}""")),
        )
    }

    @Test
    fun `null and a missing field hash differently`() {
        assertNotEquals(
            BackupChecksum.of(parse("""{"room": null}""")),
            BackupChecksum.of(parse("""{}""")),
        )
    }

    // ---- escaping -----------------------------------------------------------

    @Test
    fun `quotes newlines and separators inside a string cannot forge a match`() {
        // A note is free text, so it can contain the very characters the canonical form uses as
        // structure. If escaping were skipped, two different notes could canonicalise alike.
        val one = JsonObject(mapOf("note" to JsonPrimitive("""a","b":1"""), "x" to JsonPrimitive(1)))
        val other = JsonObject(mapOf("note" to JsonPrimitive("a"), "b" to JsonPrimitive(1)))

        assertNotEquals(BackupChecksum.of(one), BackupChecksum.of(other))
    }

    @Test
    fun `a note with a line break hashes the same after a round trip through json`() {
        val note = "Sir said:\n\"cover it later\"\tmaybe"
        val original = JsonObject(mapOf("note" to JsonPrimitive(note)))
        val reparsed = parse(original.toString())

        assertEquals(BackupChecksum.of(original), BackupChecksum.of(reparsed))
    }

    @Test
    fun `unicode survives canonicalisation`() {
        val text = "पर्यावरण अध्ययन — 9–10 AM 🎓"
        val original = JsonObject(mapOf("name" to JsonPrimitive(text)))
        val reparsed = parse(original.toString())

        assertEquals(BackupChecksum.of(original), BackupChecksum.of(reparsed))
    }

    // ---- the hash itself ----------------------------------------------------

    @Test
    fun `a hash is sixty-four lowercase hex characters`() {
        val hash = BackupChecksum.of(parse("""{"a": 1}"""))

        assertEquals(64, hash.length)
        assertTrue(hash, hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `an empty object still hashes`() {
        assertEquals(64, BackupChecksum.of(JsonObject(emptyMap())).length)
        assertEquals(64, BackupChecksum.of(JsonArray(emptyList())).length)
    }

    @Test
    fun `comparison ignores the case the hash was written in`() {
        val hash = BackupChecksum.of(parse("""{"a": 1}"""))

        assertTrue(BackupChecksum.matches(hash.uppercase(), hash))
        assertTrue(BackupChecksum.matches(hash, hash.uppercase()))
        assertFalse(BackupChecksum.matches(hash, BackupChecksum.of(parse("""{"a": 2}"""))))
    }

    @Test
    fun `only SHA-256 is offered`() {
        assertEquals("SHA-256", BackupChecksum.ALGORITHM)
        assertTrue(BackupChecksum.isSupported("SHA-256"))
        assertTrue(BackupChecksum.isSupported("sha-256"))
        assertFalse(BackupChecksum.isSupported("MD5"))
        assertFalse(BackupChecksum.isSupported("SHA-1"))
        assertFalse(BackupChecksum.isSupported(""))
    }

    // ---- helpers ------------------------------------------------------------

    private fun parse(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    private fun payloadOf(backup: String): JsonObject =
        (Json.parseToJsonElement(backup) as JsonObject).getValue("payload") as JsonObject
}
