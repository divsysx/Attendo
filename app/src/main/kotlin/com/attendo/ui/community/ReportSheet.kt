package com.attendo.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.CommunityRules
import com.attendo.core.community.ReportPayload
import com.attendo.core.model.TimeGrid
import com.attendo.ui.components.ChipChoice

/**
 * Everything the sheet knows about what is being reported on, filled in by the screen
 * that opened it. The student supplies only the delta; the context rides along invisibly.
 */
data class ReportContext(
    val room: String? = null,
    val section: String? = null,
    val subject: String? = null,
    val classDate: java.time.LocalDate? = null,
    val startHour: Int? = null,
)

/**
 * Which kinds the sheet offers. Room kinds only: reports are about rooms (and their
 * later-today claims), and the class-report kinds — which the wire enum still carries
 * for rows filed before this build — have no composer anymore. Nobody can file a new
 * class report from any screen; [CommunityReportKind.isClassKind] stays defined for
 * the data layer's read-side rules and the server's own contract.
 */
private fun kindsFor(context: ReportContext): List<CommunityReportKind> = buildList {
    if (context.room != null) {
        addAll(ROOM_KIND_ORDER)
    }
}

private val ROOM_KIND_ORDER = listOf(
    CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE,
    CommunityReportKind.ROOM_FREE_DESPITE_BUSY,
    CommunityReportKind.EXTRA_CLASS_IN_ROOM,
    CommunityReportKind.CLASS_MOVED_HERE,
    CommunityReportKind.ROOM_OTHER,
)

/** The one line a student reads when choosing what they are reporting. */
fun CommunityReportKind.sheetLabel(): String = when (this) {
    CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE -> "It's occupied"
    CommunityReportKind.ROOM_FREE_DESPITE_BUSY -> "It's free"
    CommunityReportKind.EXTRA_CLASS_IN_ROOM -> "Extra class in here"
    CommunityReportKind.CLASS_MOVED_HERE -> "A class moved in here"
    CommunityReportKind.ROOM_OTHER -> "Something else about this room"
    // The class kinds have no composer anymore, but My Reports still renders rows
    // filed before this build, and its label comes from here.
    CommunityReportKind.CLASS_CANCELLED -> "Class is cancelled"
    CommunityReportKind.CLASS_MOVED -> "Class moved away"
    CommunityReportKind.CLASS_ROOM_CHANGED -> "Different room"
    CommunityReportKind.CLASS_TIME_CHANGED -> "Different hour"
    CommunityReportKind.EXTRA_CLASS -> "Extra class"
    CommunityReportKind.CLASS_MISSING_FROM_TIMETABLE -> "Not on the timetable"
    CommunityReportKind.CLASS_OTHER -> "Something else about this class"
    CommunityReportKind.UNRECOGNIZED -> "Report"
}

/**
 * The report composer — one sheet, two decisions.
 *
 * The target is the plan's "five to ten seconds": a kind chip, the single field that kind
 * needs (if any), submit. The note is optional and collapsed in spirit — a single line,
 * never required. Everything else (room, date, hour, section, subject) came in with the
 * [context] and is stated back once, so the student can see what they are attaching the
 * report to without being asked for any of it again.
 *
 * A room-only context can also be filed as a **claim about a later slot today** ("will be
 * occupied at 2") — the "Now / Later today" choice. The two are never confused on screen:
 * a present report says "right now", a claim names its slot ("expected 2–3 PM") and asks
 * for it before Send will press. Claims are offered only when a later slot still exists
 * this morning; once the teaching day is underway past 17:00 there is nothing later to
 * claim about, and the choice quietly disappears.
 *
 * [onSubmit] runs the local twin of the server's validation and returns the reason when
 * it fails (shown inline, the sheet stays open); null means the report is queued and the
 * sheet closes. The [onSubmit]'s hour parameter is the slot the report attaches to — the
 * context's hour when it came with one, otherwise the one the student picked here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    context: ReportContext,
    onSubmit: (
        kind: CommunityReportKind,
        payload: ReportPayload,
        note: String?,
        startHour: Int?,
        observationType: CommunityObservationType,
        targetStartHour: Int?,
    ) -> String?,
    onDismiss: () -> Unit,
) {
    val kinds = kindsFor(context)
    var kind by rememberSaveable {
        mutableStateOf(kinds.firstOrNull() ?: CommunityReportKind.ROOM_OTHER)
    }
    var note by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // The claim half of the sheet. -1 is "not picked" because a nullable Int does not
    // survive the Bundle; laterToday is the Now/Later choice; claimHours is computed once
    // per composition — the sheet lives seconds, and the slot that was later when it
    // opened is later when it closes.
    var laterToday by rememberSaveable { mutableStateOf(false) }
    var claimHour by rememberSaveable { mutableStateOf(-1) }
    val claimHours = remember {
        val nowHour = java.time.LocalTime.now().hour
        TimeGrid.startHours.filter { it > nowHour }
    }
    // Claims are offered only where they mean something: a room the student is looking
    // at (no class date in the context), with at least one slot of the day still ahead.
    val claimsAvailable = context.room != null && context.classDate == null && claimHours.isNotEmpty()
    // Set the moment a submit is accepted, and never cleared: the report is queued, the
    // sheet is closing, and a second tap on the way down must not queue a twin (the
    // server's dedup window would refuse it, and "Declined — duplicate" would sit in
    // Your Reports for a report that did go out). A validation failure clears it again.
    //
    // Deliberately `remember`, not `rememberSaveable`: the guard is transient. If the
    // process dies around the tap, restoring `submitted = true` would freeze a restored
    // sheet whose report may never have been queued — and the duplicate the guard exists
    // for is the second tap in *this* composition, which `remember` covers fully. A
    // report that did reach the outbox is already durable (Room) and idempotent
    // server-side; a cross-process double-send is the server's dedup window's job, by
    // design.
    var submitted by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Report it", style = MaterialTheme.typography.titleLarge)
            Text(
                text = contextLine(
                    context = context,
                    claimHour = claimHour.takeIf { laterToday && it >= 0 },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column {
                Text("What's going on?", style = MaterialTheme.typography.labelMedium)
                ChipChoice(
                    options = kinds,
                    selected = kind,
                    label = { it.sheetLabel() },
                    onSelect = {
                        kind = it
                        error = null
                    },
                )
            }

            // The claim choice: what the student sees now, or what they expect at a later
            // slot today. Absent everywhere a claim cannot be filed — class contexts
            // (those are historical by construction) and late evenings (nothing ahead).
            if (claimsAvailable) {
                Column {
                    Text("When?", style = MaterialTheme.typography.labelMedium)
                    ChipChoice(
                        options = listOf(false, true),
                        selected = laterToday,
                        label = { if (it) "Later today" else "Now" },
                        onSelect = {
                            laterToday = it
                            error = null
                        },
                    )
                }
            }

            // A claim names its slot before Send will press: "expected 2–3 PM" is stated
            // back in the context line, and until an hour is picked the button is off —
            // a claim without a target is not a claim, and the server would only refuse
            // it after the round-trip this row saves.
            if (claimsAvailable && laterToday) {
                Column {
                    Text("Which hour?", style = MaterialTheme.typography.labelMedium)
                    ChipChoice(
                        options = claimHours,
                        selected = claimHour.takeIf { it >= 0 && it in claimHours },
                        label = { TimeGrid.slotLabel(it) },
                        onSelect = {
                            claimHour = it
                            error = null
                        },
                    )
                    Text(
                        text = "A claim is what you expect, not the timetable. Others " +
                            "see it as community-reported until the hour comes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // No kind needs a field anymore: the room kinds are one-tap choices, and
            // the class kinds that wanted a room or an hour have no composer. The
            // sheet stays a kind chip, an optional note, and Send.

            OutlinedTextField(
                value = note,
                onValueChange = {
                    note = it.take(CommunityRules.NOTE_MAX_CHARS)
                    error = null
                },
                label = { Text("Note (optional)") },
                supportingText = {
                    Text("${note.length} / ${CommunityRules.NOTE_MAX_CHARS}")
                },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Full-width and stacked, the Material bottom-sheet action pattern: the primary
            // action gets the whole width (a thumb-sized target however the sheet is held),
            // and Cancel sits under it as the quiet alternative to the scrim tap or swipe.
            Button(
                enabled = !submitted && (!laterToday || claimHour in claimHours),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // The disabled state above needs a recomposition to take effect,
                    // and two clicks dispatched inside one frame can both land before
                    // that happens — the handler consults the guard itself, so the
                    // second is a no-op whatever the frame did.
                    if (!submitted) {
                        submitted = true
                        // A claim is a *picked* claim: the toggle alone is not enough, the
                        // slot has to be one of today's still-ahead hours. `claimHours` is
                        // the same list the chips offer, so this is exactly "an hour was
                        // chosen" — and stays honest even if the day advances while the
                        // sheet is open.
                        val isClaim = claimsAvailable && laterToday && claimHour in claimHours
                        // A room report carries no payload fields: the kinds that wanted
                        // a new room or a new hour were class kinds, and those have no
                        // composer anymore. The payload stays empty by construction.
                        val payload = ReportPayload()
                        val result = onSubmit(
                            kind,
                            payload,
                            note.takeIf { it.isNotBlank() },
                            context.startHour,
                            if (isClaim) CommunityObservationType.FUTURE else CommunityObservationType.PRESENT,
                            if (isClaim) claimHour else null,
                        )
                        if (result == null) onDismiss() else {
                            submitted = false
                            error = result
                        }
                    }
                },
            ) {
                Text("Send")
            }
            TextButton(
                onClick = onDismiss,
                enabled = !submitted,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }

            Text(
                text = "Filed anonymously. Goes out when the phone is online; nothing " +
                    "about you is attached.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Room 203 · right now" — what is being reported on, said back once. Shared with the
 * poll sheet.
 *
 * The line answers the question the sheet's brevity raises: *what time is this about?* A
 * room context carries no date because the report means the present moment — so the line
 * says "right now" rather than leaving the tense unsaid.
 *
 * A claim changes the tense: [claimHour] is the slot the claim targets, and
 * "expected 2–3 PM" says two things "right now" cannot — that the statement is about the
 * future, and that the *student expected it* rather than saw it. The date it is about is
 * always today (the server enforces that too); the label states the slot, not the date,
 * because "later today" is the only future a claim may carry.
 */
internal fun contextLine(
    context: ReportContext,
    claimHour: Int? = null,
): String = buildList {
    context.room?.let { add("Room $it") }
    context.subject?.let { add(it) }
    context.section?.let { add("section $it") }
    when {
        // A room-only context is a present observation: no date comes in because the
        // moment the report means is the moment it is filed.
        claimHour != null -> add("expected ${TimeGrid.slotLabel(claimHour)}")
        context.room != null -> add("right now")
    }
}.joinToString(" · ")
