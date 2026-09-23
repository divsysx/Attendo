package com.attendo.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH 10 and section C: **one deletion is announced once, and a second deletion is not
 * silenced by the first one's acknowledgement.**
 *
 * The notice is a dialog over the whole app rather than a card on a screen, because a deletion
 * is carried out by a background validation — at app start, or on returning to the foreground
 * — at a moment when no screen is open. That is exactly what makes the two failure modes here
 * live ones:
 *
 *  * **Announcing too often.** `AccountManager.accountRemoved` is a level, not an edge. It
 *    stays true for as long as the account is gone, so every foreground, every validation and
 *    every recomposition re-reads it. Showing the dialog whenever it is true would put the same
 *    sentence back on screen every time the student switched apps — a dialog they cannot get
 *    rid of, about a fact they already read.
 *  * **Announcing too rarely.** The acknowledgement must not outlive the deletion it
 *    acknowledges. A student whose account is deleted, and who later signs in to a *second*
 *    account that is itself later deleted, has to be told about the second one — and would not
 *    be, if the first "OK" were still standing.
 *
 * Both are pinned as pure functions rather than through the ViewModel: this module's test
 * source set has no coroutines-test and no Robolectric, so a flow-based assertion is not
 * available here, and the rule is small enough to state entirely.
 */
class RemovedAccountNoticeTest {

    // ---- AUTH 10: repeated evidence of one deletion is one announcement ------

    @Test
    fun `a gone account is announced while it has not been acknowledged`() {
        assertTrue(removedNoticeVisible(removed = true, acknowledged = false))
    }

    @Test
    fun `the same deletion is not announced again after it has been acknowledged`() {
        // Every one of these is a real re-emission of the *same* fact: a second validation of
        // the same gone account, a return to the foreground, the app root recomposing, the
        // collector restarting after a configuration change. None of them is a second deletion.
        repeat(5) {
            assertFalse(
                "A repeated validation of the same gone account must not re-announce it.",
                removedNoticeVisible(removed = true, acknowledged = true),
            )
        }
    }

    @Test
    fun `an account that is present is announced as nothing at all`() {
        for (acknowledged in listOf(false, true)) {
            assertFalse(removedNoticeVisible(removed = false, acknowledged = acknowledged))
        }
    }

    // ---- a second deletion is announced in its turn --------------------------

    @Test
    fun `a second deletion is announced in its turn on the same install`() {
        // The first account is deleted and the student reads the notice.
        var acknowledged = true
        assertFalse(removedNoticeVisible(removed = true, acknowledged = acknowledged))

        // They sign in to a second account, which answers — the one thing that clears the
        // manager's flag, and so the one thing that reaches this reducer with `removed = false`.
        acknowledged = acknowledgementAfterRemovalChange(acknowledged, removed = false)
        assertFalse(
            "The acknowledgement must be cleared with the flag. Left standing, it would " +
                "silence every deletion this install ever sees after the first — the student " +
                "would be signed out of a removed account with no explanation, because they " +
                "had once dismissed an unrelated one.",
            acknowledged,
        )
        assertFalse(removedNoticeVisible(removed = false, acknowledged = acknowledged))

        // That second account is then deleted too. Same install, same run, no restart.
        assertTrue(
            "The second deletion must be announced.",
            removedNoticeVisible(removed = true, acknowledged = acknowledged),
        )
    }

    @Test
    fun `an acknowledgement is held for as long as the deletion stands`() {
        // The other direction, and the reason this is a reducer rather than "always false on
        // any change": the manager re-announces a still-gone account if it is validated again
        // after a session was minted and lost, and that re-emission is the same deletion.
        for (acknowledged in listOf(false, true)) {
            assertTrue(
                "A still-removed account must not clear the acknowledgement — that is what " +
                    "would make it re-announce on every foreground.",
                acknowledgementAfterRemovalChange(acknowledged, removed = true) == acknowledged,
            )
        }
    }

    // ---- the notice and the Account screen say one thing ----------------------

    @Test
    fun `the dialog says the same thing the account screen says`() {
        // The notice is composed over the whole app, at a moment no screen is open, so it is
        // the only place most students will ever read this. It is not allowed to be a second
        // version of the words: it must be the Account screen's own sentence, so the two
        // cannot describe the same deletion differently.
        val accountScreen = accountRemovedExplanation()

        assertTrue(
            "The dialog title must be the Account screen's opening line. Found: " +
                "\"$REMOVED_ACCOUNT_DIALOG_TITLE\"",
            accountScreen.startsWith("$REMOVED_ACCOUNT_DIALOG_TITLE."),
        )
        assertTrue(
            "The dialog must carry the whole explanation — the student reading this one has " +
                "no screen open and nowhere else to look.",
            accountScreen.contains("no longer exists") &&
                accountScreen.contains("signed out") &&
                accountScreen.contains("Nothing on this phone was deleted"),
        )
        assertTrue(
            "The button acknowledges; it does not offer an action, a link or a dismissal " +
                "that changes anything. Found: \"$REMOVED_ACCOUNT_DIALOG_DISMISS\"",
            REMOVED_ACCOUNT_DIALOG_DISMISS == "OK",
        )
    }
}
