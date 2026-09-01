package com.attendo.ui.settings

import com.attendo.data.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words on the three theme chips.
 *
 * "System default" rather than "Auto" because the setting is a preference with a
 * consequence — it hands the decision to the phone — and the label should say whose
 * decision it is.
 */
class ThemeLabelTest {

    @Test
    fun `the three options are named in plain words`() {
        assertEquals("System default", themeLabel(ThemePreference.SYSTEM))
        assertEquals("Light", themeLabel(ThemePreference.LIGHT))
        assertEquals("Dark", themeLabel(ThemePreference.DARK))
    }
}
