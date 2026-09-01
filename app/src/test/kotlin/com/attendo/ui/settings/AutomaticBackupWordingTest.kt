package com.attendo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words on the "Automatic backup" toggle, pinned because they are promises about a
 * system Attendo does not control.
 *
 * Android decides whether a backup happens and whether a restore does — it depends on
 * the device, the account, and settings Attendo cannot see — so the screen may say
 * "may" and must never say "will". And turning the toggle off cannot delete a backup
 * Android has already stored, which is the one thing a student switching it off would
 * otherwise assume. See [com.attendo.data.config.AutoBackupConfigTest] for the half of
 * the contract that lives in the manifest and the agent.
 */
class AutomaticBackupWordingTest {

    @Test
    fun `the toggle is called what it is`() {
        assertEquals("Automatic backup", AUTOMATIC_BACKUP_TITLE)
    }

    @Test
    fun `nothing on the screen promises that Android will back anything up`() {
        for (text in listOf(
            AUTOMATIC_BACKUP_OFF_NOTE,
            AUTOMATIC_BACKUP_ON_NOTE,
            AUTOMATIC_BACKUP_WHOSE_NOTE,
            AUTOMATIC_BACKUP_CONFIRM_BODY,
        )) {
            assertFalse(
                "Android's backup is conditional, so the word \"will\" is a promise the " +
                    "screen has no right to make: \"$text\"",
                Regex("\\bwill\\b").containsMatchIn(text),
            )
        }
    }

    @Test
    fun `the on state says may and says who decides`() {
        val text = AUTOMATIC_BACKUP_ON_NOTE

        assertTrue("The on note must hedge with \"may\".", text.contains("may"))
        assertTrue(
            "The on note must say that Android, not Attendo, decides.",
            text.contains("up to Android"),
        )
        assertTrue(
            "The on note must say the phone's own backup settings can veto it.",
            text.contains("backup settings"),
        )
        assertTrue(
            "The on note must admit there is no way to check a copy exists.",
            text.contains("no way to check"),
        )
    }

    @Test
    fun `turning it off is described as stopping new copies, not deleting old ones`() {
        val onNote = AUTOMATIC_BACKUP_ON_NOTE
        val confirmBody = AUTOMATIC_BACKUP_CONFIRM_BODY

        assertTrue(
            "The on note must say the limit of switching off: what Android already stored " +
                "stays stored.",
            onNote.contains("cannot delete one Android has already stored"),
        )
        assertTrue(
            "The confirmation must say the same, before the student switches on.",
            confirmBody.contains("cannot delete a copy Android has already made"),
        )
    }

    @Test
    fun `the off state says what a reinstall means and where the reliable copy is`() {
        val text = AUTOMATIC_BACKUP_OFF_NOTE

        assertTrue("The off note must say a reinstall starts fresh.", text.contains("starts fresh"))
        assertTrue(
            "The off note must point at the export, which is the copy that can be relied on.",
            text.contains("export"),
        )
    }

    @Test
    fun `the screen never implies Attendo uploads anything`() {
        val text = AUTOMATIC_BACKUP_WHOSE_NOTE

        assertTrue(
            "The note must say whose backup this is.",
            text.contains("Android's own backup"),
        )
        assertTrue(
            "The note must deny that Attendo uploads anything, since it does not.",
            text.contains("not something Attendo uploads"),
        )
        assertTrue(
            "The note must point at the export as the copy the student controls.",
            text.contains("copy you control"),
        )
    }

    @Test
    fun `the confirmation states the consequence in plain words`() {
        val text = AUTOMATIC_BACKUP_CONFIRM_BODY

        assertTrue(
            "The confirmation must say what turning on is for, in the terms a student " +
                "thinks in: reinstall, new device.",
            text.contains("reinstall") && text.contains("new device"),
        )
        assertTrue(
            "The confirmation must say Attendo cannot verify a backup happened.",
            text.contains("cannot check"),
        )
        assertTrue(
            "The confirmation must recommend the export as the dependable path.",
            text.contains("Export backup"),
        )
    }
}
