package com.attendo.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityPoll
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.CommunityRules
import com.attendo.core.community.CommunitySummaries
import com.attendo.core.model.TimeGrid
import com.attendo.data.community.CommunityRepository
import com.attendo.ui.components.ChipChoice
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The community cards, in one file because they are one visual idea: something strangers
 * reported, with how many and how long ago, and never who. No card names a reporter —
 * the server's read path already stripped that, and the UI does its part by never having
 * a field to put it in.
 */

/**
 * The Rooms tab's card: a room, its crowd tendency, how many reports say so.
 *
 * Tapping opens the room's detail screen, where the individual observations live — the
 * list card stays one line, because the tab's job is the overview, not the evidence.
 *
 * [own] — the caller's [CommunityUiState.hasOwnReport] — turns "Students report" into
 * "You report" on the reporter's own phone: the same courtesy the observation card's
 * "Your report" marker is, for the one card this tab shows. Everyone else's card is
 * unchanged, and no reporter is ever named — "You" is the reader, never someone else.
 */
@Composable
fun RoomCommunityCard(
    summary: CommunitySummaries.RoomSummary,
    serverNow: Instant?,
    own: Boolean = false,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary.room,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = tendencyLine(summary, own),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val reports = "${summary.reportCount} report" +
                if (summary.reportCount == 1) "" else "s"
            Text(
                text = listOfNotNull(reports, agoLabel(summary.latestAt, serverNow))
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun tendencyLine(summary: CommunitySummaries.RoomSummary, own: Boolean): String {
    // Who the sentence is about: the reporter's own phone already knows which report is
    // theirs (the mySentIds join the caller ran), so the line can say "You" without the
    // server ever having named anyone — and says "You and other students" when strangers
    // have piled on behind it, because the count beside the line is the whole crowd's.
    val who = when {
        !own -> "Students"
        summary.reportCount == 1 -> "You"
        else -> "You and other students"
    }
    return when (summary.tendency) {
        CommunitySummaries.Tendency.BUSY -> "$who report it occupied"
        CommunitySummaries.Tendency.FREE -> "$who report it free"
        CommunitySummaries.Tendency.UNKNOWN -> "Mixed reports"
    }
}

/**
 * One observation, in full: what was reported, its standing, and the two verdict buttons.
 *
 * This is the only place a student can second a report, so it is also the only place the
 * status vocabulary becomes visible — as words, not chips, because "corroborated" is a
 * word worth learning and an icon is not.
 *
 * The verdict buttons appear only for reports that are not the student's own, that they
 * have not already given a verdict on, and that are still open to verdicts: the server
 * refuses `own_report`, `already_verified`, and terminal statuses, so offering the tap
 * anyway would be a button the server has already promised to refuse. A verdict already
 * given instead renders as "You marked this accurate/not right" in the metadata line —
 * the same courtesy the polls' "your vote" marker is.
 * [verifiable] is the caller's [CommunityUiState.isVerifiable] — the UI hiding is
 * courtesy; the server's RPC guard is the wall.
 *
 * For the student's own report ([own] — the caller's [CommunityUiState.ownReport]) the
 * card offers the way back in place: **Undo** inside the fresh window, **Withdraw**
 * after it — the same pair My community offers, where taking back a report the room
 * can see is one tap instead of a navigation round-trip.
 */
@Composable
fun ObservationCard(
    observation: CommunityObservation,
    serverNow: Instant?,
    verifiable: Boolean,
    onVerify: (Boolean) -> Unit,
    own: CommunityRepository.OutboxUiState? = null,
    onUndo: (idempotencyKey: String) -> Unit = {},
    onWithdraw: (idempotencyKey: String) -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                text = observation.headline(),
                style = MaterialTheme.typography.bodyMedium,
            )
            observation.note?.let { note ->
                Text(
                    text = "\"$note\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    statusLabel(observation.status),
                    // The verdict tallies, in the buttons' own words: a "Not right" tap
                    // must change the card the moment the refetch lands, or the feedback
                    // loop is broken — the two-device test's "no update at all" was this
                    // line missing. Zero counts stay silent: a report with no verdicts
                    // yet has nothing to say here.
                    if (observation.verificationCount > 0) {
                        "${observation.verificationCount} accurate"
                    } else null,
                    if (observation.disputeCount > 0) {
                        "${observation.disputeCount} not right"
                    } else null,
                    // The caller's own verdict, in the buttons' own words: once given,
                    // the server refuses a second one (`already_verified`), so the card
                    // says what they chose instead of offering taps it has promised to
                    // refuse — the observation twin of the polls' "your vote" marker.
                    when (observation.myVerdict) {
                        true -> "You marked this accurate"
                        false -> "You marked this not right"
                        else -> null
                    },
                    // The ownership marker: "Your report" where the verdict buttons
                    // would be, so their absence reads as a rule, not a bug.
                    if (own != null) "Your report" else null,
                    // The claim marker comes before everything else in the line, because
                    // it rewrites the meaning of the headline above it: "Occupied,
                    // despite the timetable" with a target slot is a *prediction* by
                    // other students, not a present-tense report. Without the label the
                    // two are indistinguishable, and the type model exists precisely so
                    // they never get to be.
                    if (observation.observationType == CommunityObservationType.FUTURE) {
                        "future claim"
                    } else null,
                    // The slot the report is *about*, when it is about one: an observation
                    // carries an hour exactly when it is attached to a class slot, and
                    // saying which is the difference between advice about a moment and
                    // advice about a day. A claim carries its target slot instead.
                    observation.startHour?.let { TimeGrid.slotLabel(it) }
                        ?: observation.targetStartHour?.let { TimeGrid.slotLabel(it) },
                    agoLabel(observation.createdAt, serverNow),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (verifiable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onVerify(false) }) { Text("Not right") }
                    TextButton(onClick = { onVerify(true) }) { Text("Accurate") }
                }
            } else if (own != null) {
                OwnReportActions(
                    report = own,
                    serverNow = serverNow,
                    onUndo = onUndo,
                    onWithdraw = onWithdraw,
                )
            }
        }
    }
}

/**
 * The own-report way back, shared by every card that shows one: Undo inside the fresh
 * window (judged by the server's clock, exactly as My community judges it), Withdraw
 * after it. [CommunityRules.undoDeadline] is the one definition of the window — the
 * server enforces it, this only mirrors it so the button offered is the one that works.
 */
@Composable
private fun OwnReportActions(
    report: CommunityRepository.OutboxUiState,
    serverNow: Instant?,
    onUndo: (String) -> Unit,
    onWithdraw: (String) -> Unit,
) {
    if (report.status != CommunityRepository.OutboxUiState.Status.SENT) return
    val undoable = serverNow != null &&
        CommunityRules.undoDeadline(report.sentAt ?: report.createdAt, serverNow)!! > serverNow
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (undoable) {
            TextButton(onClick = { onUndo(report.idempotencyKey) }) { Text("Undo") }
        } else {
            TextButton(onClick = { onWithdraw(report.idempotencyKey) }) { Text("Withdraw") }
        }
    }
}

/**
 * The polls' clock while their section is on screen: the server's now at the last
 * successful fetch, advanced by the device's own ticks since.
 *
 * Compose recomposes on state, not on time — without the tick, a poll's "closes in
 * 5 min" would say 5 minutes forever and its chips would outlive the close the server
 * already enforced. The anchor is what keeps the *server's* clock authoritative: a
 * device clock wrong by hours still starts from the server's truth and only its
 * (negligible) drift since the fetch is wrong. Falls back to the observations'
 * snapshot clock when there is no polls anchor, which in practice means there are no
 * polls to judge.
 *
 * The tick is one second, not one minute: the fresh-undo window is 30 seconds, and a
 * minute tick would swap a just-asked poll's Undo to Withdraw up to 29 seconds late —
 * the two-device test's "only after going back". Second-resolution recomposition of a
 * few cards is nothing next to the countdown being honest.
 */
@Composable
fun rememberPollClock(state: CommunityUiState): Instant? =
    rememberAnchoredClock(state.pollsServerNow ?: state.serverNow, state.pollsFetchedAt)

/**
 * The observations' ticking clock — [rememberPollClock]'s twin, anchored on the
 * snapshot's server now instead of the polls fetch's.
 *
 * This is what the own-report cards judge the undo window by. A frozen snapshot clock
 * (plain `state.serverNow`) never advances, so Undo never became Withdraw without a
 * refetch happening to land — and a refetch only happens when *someone else* changed
 * something. The ticking clock makes the swap the card's own responsibility, on time,
 * with no network involvement at all.
 */
@Composable
fun rememberSnapshotClock(state: CommunityUiState): Instant? =
    rememberAnchoredClock(state.serverNow, state.snapshotFetchedAt)

/** The one anchored-clock engine both screens' clocks run on. */
@Composable
private fun rememberAnchoredClock(server: Instant?, fetchedAt: Long?): Instant? {
    if (server == null) return null
    if (fetchedAt == null) return server
    var wallNow by remember(server, fetchedAt) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(server, fetchedAt) {
        while (true) {
            delay(1_000)
            wallNow = System.currentTimeMillis()
        }
    }
    return server.plusMillis(wallNow - fetchedAt)
}

/**
 * One poll, in full: the question, the answers, and — after the student has voted —
 * what the room said.
 *
 * Before the vote the options are chips and one tap is the whole interaction, per the
 * plan's "one tap = vote"; counts stay hidden until then so the first voter is not
 * steered by a tally of two. After the vote the counts appear with "your vote" on the
 * chosen option, and the chips stop being buttons — the PK has spoken, one vote per
 * student, and a greyed-out chip pretending otherwise would be a lie.
 *
 * A poll past its close never shows chips: it is presented as closed with whatever
 * counts it had. The list it arrived in should already have hidden it — the folds
 * judge by the anchored clock — but the card re-judges for itself, because the one
 * thing this feature must never do is offer a vote the server has already refused.
 *
 * The creator's own poll ([own] — the caller's [CommunityUiState.ownPoll], which is
 * where the created-at timestamp lives, public reads not carrying it) gets the way
 * back in place, exactly as the observation card does: **Undo** inside the fresh
 * window, **Withdraw** after it. A question just asked deserves the same one-tap
 * regret as a report just filed.
 */
@Composable
fun PollCard(
    poll: CommunityPoll,
    pollClock: Instant?,
    onVote: (pollId: String, optionIndex: Int) -> Unit,
    own: CommunityPoll? = null,
    onUndo: (pollId: String) -> Unit = {},
    onWithdraw: (pollId: String) -> Unit = {},
) {
    // Null clock means "no anchor to judge by" — the fetch never succeeded, so the
    // list is empty in practice; if one ever arrives anyway, the server is left to
    // referee and the ViewModel's guard is what stands in front of it.
    val votable = pollClock == null || CommunityRules.isPollOpen(poll, pollClock)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                text = poll.question,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = listOfNotNull(
                    // The one ownership marker anywhere in the community UI: the
                    // creator's own card, where "no vote chips" would otherwise read
                    // as a bug rather than a rule (the server refuses own-poll votes).
                    if (own != null) "Your poll" else null,
                    "${poll.totalVotes} vote" + if (poll.totalVotes == 1) "" else "s",
                    when {
                        votable -> closesIn(poll.closesAt, pollClock)
                        else -> "Closed"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                // Closed: the answers stand as they fell, and nothing is clickable.
                !votable -> poll.options.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${option.count}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                poll.myVote == null && own == null -> ChipChoice(
                    options = poll.options,
                    selected = null,
                    label = { it.label },
                    onSelect = { onVote(poll.id, it.optionIndex) },
                )

                else -> poll.options.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = listOfNotNull(
                                "${option.count}",
                                if (option.optionIndex == poll.myVote) "your vote" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The creator's way back, in place: Undo inside the fresh window (judged
            // by the anchored polls clock — the same one the close is judged by, and
            // against the creation time only the owner-facing read carries), Withdraw
            // after it. Only while the poll is open: a closed or withdrawn poll is
            // not the server's to give back.
            if (own != null && own.status == com.attendo.core.community.CommunityPollStatus.OPEN) {
                val createdAt = own.createdAt
                val undoable = pollClock != null && createdAt != null &&
                    CommunityRules.undoDeadline(createdAt, pollClock)!! > pollClock
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (undoable) {
                        TextButton(onClick = { onUndo(poll.id) }) { Text("Undo") }
                    } else {
                        TextButton(onClick = { onWithdraw(poll.id) }) { Text("Withdraw") }
                    }
                }
            }
        }
    }
}

/**
 * The one line a refused vote or poll gets. It appears inside the polls section and is
 * cleared by the next refresh — a refusal is a moment, not a state.
 */
@Composable
fun PollErrorLine(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * One of the student's own polls, as "My Polls" shows it: the question, where, its
 * standing (open/closed), the vote count, and — once the server confirms it — taken
 * back. A live poll carries Withdraw; the fresh-undo window for polls is handled at
 * the poll's screen (where the question was just asked), not here, because by the
 * time a student is in Settings the minute has long gone.
 */
@Composable
fun MyPollRow(
    poll: CommunityPoll,
    serverNow: Instant?,
    onUndo: () -> Unit,
    onWithdraw: () -> Unit,
) {
    // The same window the reports row offers: judged against the server's clock,
    // against the poll's creation, and only with a clock to judge by (a null
    // serverNow means no anchor, so no undo — the row would be promising a
    // server-enforced deadline it cannot check).
    val undoable = poll.status == com.attendo.core.community.CommunityPollStatus.OPEN &&
        serverNow != null &&
        poll.createdAt?.let { CommunityRules.undoDeadline(it, serverNow) }?.let { it > serverNow } == true

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = poll.question,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    poll.room?.let { "Room $it" },
                    when (poll.status) {
                        com.attendo.core.community.CommunityPollStatus.OPEN ->
                            if (serverNow != null) closesIn(poll.closesAt, serverNow) else "open"
                        com.attendo.core.community.CommunityPollStatus.WITHDRAWN ->
                            if (poll.withdrawalKind == "undo") "Taken back" else "Withdrawn"
                        else -> "Closed"
                    },
                    "${poll.totalVotes} vote" + if (poll.totalVotes == 1) "" else "s",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (poll.status == com.attendo.core.community.CommunityPollStatus.OPEN) {
            if (undoable) {
                TextButton(onClick = onUndo) { Text("Undo") }
            } else {
                TextButton(onClick = onWithdraw) { Text("Withdraw") }
            }
        }
    }
}

// ---- words -------------------------------------------------------------------

/** The server's refusal of a vote or poll, said as a student would say it. */
internal fun pollFailureLine(code: String): String = when (code) {
    "poll_closed" -> "This poll just closed"
    "invalid_option" -> "That option is gone. Try a refresh."
    "not_found" -> "This poll is gone"
    "rate_limited_short" -> "You're voting a lot right now. Give it a minute."
    "rate_limited_day" -> "You've asked a lot of polls today."
    "restricted" -> "You're temporarily unable to take part"
    "date_out_of_range" -> "Polls can only be about a class today, yesterday or tomorrow"
    // The creator's own poll: the vote chips are hidden for it (myPollIds), so this
    // is the backstop for a chip that raced a refresh — the same words the report
    // side uses for the same situation.
    "own_poll" -> "This is your own poll"
    // A second vote is structurally impossible (PK), but a stale list can still offer
    // the chips after a vote the refresh has not carried yet.
    "already_voted" -> "You already voted in this poll"
    // A dead session, which the next community contact re-mints — hence "reopen", not
    // "never": the retry genuinely works once the app has been opened fresh.
    "not_authenticated" -> "You're signed out. Reopen the app and try again."
    // The identity moved away and was tombstoned; same pointer as the reports' line.
    "identity_superseded" -> "Your identity has moved to another phone. Check Reporting identity in Settings."
    // A pending replacement claim holds this phone's community actions for its
    // 24-hour window — not a verdict on the poll, a pause on the phone.
    "identity_frozen" -> "Your identity is moving phones. Check Reporting identity in Settings."
    else -> "Couldn't do that ($code)"
}

/**
 * The server's refusal of a verdict, in the buttons' own words — the third of the
 * three failure lines (polls, reports, verdicts). A verdict is the one community
 * write whose result the UI used to throw away entirely, so a tap the server had
 * already promised to refuse did nothing at all: this line is what the two-device
 * test's "tapping does not do anything" was missing.
 */
internal fun verdictFailureLine(code: String): String = when (code) {
    // The identity now owns this report (an import moved it here), but the local
    // outbox has not been told — the buttons race the rehydration, and the refusal
    // is the server's version of "this is yours".
    "own_report" -> "This is your own report"
    // A second verdict is structurally impossible, but a stale snapshot can still
    // offer the buttons for one already given.
    "already_verified" -> "You already marked this report"
    "rate_limited_short" -> "You're marking a lot right now. Give it a minute."
    "not_found" -> "This report is gone"
    "restricted" -> "You're temporarily unable to take part"
    "not_authenticated" -> "You're signed out. Reopen the app and try again."
    "identity_superseded" -> "Your identity has moved to another phone. Check Reporting identity in Settings."
    "identity_frozen" -> "Your identity is moving phones. Check Reporting identity in Settings."
    else -> "Couldn't do that ($code)"
}

/**
 * The server's refusal of a report, said the same way — the twin of [pollFailureLine] for
 * the outbox rows "Your reports" shows. A raw code ("Declined — date_out_of_range") is
 * honest but useless at the moment it is read; an unknown code from a newer server still
 * shows as itself, exactly as [pollFailureLine] does.
 */
internal fun reportFailureLine(code: String): String = when (code) {
    "date_out_of_range" -> "That day is too far off to report about"
    "duplicate_report" -> "You already reported this. It only counts once."
    "rate_limited_short" -> "You're reporting a lot right now. Give it a minute."
    "rate_limited_day" -> "You've reported a lot today."
    "restricted" -> "You're temporarily unable to report"
    "invalid_kind" -> "That report type isn't recognised"
    "invalid_payload" -> "That report was missing something"
    "invalid_start_hour" -> "That hour isn't in the teaching day"
    // The slot a claim named has arrived: the future it claimed about is now the present,
    // and the present has its own report kind — the refusal is the server redirecting
    // the student to it, so the line does too.
    "invalid_target" -> "That hour has already started. Report what you see instead."
    "note_too_long" -> "The note was too long"
    "room_too_long" -> "That room name is too long"
    // The session died; the next drain re-mints it before sending (see CommunitySync),
    // so the Retry on a declined row genuinely works after a reopen.
    "not_authenticated" -> "You're signed out. Reopen the app and try again."
    // The identity moved to another phone and was tombstoned there; no retry will
    // ever work, so the line points at the only two things that can: transfer it
    // back, or start fresh.
    "identity_superseded" -> "Your identity has moved to another phone. Check Reporting identity in Settings."
    // A pending replacement claim holds this phone's writes for its 24-hour window:
    // the report is queued and will send when the move completes or is cancelled —
    // not a verdict on the report, a pause on the phone.
    "identity_frozen" -> "Your identity is moving phones. Check Reporting identity in Settings. " +
        "This report is kept and will send when the move is decided."
    else -> code
}

/** "closes in 2 hr", judged by the server's clock — same rule as every other age here. */
internal fun closesIn(closesAt: Instant, serverNow: Instant?): String {
    val now = serverNow ?: return ""
    val minutes = java.time.Duration.between(now, closesAt).toMinutes()
    return when {
        minutes <= 0 -> "closing"
        minutes < 60 -> "closes in $minutes min"
        minutes < 24 * 60 -> "closes in ${minutes / 60} hr"
        else -> "closes in ${minutes / (24 * 60)} d"
    }
}

/**
 * The one line every community section shows when its data cannot be trusted as current.
 *
 * Supabase being down never removes a section — the cache keeps rendering, and this row
 * says which of two honest things is true: the data is old, or there is no data. Both
 * carry the same way out, a retry, because the failure is a network state, not a verdict
 * on the feature. Fresh data renders no row at all: silence is the signal everything is
 * as it should be.
 */
@Composable
fun CommunityStatusRow(
    status: CommunityUiState.Status,
    onRetry: () -> Unit,
) {
    if (status == CommunityUiState.Status.FRESH) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (status) {
                CommunityUiState.Status.STALE ->
                    "Reports may be out of date"
                CommunityUiState.Status.UNAVAILABLE ->
                    "Community reports unavailable"
                CommunityUiState.Status.FRESH -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/**
 * One of the student's own reports, as "My Reports" shows it: what, where, and which
 * of the states it is in. Failed and rejected rows carry their way out — "try again"
 * and "remove" — because a submission that silently vanished is the one failure this
 * feature is not allowed to have.
 *
 * A sent row inside the fresh window carries **Undo** — the penalty-free way back —
 * and after the window **Withdraw**, behind a confirmation that says only what the
 * product wants said: that withdrawal decreases reputation. The amount, the window,
 * the reputation math: none of it is in the words, on purpose. The buttons call the
 * ViewModel's undo/withdraw; the server is the one that decides what actually happens.
 */
@Composable
fun MyReportRow(
    report: CommunityRepository.OutboxUiState,
    serverNow: Instant?,
    onForget: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onWithdraw: () -> Unit,
) {
    // The undo window, judged against the server's clock (the same one every expiry
    // here is judged by) against the row's send time. Null clock means no anchor —
    // no undo offered, because advertising a server-enforced window without a clock
    // to judge it by would be promising something the client cannot check.
    val undoable = report.status == CommunityRepository.OutboxUiState.Status.SENT &&
        serverNow != null &&
        CommunityRules.undoDeadline(report.sentAt ?: report.createdAt, serverNow)!! > serverNow

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(
                    report.kind.sheetLabel(),
                    report.room?.let { "Room $it" },
                    // A claim row names its slot, so "your reports" answers the question
                    // a claim raises — *which* later hour did I expect something about?
                    if (report.observationType == CommunityObservationType.FUTURE) {
                        report.targetStartHour?.let { "expected ${TimeGrid.slotLabel(it)}" }
                    } else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = outboxStatusLine(report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (report.status) {
            // Frozen is a state, not a failure — but the same two ways out belong here:
            // Retry once the move has finished (the claim resolving on the other phone
            // is not something this screen hears about on its own), Remove to give up.
            CommunityRepository.OutboxUiState.Status.FAILED,
            CommunityRepository.OutboxUiState.Status.REJECTED,
            CommunityRepository.OutboxUiState.Status.FROZEN,
            -> {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onForget) { Text("Remove") }
            }

            CommunityRepository.OutboxUiState.Status.SENT ->
                if (undoable) {
                    TextButton(onClick = onUndo) { Text("Undo") }
                } else {
                    TextButton(onClick = onWithdraw) { Text("Withdraw") }
                }

            else -> Unit
        }
    }
}

/**
 * The one line every outbox row's state renders as — shared by "My Reports" and the room
 * page's pending cards, so the two surfaces can never disagree about what a state is
 * called. Frozen says the identity is moving, not that anything failed; a refusal keeps
 * the code's own friendly line, never a raw code alone.
 */
internal fun outboxStatusLine(report: CommunityRepository.OutboxUiState): String =
    when (report.status) {
        CommunityRepository.OutboxUiState.Status.PENDING -> "Waiting to send"
        CommunityRepository.OutboxUiState.Status.SENT -> "Sent"
        // The identity-move state: the server is holding this report until the move
        // resolves, and no amount of waiting on the network changes that — so the
        // sentence says where to look, not what failed.
        CommunityRepository.OutboxUiState.Status.FROZEN ->
            "Your identity is moving phones. It sends when the move finishes."
        CommunityRepository.OutboxUiState.Status.FAILED ->
            "Couldn't send. Check your connection."
        // A refusal carries a code the server chose; the line is ours.
        CommunityRepository.OutboxUiState.Status.REJECTED ->
            "Declined. " + (report.failureCode?.let(::reportFailureLine) ?: "the server said no")
        CommunityRepository.OutboxUiState.Status.WITHDRAWN ->
            if (report.withdrawalKind == "undo") "Taken back" else "Withdrawn"
    }

/**
 * One of the student's own reports about this room that the server has not accepted yet —
 * the room page's twin of [MyReportRow]. A report that never appears on the page it was
 * filed from reads as if it vanished, so the queued row stands in for the card it will
 * become: same words for its state, same ways out for the states that offer them.
 */
@Composable
fun PendingReportCard(
    report: CommunityRepository.OutboxUiState,
    onRetry: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "You reported: " + report.kind.sheetLabel() +
                    // A claim row names its slot, exactly as "My Reports" does — the
                    // hour is the whole point of a claim, and this card is its stand-in.
                    (if (report.observationType == CommunityObservationType.FUTURE) {
                        report.targetStartHour?.let { " expected ${TimeGrid.slotLabel(it)}" } ?: ""
                    } else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = outboxStatusLine(report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The same two ways out "My Reports" gives: a failed or frozen row can be retried
        // now or given up on. A row still waiting needs neither — nothing has gone wrong.
        when (report.status) {
            CommunityRepository.OutboxUiState.Status.FAILED,
            CommunityRepository.OutboxUiState.Status.FROZEN,
            -> {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onForget) { Text("Remove") }
            }

            else -> Unit
        }
    }
}

/**
 * The one sentence a card leads with: what was reported, with the delta folded in.
 *
 * Deliberately written as "students report …" / "moved to 211" rather than the enum's
 * name — the enum is the wire contract, this is what a student reads.
 *
 * A claim gets its own sentences, in its own tense: "students expect it occupied 2–3 PM"
 * can never be misread as a present-tense report the way "Occupied, despite the timetable"
 * alone could — and the slot it names is the whole point of the claim, so it leads.
 */
fun CommunityObservation.headline(): String {
    // The hour in a delta is the grid's own label ("9–10 AM") — a bare number with a
    // guessed meridiem once read "9 PM" for a 9 AM change.
    val delta = listOfNotNull(
        payload.newRoom?.let { "room $it" },
        payload.newStartHour?.let { TimeGrid.slotLabel(it) },
        payload.newDate?.let { "to $it" },
    ).joinToString(", ")

    if (observationType == CommunityObservationType.FUTURE) {
        val slot = targetStartHour?.let { TimeGrid.slotLabel(it) }?.let { " $it" } ?: ""
        return when (kind) {
            CommunityReportKind.ROOM_FREE_DESPITE_BUSY -> "Students expect it free$slot"
            CommunityReportKind.ROOM_OTHER -> note ?: "Students expect something in this room$slot"
            // The occupied-leaning room kinds, and anything else a claim may carry
            // (including a kind a newer server invented): the occupied phrasing is the
            // honest generic, because the busy kinds are what claims are filed about.
            else -> "Students expect it occupied$slot"
        }
    }

    return when (kind) {
        CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE -> "Occupied, despite the timetable"
        CommunityReportKind.ROOM_FREE_DESPITE_BUSY -> "Free, despite the timetable"
        CommunityReportKind.EXTRA_CLASS_IN_ROOM -> "An extra class is running in here"
        CommunityReportKind.CLASS_MOVED_HERE -> "A class moved into this room"
        CommunityReportKind.ROOM_OTHER -> note ?: "Something unusual in this room"

        CommunityReportKind.CLASS_CANCELLED -> "Students report this class cancelled"
        CommunityReportKind.CLASS_MOVED -> "Students report this class moved" +
            if (delta.isEmpty()) "" else " ($delta)"
        CommunityReportKind.CLASS_ROOM_CHANGED -> "Students report a different room" +
            if (delta.isEmpty()) "" else ": $delta"
        CommunityReportKind.CLASS_TIME_CHANGED -> "Students report a different hour" +
            if (delta.isEmpty()) "" else ": $delta"
        CommunityReportKind.EXTRA_CLASS -> "Students report an extra class at this slot"
        CommunityReportKind.CLASS_MISSING_FROM_TIMETABLE -> "Students report a class the timetable doesn't have"
        CommunityReportKind.CLASS_OTHER -> note ?: "Students report something about this class"

        CommunityReportKind.UNRECOGNIZED -> "Reported by other students"
    }
}

private fun statusLabel(status: CommunityObservationStatus): String = when (status) {
    CommunityObservationStatus.REPORTED -> "reported"
    CommunityObservationStatus.CORROBORATED -> "corroborated"
    CommunityObservationStatus.CONFIRMED -> "confirmed"
    CommunityObservationStatus.DISPUTED -> "disputed"
    // A withdrawn row never reaches a stranger's card (the server's read path filters
    // it before the client ever sees it); the branch exists so the vocabulary is total.
    CommunityObservationStatus.REJECTED,
    CommunityObservationStatus.EXPIRED,
    CommunityObservationStatus.WITHDRAWN,
    CommunityObservationStatus.UNRECOGNIZED,
        -> "no longer active"
}

/** "4 min ago", judged by the server's clock — the device's may be wrong by hours. */
internal fun agoLabel(at: Instant, serverNow: Instant?): String {
    val now = serverNow ?: return ""
    val minutes = java.time.Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} hr ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}
