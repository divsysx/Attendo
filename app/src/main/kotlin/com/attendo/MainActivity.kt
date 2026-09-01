package com.attendo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendo.ui.AttendoApp
import com.attendo.ui.theme.AttendoTheme

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
    }

    override fun onStop() {
        super.onStop()
        // The only activity, so this is the app leaving the foreground — a good moment
        // to fold the write-ahead log into the database file. See AppContainer.checkpoint.
        (application as AttendoApplication).container.checkpoint()
    }
}
