package com.attendo.core.community

import java.time.Instant

/**
 * Folding raw observations into what the Rooms tab actually shows: one line per room,
 * with the freshest signal first.
 *
 * The server sends rows; the app needs sentences. This is the whole transformation, kept
 * pure so a scenario ("three reports about 203, one stale") is a table of inputs and one
 * expected line.
 */
object CommunitySummaries {

    /**
     * A room's community signal, reduced for display.
     */
    data class RoomSummary(
        val room: String,
        /** How many distinct live observations carry [tendency] for this room. */
        val reportCount: Int,
        /** The room's overall crowd tendency across its live observations. */
        val tendency: Tendency,
        /** When the newest contributing observation was filed — drives "4 min ago". */
        val latestAt: Instant,
    )

    enum class Tendency { BUSY, FREE, UNKNOWN }

    /**
     * Reduces live observations to one [RoomSummary] per room, newest first.
     *
     * Expired or resolved observations are the caller's to filter (see
     * [CommunityRules.isObservationVisible]); this function trusts its input and only
     * groups. A room whose observations disagree is [Tendency.UNKNOWN] — the honest
     * answer when two students say opposite things is "reports disagree", not a coin flip.
     *
     * Future claims are excluded, deliberately: the summary answers "what do students say
     * about this room *now*", and a claim about the 2 PM slot is not an answer to that
     * question. Folding one in would make "Students report it occupied" a statement about
     * a moment the claim never mentioned — the exact confusion the type model exists to
     * prevent. Claims render where their slot can be said: the room's detail screen.
     */
    fun byRoom(observations: List<CommunityObservation>): List<RoomSummary> =
        observations
            .filter { it.room != null }
            .filter { it.observationType != CommunityObservationType.FUTURE }
            .groupBy { it.room!! }
            .map { (room, live) ->
                RoomSummary(
                    room = room,
                    reportCount = live.size,
                    tendency = when {
                        live.any { it.kind.tendency() == Tendency.BUSY } &&
                            live.any { it.kind.tendency() == Tendency.FREE } -> Tendency.UNKNOWN
                        live.all { it.kind.tendency() == Tendency.BUSY } -> Tendency.BUSY
                        live.all { it.kind.tendency() == Tendency.FREE } -> Tendency.FREE
                        else -> Tendency.UNKNOWN
                    },
                    latestAt = live.maxOf { it.createdAt },
                )
            }
            .sortedByDescending { it.latestAt }

    private fun CommunityReportKind.tendency(): Tendency = when (this) {
        CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
        CommunityReportKind.EXTRA_CLASS_IN_ROOM,
        CommunityReportKind.CLASS_MOVED_HERE,
        -> Tendency.BUSY

        CommunityReportKind.ROOM_FREE_DESPITE_BUSY -> Tendency.FREE

        // Room reports of other kinds carry no crowd tendency either way.
        CommunityReportKind.ROOM_OTHER,
        CommunityReportKind.CLASS_MOVED,
        CommunityReportKind.EXTRA_CLASS,
        CommunityReportKind.CLASS_CANCELLED,
        CommunityReportKind.CLASS_ROOM_CHANGED,
        CommunityReportKind.CLASS_TIME_CHANGED,
        CommunityReportKind.CLASS_MISSING_FROM_TIMETABLE,
        CommunityReportKind.CLASS_OTHER,
        CommunityReportKind.UNRECOGNIZED,
        -> Tendency.UNKNOWN
    }
}
