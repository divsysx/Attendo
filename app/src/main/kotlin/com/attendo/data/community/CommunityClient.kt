package com.attendo.data.community

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The community feature's one Supabase client, built once per process.
 *
 * The whole construction point in one place, mirroring how the rest of this app hands
 * every screen exactly one repository:
 *
 *  * **OkHttp engine** — Realtime needs a WebSocket-capable Ktor engine. The update
 *    system's `HttpURLConnection` client is untouched and unrelated.
 *  * **`defaultSchema = "community"`** — the Postgrest plugin then sends
 *    `Accept-Profile: community` on reads and `Content-Profile: community` on RPC
 *    POSTs automatically: the PostgREST cross-schema routing the backend requires
 *    (the default `public` profile 404s for the community tables).
 *  * **`PropertyConversionMethod.PASSTHROUGH`** — RPC parameter names and column names
 *    are already snake_case (`p_idempotency_key`, `class_date`); no silent camelCase
 *    rewriting may touch them.
 *  * **`SettingsSessionManager` over [CommunityIdentityStore.FILE_NAME]** —
 *    supabase-kt's default is the *default* SharedPreferences, which rides in Android
 *    backups and is cleared by nothing in particular. The anonymous session lands in
 *    a dedicated file instead.
 *
 * ### Security invariant: the identity never travels in a backup
 *
 * The anonymous-auth session token stored in `attendo-community.xml` must never be
 * included in Android Auto Backup, device-to-device transfer, or the app's own
 * [com.attendo.data.BackupRepository] export. Restoring a token onto a second device
 * would make one student two reporters — the reporter-per-person model depends on the
 * token existing in exactly one place. The enforcement is layered:
 *
 *  1. The backup rule files are include-lists; `attendo-community.xml` is deliberately
 *     absent from every include (see backup_rules.xml / data_extraction_rules.xml,
 *     where the omission is recorded as a decision, not an oversight).
 *  2. [CommunityIdentityBackupGuardTest] statically parses those rule files and fails
 *     the build's test run if anyone ever adds the file to an include.
 *  3. `BackupSnapshot` (the explicit export format) does not and must not gain a
 *     community section — its format is frozen by the approved plan.
 *  4. Clear All (AppReset) wipes the file, so a reset ends the identity for good.
 *
 * After any restore, the app simply signs in anonymously again on the next community
 * interaction — a new UUID, a fresh reporter. Identity *transfer* is deliberately not
 * implemented; it would need an explicit, secure transfer flow and is a future feature
 * on its own.
 */
class CommunityClient(
    supabaseUrl: String,
    supabasePublishableKey: String,
    context: Context,
) {
    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabasePublishableKey,
    ) {
        // Realtime needs a WebSocket-capable engine; OkHttp.create() is the engine
        // instance supabase-kt's builder takes.
        httpEngine = OkHttp.create()
        install(Auth) {
            sessionManager = SettingsSessionManager(
                SharedPreferencesSettings(
                    context.getSharedPreferences(
                        CommunityIdentityStore.FILE_NAME,
                        Context.MODE_PRIVATE,
                    )
                )
            )
            // The OAuth callback: `com.attendo://login-callback`, the one deep link this
            // app declares. Setting scheme and host makes them the client's default
            // redirect URL, so account linking sends the browser back here; MainActivity
            // hands the returning fragment to the account manager, which imports the
            // session on this same client. This is configuration, not a second session:
            // the session manager above is unchanged and still the only one.
            scheme = "com.attendo"
            host = "login-callback"
        }
        install(Postgrest) {
            defaultSchema = "community"
            propertyConversionMethod = PropertyConversionMethod.NONE
        }
        install(Realtime)
    }
}
