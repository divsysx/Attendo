package com.attendo.ui.settings

import com.attendo.core.backup.BackupCodec
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.Percent
import com.attendo.data.AppSettings
import com.attendo.data.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

    // ---- the three rows that open their own screens --------------------------

    // Settings lists what the app can be asked to do and no longer edits any of it inline, so
    // each of these three rows is a summary and a way in. The summaries are all a count or a
    // percentage, which means the screen can be read at a glance — and means a row that lied
    // about the count would send a student to a screen that disagreed with it.

    @Test
    fun `the holidays row counts what is configured`() {
        val state = SettingsUiState(
            settings = AppSettings(
                calendar = TERM.copy(
                    holidays = setOf(
                        LocalDate.of(2026, 8, 15),
                        LocalDate.of(2026, 10, 2),
                    ),
                ),
            ),
        )

        assertEquals("2 configured", state.holidaysSummary)
    }

    @Test
    fun `a count of none says None rather than zero`() {
        // "0 configured" reads as a fault. There is nothing wrong with a term with no holidays
        // in it yet, and the row should not imply otherwise.
        assertEquals("None", SettingsUiState().holidaysSummary)
        assertEquals("None", SettingsUiState().workingSaturdaysSummary)
    }

    @Test
    fun `a count of one is singular`() {
        val state = SettingsUiState(
            settings = AppSettings(
                calendar = TERM.copy(workingSaturdays = setOf(LocalDate.of(2026, 8, 1))),
            ),
        )

        assertEquals("1 configured", state.workingSaturdaysSummary)
    }

    @Test
    fun `the targets row shows the default for new courses, not the overall threshold`() {
        // The two are separate settings, and this row belongs to the one that decides what a
        // new course starts at. Showing the overall target here would describe a number the
        // screen behind it does not let you change.
        val state = SettingsUiState(
            settings = AppSettings(
                overallTarget = Percent.ofPercent(60.0),
                courseTarget = Percent.ofPercent(85.0),
            ),
        )

        assertEquals("Default: 85%", state.targetsSummary)
    }

    @Test
    fun `the targets row renders a fractional default as itself`() {
        // Nothing may round a stored target to make a summary read more tidily.
        val state = SettingsUiState(
            settings = AppSettings(courseTarget = Percent.ofPercent(72.5)),
        )

        assertEquals("Default: 73%", state.targetsSummary)
        assertEquals(7250, state.settings.courseTarget.basisPoints)
    }

    // ---- the Working Saturdays row -------------------------------------------

    @Test
    fun `a section taught on Saturday every week is not offered the setting`() {
        val state = SettingsUiState(
            settings = AppSettings(calendar = TERM, section = "1st Yr CSE-B"),
        )

        assertTrue(state.saturdayIsRecurring)
    }

    @Test
    fun `every other section keeps the setting`() {
        val sections = listOf("3rd Yr ECE-A", "2nd Yr EE-B", "4th Yr CSE-A", null)

        sections.forEach { section ->
            val state = SettingsUiState(settings = AppSettings(calendar = TERM, section = section))
            assertFalse("section=$section", state.saturdayIsRecurring)
        }
    }

    @Test
    fun `the row's summary reflects what is stored, whatever the section`() {
        // The state does not adjust the count for a recurring section; the screen hides the
        // whole row instead. Deriving it here as well would be a second place to keep in step.
        val state = SettingsUiState(
            settings = AppSettings(
                calendar = TERM.copy(workingSaturdays = setOf(LocalDate.of(2026, 8, 1))),
                section = "1st Yr CSE-A",
            ),
        )

        assertEquals("1 configured", state.workingSaturdaysSummary)
    }

    private companion object {
        /** A term whose ends fall on ordinary weekdays, so the Saturdays are unambiguous. */
        val TERM = AcademicCalendar(
            termStart = LocalDate.of(2026, 7, 28),
            termEnd = LocalDate.of(2026, 11, 20),
        )
    }
}
