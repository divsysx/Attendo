package com.attendo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.attendo.core.model.Percent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * A row of single-choice chips that wraps.
 *
 * Used everywhere a small closed set has to be picked — a start hour, a length in hours,
 * a lecture/practical/tutorial. A dropdown would hide the options; there are never more
 * than a dozen and they all fit on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoice(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled(option),
                label = { Text(label(option)) },
            )
        }
    }
}

/**
 * The whole-percentage control: a field to type one, a slider to drag one.
 *
 * This is the only way a target is set anywhere in the app — the Settings default, the
 * Attendance Targets screen, a single course — because a student who has learnt what it
 * looks like on one screen should not have to learn a second shape for the same decision.
 *
 * The stored value and the value these controls offer are deliberately not the same thing.
 * A target of 72.5% can only have arrived from an earlier build or a restored backup, and
 * rounding it to 73 so the slider would agree would be editing the student's data to suit
 * the widget. So the controls sit at the nearest whole percentage, the exact figure is
 * printed underneath when the two disagree, and **nothing is written until the student
 * actually chooses a value** — opening a screen must never change what it displays.
 */
@Composable
fun PercentPicker(
    value: Percent,
    onSelect: (Percent) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isWhole = value.basisPoints % Percent.BP_PER_PERCENT == 0
    val atWhole = value.asDouble.roundToInt().coerceIn(0, Percent.MAX_WHOLE_PERCENT)

    // The field's text, owned here so a keystroke is never answered by a re-emission of the
    // value it just produced. Re-seeded only when the value changes from *outside* this
    // control — a bulk apply, or a different course — which is what `lastEmitted` tracks.
    var text by rememberSaveable { mutableStateOf(atWhole.toString()) }
    var lastEmitted by rememberSaveable { mutableStateOf(atWhole) }
    if (atWhole != lastEmitted) {
        lastEmitted = atWhole
        text = atWhole.toString()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    // Whole numbers only. Rejecting rather than stripping keeps the field
                    // showing exactly what was typed: someone reaching for "72.5" sees the
                    // "." simply not appear, which is a clearer answer than watching it get
                    // turned into "725".
                    if (typed.length <= 3 && typed.all(Char::isDigit)) {
                        text = typed
                        typed.toIntOrNull()
                            ?.let(Percent::ofWholePercent)
                            ?.let { chosen ->
                                lastEmitted = chosen.asDouble.roundToInt()
                                onSelect(chosen)
                            }
                    }
                },
                enabled = enabled,
                singleLine = true,
                label = { Text("Target") },
                suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(124.dp),
            )
            Slider(
                value = atWhole.toFloat(),
                onValueChange = { dragged ->
                    val chosen = dragged.roundToInt().coerceIn(0, Percent.MAX_WHOLE_PERCENT)
                    text = chosen.toString()
                    lastEmitted = chosen
                    Percent.ofWholePercent(chosen)?.let(onSelect)
                },
                valueRange = 0f..Percent.MAX_WHOLE_PERCENT.toFloat(),
                // 101 stopping places, so the slider can only ever land on a whole percent.
                steps = Percent.MAX_WHOLE_PERCENT - 1,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        if (!isWhole) {
            Text(
                text = "Set to ${value.format()}%. An earlier version could store a " +
                    "fraction; it stays at ${value.format()}% unless you choose a whole " +
                    "percentage above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The Material date picker, wrapped so callers deal in [LocalDate].
 *
 * The picker speaks in UTC-midnight millis, which is a trap worth hiding once rather than
 * getting subtly wrong per screen: converting through the device zone shifts the date by a
 * day either side of midnight for anyone east or west of UTC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendoDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    earliest: LocalDate? = null,
    latest: LocalDate? = null,
) {
    val limits = remember(earliest, latest) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = utcTimeMillis.toUtcLocalDate()
                return (earliest == null || !date.isBefore(earliest)) &&
                    (latest == null || !date.isAfter(latest))
            }
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        selectableDates = limits,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onPick(it.toUtcLocalDate()) }
                    onDismiss()
                },
                enabled = pickerState.selectedDateMillis != null,
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/** Midnight UTC, which is the only thing the Material picker understands. */
fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
