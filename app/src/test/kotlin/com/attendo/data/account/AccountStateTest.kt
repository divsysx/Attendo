package com.attendo.data.account

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.Identity
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fold from supabase-kt's session vocabulary to the app's account vocabulary.
 *
 * The whole Phase 2 account model rests on one reading: an authenticated user with no
 * OAuth identities is the anonymous reporter, and one with identities is that *same*
 * reporter with a GitHub login attached. If this fold ever got that backwards — read a
 * linked session as a new user, or invent a second uid — the app would be one screen
 * away from orphaning every report this install has filed. So the fold is pinned here,
 * as a pure function, with the shapes supabase-kt actually delivers.
 */
class AccountStateTest {

    @Test
    fun `an anonymous session reads as Anonymous`() {
        // What signInAnonymously produces: a user with no OAuth identities.
        val status = SessionStatus.Authenticated(userSession(ANON_UID, emptyList()))

        assertEquals(AccountState.Anonymous(ANON_UID), accountStateOf(status))
    }

    @Test
    fun `a session whose identities never arrived is still anonymous`() {
        // identities is nullable in the wire format; null means "none", the same
        // reading an empty list gets — never a guess at a link that might exist.
        val status = SessionStatus.Authenticated(userSession(ANON_UID, null))

        assertEquals(AccountState.Anonymous(ANON_UID), accountStateOf(status))
    }

    @Test
    fun `a linked session reads as Linked and keeps the same uid`() {
        // The Phase 2 contract in miniature: linking attaches an identity to the
        // *existing* user, so the uid the linked session carries is the uid the
        // anonymous one had. The fold must pass it through untouched — a second uid
        // here would be the moment the reporter got duplicated.
        val status = SessionStatus.Authenticated(userSession(ANON_UID, listOf(githubIdentity(ANON_UID))))

        assertEquals(AccountState.Linked(ANON_UID), accountStateOf(status))
    }

    @Test
    fun `no session at all reads as NoSession`() {
        assertEquals(AccountState.NoSession, accountStateOf(SessionStatus.NotAuthenticated(isSignOut = false)))
    }

    @Test
    fun `a sign-out reads as NoSession too`() {
        // Clear All ends the session; the account section must not keep showing an
        // account for a session that no longer exists.
        assertEquals(AccountState.NoSession, accountStateOf(SessionStatus.NotAuthenticated(isSignOut = true)))
    }

    @Test
    fun `a session still being loaded reads as NoSession`() {
        // Before the first loadFromStorage, nothing is known. The honest reading is
        // "no session", not a guess in either direction.
        assertEquals(AccountState.NoSession, accountStateOf(SessionStatus.Initializing))
    }

    @Test
    fun `a session that failed to refresh reads as NoSession`() {
        val status = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(RuntimeException("offline")))

        assertEquals(AccountState.NoSession, accountStateOf(status))
    }

    @Test
    fun `an authenticated session with no user object reads as NoSession`() {
        // The token arrived but the user behind it never did. There is no uid to show
        // and nothing to link to, so this is no session rather than an empty account.
        assertEquals(AccountState.NoSession, accountStateOf(SessionStatus.Authenticated(userSession(user = null))))
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        /** The one uid this install has, before and after linking. */
        const val ANON_UID = "6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1"

        /** The session supabase-kt holds for an authenticated user. */
        fun userSession(uid: String, identities: List<Identity>?): UserSession =
            userSession(user(uid, identities))

        fun userSession(user: UserInfo?): UserSession = UserSession(
            accessToken = "token",
            refreshToken = "refresh",
            expiresIn = 3_600,
            tokenType = "bearer",
            user = user,
        )

        private fun user(uid: String, identities: List<Identity>?) = UserInfo(
            aud = "authenticated",
            id = uid,
            identities = identities,
        )

        /** What linkIdentity(GitHub) leaves behind: an identity row on the same user. */
        private fun githubIdentity(uid: String) = Identity(
            id = "1",
            identityData = JsonObject(emptyMap()),
            provider = "github",
            userId = uid,
        )
    }
}
