package com.attendo.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel

/**
 * The student's own community activity: every report they filed and every poll they
 * asked, with the actions that belong to an owner — undo, withdraw, retry, remove.
 *
 * This lived inline in Settings until it outgrew it: two sections of stateful rows in
 * a screen whose job is term shape and preferences. A dedicated screen gives the rows
 * room to be read, and Settings back its shape. The ViewModel is the app-level shared
 * one: the screen refreshes on entry, rides every Realtime nudge, and every action
 * here goes through the repository like Settings' did.
 */
@Composable
fun MyCommunityScreen(
    onBack: () -> Unit,
    // The app-level shared instance: this screen refreshes on entry and rides the
    // same Realtime nudge every other community surface does.
    communityViewModel: CommunityViewModel,
) {
    val community by communityViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { communityViewModel.onShown() }

    // The ticking snapshot clock — hoisted out of the LazyColumn (composable scope
    // only above it), and shared by both lists below: it is what makes a sent row's
    // Undo become Withdraw thirty seconds after *sending*, not the next time some
    // other student's activity happens to trigger a refetch.
    val snapshotClock = rememberSnapshotClock(community)

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = "My community",
            subtitle = "Reports you filed and polls you asked",
            onBack = onBack,
        )

        if (!community.loaded) {
            LoadingPane()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The honest line when a fetch failed: what is on screen may be old,
                // and Retry is the way out. Silence would read as fresh — the exact
                // confusion that hid stale polls here during production verification.
                item {
                    CommunityStatusRow(
                        status = community.status,
                        onRetry = communityViewModel::refresh,
                    )
                }

                item { SectionLabel("My reports") }
                item {
                    Column {
                        when {
                            !community.available -> Text(
                                text = "Not available on this install. Room and class " +
                                    "reports need the community service, which this build " +
                                    "is not configured for.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            community.myReports.isEmpty() -> Text(
                                text = "None yet. When you report a room from the Rooms " +
                                    "screen, it is filed anonymously under a random ID " +
                                    "created on this phone. No name, no email, nothing " +
                                    "that identifies you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            else -> {
                                community.myReports.forEach { report ->
                                    MyReportRow(
                                        report = report,
                                        serverNow = snapshotClock,
                                        onForget = { communityViewModel.forget(report.idempotencyKey) },
                                        onRetry = { communityViewModel.retryNow(report.idempotencyKey) },
                                        onUndo = { communityViewModel.undoReport(report.idempotencyKey) },
                                        onWithdraw = {
                                            communityViewModel.withdrawReport(report.idempotencyKey)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item { HorizontalDivider(Modifier.fillMaxWidth()) }

                item { SectionLabel("My polls") }
                item {
                    Column {
                        // A local, so the null/empty branches below can smart-cast —
                        // the state property is delegated and cannot be cast itself.
                        val myPolls = community.myPolls
                        when {
                            !community.available -> Text(
                                text = "Not available on this install.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            myPolls == null -> if (community.myPollsSettled) {
                                // The fetch answered and failed, with nothing to fall
                                // back on — the honest line, with the same way out the
                                // status row gives the reports.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Couldn't load your polls. Check your connection.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = communityViewModel::refresh) {
                                        Text("Retry")
                                    }
                                }
                            } else {
                                // The first fetch is still out. My Reports has already
                                // painted from the outbox cache; saying "check your
                                // connection" here would call a running request a
                                // failure — the flash of a wrong error this section
                                // used to show on every first visit.
                            }

                            myPolls.isEmpty() -> Text(
                                text = "None yet. Polls you ask from a room's screen show " +
                                    "up here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            else -> myPolls.forEach { poll ->
                                MyPollRow(
                                    poll = poll,
                                    serverNow = snapshotClock,
                                    onUndo = { communityViewModel.undoPoll(poll.id) },
                                    onWithdraw = { communityViewModel.withdrawPoll(poll.id) },
                                )
                            }
                        }
                    }
                }

                item { HorizontalDivider(Modifier.fillMaxWidth()) }

                // The privacy line, once, at the bottom — it said the same thing in both
                // sections when they lived in Settings; here it belongs to the whole
                // screen, because it is true of both lists and of nothing else.
                item {
                    Text(
                        text = "Everything here is filed anonymously under a random ID. " +
                            "No name, no email, nothing that identifies you. That ID is " +
                            "never included in any backup. It reaches another phone only " +
                            "when you deliberately move it (Reporting identity, in " +
                            "Settings) or sign in with GitHub, which restores it " +
                            "wherever you sign in. Clear all data ends it on this " +
                            "phone; an account's identity stays with the account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
