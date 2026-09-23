package com.attendo.ui.settings

import com.attendo.data.account.AccountState
import com.attendo.data.account.HandoffFailure
import com.attendo.data.account.HandoffNotice
import com.attendo.data.account.handoffFailureOf
import com.attendo.data.account.handoffNoticeOfFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/**
 * The words the Account screen says, pinned for the same reason every other words test
 * exists: these few sentences are the entire explanation of what an account is, and a
 * promise that has drifted from the feature is worse than no sentence at all — in either
 * direction.
 *
 * These pins used to assert the opposite of what they assert now. The account began as a
 * reporting identity made permanent, and the copy was held to saying exactly that: no
 * sync, attendance stays on the phone, the account covers Community only. Attendance sync
 * then shipped, and that copy became the wrong promise — the reassuring one. The test was
 * rewritten with it, deliberately, as its own comment said it would be, and the honesty
 * pins now run the other way: every paragraph must name attendance as part of the
 * account, and the obsolete "Community only" reassurance must not come back.
 *
 * What has not changed is the other half of the job: the two GitHub paths must stay
 * unmistakably distinct, nothing may imply a merge, and signing out must not read as a
 * deletion. Those are the claims a student acts on.
 */
class AccountSettingsWordsTest {

    private val noSession = AccountState.NoSession
    private val anonymous = AccountState.Anonymous("6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1")
    private val linked = AccountState.Linked("6b1f0d3a-8c4e-4a2b-9f7d-1e5c2a8b40d1")

    /**
     * The sentence a connectivity failure must reach the student as, written out in
     * full. Asserted by equality rather than by substring on purpose: classifying a
     * transport failure correctly is only half the fix, and a `contains("connection")`
     * pin would still pass if the wording around it drifted into something the student
     * cannot act on.
     */
    private val connectionSentence =
        "Couldn't reach the sign-in service. Check your internet connection and try again."

    @Test
    fun `the headline says whether the student is signed in`() {
        // NoSession and Anonymous are the same thing to the student — no GitHub login
        // attached yet — and the headline must not make either sound technical.
        assertEquals("Not signed in", accountValueLabel(noSession))
        assertEquals("Not signed in", accountValueLabel(anonymous))
        assertEquals("Signed in with GitHub", accountValueLabel(linked))
    }

    @Test
    fun `the two paths are two buttons with two different names`() {
        // The labels are the flows. One button attaches GitHub to the identity this
        // phone holds; the other authenticates an account this phone does not hold.
        // They must be distinct, and neither may collapse back into the ambiguous
        // "Sign in with GitHub" that could mean either.
        assertNotEquals(CONNECT_LABEL, SIGN_IN_LABEL)
        assertTrue(CONNECT_LABEL.contains("account"))
        assertTrue(SIGN_IN_LABEL.contains("existing"))
        for (label in listOf(CONNECT_LABEL, SIGN_IN_LABEL)) {
            assertFalse(
                "A GitHub button must not be called \"Sign in with GitHub\": one label " +
                    "cannot say which of the two operations it performs.",
                label == "Sign in with GitHub",
            )
        }
    }

    @Test
    fun `not being signed in says what the two buttons are for`() {
        // The explanation under the headline names both paths — carrying this phone's
        // identity and its attendance to an account, and reaching one from elsewhere —
        // and says what is true of a phone that has not signed in: nothing leaves it.
        for (state in listOf(noSession, anonymous)) {
            val words = accountExplanation(state)
            assertTrue(
                "The explanation must name the connect path (${state::class.simpleName}).",
                words.contains("Connect GitHub"),
            )
            assertTrue(
                "The explanation must name the existing-account path " +
                    "(${state::class.simpleName}).",
                words.contains("sign in to an account you already have"),
            )
            assertTrue(
                "The explanation must say that until an account exists, nothing leaves " +
                    "this phone (${state::class.simpleName}). That is the reassurance a " +
                    "student who has not signed in is owed, and it is only true of them.",
                words.contains("nothing leaves this phone"),
            )
        }
    }

    @Test
    fun `a signed-in install is told what the account now carries`() {
        val words = accountExplanation(linked)

        assertTrue(words.contains("Community identity"))
        assertTrue(words.contains("GitHub"))
        // The continuity promise, in the student's words: the same identity is there
        // on any phone they sign in on. That is what the server actually holds — the
        // association — so it is what the copy may say.
        assertTrue(words.contains("any phone you sign in on"))
        // And the other half of what signing in did, which is new: the attendance is
        // the account's business now. A signed-in student who is still told their
        // attendance is local-only is being told their data is somewhere it is not.
        assertTrue(
            "A signed-in install must be told the account holds its attendance.",
            words.contains("private copy of your attendance"),
        )
    }

    @Test
    fun `every account paragraph states the account's scope in both directions`() {
        // The scope sentence must say what the account *carries* — the Community
        // reporting identity, its reports, continuity across phones, and the private
        // copy of the attendance — and, just as plainly, that none of it happens
        // without an account. The banned shape is the reassurance this copy used to
        // give: "the account covers Community only … not connected to it", which was
        // true before attendance sync shipped and now describes the one thing the
        // feature does. A student reading it would believe their attendance was
        // nowhere but their phone while the app was uploading it.
        val paragraphs = listOf(
            accountExplanation(noSession),
            accountExplanation(anonymous),
            accountExplanation(linked),
            WHAT_AN_ACCOUNT_IS,
            SIGN_IN_CONFIRM,
        )
        for (words in paragraphs) {
            assertTrue(
                "The paragraph must name what the account carries (Community): " +
                    "\"${words.take(60)}…\"",
                words.contains("Community"),
            )
            assertTrue(
                "The paragraph must name attendance and courses as the account's " +
                    "business too.",
                words.contains("attendance") && words.contains("courses"),
            )
            assertFalse(
                "The obsolete \"Community only\" scope must not come back: attendance " +
                    "is part of the account now, and saying otherwise misdescribes " +
                    "where the student's data is.",
                words.contains("Community only") || words.contains("not connected"),
            )
            assertFalse(
                "The bare \"not part of this\" phrasing must not come back: it " +
                    "names no scope, so it reads as reassurance.",
                words.contains("not part of this"),
            )
        }
        // The standing paragraph is the one place the full list of what travels
        // lives, down to the settings that go with the records.
        for (named in listOf("targets", "timetable", "holidays", "working Saturdays")) {
            assertTrue("The standing paragraph must name $named as travelling.", WHAT_AN_ACCOUNT_IS.contains(named))
        }
        assertTrue(
            "And it must say the alternative is still real: no account, no copy.",
            WHAT_AN_ACCOUNT_IS.contains("on a phone that has never signed in"),
        )
    }

    @Test
    fun `the sign-in confirmation discloses exactly what signing in does`() {
        // Signing in is the one account action that replaces this phone's identity and
        // takes its attendance with it, so its confirmation is the one place all of its
        // facts must be said: the account's Community identity and its reports are
        // restored, the attendance joins the account in both directions, a phone
        // holding another account's attendance is asked before anything is replaced,
        // the signed-out activity is not carried into the account and is discarded
        // only after the sign-in succeeds, a failed or cancelled attempt discards
        // nothing, and a GitHub login Attendo has never seen becomes a new account.
        // And it may not imply a merge — nothing is merged, ever.
        assertTrue(SIGN_IN_CONFIRM.contains("are restored to this phone"))
        assertTrue(
            "The attendance half must be stated plainly, in both directions: the " +
                "account's copy comes down, and what this phone records goes up.",
            SIGN_IN_CONFIRM.contains("copy held for the account is brought down here") &&
                SIGN_IN_CONFIRM.contains("is uploaded"),
        )
        assertTrue(
            "A phone that already holds another account's attendance must be told it " +
                "will be asked first — the replacement is destructive and never silent.",
            SIGN_IN_CONFIRM.contains("asks before anything on it is replaced"),
        )
        assertTrue(SIGN_IN_CONFIRM.contains("will not be carried into the account"))
        assertTrue(
            "The discard's timing must be stated: only after a successful sign-in.",
            SIGN_IN_CONFIRM.contains("after you sign in successfully"),
        )
        assertTrue(
            "The failure promise must be stated: a cancelled or incomplete sign-in " +
                "discards nothing — that is what makes pressing the button safe to " +
                "walk away from.",
            SIGN_IN_CONFIRM.contains("If you cancel, or signing in does not complete, nothing is discarded"),
        )
        assertTrue(
            "The discarded activity must be named concretely: reports, polls and votes.",
            SIGN_IN_CONFIRM.contains("reports, polls and votes"),
        )
        assertTrue(SIGN_IN_CONFIRM.contains("new account will be created"))
        assertFalse(
            "Nothing is merged into an account — the copy must never imply it is.",
            SIGN_IN_CONFIRM.contains("merge", ignoreCase = true),
        )
    }

    @Test
    fun `every explanation says what the account carries, attendance included`() {
        // The successor of the pin that used to ban the word "sync" while sync did not
        // exist. It is the same honesty pin, running the other way: the account's
        // attendance copy is real, so the copy must not go on describing an account
        // that never touches it, and no explanation may claim the attendance stays on
        // the phone while the app is uploading it.
        val explanations = listOf(
            accountExplanation(noSession),
            accountExplanation(anonymous),
            accountExplanation(linked),
        )
        for (words in explanations) {
            assertFalse(
                "No explanation may say the attendance is not connected to the account, " +
                    "or stay on this phone unconditionally: \"${words.take(60)}…\"",
                words.contains("not connected") || words.contains("stay on this phone"),
            )
        }
        // And the standing paragraph says what the copy is for, in one word a student
        // recognises: a copy for the account, not a share with anybody.
        assertTrue(WHAT_AN_ACCOUNT_IS.contains("private copy"))
        assertTrue(
            "An account's data is the student's own; the copy must say nobody else sees it.",
            WHAT_AN_ACCOUNT_IS.contains("shared with anyone else"),
        )
    }

    @Test
    fun `no answer mentions rate limits`() {
        // The bug the device test exposed: an unidentified failure was shown as
        // "usually a rate limit". Nothing in the app's own handling can identify a
        // rate limit — the one request the link press makes is answered with a URL or
        // with a failure the app does not classify, and the sign-in press makes no
        // request at all — so the words "rate" and "limit" belong to no answer, ever,
        // until something can actually identify one again.
        for (message in AccountMessage.entries) {
            assertFalse(
                "No account answer may speak of rate limits ($message).",
                accountMessageLabel(message).contains("rate", ignoreCase = true),
            )
        }
    }

    @Test
    fun `every message answers in one line`() {
        // One recognisable fact per outcome: reassurance for the handoff, a plain
        // word for the quiet exit, and a next move for everything that went wrong.
        assertTrue(accountMessageLabel(AccountMessage.OPENING).contains("browser"))
        assertTrue(accountMessageLabel(AccountMessage.CANCELLED).contains("cancelled"))
        // NETWORK is not pinned here: it is asserted in full, with the sentence the
        // student actually reads, in `a connectivity failure reaches the student as
        // the connection sentence` below.
        assertTrue(accountMessageLabel(AccountMessage.FAILED).contains("try again"))
        // The one verdict the server names for us, in the student's words: their
        // GitHub account is already connected — most often to an install of their own
        // — and the way into that account is the other button on this screen.
        assertTrue(accountMessageLabel(AccountMessage.IDENTITY_TAKEN).contains("already connected"))
        assertTrue(accountMessageLabel(AccountMessage.IDENTITY_TAKEN).contains("sign in to it"))
        assertTrue(accountMessageLabel(AccountMessage.IMPORT_FAILED).contains("try again"))
        assertTrue(accountMessageLabel(AccountMessage.SIGNED_OUT).contains("Signed out"))
        assertTrue(accountMessageLabel(AccountMessage.SIGNED_OUT).contains("Nothing"))
        assertTrue(accountMessageLabel(AccountMessage.SIGNOUT_FAILED).contains("try again"))
    }

    @Test
    fun `a connectivity failure reaches the student as the connection sentence`() {
        // The whole chain, end to end, from the exception the operation threw to the
        // words on the screen: classify → map to a message → render it. Each half is
        // pinned in its own file, and this is what proves the halves connect — a
        // classification that lands on the generic sentence, or a NETWORK message whose
        // wording drifted, fails here even with both halves individually green.
        val offline = UnknownHostException("supabase.co")

        // The link press: the one request it makes fails in transport.
        assertEquals(
            connectionSentence,
            accountMessageLabel(accountMessageOf(handoffFailureOf(offline))),
        )
        // The callback import: the fetch of the callback's user fails offline. This is
        // the path that used to answer every failure with "Couldn't finish signing in."
        assertEquals(
            connectionSentence,
            accountMessageLabel(accountMessageOf(handoffNoticeOfFailure(offline))),
        )
        // And it is genuinely the NETWORK copy, not a coincidence of one path.
        assertEquals(connectionSentence, accountMessageLabel(AccountMessage.NETWORK))
    }

    @Test
    fun `no other outcome is answered with the connection sentence`() {
        // The distinctions, from the student's side. Being told to check a connection
        // that was never the problem sends them to retry something that will fail
        // again, so every one of these must reach its own answer instead.
        for (notice in listOf(
            HandoffNotice.CANCELLED,
            HandoffNotice.REFUSED,
            HandoffNotice.IDENTITY_TAKEN,
            HandoffNotice.IMPORT_FAILED,
        )) {
            assertNotEquals(
                "A $notice callback must not be answered with the connection sentence.",
                connectionSentence,
                accountMessageLabel(accountMessageOf(notice)),
            )
        }
        assertNotEquals(
            "A failure that is not the network's must not be answered with the connection sentence.",
            connectionSentence,
            accountMessageLabel(accountMessageOf(HandoffFailure.UNKNOWN)),
        )
        assertNotEquals(
            "A sign-out that could not reach the server has its own answer.",
            connectionSentence,
            accountMessageLabel(AccountMessage.SIGNOUT_FAILED),
        )
    }

    @Test
    fun `the sign-out confirmation separates signing out from every deletion`() {
        // Sign out is its own operation, and the confirmation is where it could be
        // mistaken for the others. It must say what leaves (the login), what stays
        // (everything), and the whole shape of what happens next: the fresh anonymous
        // identity is temporary, its activity is discarded at the sign-in that
        // follows, and the account's own identity and reports are restored instead.
        // Saying the loop here, at sign-out, is what keeps the second confirmation
        // (the sign-in's) from being the first time the student hears about it.
        assertTrue(SIGN_OUT_CONFIRM.contains("Nothing is deleted"))
        assertTrue(SIGN_OUT_CONFIRM.contains("fresh anonymous identity"))
        assertTrue(SIGN_OUT_CONFIRM.contains("when you sign back in"))
        assertTrue(SIGN_OUT_CONFIRM.contains("discarded"))
        assertTrue(SIGN_OUT_CONFIRM.contains("restored instead"))
        assertTrue(SIGN_OUT_CONFIRM.contains("sign in again with the same GitHub"))
        // And it must not claim the way back is closed — that was true before the
        // sign-in path existed, and is exactly what this stage changed.
        assertFalse(SIGN_OUT_CONFIRM.contains("cannot sign back in"))
    }

    @Test
    fun `a successful handoff retires the message that described it`() {
        // The transition the callback drives: the state turns Linked on its own — for
        // a link, this install's user; for a sign-in, the account's — and the
        // "Opening GitHub…" sentence must not outlive the browser's return, because
        // the headline has already answered it. Any other state leaves the last
        // message alone: a refusal's sentence is still true until something changes it.
        assertNull(messageAfterHandoff(linked, AccountMessage.OPENING))
        assertNull(messageAfterHandoff(linked, AccountMessage.FAILED))
        assertNull(messageAfterHandoff(linked, null))
        assertEquals(AccountMessage.OPENING, messageAfterHandoff(anonymous, AccountMessage.OPENING))
        assertEquals(AccountMessage.FAILED, messageAfterHandoff(noSession, AccountMessage.FAILED))
        assertNull(messageAfterHandoff(noSession, null))
    }

    /**
     * REVIEW 22 / section 6: the sentence a deleted account produces, pinned by equality of
     * the facts it must carry and the three it must not.
     *
     * Shown on the Account screen as a card, once per deletion (the flag is a StateFlow the
     * screen reads, not a dialog on every foreground). It must say the account was removed,
     * that it no longer exists on Attendo's servers, that the student has been signed out,
     * that nothing on this phone was deleted, and that signing in again is a *new* account
     * whose data will not be restored or merged. It must not collapse into "session expired",
     * "network error", or "something went wrong" — those are different states with different
     * sentences — and it must not claim local attendance was deleted, because it was not.
     */
    @Test
    fun `a removed account is explained as a removal, not a session or a network error`() {
        val words = accountRemovedExplanation()

        assertTrue("Must name the removal.", words.contains("removed"))
        assertTrue("Must say the account no longer exists on the servers.", words.contains("no longer exists"))
        assertTrue("Must say the student has been signed out.", words.contains("signed out"))
        assertTrue(
            "Must say nothing local was deleted — the attendance claim is retained on purpose.",
            words.contains("Nothing on this phone was deleted"),
        )
        assertTrue("Must say the next sign-in is a new account.", words.contains("new account"))
        assertTrue(
            "Must say the removed account's data is not restored or merged.",
            words.contains("won't be restored or merged"),
        )
        assertFalse(
            "A deletion is not a lapsed session.",
            words.contains("session expired", ignoreCase = true),
        )
        assertFalse(
            "A deletion is not a network error.",
            words.contains("network error", ignoreCase = true) ||
                words.contains("Couldn't reach", ignoreCase = true),
        )
        assertFalse(
            "A deletion is a fact about the account, not an app failure.",
            words.contains("something went wrong", ignoreCase = true),
        )
        assertFalse(
            "Local attendance was not deleted and the copy must not claim it was.",
            words.contains("your data was deleted", ignoreCase = true) ||
                words.contains("attendance was deleted", ignoreCase = true),
        )
    }

    /**
     * AUTH 9 / section C: the removal is announced at the app boundary, and the announcement
     * is the *same sentence* the Account screen shows.
     *
     * The dialog exists because a deletion is carried out by a background validation — at app
     * start, or on returning to the foreground — with no screen open and nothing to navigate
     * to. A student whose account was removed while they were asleep would otherwise keep
     * using the app, signed out and unsynced, and be told nothing until they happened to open
     * Settings. So the title is asserted against the Account screen's own opening line rather
     * than against a literal: the two renderings are one statement in two places, and a pin on
     * a copy of the string would let them drift apart while both stayed green.
     */
    @Test
    fun `the removal is announced in the app's own words, not a second set`() {
        val accountScreen = accountRemovedExplanation()

        assertTrue(
            "The dialog title must be the sentence the Account screen opens with. " +
                "Found: \"$REMOVED_ACCOUNT_DIALOG_TITLE\"",
            accountScreen.startsWith("$REMOVED_ACCOUNT_DIALOG_TITLE."),
        )
        assertTrue(
            "The button must be an acknowledgement — the app is still usable and nothing " +
                "is being asked. Found: \"$REMOVED_ACCOUNT_DIALOG_DISMISS\"",
            REMOVED_ACCOUNT_DIALOG_DISMISS == "OK",
        )

        // And the dialog body is the same function, not a second copy of the words: the
        // notice may not be able to say something the Account screen does not.
        val notice = sourceFile("app/src/main/kotlin/com/attendo/ui/settings/RemovedAccountNotice.kt").readText()
        assertTrue(
            "The dialog body must call accountRemovedExplanation() — one function, two " +
                "renderings, so they cannot disagree about the same deletion.",
            notice.contains("accountRemovedExplanation()"),
        )
        assertFalse(
            "The notice must not spell out its own explanation. A literal here is how the " +
                "two renderings drift.",
            notice.contains("no longer exists"),
        )
    }

    /**
     * Section C, second half: the sentence that keeps "nothing on this phone was deleted"
     * true.
     *
     * At the deletion nothing local is touched, and [accountRemovedExplanation] says so. The
     * moment a different account signs in, that stops being the whole story: the rows *are*
     * cleared — a claim with rows under it uploads them into the next account, which is the
     * leak this lifecycle exists to stop. What makes the clearing acceptable, and what this
     * copy must say, is that a copy was written first and is still here. It must say exactly
     * three things and no more: the attendance was kept, it was set aside rather than handed
     * over, and it is not part of this account.
     */
    @Test
    fun `the set-aside copy says kept, not deleted, and not part of this account`() {
        val words = attendanceSetAsideExplanation()

        assertTrue(
            "Must say the attendance is still on the phone — that is the whole point of " +
                "setting it aside rather than clearing it outright.",
            words.contains("kept on this phone"),
        )
        assertTrue(
            "Must say it was set aside, and why: so it could not be uploaded as this " +
                "account's. Without the reason it reads as housekeeping.",
            words.contains("set aside") && words.contains("could not be uploaded as this account's"),
        )
        assertTrue(
            "Must say a copy survives, in the student's words.",
            words.contains("A copy of it is still on this phone"),
        )
        assertTrue(
            "Must say it is not part of the account it was set aside beside — otherwise it " +
                "reads as inherited, which is the reading the whole boundary prevents.",
            words.contains("not part of this account"),
        )
        // Naming the *removed account* is right — it is what tells the student which
        // attendance this is. Claiming the *attendance* went the same way is not: the file is
        // what stands between an administrator's action and a lost term, and that sentence is
        // the one that would make a student stop looking.
        for (loss in listOf("deleted", "was removed", "were removed", "discarded", "erased")) {
            assertFalse(
                "The copy must not describe the attendance as $loss. The file is what stands " +
                    "between an administrator's action and a lost term, and that sentence is " +
                    "the one that would make a student stop looking.",
                words.contains(loss, ignoreCase = true),
            )
        }
        assertTrue(
            "Naming the *removed account* is still right — it is what tells the student " +
                "which attendance this is — so the check above must not have been satisfied " +
                "by a copy that simply stopped mentioning it.",
            words.contains("removed account"),
        )
        assertFalse(
            "Must not promise a way to bring it back: nothing in the app offers one.",
            words.contains("restore", ignoreCase = true) || words.contains("recover", ignoreCase = true),
        )
        assertFalse(
            "Must not mention sync. This card is about a file that was written to the " +
                "phone and is still on it, and naming the sync machinery would invite " +
                "the student to think the file is somewhere they can go and fetch it " +
                "back from. Nothing in the app offers a way back to it, so the copy " +
                "must not so much as imply one.",
            words.contains("sync", ignoreCase = true),
        )
    }

    private fun sourceFile(relative: String): java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { java.io.File(it, relative) }
            .first(java.io.File::exists)

    @Test
    fun `account switch confirmation states destructive replacement and cancel safety`() {
        assertTrue(
            "Must state cannot merge different accounts",
            ACCOUNT_SWITCH_CONFIRM.contains("cannot merge", ignoreCase = true),
        )
        assertTrue(
            "Must state data will be replaced/cleared",
            ACCOUNT_SWITCH_CONFIRM.contains("replaced") || ACCOUNT_SWITCH_CONFIRM.contains("cleared"),
        )
        assertTrue(
            "Must state cannot be undone",
            ACCOUNT_SWITCH_CONFIRM.contains("cannot be undone", ignoreCase = true),
        )
        assertTrue(
            "Must guarantee cancel safety",
            ACCOUNT_SWITCH_CONFIRM.contains("If you cancel, nothing is changed or deleted", ignoreCase = true),
        )
    }

    @Test
    fun `reconcile returning prompt offers merge and restore options`() {
        assertTrue(
            "Must mention local changes/edits",
            RECONCILE_RETURNING_PROMPT.contains("local changes", ignoreCase = true),
        )
        assertTrue(
            "Must offer merge",
            RECONCILE_RETURNING_PROMPT.contains("merge", ignoreCase = true),
        )
        assertTrue(
            "Must offer restore",
            RECONCILE_RETURNING_PROMPT.contains("restore", ignoreCase = true),
        )
    }
}
