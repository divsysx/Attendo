package com.attendo.data.community

import android.content.Context
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The community reporter's anonymous identity, and the only class that creates or ends it.
 *
 * The identity is a Supabase anonymous-auth UUID: not PII, revocable server-side, and
 * keyed to nothing the student typed. On the first community contact — a view of
 * community data (the read path mints so a fresh install can read without
 * contributing) or a contribution — the app signs in anonymously; from then on the
 * session lives in [FILE_NAME] (see [CommunityClient] for the never-in-a-backup
 * invariant) and supabase-kt refreshes it. Nothing outside a community surface can
 * mint one: attendance, courses, backup, and settings-without-the-community-section
 * usage never reaches this class.
 *
 * A hard session failure does not panic: the next write re-signs-in anonymously, which
 * mints a new UUID. The old identity's trust is lost — the approved v1 tradeoff,
 * documented in the plan; reputation is cheap to rebuild and identity is cheap to lose.
 * Reading works either way, because the read path only needs *an* authenticated session.
 */
class CommunityIdentityStore(private val context: Context) {

    /**
     * Ensures an anonymous session exists, creating one when needed.
     *
     * Returns the user id, or null when Supabase could not be reached or this build has
     * no endpoint configured — both meaning "community writes unavailable for now",
     * never an exception into the UI.
     */
    suspend fun ensureSession(client: CommunityClient): String? = withContext(Dispatchers.IO) {
        val auth = client.client.auth
        runCatching {
            // Loads the stored session (refreshing it if expired); null when there is
            // none to load or it cannot be revived.
            val restored = runCatching { auth.loadFromStorage() }.getOrDefault(false)
            if (!restored) auth.signInAnonymously()
            auth.currentUserOrNull()?.id
        }.getOrNull()
    }

    /**
     * Ends the identity: signs the server-side session out and wipes the local file.
     * Called by Clear All — and only by Clear All, which restarts the process right
     * after, so nothing holds a stale session in memory.
     */
    suspend fun signOutAndWipe(client: CommunityClient) = withContext(Dispatchers.IO) {
        runCatching { client.client.auth.signOut() }
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    companion object {
        /**
         * The dedicated preferences file holding only the anonymous session. Never in
         * any backup include-list (statically guarded by
         * [CommunityIdentityBackupGuardTest]); wiped by AppReset.
         */
        const val FILE_NAME: String = "attendo-community"
    }
}
