package com.attendo.data.community

import com.attendo.BuildConfig

/**
 * Whether this build can talk to Supabase at all.
 *
 * The URL and publishable key are read from local.properties at configuration time and
 * land in BuildConfig — never in source, never committed. A checkout without them
 * (a contributor machine, a CI runner) builds fine and gets `null` here, which makes
 * [CommunityClient] report itself unavailable and every community surface degrade to
 * "not available on this install". Attendance never notices.
 *
 * Only the *publishable* key ever exists here. The service-role/secret key is a server
 * credential; it is neither read nor stored anywhere in this app.
 */
object CommunitySupabaseConfig {
    val available: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
}
