package com.attendo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendo.ui.AttendoApp
import com.attendo.ui.theme.AttendoTheme
import com.attendo.data.account.errorCodeIn

/**
 * The app's only activity. Navigation between the two tabs and their detail screens
 * happens inside Compose — see [AttendoApp].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appearanceStore = (application as AttendoApplication).container.appearance
        setContent {
            // The one place the theme is decided. The store's flow makes a change in
            // Settings recompose this call and with it every screen underneath — there is
            // no second place to get out of sync — and isSystemInDarkTheme() keeps
            // recomposing on its own, so "System default" follows the phone at sunset and
            // back without any code of ours running.
            val appearance by appearanceStore.appearance.collectAsStateWithLifecycle()
            AttendoTheme(
                darkTheme = appearance.theme.isDarkNow(isSystemInDarkTheme()),
                dynamicColor = appearance.dynamicColors,
            ) {
                AttendoApp()
            }
        }
        // The app can also be launched *by* the OAuth callback (the browser following
        // com.attendo://login-callback back), in which case this intent carries the
        // session. Cold start and warm return both land in the same place.
        handleLoginCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLoginCallback(intent)
    }

    /**
     * The OAuth callback, if that is what this intent is.
     *
     * The callback is only ever `com.attendo://login-callback`, and it comes back in one
     * of three shapes. A token in the fragment (`#access_token=…`) is the session
     * itself, and goes to the account manager's own import — the same library calls
     * its deeplink helper would make (parse, fetch the user, import), but in the app's
     * scope and with a failure that becomes a sentence instead of silence. The account
     * session *is* the community session, so the import lands in the store the whole
     * app already reads.
     *
     * `#error=access_denied` is the student saying "no" on GitHub's consent screen.
     * Any other `#error=…` is the sign-in failing out there, and those fragments carry
     * the server's own `error_code` when it has one — read it, because one code
     * (`identity_already_exists`, the GitHub account already being linked to another
     * user) is a state the student can recognise and act on, while every other code
     * gets the same safe refusal. The library cannot say any of this: its callback
     * parser demands a token and throws on anything else, on the main thread, where
     * nothing of ours could catch it.
     *
     * A fragment with neither a token nor an error is nothing we can use, and is
     * ignored rather than parsed. Every other intent — the launcher, back presses,
     * everything else — is not a callback and passes through untouched.
     */
    private fun handleLoginCallback(intent: Intent) {
        val container = (application as AttendoApplication).container
        if (container.communityClient == null) return
        val data = intent.data ?: return
        if (data.scheme != "com.attendo" || data.host != "login-callback") return
        val fragment = data.fragment.orEmpty()
        val account = container.accountManager ?: return
        when {
            "access_token=" in fragment -> account.importCallbackSession(fragment)
            "error=access_denied" in fragment -> account.onAuthCancelled()
            "error=" in fragment -> account.onAuthRefused(errorCodeIn(fragment))
            // Neither a token nor an error: nothing to import, nothing to say.
        }
    }

    override fun onStop() {
        super.onStop()
        // The only activity, so this is the app leaving the foreground — a good moment
        // to fold the write-ahead log into the database file. See AppContainer.checkpoint.
        (application as AttendoApplication).container.checkpoint()
    }

    /**
     * The account-lifecycle check's foreground boundary — and its startup one.
     *
     * `onStart` runs on the first launch and on every return to the foreground, which is
     * exactly the pair of moments the answer can have changed while this app was not
     * looking: an administrator deleting the account, or the session lapsing elsewhere.
     * There is no separate cold-start call because there is no separate cold-start state to
     * cover — the first `onStart` follows the first `onCreate`.
     *
     * Nothing here waits for the answer. The check is fire-and-forget
     * ([AppContainer.revalidateAccount]) and its verdict is applied on the account
     * manager's own state, so a slow or unreachable server delays nothing the student can
     * see and cannot fail this callback.
     */
    override fun onStart() {
        super.onStart()
        (application as AttendoApplication).container.revalidateAccount()
    }
}
