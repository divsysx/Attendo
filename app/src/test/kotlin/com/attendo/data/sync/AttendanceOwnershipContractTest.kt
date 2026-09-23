package com.attendo.data.sync

import com.attendo.core.sync.AttendanceSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The persistence half of the cross-account boundary, pinned as source and as data.
 *
 * [com.attendo.data.account.AccountLifecycleBoundaryTest] pins the *decisions* — who may push,
 * who may pull, whose tombstone is whose. This pins the storage those decisions are read from,
 * because a correct rule applied to the wrong row is still a crossed boundary, and every claim
 * below is one that a later refactor could quietly break:
 *
 *  * **Sync state is per account, so B cannot inherit A's `initialPushDone`.** The row is keyed
 *    by `userId` and read by `userId`. If it were ever keyed by the device — a singleton, a
 *    "current account" column, a preference — a new account would read the outgoing account's
 *    `true` and skip its initial push, or read its `false` and perform one over rows that are
 *    not its own.
 *  * **A tombstone knows which account recorded it, and the push reads it that way.** Both the
 *    SQL scope and the in-Kotlin rule are asserted, because the SQL is the one that runs and
 *    the rule is the one the tests can state.
 *
 * The Room database itself cannot run here (this module's test source set has no
 * instrumentation and no Robolectric), so what is asserted is the contract the DAO and the
 * store declare — the annotations and query text Room compiles, and the call the store makes.
 */
class AttendanceOwnershipContractTest {

    private val entities =
        sourceFile("app/src/main/kotlin/com/attendo/data/db/AttendanceSyncEntities.kt").readText()
    private val store =
        sourceFile("app/src/main/kotlin/com/attendo/data/sync/AttendanceSyncStore.kt").readText()

    // ------------------------------------ sync state is keyed by the account

    /** AUTH 11: the state row's identity is the account, so B's row is a different row. */
    @Test
    fun `sync state is keyed by the account and read by the account`() {
        assertTrue(
            "attendance_sync_state must be keyed by userId: a device-keyed state row is the " +
                "one thing that would let a new account inherit the previous account's " +
                "initial-push flag.",
            entities.contains("@PrimaryKey val userId: String"),
        )
        assertTrue(
            "The state read must select by userId — a read that did not would hand whichever " +
                "account asked the row belonging to another.",
            entities.contains("SELECT * FROM attendance_sync_state WHERE userId = :userId"),
        )
    }

    /**
     * AUTH 13: B cannot inherit A's `initialPushDone`.
     *
     * The initial-push decision is read from the *linked* account's own row. This is the
     * specific line that makes that true, and the reason it is asserted rather than assumed:
     * `initial = !state.initialPushDone` is what decides whether a push takes every local row
     * or only the dirty ones, and it is read from `ensureState(userId)` — the incoming
     * account's row, created empty if it has never synced.
     */
    @Test
    fun `a first push for an unseen account starts from an empty state row`() {
        val ensureState = methodBodyOf(store, "ensureState")

        assertTrue(
            "ensureState must look the state up by the uid it was given.",
            ensureState.contains("syncDao.state(userId)"),
        )
        assertTrue(
            "and must create the row for *that* uid — never a singleton, never a default " +
                "account — so a uid this device has never synced reads as initialPushDone " +
                "false, which is what makes its first push its own.",
            ensureState.contains("AttendanceSyncStateEntity(userId = userId)"),
        )
    }

    @Test
    fun `the initial-push flag is read for the account being pushed`() {
        val preparePush = methodBodyOf(store, "preparePush")

        assertTrue(
            "preparePush must read the state of the account it is pushing for.",
            preparePush.contains("ensureState(userId)"),
        )
        assertTrue(
            "and decide the initial push from that row's own flag.",
            preparePush.contains("val initial = !state.initialPushDone"),
        )
    }

    // ------------------------------- a tombstone knows whose deletion it is

    /** AUTH 10, first layer: the SQL the push actually runs is scoped by account. */
    @Test
    fun `the pending-tombstone query will not return another account's rows`() {
        assertTrue(
            "The tombstone read must be scoped to the account, with the empty uid kept for " +
                "rows recorded before any account owned this device.",
            entities.contains(
                "SELECT * FROM attendance_sync_tombstones WHERE pushedAt IS NULL AND " +
                    "(userId = :userId OR userId = '') ORDER BY deletedAt",
            ),
        )
    }

    /**
     * AUTH 10, second layer: the rule is applied to the rows as well, in a form tests can pin.
     *
     * Defence in depth on the one boundary where a mistake is a cross-account *write* — every
     * other leak copies a row into the wrong account, and this one deletes a row out of the
     * right one's cloud data. The two layers cannot disagree; the filter rejects a row only
     * for the reason the rule states.
     */
    @Test
    fun `the push applies the cross-account rule to the tombstones it read`() {
        val preparePush = methodBodyOf(store, "preparePush")

        assertTrue(
            "preparePush must filter the tombstones through the same rule the tests pin.",
            preparePush.contains("AttendanceSyncPolicy.tombstonePushableBy(it.userId, userId)"),
        )
        // And the rule is the one asserted here: another account's tombstone is refused.
        assertFalse(
            AttendanceSyncPolicy.tombstonePushableBy(tombstoneUserId = "uid-a", accountUserId = "uid-b"),
        )
    }

    /**
     * AUTH 12 / 13 / 14 / 17, in its strongest form: **no code path silently releases the
     * attendance claim, and none invents a new way for attendance to change hands.**
     *
     * The tempting cleanup after a deleted account is to clear the owner row, and the shortcut
     * is already sitting there — `AttendanceSyncDao.clearOwner()` is declared. It has no
     * callers, and this asserts that it acquires none: clearing the row is what would hand the
     * next account an initial push over the outgoing account's entire history, because a uid
     * this device has never synced reads as `initialPushDone = false`.
     *
     * ### Why two call sites is still the right number
     *
     * Terminating a claim does not add one. The terminated lifecycle ends through
     * [AttendanceSyncStore.releaseTerminatedClaim], which *calls*
     * [AttendanceSyncStore.switchAttendanceOwner] rather than reimplementing it — so "the local
     * attendance was emptied" has exactly one implementation, and a bug in it is one bug. The
     * third path, in other words, is a third *reason* to reach a claim that already existed,
     * not a third claim. Asserted as a count because a count is what a new primitive would
     * break.
     */
    @Test
    fun `the only way ownership changes is a first claim or a confirmed switch`() {
        assertTrue(
            "clearOwner must still exist for whoever needs it — this test is about it never " +
                "being reached, not about removing it.",
            entities.contains("suspend fun clearOwner()"),
        )

        val callSites = allMainSources().flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> line.contains("clearOwner()") && !line.contains("fun clearOwner()") }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(
            "Nothing may release the attendance claim by erasing it. Erasing it is what would " +
                "let the next account perform an initial push over the deleted account's rows " +
                "— see AuthLifecyclePolicy.ownerAfterAccountDeletion, which deliberately " +
                "answers NamesGoneAccount, and AttendanceSyncStore.releaseTerminatedClaim, " +
                "which releases only after the phone is empty. Found: $callSites",
            emptyList<String>(),
            callSites,
        )

        // ...and the claim is still reached in exactly two places.
        val claimSites = allMainSources().flatMap { file ->
            val lines = file.readLines()
            lines.withIndex()
                .filter { (_, line) -> line.contains("claimAttendance(") && !line.contains("suspend fun") }
                .map { (index, _) ->
                    // The branch that reached it, not just the call: the call line itself is a
                    // one-liner and says nothing about *why* ownership is moving.
                    (index - 12).coerceAtLeast(0)..index
                }
                .map { window -> lines.slice(window).joinToString("\n") }
        }
        assertEquals(
            "Ownership may be claimed in exactly two places, and a third would be a third way " +
                "for attendance to change hands. Found: " +
                claimSites.map { it.lines().last { l -> l.contains("claimAttendance(") }.trim() },
            2,
            claimSites.size,
        )
        assertTrue(
            "One is the engine's first claim — the only path where 'no owner at all' is the " +
                "reason, and so the only one where an initial push of every local row is " +
                "correct.",
            claimSites.any { it.contains("OwnershipVerdict.CLAIM") },
        )
        val switch = methodBodyOf(store, "switchAttendanceOwner")
        assertTrue(
            "The other is the explicit, confirmed switch — the existing mechanism both the " +
                "switch flow and the terminated lifecycle end through, rather than replacing.",
            switch.contains("claimAttendance(newUserId, at)") && switch.contains("withTransaction"),
        )
    }

    /**
     * AUTH 12 / 13: the terminated flag is persisted, guarded, and written in exactly one place.
     *
     * This is the state that survives a process death, and the whole reason the boundary holds
     * when the deletion and the next sign-in are days and several restarts apart. It is
     * asserted at the level of the column, the query and the caller, because each of the three
     * can fail on its own:
     *
     *  * without the column, the fact is in memory only and a restart forgets the account is
     *    gone — which reads as "a different live account", the prompt about a ghost;
     *  * without the guard, any later pass could terminate a claim that is not the gone
     *    account's, or flip the flag back;
     *  * without the single caller, the write is not connected to the verdict that establishes
     *    it, and nothing would set it at all.
     */
    @Test
    fun `a terminated claim is persisted, guarded, and written only from the deletion`() {
        assertTrue(
            "The ownership row must carry the termination. A flag held in memory would be " +
                "lost by the process death that happens between a deletion and the next " +
                "sign-in, and B would be read as someone else's live account.",
            entities.contains("val terminatedAt: Instant? = null"),
        )
        assertTrue(
            "It must default to NULL: every claim written before this column existed, and " +
                "every fresh claim, is a live one. A non-null default would terminate the " +
                "whole install on upgrade.",
            entities.contains("@ColumnInfo(defaultValue = \"NULL\")"),
        )

        // The guard, in the query Room actually compiles: only the gone account's row, and only
        // while it is still live. Both halves matter — the uid check is what stops evidence
        // about one account being applied to another claim, and `IS NULL` is what makes a
        // repeated call after a process death a no-op rather than a rewrite.
        // The query *above* the declaration — `@Query` annotates the function it precedes.
        val terminateDeclaration = entities.indexOf("suspend fun terminateClaim(")
        assertTrue("terminateClaim must exist.", terminateDeclaration > 0)
        val terminateQuery = entities.substring(0, terminateDeclaration)
            .substringAfterLast("@Query(")
        assertTrue(
            "terminateClaim must be scoped to the account the server established is gone. " +
                "Found: $terminateQuery",
            terminateQuery.contains("WHERE id = 0 AND userId = :userId"),
        )
        assertTrue(
            "terminateClaim must be idempotent: a second call (a second validation of the " +
                "same gone account) must change nothing. Found: $terminateQuery",
            terminateQuery.contains("terminatedAt IS NULL"),
        )

        val writers = allMainSources().flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> line.contains("terminatedAt =") && line.contains("putOwner").not() }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertTrue(
            "The flag is written by a DAO UPDATE, not by re-inserting the owner row: an " +
                "insert is a REPLACE, which would rebuild the row and clear what it protects. " +
                "Found: $writers",
            writers.isEmpty() || writers.all { it.contains("terminatedAt = :at") },
        )
    }

    /**
     * AUTH 15 / CRITICAL data-safety: **the outgoing body is set aside before anything is
     * cleared, and the clearing is the switch's own transaction.**
     *
     * Order is the whole safety property here. `preserve()` before the clearing transaction
     * means a failure to write the copy throws before a single row has gone, which leaves the
     * phone exactly as it was — still A's, still refused for B — and the next pass arrives at
     * the same quarantine verdict and tries again. The reverse order would clear first and lose
     * the only copy of a body that was removed by an administrator with no confirmation from
     * the student, which is the one deletion in the app that cannot be asked about.
     *
     * The ordering is asserted on [AttendanceSyncStore.switchAttendanceOwner] because that is
     * where it now lives: it is the one method that clears local attendance, and it reads the
     * claim itself rather than trusting whatever the caller last saw. A caller whose answer went
     * stale — an approval prompt built while the outgoing account was still live — therefore
     * cannot empty a terminated claim's phone without taking the copy first. See
     * `StaleAccountTransitionTest`, which pins that path from the other side.
     *
     * Asserted as source because that is what the ordering *is*: a behavioural test would have
     * to fake a filesystem failure at a seam this module cannot reach.
     */
    @Test
    fun `the removed account's body is set aside before the phone is cleared`() {
        val switch = methodBodyOf(store, "switchAttendanceOwner")
        val terminatedAt = switch.indexOf("terminated")
        val preserveAt = switch.indexOf("preserve()")
        val transactionAt = switch.indexOf("withTransaction")
        val clearAt = switch.indexOf("deleteAll()")

        assertTrue(
            "The copy must be conditional on the claim being terminated — a live claim is an " +
                "account the student approved replacing, and nothing is kept of it.",
            terminatedAt >= 0 && terminatedAt < preserveAt,
        )
        assertTrue(
            "The copy must be written before the transaction that clears, not inside it and " +
                "not after it. Nothing else makes 'the body was preserved' true at the moment " +
                "the body stops existing.",
            preserveAt >= 0 && preserveAt < transactionAt,
        )
        assertTrue(
            "The copy must precede the first deleteAll() by source order as well, so the " +
                "ordering survives any later restructuring of the transaction body.",
            preserveAt < clearAt,
        )
        assertFalse(
            "The copy must not be wrapped in runCatching: a failure to write it has to stop " +
                "the clear, and swallowing it would clear a body that was never preserved.",
            switch.contains("runCatching"),
        )
        assertFalse(
            "Every attendance table must be cleared inside switchAttendanceOwner's " +
                "transaction and nowhere else, so that 'the rows are gone' and 'the copy was " +
                "made' cannot come apart.",
            switch.substring(0, transactionAt).contains("deleteAll()"),
        )

        val release = methodBodyOf(store, "releaseTerminatedClaim")
        assertTrue(
            "releaseTerminatedClaim must reach the switch rather than repeat it, so that " +
                "emptying the phone has one implementation and one guard.",
            release.contains("switchAttendanceOwner("),
        )
    }

    // ---------------------------------------------------------------- plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)

    /** Every Kotlin file in the app's main source tree — for "no call site anywhere" claims. */
    private fun allMainSources(): List<File> =
        mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun mainSourceRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "app/src/main/kotlin") }
            .first(File::exists)

    /** The source of one method, from its `fun` line to the next member after it. */
    private fun methodBodyOf(source: String, name: String): String {
        val start = source.indexOf("suspend fun $name(").takeIf { it >= 0 } ?: source.indexOf("fun $name(")
        assertTrue("$name must exist — the invariant it carries is not optional.", start >= 0)
        val rest = source.substring(start)
        val end = listOf(
            rest.indexOf("\n    suspend fun "),
            rest.indexOf("\n    private suspend fun "),
            rest.indexOf("\n    fun "),
            rest.indexOf("\n    private fun "),
            rest.indexOf("\n    /**"),
        ).filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
