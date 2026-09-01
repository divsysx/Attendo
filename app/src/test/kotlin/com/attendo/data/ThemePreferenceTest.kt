package com.attendo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a theme preference becomes a dark-or-not decision.
 *
 * The whole appearance feature hangs off one pure function — [ThemePreference.isDarkNow] —
 * so the one behaviour that matters (System follows the phone; the other two override it)
 * is tested here rather than on a device. Everything downstream is Compose reading this
 * answer, and a wrong answer here is a wrong theme everywhere.
 */
class ThemePreferenceTest {

    @Test
    fun `system default follows whatever the phone is doing`() {
        assertTrue(ThemePreference.SYSTEM.isDarkNow(systemInDark = true))
        assertFalse(ThemePreference.SYSTEM.isDarkNow(systemInDark = false))
    }

    @Test
    fun `light overrides the phone even when the phone is dark`() {
        assertFalse(ThemePreference.LIGHT.isDarkNow(systemInDark = true))
        assertFalse(ThemePreference.LIGHT.isDarkNow(systemInDark = false))
    }

    @Test
    fun `dark overrides the phone even when the phone is light`() {
        assertTrue(ThemePreference.DARK.isDarkNow(systemInDark = true))
        assertTrue(ThemePreference.DARK.isDarkNow(systemInDark = false))
    }

    @Test
    fun `a fresh install is System default with dynamic colours on`() {
        val appearance = Appearance()

        assertEquals(ThemePreference.SYSTEM, appearance.theme)
        // On by default: a phone that offers wallpaper colours should show them without
        // being asked, and one that does not falls back to Attendo's palette anyway.
        assertTrue(appearance.dynamicColors)
    }

    @Test
    fun `the reset wipes neither the appearance file nor the Android backup file`() {
        // The contract that keeps "Clear all Attendo data" from clearing the phone's own
        // preferences along with the student's data: the appearance file and the Automatic
        // backup file are deliberately absent from what the reset touches. See
        // AppResetContractTest for the rest of that contract.
        val preserved = listOf(AppearanceStore.FILE_NAME, AndroidBackupStore.FILE_NAME)

        for (file in preserved) {
            assertFalse(AppReset.clearedPreferenceFiles.contains(file))
            assertFalse(AppReset.clearedDirectories.contains(file))
        }
    }
}
