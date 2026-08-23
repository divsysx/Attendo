package com.attendo.ui.settings

import com.attendo.core.backup.BackupCodec
import com.attendo.data.AppSettings
import com.attendo.data.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The three labels the Settings screen derives, all of which are about naming.
 *
 * The About block used to read "Attendo 1.0", which reads as the product's name — the version
 * of a thing is not part of what it is called, and once it is in the name every mention of the
 * app anywhere in the app has a release number stuck to it. So the screen renders the product
 * name and [SettingsUiState.versionLabel] as two lines, and this test holds the version label
 * to being only the version.
 *
 * The name row is here for the same reason in reverse: an unset name has to say so in words
 * rather than leave the row looking broken. [SettingsUiState.nameFieldValue] is the opposite
 * case again — an unset name has to say *nothing*, because it is about to be typed into.
 */
class SettingsUiStateTest {

    // ---- the About block ----------------------------------------------------

    @Test
    fun `the version label is the version, and nothing about the product`() {
        val state = SettingsUiState(version = AppVersion(name = "1.0", code = 1L))

        assertEquals("Version 1.0", state.versionLabel)
        assertFalse(state.versionLabel.contains(BackupCodec.APP_NAME))
    }

    @Test
    fun `the product name is never joined to its version number`() {
        // The exact string this whole change existed to remove.
        val state = SettingsUiState(version = AppVersion(name = "1.0", code = 1L))

        assertFalse(state.versionLabel.contains("Attendo 1.0"))
        assertEquals("Attendo", BackupCodec.APP_NAME)
    }

    @Test
    fun `a build whose version cannot be read still says something`() {
        // PackageManager can fail; "Version" followed by nothing would look like a bug.
        assertEquals("Version unknown", SettingsUiState().versionLabel)
        assertEquals("Version unknown", SettingsUiState(version = AppVersion("  ", 0L)).versionLabel)
    }

    // ---- the name row -------------------------------------------------------

    @Test
    fun `the name row shows the name once there is one`() {
        val state = SettingsUiState(settings = AppSettings(displayName = "Divyansh"))

        assertEquals("Divyansh", state.displayNameLabel)
    }

    @Test
    fun `the name row says so when there is not`() {
        assertEquals("Not set", SettingsUiState().displayNameLabel)
        assertEquals(
            "Not set",
            SettingsUiState(settings = AppSettings(displayName = "   ")).displayNameLabel,
        )
    }

    // ---- the name field ------------------------------------------------------

    // A shipped build once put a greyed-out example name in this field. A placeholder is not a
    // value, but on screen it is indistinguishable from one: the field looked pre-filled with
    // somebody else's name, and the only way to find out otherwise was to tap Save and see what
    // the greeting said. These four tests pin the field to showing the saved name and nothing
    // else, in every state the store can be in.

    @Test
    fun `an install with no name opens the field empty`() {
        assertEquals("", SettingsUiState().nameFieldValue)
        assertEquals("", SettingsUiState(settings = AppSettings(displayName = null)).nameFieldValue)
    }

    @Test
    fun `a saved name opens the field on that name`() {
        val state = SettingsUiState(settings = AppSettings(displayName = "Divyansh"))

        assertEquals("Divyansh", state.nameFieldValue)
    }

    @Test
    fun `clearing the name opens the field empty again`() {
        // What SettingsStore.setDisplayName("") leaves behind: the key removed, so the next read
        // returns null. The dialog must not resurrect the name it just cleared.
        val named = SettingsUiState(settings = AppSettings(displayName = "Divyansh"))
        val cleared = named.copy(settings = named.settings.copy(displayName = null))

        assertEquals("Divyansh", named.nameFieldValue)
        assertEquals("", cleared.nameFieldValue)
    }

    @Test
    fun `the field never falls back to a name nobody typed`() {
        // The empty cases must be empty, not an example, a hint, or a previous owner's name.
        val empty = listOf(null, "", " ", "   ", "\t", "\n")

        empty.forEach { stored ->
            val state = SettingsUiState(settings = AppSettings(displayName = stored))
            assertEquals("stored=${stored?.let { "\"$it\"" }}", "", state.nameFieldValue)
        }
    }
}
