package com.attendo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which of the three themes Attendo should draw in.
 *
 * The resolution of a preference into "dark or not" is [isDarkNow], a pure function, so
 * the one decision that matters — System follows the phone, the other two override it —
 * lives where it can be tested rather than inside a composable.
 */
enum class ThemePreference {
    /** Whatever the phone is doing, including when it changes at sunset and back. */
    SYSTEM,

    /** Attendo's light palette, regardless of the phone's setting. */
    LIGHT,

    /** Attendo's dark palette, regardless of the phone's setting. */
    DARK,

    ;

    /** Whether the app should draw dark right now, given what the system is doing. */
    fun isDarkNow(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }
}

/** What the app should look like, as one value the whole UI follows. */
data class Appearance(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    /**
     * Whether to ask Android for wallpaper-derived colours on devices that offer them
     * (Android 12+). Unsupported devices fall back to Attendo's own palette whatever this
     * says — the switch is a preference, not a promise.
     */
    val dynamicColors: Boolean = true,
)

/**
 * Reads and writes [Appearance].
 *
 * In its own preferences file, like [AndroidBackupStore], because it is a preference about
 * this phone rather than data the student recorded: "Clear all Attendo data" must not
 * reset someone's dark mode along with their semester, and an imported JSON backup must
 * not impose one phone's theme on another. It *is* listed in the Android backup rules, so
 * a student who turns Android's backup on gets their look back on a new phone — a choice
 * they made, travelling with data they chose to back up.
 */
class AppearanceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(read())

    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun setTheme(theme: ThemePreference) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _appearance.value = read()
    }

    fun setDynamicColors(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLORS, enabled).apply()
        _appearance.value = read()
    }

    /** An unrecognised theme name reads as System default: the setting must never crash. */
    private fun read(): Appearance = Appearance(
        theme = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemePreference.entries.firstOrNull { it.name == name } }
            ?: ThemePreference.SYSTEM,
        dynamicColors = prefs.getBoolean(KEY_DYNAMIC_COLORS, true),
    )

    companion object {
        /** Matches the path named in the backup rules XML. */
        const val FILE_NAME: String = "attendo-appearance"

        private const val KEY_THEME = "theme"
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
    }
}
