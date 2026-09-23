package com.attendo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.dayMonth

/**
 * Which Saturdays in the term are teaching days, on their own screen.
 *
 * The college runs Monday–Friday, so Saturday is a holiday unless a date is ticked here —
 * the inversion is deliberate, and the sentence at the top of the screen says so, because a
 * list of empty boxes under the heading "Working Saturdays" otherwise reads as a list of
 * Saturdays that are working.
 *
 * Nine sections are not offered this screen at all: the timetable teaches them on Saturday
 * every week, so their Saturdays come from the weekly pattern rather than from a list. See
 * [com.attendo.core.model.RecurringSaturdays]. The row is hidden in Settings, and a deep
 * link here would find nothing to configure — but the screen does not have to defend itself,
 * because nothing navigates to it for those sections.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkingSaturdaysScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(title = "Working Saturdays", onBack = onBack)

        if (!state.loaded) {
            LoadingPane()
            return@Column
        }

        val calendar = state.calendar
        val saturdays = remember(calendar.termStart, calendar.termEnd) {
            calendar.saturdaysInTerm()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Saturday is a holiday unless you say otherwise, so a Saturday " +
                        "slot only generates classes on the dates ticked here. A class on a " +
                        "Saturday you untick is cancelled rather than missed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionLabel(state.workingSaturdaysSummary) }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    saturdays.forEach { date ->
                        val isWorking = date in calendar.workingSaturdays
                        FilterChip(
                            selected = isWorking,
                            onClick = {
                                if (isWorking) {
                                    viewModel.removeWorkingSaturday(date)
                                } else {
                                    viewModel.addWorkingSaturday(date)
                                }
                            },
                            label = { Text(date.dayMonth()) },
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Ticking a Saturday generates that day's classes straight away; " +
                        "unticking one takes back the classes nobody has marked yet, and " +
                        "leaves everything you have already recorded alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
