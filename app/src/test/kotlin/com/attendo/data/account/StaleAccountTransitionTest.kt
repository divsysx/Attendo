package com.attendo.data.account

import com.attendo.core.sync.AttendanceSyncPolicy
import com.attendo.core.sync.AttendanceSyncPolicy.AccountTransition
import com.attendo.core.sync.AttendanceSyncPolicy.LocalAttendanceOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decision a transition press carries has to be re-derived from the claim at the moment it
 * is taken, not from the claim as it stood when the screen offered it.
 *
 * ### The window this closes
 *
 * An account transition is a **two-step conversation**: the screen reads the claim, decides
 * what to ask, and renders a prompt; the student reads it and presses. Everything the press
 * needs was established at the first step and is carried in the prompt — and one thing can
 * change in between. The outgoing account, live when the question was asked, can be deleted by
 * an administrator while the question is still on screen.
 *
 * That changes which operation the press *is*. `DIFFERENT_ACCOUNT_REFUSE` asks "replace the
 * other account's attendance?" — a question about a live account and the person entitled to
 * answer for it. Over a claim that has since been terminated the same words describe something
 * else entirely: an account nobody can ask, whose rows exist nowhere else. A press that ran the
 * approved-switch path on that state would clear the only copy of a removed student's term,
 * with no set-aside copy taken, because nothing on that path ever writes one.
 *
 * So the press re-reads, and the answer decides the route:
 *
 *  * still live → the ordinary approved switch, unchanged, [AccountTransition.SWITCH_APPROVED];
 *  * now terminated → [AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION], which sets the body
 *    aside before it clears anything.
 *
 * ### Why the routing is not what makes it safe
 *
 * The re-read closes the window to the gap between two adjacent statements, not to zero, and the
 * routing above would be worth nothing if it were the only guard — a caller that read the claim
 * a moment earlier would still be holding the stale answer. So the invariant is enforced at the
 * destructive boundary as well:
 * [com.attendo.data.sync.AttendanceSyncStore.switchAttendanceOwner] reads the claim itself
 * immediately before its transaction and writes the copy when the claim is terminated. Both
 * halves are asserted here, because either one alone is a bug waiting for a refactor.
 *
 * The Room and file stores cannot run in this source set (no instrumentation, no Robolectric),
 * so the storage half is pinned as source — the same way `AttendanceOwnershipContractTest` pins
 * it — and the decision half as the pure function both the screen and the prompt are built from.
 */
class StaleAccountTransitionTest {

    private val viewModel = sourceFile(
        "app/src/main/kotlin/com/attendo/ui/settings/AccountViewModel.kt",
    ).readText()

    private val store = sourceFile(
        "app/src/main/kotlin/com/attendo/data/sync/AttendanceSyncStore.kt",
    ).readText()

    // ------------------------------------------------ the decision, re-derived

    /**
     * REGRESSION: a live A → B prompt whose A is then deleted must not run the approved switch.
     *
     * The approval was given for a live account. Once the account is gone the approval is not
     * an answer to any question that still has a subject, and the transition it produces is the
     * one that preserves the body first — not the one that replaces it.
     */
    @Test
    fun `an approval whose account was deleted becomes the new-lifecycle path`() {
        val liveA = LocalAttendanceOwner(userId = "uid-a", terminated = false)
        val deletedA = LocalAttendanceOwner(userId = "uid-a", terminated = true)

        assertEquals(
            "While A is live this is the ordinary approved switch the prompt asked about.",
            AccountTransition.SWITCH_APPROVED,
            AttendanceSyncPolicy.accountTransition(liveA, "uid-b", isExplicitSwitchApproved = true),
        )
        assertEquals(
            "Once A has been deleted the same press is a new lifecycle, not a switch: there is " +
                "nobody left to have approved replacing A's attendance, and A's rows are the " +
                "only copy of that term.",
            AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION,
            AttendanceSyncPolicy.accountTransition(deletedA, "uid-b", isExplicitSwitchApproved = true),
        )
    }

    /**
     * REGRESSION: a terminated claim can never reach the ordinary approved switch — the exact
     * cross product, so no uid and no approval flag finds a way round the terminated check.
     */
    @Test
    fun `a terminated claim can never reach the ordinary switch, approved or not`() {
        val deletedA = LocalAttendanceOwner(userId = "uid-a", terminated = true)

        for (approved in listOf(false, true)) {
            for (uid in listOf("uid-b", "uid-c")) {
                assertEquals(
                    "approved=$approved, incoming=$uid: the terminated check must come before " +
                        "the approval, or an approval given for a live account would still be " +
                        "honoured over a claim whose account no longer exists.",
                    AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION,
                    AttendanceSyncPolicy.accountTransition(deletedA, uid, isExplicitSwitchApproved = approved),
                )
            }
            assertEquals(
                "approved=$approved: the uid the claim actually names is the one case a " +
                    "terminated flag must not be able to make unusable — it is still A's claim.",
                AccountTransition.SAME_ACCOUNT_RETURN,
                AttendanceSyncPolicy.accountTransition(deletedA, "uid-a", isExplicitSwitchApproved = approved),
            )
        }
    }

    // ------------------------------------------------- the press, re-derived

    /**
     * REGRESSION: the switch press reads the claim again before it acts, and routes on what it
     * finds rather than on what the prompt remembered.
     */
    @Test
    fun `the switch press re-reads the claim and routes on the fresh answer`() {
        val press = methodBodyOf(viewModel, "confirmAccountSwitch")
        val readAt = press.indexOf("sync.attendanceOwner()")
        val switchAt = press.indexOf("switchAttendanceOwner(")
        val releaseAt = press.indexOf("releaseTerminatedClaim(")

        assertTrue(
            "The press must read the claim again — the prompt carries the answer from when it " +
                "was shown, and that is the one thing that can have gone stale.",
            readAt >= 0,
        )
        assertTrue(
            "The re-read must precede the destructive call. Read after it, it is a log line " +
                "rather than a guard.",
            readAt < switchAt,
        )
        assertTrue(
            "The routing must be the same policy the prompt was built from, not a second rule " +
                "about the same question.",
            press.contains("AttendanceSyncPolicy.accountTransition("),
        )
        assertTrue(
            "The press carries the approval the student gave — it is the *claim* that has to be " +
                "re-read, not the decision.",
            press.contains("isExplicitSwitchApproved = true"),
        )
        assertTrue(
            "A claim that has become terminated must have a route to the preserving path, not " +
                "only the approved switch.",
            releaseAt >= 0,
        )
        assertTrue(
            "The terminated route must be reached from the new-lifecycle arm, so the press " +
                "cannot take it on any other evidence.",
            press.indexOf("AccountTransition.NEW_LIFECYCLE_AFTER_TERMINATION") in 0 until releaseAt,
        )
    }

    /**
     * REGRESSION: RESTORE_REMOTE re-reads too, and refuses when the account has gone.
     *
     * Refusing is the point rather than an omission. There is no cloud copy to restore from —
     * the account is gone — so the local rows are the last copy of that work, and clearing them
     * would not merely lose them: the quarantine path writes the copy that is supposed to
     * outlive a removed account, and it writes it in the same breath as clearing. A restore
     * that cleared here first would leave *that* path to preserve an already-empty database over
     * the only good copy, so "restore my backup" would end as the deletion of the thing it was
     * protecting.
     */
    @Test
    fun `restore cannot bypass the invariant when the account changed under the prompt`() {
        val reconcile = methodBodyOf(viewModel, "reconcileReturningAccount")
        val armAt = reconcile.indexOf("ReturningReconciliation.RESTORE_REMOTE ->")
        assertTrue("The restore arm must exist.", armAt >= 0)

        val arm = reconcile.substring(armAt)
        val readAt = arm.indexOf("attendanceOwner()")
        val restoreAt = arm.indexOf("restoreRemote(")

        assertTrue(
            "The restore must re-read the claim before it discards anything — its prompt is " +
                "built from a claim read when the prompt was shown, exactly like the switch's.",
            readAt >= 0,
        )
        assertTrue(
            "The re-read must guard the destructive call, not follow it.",
            readAt < restoreAt,
        )
        assertTrue(
            "The guard must be on the claim being terminated: that is the state in which the " +
                "local rows are a removed account's only copy.",
            arm.contains("terminated"),
        )

        // ...and the refusal is enforced at the boundary as well, so the screen's decision is
        // not the only thing standing between a stale prompt and a cleared body.
        val restore = methodBodyOf(store, "restoreRemote")
        assertTrue(
            "AttendanceSyncStore.restoreRemote must refuse a terminated claim on its own: " +
                "clearing there would orphan the body the quarantine path is about to preserve.",
            restore.indexOf("terminated") in 0 until restore.indexOf("deleteAll()"),
        )
        assertTrue(
            "The refusal must write nothing, which is what an early return from the " +
                "transaction body is.",
            restore.contains("return@withTransaction"),
        )
    }

    // ------------------------------------------------------ the boundary itself

    /**
     * REGRESSION / CRITICAL data-safety: a preservation failure leaves A's rows and A's
     * terminated claim exactly as they were.
     *
     * The copy is written *before* the transaction that clears, and on no path is it optional
     * or swallowed — so a failure to write it throws with nothing cleared, and the next pass
     * arrives at the same quarantine verdict and tries again. Asserted as source because the
     * ordering is what the property is, and because faking a filesystem failure belongs to a
     * seam this module cannot reach.
     */
    @Test
    fun `a failed copy leaves the removed account's rows and claim untouched`() {
        val switch = methodBodyOf(store, "switchAttendanceOwner")
        val preserveAt = switch.indexOf("preserve()")
        val transactionAt = switch.indexOf("withTransaction")

        assertTrue(
            "The copy must be called before the transaction opens. Inside it, a throw would " +
                "roll back — but a copy written and then rolled back leaves the body gone with " +
                "the file as the only record, which is not the state this promises.",
            preserveAt >= 0 && preserveAt < transactionAt,
        )
        assertFalse(
            "The copy must not be wrapped in runCatching: swallowing a failed write would " +
                "clear rows that were never preserved, which is the one outcome this guard " +
                "exists to make unreachable.",
            switch.contains("runCatching"),
        )
        assertFalse(
            "No attendance table may be cleared before the transaction, so there is no path " +
                "on which rows go before the copy does.",
            switch.substring(0, transactionAt).contains("deleteAll()"),
        )

        // The claim is not rewritten on this path either. A failed copy must leave the claim
        // terminated — the flag is what makes the next pass try again rather than treat the
        // phone as unclaimed, whose first push would upload whatever rows are still there.
        assertTrue(
            "The switch must re-read the claim before it clears, so the flag that decides " +
                "whether to copy is read at the transition and not inherited from a caller.",
            switch.indexOf("terminated") in 0 until preserveAt,
        )
        assertFalse(
            "The switch must not write the terminated flag: it is set by the deletion, from " +
                "the server's verdict alone. A switch that could set or clear it would be a " +
                "second opinion about whether an account is gone.",
            switch.contains("terminateClaim"),
        )
    }

    /**
     * The screen class reaches for no table of its own. Every destructive transition is one of
     * the two store methods above, which is what makes their guards the only ones that matter.
     */
    @Test
    fun `the account screen clears attendance only through the guarded transitions`() {
        for (destructive in listOf("deleteAll(", "clearTombstones(", "claimAttendance(", "terminateClaim(")) {
            assertFalse(
                "AccountViewModel must not reach for \"$destructive\": the account screen is a " +
                    "view of the lifecycle, and every write that empties or claims the phone " +
                    "belongs to the store, behind the guard that reads the claim first.",
                viewModel.contains(destructive),
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)

    /** The source of one method, from its `fun` line to the next member after it. */
    private fun methodBodyOf(source: String, name: String): String {
        val start = source.indexOf("suspend fun $name(").takeIf { it >= 0 } ?: source.indexOf("fun $name(")
        assertTrue("$name must exist — the transition it routes is not optional.", start >= 0)
        val rest = source.substring(start)
        val end = listOf(
            rest.indexOf("\n    suspend fun "),
            rest.indexOf("\n    private suspend fun "),
            rest.indexOf("\n    fun "),
            rest.indexOf("\n    private fun "),
            rest.indexOf("\n    companion object {"),
            rest.indexOf("\n    /**"),
        ).filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
