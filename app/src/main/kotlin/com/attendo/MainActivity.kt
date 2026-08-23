package com.attendo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            AttendoTheme {
                AttendoApp()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The only activity, so this is the app leaving the foreground — and the moment a
        // backup or device transfer is most likely to read the database file. See
        // AppContainer.checkpoint.
        (application as AttendoApplication).container.checkpoint()
    }
}
