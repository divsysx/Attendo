package com.attendo.ui.community

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wipe rule, from the 2026-09-10 follow-up: clearing the backend
 * (`delete from community.observations / polls`) must clear the phones too —
 * every surface, without re-entering the screen.
 *
 * Three halves, each pinned where it lives:
 *
 *  1. **The announcement.** Deletes fired no pulse (0012 pulsed insert+update
 *     only, on the reasoning that only retention deletes rows — which missed
 *     the admin wipe deleting *visible* rows). 0014 makes every change to a
 *     pulsed table news, whatever the operation, so an open phone refetches
 *     the moment a wipe lands.
 *  2. **The surfaces that clear themselves.** The observations cache is
 *     replaced wholesale by every refresh, and polls are fetched live — so a
 *     refetch after a wipe empties both by construction. No pin needed; what
 *     needed the pin was the half that does *not* clear itself:
 *  3. **The outbox.** "My Reports" rows are device-local and outlived the
 *     wipe until the 24-hour prune. The reconciliation in refresh() deletes
 *     every server-confirmed row the server's own owner-facing answer no
 *     longer lists — the server's memory of a report is the truth "My
 *     Reports" keeps.
 *
 * Contract tests in this repo's source-reading style (see
 * `CommunityResilienceTest`) because every guarantee here is a shape.
 */
class WipeReconciliationTest {

    // ------------------------------------------------------ 1. the announcement

    @Test
    fun `a wipe is announced like every other change`() {
        val migration = source("supabase/migrations/0014_pulse_deletes.sql")
        // Both triggers, both tables: insert, update, *and* delete. The wipe
        // deletes visible rows — their disappearance is exactly the news a
        // subscriber needs, and 0012's insert+update triggers stayed silent on it.
        val obsTrigger = migration.substring(migration.indexOf("create trigger observations_pulse"))
        val pollTrigger = migration.substring(migration.indexOf("create trigger polls_pulse"))
        listOf("observations" to obsTrigger, "polls" to pollTrigger).forEach { (name, trigger) ->
            assertTrue(
                "The $name pulse trigger must fire on delete — a wipe without a pulse is a " +
                    "wipe no open phone ever hears about.",
                trigger.contains("after insert or update or delete"),
            )
        }
        // pulse_row() itself must be operation-blind: it reads only tg_table_name,
        // never NEW or OLD, so the same function serves the delete case unchanged.
        val pulseRow = migration.substringBefore("drop trigger if exists")
        assertTrue(
            "0014 must not need to touch pulse_row() — it branches on no row data, which " +
                "is what makes serving DELETE a trigger change and nothing more.",
            !pulseRow.contains("drop function"),
        )
    }

    // ----------------------------------------------------------- 3. the outbox

    @Test
    fun `sent rows reconcile against the server's memory`() {
        val repo = source("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt")
        // refresh() must run the reconciliation on every pass — the pulse-driven
        // refetches included, which is the whole point: the wipe reaches an open
        // phone as a refetch, so the reconciliation must ride the same refetch.
        val refresh = repo.substring(repo.indexOf("suspend fun refresh()"))
        assertTrue(
            "refresh() must reconcile the outbox before declaring success.",
            refresh.substringBefore("suspend fun reconcileSentReportsWith").contains("reconcileSentReportsWith(api)"),
        )
        val reconcile = repo.substring(repo.indexOf("private suspend fun reconcileSentReportsWith"))
        // The race rule: candidates captured *before* the fetch. A report accepted
        // while the fetch is in flight stamps its server id after the list was
        // taken — judging it against an answer that predates it would delete a
        // live report and its undo button with it.
        val candidatesAt = reconcile.indexOf("val candidates")
        val fetchAt = reconcile.indexOf("val serverTruth")
        assertTrue(
            "Reconciliation must capture its candidates before fetching the server's " +
                "answer, or a send landing mid-fetch is judged against a stale list.",
            candidatesAt in 0 until fetchAt,
        )
        // Only server-confirmed rows are the server's to confirm or deny: the
        // criterion is the server id, never the sent stamp alone (which lands
        // before the id does), and pending/failed/declined rows stay untouched.
        assertTrue(
            "Only rows carrying a serverObservationId may reconcile — local-only rows are " +
                "the student's record, not the server's.",
            reconcile.contains("filter { it.serverObservationId != null }") &&
                reconcile.contains("filter { it.serverObservationId !in known }"),
        )
        // The wipe of last resort must be the delete, and only of orphans.
        assertTrue(
            "Reconciliation deletes exactly the orphaned rows, by their own key.",
            reconcile.contains("dao.deleteOutbox(it.idempotencyKey)"),
        )
    }

    @Test
    fun `none is never mistaken for unreachable`() {
        val api = source("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt")
        val pending = api.substring(api.indexOf("suspend fun myPendingReports"))
        // A refused envelope is not an answer, and a transport failure is not
        // "the server has none of your reports": both must be null, because a
        // network blip that read as empty would erase the outbox wholesale.
        assertTrue(
            "A refused envelope must be null, not an empty list.",
            pending.contains("if (!obj.bool(\"ok\")) return@runCatching null"),
        )
        assertTrue(
            "A failed call must be null — getOrDefault(emptyList()) is the exact bug " +
                "shape this replaces.",
            pending.contains("}.getOrNull()") && !pending.contains("getOrDefault(emptyList())"),
        )
    }

    @Test
    fun `the server outlives the outbox`() {
        // The safety argument under the reconciliation: within an outbox row's
        // 24-hour life, the server's owner-facing answer still lists every
        // observation it holds — its lookback (7 days past expiry, 30 for
        // withdrawn) outlasts the prune window by design. Absence therefore
        // means the row is gone, never merely old.
        val rpcs = source("supabase/migrations/0009_withdrawal_undo_rpcs.sql")
        val pendingReports = rpcs.substring(rpcs.indexOf("create or replace function community.my_pending_reports"))
        assertTrue(
            "my_pending_reports must look back past the outbox's 24-hour life for live rows.",
            pendingReports.contains("o.expires_at > now() - interval '7 days'") &&
                pendingReports.contains("o.withdrawn_at > now() - interval '30 days'"),
        )
        val sync = source("src/main/kotlin/com/attendo/data/community/CommunitySync.kt")
        assertTrue(
            "The outbox prune stays at 24 hours — if it ever outgrew the server's " +
                "lookback, absence would stop meaning gone and this pin is the alarm.",
            sync.contains("pruneOutbox(now().minusSeconds(24 * 3600))"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String {
        val file = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
        return file.readText()
    }
}
