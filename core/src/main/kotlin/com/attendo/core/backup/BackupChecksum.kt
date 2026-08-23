package com.attendo.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * The backup format's integrity check: SHA-256 over a canonical rendering of the payload.
 *
 * ### What this catches, and what it does not
 *
 * A backup travels through whatever the student has to hand — a chat app, a USB cable, a
 * cloud drive, an SD card that has been in a pocket for a month. Every one of those can hand
 * back a file that is *shorter* than it went in, or has had a block go bad, and JSON is
 * quite capable of parsing cleanly after losing a chunk from the middle of an array. A
 * checksum turns "restored 40 of your 220 classes and said it worked" into a refusal.
 *
 * It is a corruption check, not a signature: anyone able to edit the file can recompute the
 * hash. That is the right scope — the threat here is a damaged file, not a forged one, and
 * the student is the only party involved.
 *
 * ### Why canonical, rather than hashing the bytes
 *
 * Hashing the file's literal text would make the checksum a hash of the *formatting*: the
 * same data pretty-printed instead of compact, or written by a serialiser that orders keys
 * differently, would fail verification while meaning exactly the same thing. So the payload
 * is re-rendered to a canonical form first — object keys sorted, no whitespace — and that is
 * what gets hashed. The result depends on the data and nothing else, which is what makes it
 * safe to reformat a backup file by hand and still have it import.
 *
 * Numbers are hashed as they were written rather than reparsed. Canonicalising `7500` and
 * `7.5e3` to one form would mean deciding what a number *is*, and this format only ever
 * writes integers; leaving the literal alone keeps the hash honest about what it verified.
 */
internal object BackupChecksum {

    const val ALGORITHM: String = "SHA-256"

    fun isSupported(algorithm: String): Boolean = algorithm.equals(ALGORITHM, ignoreCase = true)

    fun of(payload: JsonElement): String {
        val canonical = canonicalise(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun matches(expected: String, actual: String): Boolean = expected.equals(actual, ignoreCase = true)

    /**
     * Renders [element] with object keys in lexicographic order and no insignificant
     * whitespace. Walked explicitly rather than via `JsonElement.toString()`, which
     * preserves whatever key order the document happened to arrive in.
     */
    private fun canonicalise(element: JsonElement): String =
        StringBuilder().also { write(it, element) }.toString()

    private fun write(out: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries
                    .sortedBy { it.key }
                    .forEachIndexed { index, (key, value) ->
                        if (index > 0) out.append(',')
                        out.append(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key)))
                        out.append(':')
                        write(out, value)
                    }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(',')
                    write(out, value)
                }
                out.append(']')
            }

            JsonNull -> out.append("null")

            is JsonPrimitive ->
                // Strings go through the serialiser so escaping is the library's problem;
                // numbers and booleans keep the literal the file was written with.
                if (element.isString) {
                    out.append(Json.encodeToString(JsonPrimitive.serializer(), element))
                } else {
                    out.append(element.content)
                }
        }
    }
}
