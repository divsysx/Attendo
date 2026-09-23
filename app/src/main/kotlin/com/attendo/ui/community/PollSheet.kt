package com.attendo.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attendo.core.community.CommunityRules

/**
 * The poll composer — the question, two to four answers, nothing else.
 *
 * A poll is the report's younger sibling: the context came in with [context] exactly as
 * it does for a report, the student supplies only what no screen already knows (the
 * question and its answers), and everything the server will decide — closes_at, the
 * option order, the counts — is left to the server.
 *
 * [onSubmit] is asynchronous where the report sheet's is not: a poll is not queued to
 * the outbox (it is a question about *now*), so the sheet waits for the server's answer
 * through [onSubmit]'s callback — null closes the sheet, anything else shows inline and
 * keeps it open. The Ask button is disabled while a request is in flight, which is the
 * one guard against a double-tap asking twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollSheet(
    context: ReportContext,
    onSubmit: (
        question: String,
        options: List<String>,
        startHour: Int?,
        onResult: (String?) -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    // Held as an immutable List and replaced whole on every edit — a mutable collection
    // inside a MutableState invites in-place mutation, which no recomposition would ever
    // notice. The saver round-trips through a fresh list for the same reason.
    var options by rememberSaveable(
        stateSaver = listSaver<List<String>, String>(
            save = { it.toList() },
            restore = { it.toList() },
        ),
    ) { mutableStateOf(listOf("", "")) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // Transient by the same rule as the report sheet's `submitted`: restoring a persisted
    // in-flight flag after process death would freeze a sheet whose request died with the
    // process, and the double-tap the flag exists for is inside one composition, which
    // `remember` covers fully. A poll that did go out is the server's to dedup.
    var sending by remember { mutableStateOf(false) }

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
            Text(
                text = "Ask the room",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = contextLine(context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = question,
                onValueChange = {
                    question = it.take(CommunityRules.QUESTION_MAX_CHARS)
                    error = null
                },
                label = { Text("Question") },
                supportingText = {
                    Text("${question.length} / ${CommunityRules.QUESTION_MAX_CHARS}")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // The answers, two to choose from, up to four. Editing the list is part of
            // asking: an empty option is simply not offered, exactly as a blank answer
            // is not one.
            options.forEachIndexed { index, option ->
                Row {
                    OutlinedTextField(
                        value = option,
                        onValueChange = { edit ->
                            options = options.toMutableList().also {
                                it[index] = edit.take(CommunityRules.OPTION_LABEL_MAX_CHARS)
                            }
                            error = null
                        },
                        label = { Text("Option ${index + 1}") },
                        singleLine = true,
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                    )
                    if (options.size > CommunityRules.MIN_OPTIONS) {
                        IconButton(
                            onClick = {
                                options = options.toMutableList().also { it.removeAt(index) }
                                error = null
                            },
                            enabled = !sending,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove option ${index + 1}",
                            )
                        }
                    }
                }
            }
            if (options.size < CommunityRules.MAX_OPTIONS) {
                TextButton(
                    onClick = { options = options.toMutableList().also { it.add("") } },
                    enabled = !sending,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Add option")
                }
            }

            // A poll is a question about a room, and the room came in with the context —
            // there is no hour to pick and no date to name.

            error?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // The same stacked, full-width actions as the report sheet: one primary with a
            // thumb-sized target, and Cancel as the quiet alternative.
            Button(
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // The handler consults the guard itself — recomposition's disable
                    // can lag a second click dispatched in the same frame.
                    if (!sending) {
                        sending = true
                        onSubmit(
                            question.trim(),
                            options.map { it.trim() }.filter { it.isNotEmpty() },
                            context.startHour,
                        ) { failure ->
                            if (failure == null) onDismiss() else {
                                sending = false
                                error = failure
                            }
                        }
                    }
                },
            ) {
                Text(if (sending) "Asking…" else "Ask")
            }
            TextButton(
                onClick = onDismiss,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }

            Text(
                text = "Asked anonymously. Polls close with the class day. " +
                    "Yesterday's question is nobody's.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
