package com.attendo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of "Clear all Attendo data" and of the note about Android restoring old data
 * — pinned, like the backup wording, because each sentence is a claim about something
 * destructive or something Attendo does not control.
 *
 * The confirmation must say what is lost and that it cannot be undone. Neither it nor the
 * reinstall hint may claim that clearing deletes Android's copy of anything, or that
 * Android definitely restores; and the hint must draw the one line a confused student
 * needs — a normal app update keeps the data, a restore is the thing that can bring old
 * data back.
 */
class ClearAllWordingTest {

    @Test
    fun `the confirmation says what is lost and that it cannot be undone`() {
        assertEquals("Clear all Attendo data?", CLEAR_ALL_CONFIRM_TITLE)

        val body = CLEAR_ALL_CONFIRM_BODY
        assertTrue(body.contains("permanently removes all Attendo data"))
        assertTrue(body.contains("including your attendance and settings"))
        assertTrue(body.contains("cannot be undone"))
    }

    @Test
    fun `the confirmation says the Automatic backup setting is left alone`() {
        assertTrue(
            "Clearing data must not look like it flips the Automatic backup toggle, and " +
                "must say so where the student is about to press the button.",
            CLEAR_ALL_CONFIRM_BODY.contains("Automatic backup setting is not changed"),
        )
    }

    @Test
    fun `nothing claims that clearing deletes Android's copy`() {
        for (text in listOf(CLEAR_ALL_CONFIRM_BODY, CLEAR_ALL_NOTE, ANDROID_RESTORE_HINT)) {
            // Any sentence that puts "delete" and "backup" together must be denying the
            // deletion ("does not delete any backup", "Neither deletes the backup"),
            // never asserting it — clearing local data cannot touch what Android holds.
            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            for (sentence in sentences) {
                if (sentence.contains("delet", ignoreCase = true) &&
                    sentence.contains("backup", ignoreCase = true)
                ) {
                    val denied = listOf("not ", "Neither", "cannot").any { sentence.contains(it) }
                    assertTrue(
                        "Clearing local data cannot delete a backup Android or Google " +
                            "already holds, and a sentence combining \"delete\" and " +
                            "\"backup\" must deny it, not assert it: \"$sentence\"",
                        denied,
                    )
                }
            }
        }
    }

    @Test
    fun `the hint explains old data after a reinstall without promising a restore`() {
        val hint = ANDROID_RESTORE_HINT

        assertTrue("The hint must say Android *may* restore.", hint.contains("may restore"))
        assertFalse(
            "Android's restore is conditional; \"will restore\" would promise it.",
            hint.contains("will restore"),
        )
        assertTrue(
            "The hint must point at Clear all Attendo data as the way to start fresh.",
            hint.contains("Clear all Attendo data"),
        )
        assertTrue(
            "The hint must also point at Android's own storage setting, for a student who " +
                "cannot open the app.",
            hint.contains("App info → Storage → Clear storage"),
        )
        assertTrue(
            "The hint must say Attendo does not control Android's backup or restore.",
            hint.contains("does not control"),
        )
    }

    @Test
    fun `the hint distinguishes an update from a restore`() {
        assertTrue(
            "A normal app update keeps the data as it is; a restore is the only thing " +
                "that can bring old data back. The hint must draw that line.",
            ANDROID_RESTORE_HINT.contains("A normal app update"),
        )
    }

    @Test
    fun `the button names exactly what it does`() {
        assertEquals("Clear all Attendo data", CLEAR_ALL_BUTTON)
    }
}
