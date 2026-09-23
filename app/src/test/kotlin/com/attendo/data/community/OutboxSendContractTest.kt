package com.attendo.data.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The send-side counterpart of the sign-in cleanup contract (see
 * [com.attendo.data.account.AccountArchitectureTest]): the cleanup only protects
 * anyone if a drain pass can never send a row it should not — neither a row the
 * cleanup removed, nor a row still pending under an identity that is being replaced.
 *
 * Two mechanisms, each with its own job:
 *
 *  * **The gate.** A pass holds the send gate for its whole run — identity step
 *    included — and the account layer's sign-in swap (retire the outgoing identity,
 *    discard its local rows, import the account's session) holds the same gate for
 *    its whole run. The two are mutually exclusive, so a send can never straddle the
 *    session switch: no row flies under a session that is being replaced, and no
 *    drain mints a throwaway identity while a replacement is in flight.
 *  * **The re-ask.** The clearings that do *not* hold the gate — startFreshIdentity,
 *    a superseded-identity restart, a reset — can still land between a pass reading
 *    its row list and sending it, so every send re-checks the table first. The rows
 *    held in memory by a running pass belong to an identity that may be about to stop
 *    existing on this phone, and the server stamps a report's reporter from the
 *    session that sends it, so sending one anyway is exactly the misfile ("a pending
 *    operation submitted under another account") the cleanup exists to prevent.
 */
class OutboxSendContractTest {

    @Test
    fun `a drain pass and the sign-in swap share one gate`() {
        // The gate is a single Mutex inside the drain engine, taken two ways: by every
        // drain pass (around its identity step *and* its sends — a mint during a
        // session swap would be a new throwaway identity nobody asked for) and by
        // withSendsBlocked, the one door the account layer's import window uses. If
        // the pass's lock and the swap's lock are not the same mutex, they serialize
        // against nothing and the swap can interleave mid-send.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunitySync.kt").readText()
        val drain = methodBodyOf(source, "drainOnce")

        assertTrue(
            "The drain engine must own one send gate.",
            source.contains("private val sendGate = Mutex()"),
        )
        assertTrue(
            "withSendsBlocked must hold the send gate — it is the door the sign-in " +
                "swap uses, so it must exclude exactly what a drain pass does.",
            source.contains("sendGate.withLock { block() }"),
        )
        assertTrue(
            "A drain pass must run under the gate: sends, and the table reads around " +
                "them, must not interleave with the swap.",
            drain.contains("sendGate.withLock"),
        )
        assertTrue(
            "The identity step must sit inside the gate too — a drain minting a " +
                "session while the swap replaces one would create a throwaway " +
                "identity nobody asked for.",
            drain.indexOf("sendGate.withLock") < drain.indexOf("ensureSession(client)"),
        )
    }

    @Test
    fun `a drain pass re-asks the table before each send`() {
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunitySync.kt").readText()
        val body = methodBodyOf(source, "sendRows")

        val recheckAt = body.indexOf("dao.outboxPendingCount(row.idempotencyKey)")
        assertTrue(
            "sendRows must re-check each row against the table before sending it — the " +
                "row list was read before the send loop began, and a discard that lands " +
                "mid-pass must win over whatever the list still holds.",
            recheckAt >= 0,
        )
        val sendAt = body.indexOf("api.submitReport(")
        assertTrue(
            "The re-check must come before the send, per row, not after it.",
            recheckAt in 0 until sendAt,
        )
    }

    @Test
    fun `the re-check asks the same pending predicate the pass was built from`() {
        // The count query must mean exactly what pendingOutbox means — sentAt is null
        // and rejectionCode is null — or the two can disagree about what "pending" is
        // and the re-check would skip rows a pass should send (or trust rows it
        // should not). One source of truth, two spellings of the same where-clause.
        val source = sourceFile("src/main/kotlin/com/attendo/data/db/CommunityEntities.kt").readText()

        assertTrue(
            "The DAO must offer the per-row pending count the send loop re-asks with.",
            source.contains("suspend fun outboxPendingCount(key: String): Int"),
        )
        val predicate = "sentAt is null and rejectionCode is null"
        assertEquals(
            "The pending predicate must appear exactly twice — pendingOutbox and " +
                "outboxPendingCount — and nowhere else: a third spelling is a third " +
                "meaning waiting to drift.",
            2,
            Regex(Regex.escape(predicate)).findAll(source).count(),
        )
    }

    // ---------------------------------------------------------------- plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)

    /** The source of one method, from its `fun` line to the next member after it. */
    private fun methodBodyOf(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("$name must exist — the send loop is not optional.", start >= 0)
        val rest = source.substring(start)
        val end = listOf(
            rest.indexOf("\n    private suspend fun "),
            rest.indexOf("\n    suspend fun "),
            rest.indexOf("\n    fun "),
            rest.indexOf("\n    companion object {"),
        ).filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
