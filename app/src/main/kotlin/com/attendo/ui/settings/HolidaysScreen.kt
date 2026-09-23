package com.attendo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.ui.components.AttendoDatePickerDialog
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.fullLabel
import com.attendo.ui.longLabel

/**
 * The term's holidays, on their own screen.
 *
 * Settings shows the count and the way in; the list, the picker and the removals live here.
 * A holiday is a fact about a date — a class held on one is cancelled rather than missed, so
 * it counts for neither side of the percentage — which makes this a list worth being able to
 * read in full rather than a row of chips squeezed under a heading.
 *
 * Adding and removing go through [SettingsViewModel], the same way they always have, so each
 * one still corrects the sessions table: declaring a holiday cancels that day's unmarked
 * classes, and removing it puts them back. The dates themselves are the account's existing
 * `holidays` field and sync exactly as before.
 */
@Composable
fun HolidaysScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(
            title = "Holidays",
            onBack = onBack,
            actions = {
                TextButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add")
                }
            },
        )

        if (!state.loaded) {
            LoadingPane()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = "A class on a day you mark a holiday is cancelled rather than " +
                        "missed, so it counts for neither side of your percentage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (state.holidays.isEmpty()) {
                item {
                    Text(
                        text = "None yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            } else {
                item { SectionLabel(state.holidaysSummary) }
                items(
                    count = state.holidays.size,
                    key = { index -> state.holidays[index].toString() },
                ) { index ->
                    val date = state.holidays[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = date.longLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = date.fullLabel(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.removeHoliday(date) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${date.fullLabel()}",
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (picking) {
        AttendoDatePickerDialog(
            initial = state.today.coerceIn(state.calendar.termStart, state.calendar.termEnd),
            earliest = state.calendar.termStart,
            latest = state.calendar.termEnd,
            onPick = viewModel::addHoliday,
            onDismiss = { picking = false },
        )
    }
}
