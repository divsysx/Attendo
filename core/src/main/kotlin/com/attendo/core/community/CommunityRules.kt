package com.attendo.core.community

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The community feature's pure logic, mirrored from the Postgres contract it talks to.
 *
 * Everything here is a twin of a rule the server enforces (see
 * `supabase/migrations/0003_rpcs.sql`): the same bounds, the same expiry arithmetic, the
 * same dedup shape. The server is the authority — this class exists so the Report sheet
 * can refuse an invalid submission before a round-trip, and so the tests can pin the
 * client and the contract to each other. When one side changes, the other must follow;
 * the failing test is what says so.
 */
object CommunityRules {

    // ---------------------------------------------------------------- bounds

    const val NOTE_MAX_CHARS: Int = 280
    const val ROOM_MAX_CHARS: Int = 60
    const val QUESTION_MIN_CHARS: Int = 3
    const val QUESTION_MAX_CHARS: Int = 160
    const val OPTION_LABEL_MAX_CHARS: Int = 40
    const val MIN_OPTIONS: Int = 2
    const val MAX_OPTIONS: Int = 4

    /**
     * Rejects a submission the server would reject, with the reason.
     *
     * Mirrors `community.validate_payload` plus the field-length checks in
     * `submit_report`/`create_poll`. Null means acceptable.
     */
    fun reportValidationError(
        kind: CommunityReportKind,
        room: String?,
        classDate: LocalDate?,
        startHour: Int?,
        payload: ReportPayload,
        note: String?,
    ): String? {
        if (kind == CommunityReportKind.UNRECOGNIZED) {
            return "unknown report kind"
        }

        // Payload vocabulary: the server rejects any key outside these five.
        val roomField = payload.newRoom
        if (roomField != null && roomField.length > ROOM_MAX_CHARS) {
            return "new room must be at most $ROOM_MAX_CHARS characters"
        }
        if (kind == CommunityReportKind.CLASS_ROOM_CHANGED && roomField.isNullOrBlank()) {
            return "a new room is required for a room change"
        }
        val hourField = payload.newStartHour
        if (hourField != null && hourField !in 9..17) {
            return "new start hour must be between 9 and 17"
        }
        if (kind == CommunityReportKind.CLASS_TIME_CHANGED && hourField == null) {
            return "a new start hour is required for a time change"
        }

        // Context requirements: room kinds need a room, class kinds need a slot.
        if (kind.isRoomKind && room.isNullOrBlank()) {
            return "a room is required for room observations"
        }
        if (kind.isClassKind && (classDate == null || startHour == null)) {
            return "a class date and hour are required for class observations"
        }

        // Field bounds.
        if (room != null && room.length > ROOM_MAX_CHARS) {
            return "room must be at most $ROOM_MAX_CHARS characters"
        }
        if (note != null && note.length > NOTE_MAX_CHARS) {
            return "note must be at most $NOTE_MAX_CHARS characters"
        }
        return null
    }

    /**
     * Mirrors `create_poll`'s validation. Null means acceptable.
     */
    fun pollValidationError(question: String?, options: List<String>?): String? {
        if (question == null || question.length !in QUESTION_MIN_CHARS..QUESTION_MAX_CHARS) {
            return "question must be $QUESTION_MIN_CHARS-$QUESTION_MAX_CHARS characters"
        }
        if (options == null || options.size !in MIN_OPTIONS..MAX_OPTIONS) {
            return "a poll needs $MIN_OPTIONS-$MAX_OPTIONS options"
        }
        if (options.any { it.isEmpty() || it.length > OPTION_LABEL_MAX_CHARS }) {
            return "options must be 1-$OPTION_LABEL_MAX_CHARS characters"
        }
        if (options.toSet().size != options.size) {
            return "options must be distinct"
        }
        return null
    }

    /**
     * Whether `create_poll` would accept this class date: the server clamps to its own
     * today ± 1 day, so a poll about last week is refused before the round-trip. The
     * client's today can be wrong; the server's answer is final — this is the preview,
     * not the authority.
     */
    fun isPollDateInRange(today: LocalDate, classDate: LocalDate): Boolean =
        classDate >= today.minusDays(1) && classDate <= today.plusDays(1)

    /**
     * Whether `submit_report` would accept this class date — the same ±1-day window,
     * because both RPCs clamp to the server's today the same way. Reports need this twin
     * for the same reason polls do: a report filed from a day review weeks away should be
     * refused in the sheet, where the student can see it, not after the outbox round-trip
     * as a "Declined" row.
     */
    fun isReportDateInRange(today: LocalDate, classDate: LocalDate): Boolean =
        isPollDateInRange(today, classDate)

    // ------------------------------------------------------------- future claims

    /**
     * Rejects a future claim the server would reject, with the reason. Null means
     * acceptable.
     *
     * The twin of the claim block in `submit_report` (migration 0007): a claim is about a
     * room, at a later slot of *today*, on the 9–17 grid, that has not started yet. Once
     * the target hour arrives, "will be occupied at 2" is no longer a claim about the
     * future — the student should file a present report instead, and this says so before
     * the round-trip. The server's clock is the authority; this is the preview.
     */
    fun futureClaimValidationError(
        kind: CommunityReportKind,
        room: String?,
        today: LocalDate,
        now: java.time.LocalDateTime,
        targetStartHour: Int?,
    ): String? {
        if (!kind.isRoomKind) {
            return "claims are about rooms"
        }
        if (room.isNullOrBlank()) {
            return "a room is required for a claim"
        }
        if (targetStartHour == null) {
            return "pick the hour the claim is about"
        }
        if (targetStartHour !in 9..17) {
            return "the hour must be between 9 and 17"
        }
        if (!now.isBefore(today.atTime(targetStartHour, 0))) {
            return "that hour has already started. Report what you see instead"
        }
        return null
    }

    /**
     * When a future claim stops mattering — the client-side twin of
     * `community.claim_expiry` (migration 0007).
     *
     * A claim's entire value is the window before and during the slot it names: it dies at
     * the target slot's end + 30 minutes, so a stale claim never lingers into the evening
     * as advice about a moment already gone. UTC arithmetic, matching the server's
     * `make_timestamptz(..., 'UTC')`, so both sides compute the same instant.
     */
    fun claimExpiresAt(eventDate: LocalDate, targetStartHour: Int): Instant =
        eventDate.atStartOfDay(ZoneOffset.UTC)
            .plusSeconds((targetStartHour + 1) * 3600L)
            .plusSeconds(30 * 60L)
            .toInstant()

    // ---------------------------------------------------------------- undo/withdraw

    /**
     * The fresh-undo window, in seconds — the client mirror of
     * `community.fresh_undo_window()` (migration 0012).
     *
     * The server owns the number; this constant exists so the client can *show*
     * undo for about as long as the server will *honor* it — and so a test can
     * pin the two to each other (the server's pgTAP suite asserts its side, and
     * the client tests assert this). One place, both sides, one number: change
     * it in the migration, change it here, and the failing tests on both sides
     * are the checklist.
     *
     * Thirty seconds: long enough to regret a tap, short enough that "Undo" is
     * not a standing offer on the card — after it, Withdraw is the way back.
     */
    const val FRESH_UNDO_SECONDS: Int = 30

    /**
     * When a just-submitted report or poll stops being undoable — the local
     * preview of the server's `created_at + fresh_undo_window()`. Null clock
     * (never anchored to a server time) means undo is not offered: a client
     * that cannot judge time against the server should not advertise a
     * server-enforced window it may get wrong.
     */
    fun undoDeadline(createdAt: Instant, serverNow: Instant?): Instant? =
        serverNow?.plus(Duration.between(serverNow, createdAt))?.plusSeconds(FRESH_UNDO_SECONDS.toLong())

    // ---------------------------------------------------------------- expiry

    /**
     * When a report stops mattering — the client-side twin of `community.expiry_for`.
     *
     * A class-bound report lives until two hours after that class date ends (the teaching
     * day ends 18:00; 20:00 is the expiry). A room-only report lives three hours, because
     * "is this room occupied" is a question about now, not about a date.
     *
     * The arithmetic is done in UTC to match the server's `make_timestamptz(..., 'UTC')`,
     * which keeps this function's answer and the server's one row identical — the app
     * never has to reconcile a client-computed expiry against a server one.
     */
    fun reportExpiresAt(now: Instant, classDate: LocalDate?): Instant =
        if (classDate != null) {
            classDate.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(20 * 3600)
        } else {
            now.plusSeconds(3 * 3600)
        }

    /**
     * When a poll closes — the twin of the `closes_at` block in `create_poll`.
     *
     * A class-bound poll closes 30 minutes after the teaching day (18:30 UTC). Otherwise
     * three hours. Never more than 24 hours out, and never in the past — a class-bound
     * poll asked about yesterday would otherwise be born closed, so it gets 30 minutes.
     */
    fun pollClosesAt(now: Instant, classDate: LocalDate?): Instant {
        val slotEnd = if (classDate != null) {
            classDate.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds((18 * 3600) + 1800)
        } else {
            now.plusSeconds(3 * 3600)
        }
        val capped = minOf(slotEnd, now.plusSeconds(24 * 3600))
        return if (capped <= now) now.plusSeconds(30 * 60) else capped
    }

    /**
     * Whether an observation should still be shown, computed the same way the server's
     * read path does: expiry and status, never the device's idea of what is active.
     */
    fun isObservationVisible(
        observation: CommunityObservation,
        now: Instant,
    ): Boolean = observation.status.isActive && observation.expiresAt > now

    /**
     * Whether a poll can still be voted in — the twin of [isObservationVisible] for the
     * `cast_vote` gate (`status = 'open' and closes_at > now()`). The clock a caller
     * passes must be the anchored one (see `CommunityUiState.pollClock`), because the
     * difference between the fetch's clock and now is exactly what this question is
     * about: a poll held on screen past its close must stop being actionable even
     * though the list it came from is the last good fetch.
     */
    fun isPollOpen(poll: CommunityPoll, now: Instant): Boolean =
        poll.status == CommunityPollStatus.OPEN && poll.closesAt > now

    /**
     * The dedup bucket a submission lands in — the client-side shape of the
     * `obs_dedup_unique` index. Two submissions of the same kind about the same context in
     * the same hour are one report, so the client can predict "this would be a duplicate"
     * and the tests can pin that prediction to the server's uniqueness scope. A claim's
     * target hour is part of its identity: "occupied at 11" and "occupied at 14" are two
     * claims (migration 0007 added the column to the index for exactly that reason).
     */
    fun dedupKey(
        reporterId: String,
        kind: CommunityReportKind,
        room: String?,
        classDate: LocalDate?,
        startHour: Int?,
        payload: ReportPayload,
        atHourStart: Instant,
        targetStartHour: Int? = null,
    ): List<String?> = listOf(
        reporterId,
        kind.wireName,
        room ?: "",
        classDate?.toString() ?: "",
        (startHour ?: -1).toString(),
        payload.newRoom ?: "",
        (targetStartHour ?: -1).toString(),
        // The server truncates created_at to the hour; the caller passes the same.
        atHourStart.toString(),
    )
}
