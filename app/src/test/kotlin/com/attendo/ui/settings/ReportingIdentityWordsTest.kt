package com.attendo.ui.settings

import com.attendo.ui.community.pollFailureLine
import com.attendo.ui.community.reportFailureLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Reporting identity screen's words, pinned the same way the community's are
 * (see CommunityReportWordsTest): every refusal code the transfer RPCs can say has
 * a line a student can act on, the confidential warning is one unchanging string,
 * and the confirmations carry it — because the whole feature turns on that warning
 * being said the same way every time.
 *
 * The code lists are kept in step with `0015_reporting_identity_transfer.sql`:
 * the `{ok, code}` envelopes plus `not_authenticated` and `identity_superseded`,
 * which arrive as raised exceptions and so by a different road.
 */
class ReportingIdentityWordsTest {

    @Test
    fun `every code an export can refuse becomes a line a student can act on`() {
        // begin_identity_transfer's complete refusal set.
        for (code in listOf(
            "nothing_to_transfer",
            "transfer_already_pending",
            "rate_limited",
            "identity_superseded",
            "not_authenticated",
        )) {
            assertReadable(code, exportRefusalLine(code))
        }
    }

    @Test
    fun `every code a claim can refuse becomes a line a student can act on`() {
        // claim_reporting_identity's complete refusal set — every state the transfer
        // state machine can be in that isn't a win. `nothing_to_replace` is 0017's:
        // a confirmed replacement claim against a phone whose history is already
        // gone.
        for (code in listOf(
            "invalid_code",
            "transfer_unknown",
            "transfer_used",
            "transfer_aborted",
            "transfer_expired",
            "transfer_self",
            "transfer_in_progress",
            "identity_not_fresh",
            "nothing_to_replace",
            "identity_superseded",
            "transfer_not_ready",
            "not_authenticated",
        )) {
            assertReadable(code, claimRefusalLine(code))
        }
    }

    @Test
    fun `every code an approve or abort can refuse becomes a line a student can act on`() {
        for (code in listOf("transfer_not_ready", "not_authenticated")) {
            assertReadable(code, transferDecisionFailureLine(code))
        }
    }

    @Test
    fun `an unknown transfer refusal keeps its code instead of hiding it`() {
        // Same rule as the community lines: tomorrow's server can send a code this
        // build has never heard of, and showing it raw is the honest fallback.
        assertEquals("The server refused (some_future_code).", exportRefusalLine("some_future_code"))
        assertEquals("The server refused (some_future_code).", claimRefusalLine("some_future_code"))
        assertEquals(
            "The server refused (some_future_code).",
            transferDecisionFailureLine("some_future_code"),
        )
    }

    @Test
    fun `a superseded identity is pointed at the one screen that can fix it`() {
        // The line must say where to go, in every place a superseded identity can
        // surface: a declined report, a refused vote or poll, and the identity
        // screen's own export refusal.
        assertEquals(
            "Your identity has moved to another phone. Check Reporting identity in Settings.",
            reportFailureLine("identity_superseded"),
        )
        assertEquals(
            "Your identity has moved to another phone. Check Reporting identity in Settings.",
            pollFailureLine("identity_superseded"),
        )
        assertReadable("identity_superseded", exportRefusalLine("identity_superseded"))
    }

    // -------------------------------------------------- the confidential warning

    @Test
    fun `the confidential warning says the three things that make it confidential`() {
        // Treat it like a password; Attendo never backs it up; it can move everything
        // the identity has. If any of the three stops being said, the file stops
        // being described honestly.
        assertTrue(IDENTITY_FILE_WARNING.contains("Highly confidential"))
        assertTrue(IDENTITY_FILE_WARNING.contains("move everything you have reported"))
        assertTrue(IDENTITY_FILE_WARNING.contains("never includes it in any backup"))
        assertTrue(IDENTITY_FILE_WARNING.contains("Treat it like a password"))
    }

    @Test
    fun `both confirmations carry the confidential warning`() {
        // Export, verbatim. Import states its own stakes and doesn't need the
        // password warning repeated — but both must exist, and the export's must be
        // built on the one string above, not a drifting copy.
        assertTrue(IDENTITY_FILE_CONFIRM_EXPORT.contains(IDENTITY_FILE_WARNING))
        assertTrue(IDENTITY_FILE_CONFIRM_IMPORT.contains("24 hours to cancel"))
        assertTrue(
            "The import confirmation must still say an import never merges — the " +
                "replace offer only exists because of that rule.",
            IDENTITY_FILE_CONFIRM_IMPORT.contains("never merges two"),
        )
    }

    // ------------------------------------------------------------- the replacement

    @Test
    fun `the destructive confirmation states the deletion plainly and its timing honestly`() {
        // The replacement dialog is the one place in the app where "yes" deletes
        // something permanently. The copy must say what is deleted (everything the
        // identity made — reports, polls, votes, verifications, reputation, transfer
        // history), that it is permanent and unrecoverable, and — just as important —
        // WHEN it happens: not at confirm, only if and when the move completes. A
        // dialog that says "deleted" but means "deleted in 24 hours if nobody
        // cancels" would be honest about the wrong thing.
        val text = IDENTITY_FILE_CONFIRM_REPLACE
        for (phrase in listOf(
            "never merge two",
            "deleted for good",
            "every report, poll, vote and verification",
            "the reputation it earned",
            "its transfer history",
            "permanently erased from Attendo's servers",
            "cannot be undone",
            "cannot be recovered",
            "Nothing is deleted straight away",
            "24-hour",
            "cancel the move",
            "paused while it waits",
            "kept and sent once the move is decided",
            "deletion happens only if and when the move completes",
            "If it is cancelled or runs out, this phone keeps its own identity",
        )) {
            assertTrue(
                "The destructive confirmation must say \"$phrase\" — a student acting " +
                    "on this dialog is acting on permanent deletion.",
                text.contains(phrase),
            )
        }
    }

    @Test
    fun `the frozen lines say the report is kept, not declined`() {
        // identity_frozen is the pending replacement's write freeze seen from the
        // community surfaces: the student filed a report, the server said "not now".
        // The line must say the report survived and will send when the move is
        // decided — anything that reads as a decline invites a re-file, and the
        // duplicate would be a second report for the same thing.
        assertEquals(
            "Your identity is moving phones. Check Reporting identity in Settings. " +
                "This report is kept and will send when the move is decided.",
            reportFailureLine("identity_frozen"),
        )
        assertEquals(
            "Your identity is moving phones. Check Reporting identity in Settings.",
            pollFailureLine("identity_frozen"),
        )
    }

    @Test
    fun `the screen asks for the passphrase only through the dialog`() {
        // A structural pin in the contract style this repo uses for behavior that
        // lives in a composable: the passphrase must reach the ViewModel as a
        // CharArray (which the repository wipes), never as a String that survives in
        // state, and the dialog must blank its own copy before the call.
        val screen = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityScreen.kt").readText()
        val viewModel = sourceFile(
            "src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt",
        ).readText()

        assertTrue(
            "The passphrase must cross as a CharArray the repository can wipe.",
            screen.contains("onConfirm: (CharArray) -> Unit") &&
                screen.contains("passphrase.toCharArray()"),
        )
        assertTrue(
            "The dialog must blank its own copy before handing the array over.",
            screen.contains("val chars = passphrase.toCharArray()"),
        )
        assertFalse(
            "The ViewModel must never hold a passphrase in state — it travels call to call.",
            viewModel.contains("passphrase: String"),
        )
    }

    // ------------------------------------------------------------------ plumbing

    /** A readable line is not the raw-code fallback and not blank. */
    private fun assertReadable(code: String, line: String) {
        assertFalse(
            "The refusal \"$code\" must have its own line, not the raw-code fallback.",
            line.contains("($code)"),
        )
        assertTrue("The refusal \"$code\" must have a line a student can act on.", line.isNotBlank())
    }

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
