package com.attendo.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Shared plumbing for the backup tests.
 *
 * The tampering helpers exist because most of what a backup has to defend against is a file
 * that is *nearly* right — one field missing, one reference pointing nowhere, one number out of
 * range. Producing those by hand-writing JSON would test the fixture more than the codec, so
 * instead a real export is taken apart, one thing is changed, and it is put back together.
 *
 * [repack] re-stamps the checksum after editing; [tamper] deliberately does not. Which one a
 * test uses is the difference between "this file says something invalid" and "this file has
 * been damaged", and the codec is supposed to tell those apart.
 */

internal const val TEST_APP_VERSION_NAME: String = "1.4"

internal const val TEST_APP_VERSION_CODE: Long = 14L

internal val TEST_EXPORTED_AT: Instant = Instant.parse("2026-12-20T07:45:00Z")

internal fun encoded(
    snapshot: BackupSnapshot = BackupFixtures.snapshot(),
    pretty: Boolean = true,
): String = BackupCodec.encode(
    snapshot = snapshot,
    appVersionName = TEST_APP_VERSION_NAME,
    appVersionCode = TEST_APP_VERSION_CODE,
    exportedAt = TEST_EXPORTED_AT,
    pretty = pretty,
)

/** Decodes, insisting on success and reporting every problem if it fails. */
internal fun readOk(text: String): Backup = when (val result = BackupCodec.decode(text)) {
    is BackupReadResult.Ok -> result.backup
    is BackupReadResult.Failed -> throw AssertionError(
        "expected a readable backup, but it was refused: " +
            result.problems.joinToString("; ") { it.message },
    )
}

/** Decodes, insisting on refusal. */
internal fun refusalsOf(text: String): List<BackupProblem> =
    when (val result = BackupCodec.decode(text)) {
        is BackupReadResult.Ok -> throw AssertionError(
            "expected this backup to be refused, but it was accepted: ${result.backup.summary}",
        )

        is BackupReadResult.Failed -> result.problems
    }

/** Decodes, insisting on refusal for a particular reason. */
internal inline fun <reified T : BackupProblem> refusal(text: String): T {
    val found = refusalsOf(text)
    return found.filterIsInstance<T>().firstOrNull() ?: throw AssertionError(
        "expected a ${T::class.simpleName}, got ${found.map { it::class.simpleName }}: " +
            found.joinToString("; ") { it.message },
    )
}

// ---- tampering ----------------------------------------------------------------

/** Rewrites a backup's payload and re-stamps the checksum, so the *contents* are under test. */
internal fun repack(text: String, edit: (JsonObject) -> JsonObject): String {
    val root = Json.parseToJsonElement(text).jsonObject
    val payload = edit(root.getValue("payload").jsonObject)
    return JsonObject(
        root + mapOf(
            "payload" to payload,
            "checksum" to JsonObject(
                mapOf(
                    "algorithm" to JsonPrimitive(BackupChecksum.ALGORITHM),
                    "value" to JsonPrimitive(BackupChecksum.of(payload)),
                ),
            ),
        ),
    ).toString()
}

/** Rewrites the envelope and leaves the checksum alone — this is damage, not invalid data. */
internal fun tamper(text: String, edit: (JsonObject) -> JsonObject): String =
    edit(Json.parseToJsonElement(text).jsonObject).toString()

/** Rewrites the payload and leaves the checksum stale, which is what corruption looks like. */
internal fun tamperPayload(text: String, edit: (JsonObject) -> JsonObject): String {
    val root = Json.parseToJsonElement(text).jsonObject
    return root.withField("payload", edit(root.getValue("payload").jsonObject)).toString()
}

internal fun JsonObject.withField(key: String, value: JsonElement): JsonObject =
    JsonObject(this + (key to value))

internal fun JsonObject.withField(key: String, value: String): JsonObject =
    withField(key, JsonPrimitive(value))

internal fun JsonObject.withField(key: String, value: Int): JsonObject =
    withField(key, JsonPrimitive(value))

internal fun JsonObject.withoutField(key: String): JsonObject = JsonObject(this - key)

internal fun JsonObject.withList(key: String, edit: (List<JsonObject>) -> List<JsonObject>): JsonObject =
    withField(key, JsonArray(edit(getValue(key).jsonArray.map { it.jsonObject })))

/** Rewrites the one record in [key] whose `ref` matches, leaving the rest of the file alone. */
internal fun JsonObject.withRecord(
    key: String,
    ref: String,
    edit: (JsonObject) -> JsonObject,
): JsonObject = withList(key) { records ->
    var found = false
    val rewritten = records.map { record ->
        if (record.getValue("ref").jsonPrimitive.content == ref) {
            found = true
            edit(record)
        } else {
            record
        }
    }
    if (!found) throw AssertionError("no $key with ref \"$ref\" in the fixture")
    rewritten
}

internal fun JsonObject.recordCount(key: String): Int = getValue(key).jsonArray.size
