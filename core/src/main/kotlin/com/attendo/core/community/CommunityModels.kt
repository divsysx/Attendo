package com.attendo.core.community

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

/**
 * What a student is reporting about a room or a class.
 *
 * The values and their wire names are the `community.report_kind` Postgres enum exactly —
 * one wrong letter here means the server rejects a submission it should have accepted, so
 * the mapping is pinned by tests against the strings in the migration. Unknown values from
 * a newer server arrive as [UNRECOGNIZED] rather than crashing a reader, per the
 * additive-only backend contract.
 */
enum class CommunityReportKind(val wireName: String) {
    ROOM_OCCUPIED_DESPITE_FREE("room_occupied_despite_free"),
    ROOM_FREE_DESPITE_BUSY("room_free_despite_busy"),
    CLASS_MOVED_HERE("class_moved_here"),
    EXTRA_CLASS_IN_ROOM("extra_class_in_room"),
    ROOM_OTHER("room_other"),

    CLASS_MOVED("class_moved"),
    EXTRA_CLASS("extra_class"),
    CLASS_CANCELLED("class_cancelled"),
    CLASS_ROOM_CHANGED("class_room_changed"),
    CLASS_TIME_CHANGED("class_time_changed"),
    CLASS_MISSING_FROM_TIMETABLE("class_missing_from_timetable"),
    CLASS_OTHER("class_other"),

    /** A wire value this build does not know. Rendered generically, never written. */
    UNRECOGNIZED(""),

    ;

    companion object {
        fun fromWire(name: String): CommunityReportKind =
            entries.firstOrNull { it.wireName == name } ?: UNRECOGNIZED
    }

    /** Kinds whose subject is a room right now rather than a class slot. */
    val isRoomKind: Boolean
        get() = this in ROOM_KINDS

    /** Kinds whose subject is a class slot; these require a date and hour. */
    val isClassKind: Boolean
        get() = this in CLASS_KINDS && this != UNRECOGNIZED
}

/**
 * What a report *is*, as distinct from what it is *about* — the `community.observation_type`
 * Postgres enum (migration 0007).
 *
 * A report is one of three things, and the difference is not derivable from dates:
 *  * [PRESENT] — what the reporter sees at the moment of filing (room reports).
 *  * [HISTORICAL] — about a selected slot, via its class date and hour (class reports; the
 *    server derives this type from the slot, no client states it).
 *  * [FUTURE] — an *expectation* about a later slot today: a claim, carried by an explicit
 *    [CommunityObservation.eventDate] and target interval, never by a repurposed date.
 *
 * The server is the authority; a claim is community-reported expectation and never
 * timetable data — nothing about it may feed availability, free/busy math, or attendance.
 */
enum class CommunityObservationType(val wireName: String) {
    PRESENT("present"),
    HISTORICAL("historical"),
    FUTURE("future"),

    /** A newer server's type this build cannot name. Read as present, never written. */
    UNRECOGNIZED(""),

    ;

    companion object {
        fun fromWire(name: String?): CommunityObservationType =
            entries.firstOrNull { it.wireName == name } ?: PRESENT
    }
}

private val ROOM_KINDS = setOf(
    CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
    CommunityReportKind.ROOM_FREE_DESPITE_BUSY,
    CommunityReportKind.CLASS_MOVED_HERE,
    CommunityReportKind.EXTRA_CLASS_IN_ROOM,
    CommunityReportKind.ROOM_OTHER,
)

private val CLASS_KINDS = setOf(
    CommunityReportKind.CLASS_MOVED,
    CommunityReportKind.EXTRA_CLASS,
    CommunityReportKind.CLASS_CANCELLED,
    CommunityReportKind.CLASS_ROOM_CHANGED,
    CommunityReportKind.CLASS_TIME_CHANGED,
    CommunityReportKind.CLASS_MISSING_FROM_TIMETABLE,
    CommunityReportKind.CLASS_OTHER,
)

/**
 * Where an observation stands. The server owns every transition; the client only reads.
 */
enum class CommunityObservationStatus(val wireName: String) {
    REPORTED("reported"),
    CORROBORATED("corroborated"),
    CONFIRMED("confirmed"),
    DISPUTED("disputed"),
    REJECTED("rejected"),
    EXPIRED("expired"),
    /** The reporter took it back (migration 0009). Terminal, invisible to strangers. */
    WITHDRAWN("withdrawn"),

    /** A newer server's status this build cannot name. Shown as "no longer active". */
    UNRECOGNIZED(""),

    ;

    companion object {
        fun fromWire(name: String): CommunityObservationStatus =
            entries.firstOrNull { it.wireName == name } ?: UNRECOGNIZED
    }

    /** True while the observation is one strangers may still see and verify. */
    val isActive: Boolean
        get() = this == REPORTED || this == CORROBORATED || this == CONFIRMED
}

/** A poll's lifetime, again read-only on this side. */
enum class CommunityPollStatus(val wireName: String) {
    OPEN("open"),
    CLOSED("closed"),
    EXPIRED("expired"),
    /** The creator took it back (migration 0009). Terminal, invisible to strangers. */
    WITHDRAWN("withdrawn"),

    /** A newer server's status. Rendered as closed. */
    UNRECOGNIZED(""),

    ;

    companion object {
        fun fromWire(name: String): CommunityPollStatus =
            entries.firstOrNull { it.wireName == name } ?: UNRECOGNIZED
    }
}

/**
 * The delta a report carries: what changed, not the whole context.
 *
 * This is the `payload` jsonb column's client-side twin. The server accepts only the five
 * keys below and rejects everything else, so [toJson] never writes a key the server has not
 * already blessed. It is deliberately a flat class rather than a sealed hierarchy of twelve
 * variants: the payload vocabulary is five keys wide, and validation lives in
 * [CommunityRules] where the server contract is mirrored in one place.
 */
data class ReportPayload(
    /** The room a class moved to. Required for [CommunityReportKind.CLASS_ROOM_CHANGED]. */
    val newRoom: String? = null,
    /** The hour a class moved to, 9..17. Required for [CommunityReportKind.CLASS_TIME_CHANGED]. */
    val newStartHour: Int? = null,
    /** The date a class moved to, when the student knows it. */
    val newDate: LocalDate? = null,
    val subject: String? = null,
    val section: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotNull("new_room", newRoom?.takeIf { it.isNotBlank() })
        newStartHour?.let { put("new_start_hour", it) }
        putIfNotNull("new_date", newDate?.toString())
        putIfNotNull("subject", subject)
        putIfNotNull("section", section)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIfNotNull(
        key: String, value: String?
    ) {
        if (value != null) put(key, value)
    }

    companion object {
        /**
         * Reads a server payload defensively: unknown keys are dropped rather than trusted,
         * and malformed values read as null rather than throwing — a broken row must never
         * take a card down with it.
         */
        fun fromJson(json: JsonObject?): ReportPayload = ReportPayload(
            newRoom = json?.str("new_room")?.takeIf { it.isNotBlank() },
            newStartHour = json?.int("new_start_hour")?.takeIf { it in 9..17 },
            newDate = json?.str("new_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            subject = json?.str("subject"),
            section = json?.str("section"),
        )

        private fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private fun JsonObject.int(key: String): Int? =
            str(key)?.toIntOrNull()
    }
}

/**
 * One observation as the app renders it — the sanitized shape the server exposes: no
 * reporter identity, no idempotency key, no per-user verification rows. Counts only.
 *
 * [observationType] and the target fields are the claim model added by migration 0007: a
 * [CommunityObservationType.FUTURE] row carries [eventDate] and [targetStartHour]/
 * [targetEndHour] explicitly — futurity is stated by the server, never inferred here from
 * a date. Rows from a server older than the migration read as present observations, which
 * is what they were.
 */
data class CommunityObservation(
    val id: String,
    val kind: CommunityReportKind,
    val status: CommunityObservationStatus,
    val room: String?,
    val section: String?,
    val subject: String?,
    val classDate: LocalDate?,
    val startHour: Int?,
    val payload: ReportPayload,
    val note: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val observationType: CommunityObservationType = CommunityObservationType.PRESENT,
    /** The date a future claim is about — always the day it was filed. */
    val eventDate: LocalDate? = null,
    /** The claim's target slot start, 9..17; the interval ends one hour later. */
    val targetStartHour: Int? = null,
    /** The claim's target slot end, 10..18 — server-computed, read-only here. */
    val targetEndHour: Int? = null,
    /**
     * When the reporter took this observation back, when it is one of the caller's own
     * rows read through `my_pending_reports` (migration 0009). Null on public rows —
     * strangers never learn a row was withdrawn; it simply stops existing for them.
     */
    val withdrawnAt: Instant? = null,
    /** 'undo' or 'withdraw' — which way it was taken back. Owner-facing reads only. */
    val withdrawalKind: String? = null,
    /** How many strangers confirmed it — owner-facing reads only. */
    val verificationCount: Int = 0,
    /** How many strangers called it not right — owner-facing reads only. */
    val disputeCount: Int = 0,
    /**
     * The caller's own verdict on this observation, when they have given one — the
     * observation twin of [CommunityPoll.myVote]. Read from the caller's own
     * `verifications` row (RLS read-own, exactly like `poll_votes`), so a card can
     * say "you marked this" instead of offering buttons the server would refuse
     * with `already_verified`. Null means no verdict from this student yet.
     */
    val myVerdict: Boolean? = null,
)

/**
 * A poll and its tallies, again counts-only.
 *
 * [myVote] is the one per-user fact the server lets a client read (its own `poll_votes`
 * row, by RLS) — it exists so the card can say "your vote" instead of letting the student
 * vote twice. Null means the student has not voted in this poll.
 */
data class CommunityPoll(
    val id: String,
    val room: String?,
    val section: String?,
    val subject: String?,
    val classDate: LocalDate?,
    val startHour: Int?,
    val question: String,
    val status: CommunityPollStatus,
    val options: List<PollOption>,
    val totalVotes: Int,
    val closesAt: Instant,
    val myVote: Int? = null,
    /**
     * When the creator took this poll back, when it is one of the caller's own rows
     * read through `my_polls` (migration 0009). Null on public rows.
     */
    val withdrawnAt: Instant? = null,
    /** 'undo' or 'withdraw' — which way it was taken back. Owner-facing reads only. */
    val withdrawalKind: String? = null,
    /** When the poll was asked — owner-facing reads carry it; public reads do not. */
    val createdAt: Instant? = null,
)

data class PollOption(
    val optionIndex: Int,
    val label: String,
    val count: Int,
)
