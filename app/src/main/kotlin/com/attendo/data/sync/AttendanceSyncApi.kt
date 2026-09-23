package com.attendo.data.sync

import android.util.Log
import com.attendo.core.sync.SyncTable
import com.attendo.data.community.CommunityClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * The `attendance` schema over PostgREST: upsert rows in, read revisions out.
 *
 * There is deliberately no server-side push/pull RPC. Migration 0020 defines tables,
 * triggers and row-level security, and the intended architecture is that clients read
 * and write those tables directly under RLS — so this class is a thin translation of
 * that intent and decides nothing. Everything it can do, the policy's `authenticated`
 * grants already allow, and nothing it can do reaches another account's rows, because
 * every policy is `user_id = auth.uid()`. It never issues a DELETE: deletion in this
 * schema is a tombstone, which is an UPDATE, and no role is granted the alternative.
 *
 * ### Why `attendance` and not the default schema
 *
 * [CommunityClient] installs Postgrest with `defaultSchema = "community"`, and that stays
 * as it is — the community feature is written against it and has no business changing.
 * Every call here therefore names the schema explicitly through `from(schema, table)`,
 * which is the per-request half of the same setting. One Supabase client, one session,
 * two schemas.
 *
 * ### Transport failures versus refusals
 *
 * The distinction the caller needs is "try again" versus "do not": a `RestException` is
 * the server answering, and everything else — no route, no network, a timeout, a dead
 * socket — is [PushResult.Unreachable]. A refusal is not retried by this client because
 * the server will say the same thing again; it is surfaced instead, and the rows keep
 * their `dirty` flag, so nothing is lost either way.
 */
class AttendanceSyncApi(private val client: CommunityClient) {

    private val postgrest get() = client.client.postgrest

    /** The outcome of writing rows. */
    sealed interface PushResult {
        /** The server accepted the batch — which may include rows its triggers declined to change. */
        data object Ok : PushResult

        /** The server answered "no". [code] is its reason, for the record; the rows stay dirty. */
        data class Refused(val code: String) : PushResult

        /** No answer. Retryable, and never a reason to change anything locally. */
        data object Unreachable : PushResult
    }

    /** The outcome of reading a page of revisions. */
    sealed interface PullResult<out T> {
        /** [rows] ascend by revision. [full] means the page hit its limit and more may follow. */
        data class Ok<T>(val rows: List<T>, val full: Boolean) : PullResult<T>

        data object Unreachable : PullResult<Nothing>
    }

    /**
     * Writes rows to `attendance.<table>`.
     *
     * An upsert, not an insert: a row this device has already pushed and since edited must
     * update the cloud row rather than collide with it. The conflict target is the primary
     * key, which for four of the five tables is the client-assigned `id` and for
     * `accounts` is `user_id` — in both cases exactly the column that identifies the row.
     *
     * The server's `lww_touch` trigger decides whether each row in the batch actually
     * changes anything; a stale row in the batch is a no-op there, which is what makes
     * re-pushing after a failed response free.
     */
    suspend fun push(table: SyncTable, rows: List<JsonObject>): PushResult =
        push(table.wireName, rows)

    /** The accounts row is a singleton per account and has no [SyncTable] of its own. */
    suspend fun pushAccounts(row: JsonObject): PushResult = push(ACCOUNTS, listOf(row))

    private suspend fun push(table: String, rows: List<JsonObject>): PushResult {
        if (rows.isEmpty()) return PushResult.Ok
        Log.d(TAG, "operation: push, table: $table, schema: $SCHEMA, request started (rowCount: ${rows.size}), ${authDiagnostic()}")
        return runCatching {
            postgrest.from(SCHEMA, table).upsert(rows)
            Log.d(TAG, "operation: push, table: $table, schema: $SCHEMA, HTTP status: 2xx -> PushResult.Ok")
            PushResult.Ok
        }.recoverCatching { error ->
            if (error is RestException) {
                val code = refusalCode(error.message)
                Log.e(
                    TAG,
                    "operation: push, table: $table, schema: $SCHEMA, ${failureDiagnostic(error)}, " +
                        "${authDiagnostic()} -> PushResult.Refused(code: $code)",
                    error
                )
                PushResult.Refused(code)
            } else {
                throw error
            }
        }.getOrElse { error ->
            Log.e(
                TAG,
                "operation: push, table: $table, schema: $SCHEMA, ${failureDiagnostic(error)}, " +
                    "${authDiagnostic()} -> PushResult.Unreachable",
                error
            )
            PushResult.Unreachable
        }
    }

    /**
     * One page of `attendance.<table>` rows with `revision > from`, ascending.
     *
     * Ascending is not cosmetic: the caller applies rows in the order they are returned and
     * advances its cursor to the highest one it applied, so a page that arrived in any other
     * order would let the cursor jump past a row it never saw.
     *
     * The rows are decoded with the app's own [wireJson] rather than through `decodeList`,
     * which is reified-only and would reach for the default `Json` — one whose `explicitNulls`
     * and unknown-key settings are not the ones these DTOs are written against.
     */
    suspend fun <T> pull(
        table: SyncTable,
        from: Long,
        limit: Long,
        deserializer: KSerializer<T>,
    ): PullResult<T> = runCatching {
        Log.d(TAG, "operation: pull, table: ${table.wireName}, schema: $SCHEMA, request started (from: $from, limit: $limit), ${authDiagnostic()}")
        val result = postgrest.from(SCHEMA, table.wireName)
            .select(Columns.list("*")) {
                filter { gt(REVISION, from) }
                order(REVISION, Order.ASCENDING)
                limit(limit)
            }
        val body = result.data
        Log.d(
            TAG,
            "operation: pull, table: ${table.wireName}, schema: $SCHEMA, HTTP status: 2xx (postgrest-kt throws instead of returning on non-2xx), contentRange: ${result.headers["Content-Range"] ?: "none"}, bodyLength: ${body.length}"
        )
        val rows = wireJson.decodeFromString(ListSerializer(deserializer), body)
        Log.d(TAG, "operation: pull, table: ${table.wireName}, schema: $SCHEMA, JSON decode succeeded (rowCount: ${rows.size}) -> PullResult.Ok(full: ${rows.size >= limit})")
        PullResult.Ok(rows = rows, full = rows.size >= limit)
    }.getOrElse { error ->
        Log.e(
            TAG,
            "operation: pull, table: ${table.wireName}, schema: $SCHEMA, ${failureDiagnostic(error)}, ${authDiagnostic()} -> PullResult.Unreachable",
            error
        )
        PullResult.Unreachable
    }

    /**
     * The account's settings row, or null when the account has none yet.
     *
     * A device that has never pushed has no row, and that is a normal first-run state
     * rather than an error — so "no row" and "no answer" are deliberately different
     * results here, and the caller treats only the second as a failure.
     */
    suspend fun pullAccounts(): PullResult<AccountRow> = runCatching {
        Log.d(TAG, "operation: pullAccounts, table: $ACCOUNTS, schema: $SCHEMA, request started, ${authDiagnostic()}")
        val result = postgrest.from(SCHEMA, ACCOUNTS)
            .select(Columns.list("*")) { limit(1) }
        val body = result.data
        Log.d(
            TAG,
            "operation: pullAccounts, table: $ACCOUNTS, schema: $SCHEMA, HTTP status: 2xx (postgrest-kt throws instead of returning on non-2xx), contentRange: ${result.headers["Content-Range"] ?: "none"}, bodyLength: ${body.length}"
        )
        val rows = wireJson.decodeFromString(ListSerializer(AccountRow.serializer()), body)
        Log.d(TAG, "operation: pullAccounts, table: $ACCOUNTS, schema: $SCHEMA, JSON decode succeeded (rowCount: ${rows.size}) -> PullResult.Ok")
        PullResult.Ok(rows = rows, full = false)
    }.getOrElse { error ->
        Log.e(
            TAG,
            "operation: pullAccounts, table: $ACCOUNTS, schema: $SCHEMA, ${failureDiagnostic(error)}, ${authDiagnostic()} -> PullResult.Unreachable",
            error
        )
        PullResult.Unreachable
    }

    /**
     * Whether this request will carry a session, without naming the session.
     *
     * Temporary diagnostic. Reports presence and shape only — never the token, the
     * refresh token, or anything derived from either. `currentAccessTokenOrNull()` is
     * consulted for its null-ness alone and its value is discarded.
     */
    private fun authDiagnostic(): String {
        val auth = client.client.auth
        val tokenPresent = runCatching { auth.currentAccessTokenOrNull() != null }.getOrDefault(false)
        val identities = runCatching { auth.currentIdentitiesOrNull()?.size }.getOrNull()
        val uid = runCatching { auth.currentSessionOrNull()?.user?.id }.getOrNull()
        return "authenticated: $tokenPresent, sync userId: ${uid ?: "none"}, identity count: ${identities ?: "unknown"}"
    }

    /**
     * What kind of failure this is, in the terms the classification actually turns on.
     *
     * Temporary diagnostic. The point is to stop a server refusal, a decode failure and a
     * dead socket from being indistinguishable in the log the way they are in [PullResult].
     */
    private fun failureDiagnostic(error: Throwable): String = when (error) {
        is RestException -> "failure kind: server-answered, HTTP status: ${error.statusCode}, " +
            "postgrestError: ${error.error}, postgrestDesc: ${error.description}, " +
            "exception class: ${error::class.qualifiedName}, exception message: ${error.message}"

        is SerializationException -> "failure kind: response-decode, " +
            "exception class: ${error::class.qualifiedName}, exception message: ${error.message}"

        else -> "failure kind: transport-or-other, HTTP status: none (no HTTP response was received), " +
            "exception class: ${error::class.qualifiedName}, exception message: ${error.message}"
    }

    private companion object {
        private const val TAG = "AttendanceSyncDiag"

        /** The schema migration 0020 creates. Reached per-request; see the class note. */
        const val SCHEMA = "attendance"

        const val ACCOUNTS = "accounts"

        /** The shared delta cursor's column, named once. */
        const val REVISION = "revision"

        /**
         * PostgREST's reason, trimmed to something a log line can hold. The message
         * carries a JSON body; the code is the leading token before it.
         */
        fun refusalCode(message: String?): String =
            message?.trim()?.take(200)?.ifEmpty { null } ?: "refused"
    }
}
