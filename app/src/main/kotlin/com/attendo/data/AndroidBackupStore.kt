package com.attendo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Automatic backup" toggle in Settings → Data & backup: whether Attendo's data may
 * take part in Android's own backup.
 *
 * This is a deliberate, per-student choice, so it is off until someone turns it on —
 * which means an uninstall is a fresh start unless the student said otherwise. It lives
 * in its own preferences file, separate from [SettingsStore]: a restore from an explicit
 * backup file replaces every setting in `attendo-settings.xml`, and a file from before
 * this toggle existed must not switch Android's backup back on (or off) for the phone.
 *
 * [AttendoBackupAgent] reads the same file directly — see [isEnabled] for why.
 */
class AndroidBackupStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    companion object {
        /** Matches the path named in the backup rules XML. */
        const val FILE_NAME: String = "attendo-android-backup"

        const val KEY_ENABLED: String = "enabled"

        /**
         * What the backup agent asks, from whatever context it is handed.
         *
         * Android runs a backup pass in a restricted mode of the app process: the base
         * `Application` class instead of [com.attendo.AttendoApplication], no content
         * providers, nothing else of the app's machinery — so the agent cannot reach the
         * container or this store's flow. It reads the preference the way the backup
         * framework itself reads preferences: straight from the file.
         */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
    }
}
