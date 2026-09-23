package com.attendo.data.community

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Realtime community changes — an optional freshness layer, never a source of truth.
 *
 * One channel, app-wide (the free-tier budget is exactly one), subscribed to one
 * table: `community.activity_pulses` (migration 0012) — one content-free row per
 * public table, bumped by a trigger on every insert or update of observations and
 * polls. The app never consumed the direct events' payloads (every event meant the
 * same thing: "refetch"), and the direct subscription had a hole the two-device
 * test found: Realtime delivers an UPDATE only to subscribers who can SELECT the
 * *new* row, and the read policies make a withdrawn report, a withdrawn or closed
 * poll, and a rejected report invisible to everyone but their owner — so the very
 * changes that make content disappear were the ones that arrived as silence. The
 * pulse rows are always visible and carry nothing but a table name and a timestamp,
 * so every change — appearance, tally, status transition, disappearance — reaches
 * every subscriber.
 *
 * Every consumer follows the discipline the Phase 4 validation proved sound:
 *
 *  * **Snapshot first.** Subscription is not activation — the production probes learned
 *    that the backend confirms asynchronously — and Realtime never replays history, so
 *    the flow emits [Change.ACTIVATED] once the channel is live: the collector answers
 *    with one fetch that covers everything missed while disconnected. Events then only
 *    *advance* the snapshot.
 *  * **Failures are quiet.** A flow that errors (no network, engine down) ends itself;
 *    the UI keeps rendering the last snapshot, marked stale, and the caller resubscribes
 *    on a slow drumbeat. Nothing crashes, nothing retries aggressively.
 */
class RealtimeObservations(private val client: CommunityClient) {

    private val supabase: SupabaseClient get() = client.client

    /**
     * A cold flow of community changes — the pulse table, one channel. Collecting is
     * also what subscribes; the channel is torn down when the collector goes.
     */
    fun changes(): Flow<Change> {
        val channel: RealtimeChannel = supabase.channel(CHANNEL)
        return channelFlow {
            // The flow must exist before anything else happens: a postgres_change
            // registers its server-side config when the flow is *created* (and the
            // library throws if the channel has already joined), so creating it here,
            // before subscribe, is what puts the pulse table in the join payload.
            val pulses = channel.postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
                table = PULSES
            }
            // Armed before the join, so no event that lands the instant activation
            // completes can fall on a listener that is not listening yet.
            launch { pulses.collect { send(it.toChange()) } }
            // The reconnect half (the airplane-mode test's finding): the library
            // rejoins channels itself when the websocket comes back, but Realtime
            // never replays the changes that happened while the socket was down —
            // so a channel that silently rejoins leaves the screen showing the
            // pre-disconnect snapshot until some *future* change happens to bump it.
            // The channel's own status flow is the only signal a rejoin exists, so
            // every transition to SUBSCRIBED emits the same nudge the first join
            // does: "you are live — fetch once, covering what was missed."
            launch {
                channel.status.collect { status ->
                    if (status == RealtimeChannel.Status.SUBSCRIBED) send(Change.ACTIVATED)
                }
            }
            // subscribe() is the call this class was missing the first time it was
            // written: postgresChangeFlow only registers a listener — nothing reaches
            // the server until the channel is subscribed, so the app once collected a
            // perfectly wired flow that never connected, never joined, and never fired.
            // subscribe() opens the websocket (connectOnSubscribe) and joins with the
            // session's token, which the library resolves from the installed Auth
            // plugin — delivery is RLS-filtered, so the token is not optional.
            channel.subscribe()
            // Join-ok is not activation (the probe lesson), and history is never
            // replayed: this nudge makes the collector fetch once, covering everything
            // that changed while nobody was listening. The status collector above
            // usually fires the same nudge at the same moment — the ViewModel's
            // refetch floor collapses the pair into one fetch, and this one stands
            // guard in case the status flow ever lags the join.
            send(Change.ACTIVATED)
        }
            .catch { /* a broken realtime feed never takes the screen down */ }
            .onCompletion { runCatching { supabase.realtime.removeChannel(channel) } }
    }

    /**
     * The one shape the UI consumes. The collector's only job is to notice that the
     * snapshot it holds is out of date; [id] rides along for whoever wants more —
     * on the pulse table it is the table name that pulsed.
     */
    data class Change(
        val kind: Kind,
        val id: String,
    ) {
        enum class Kind { ACTIVATED, UPSERT, REMOVE }

        companion object {
            /** "You are live — fetch once to cover what was missed." */
            val ACTIVATED = Change(Kind.ACTIVATED, "")
        }
    }

    private fun PostgresAction.toChange(): Change {
        // The pulse rows have no id; their key is table_name, and that is the useful
        // thing to carry — "which table changed" — for whoever reads [Change.id].
        val record: kotlinx.serialization.json.JsonObject? = when (this) {
            is PostgresAction.Insert -> record
            is PostgresAction.Update -> record
            is PostgresAction.Delete -> null
            is PostgresAction.Select -> record
        }
        val source = record ?: (this as? PostgresAction.Delete)?.oldRecord
        val key = source?.get("table_name")?.jsonPrimitive?.contentOrNull
            ?: source?.get("id")?.jsonPrimitive?.contentOrNull
            ?: // An action we cannot map (a newer server's shape): dropped, not guessed.
            return Change(Change.Kind.REMOVE, "")
        return Change(
            kind = if (record == null) Change.Kind.REMOVE else Change.Kind.UPSERT,
            id = key,
        )
    }

    private companion object {
        const val SCHEMA = "community"
        const val PULSES = "activity_pulses"
        const val CHANNEL = "realtime:community"
    }
}
