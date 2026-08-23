package com.attendo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import com.attendo.core.engine.DayMark
import com.attendo.core.engine.MonthGrid
import com.attendo.core.engine.TargetAdvice
import com.attendo.core.engine.Tally
import com.attendo.core.model.Percent
import com.attendo.core.model.SessionKind
import com.attendo.ui.display
import com.attendo.ui.hours
import com.attendo.ui.initial
import com.attendo.ui.theme.bands
import java.time.LocalDate

/**
 * The big figure at the top of the dashboard and of each course.
 *
 * Prints the tally underneath the percentage on purpose: "73.5%" alone invites the
 * question this app exists to answer, and "49 of 66 hours" answers it.
 */
@Composable
fun PercentHeadline(
    percent: Percent?,
    tally: Tally,
    target: Percent,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = percent.display(),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = if (tally.isEmpty) "  nothing held yet" else "  ${tally.unitsAttended} of ${hours(tally.unitsHeld)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        AttendanceBar(
            tally = tally,
            target = target,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * A progress bar with a notch at the target.
 *
 * The notch is why this is drawn by hand rather than being a `LinearProgressIndicator`:
 * the useful question is not "how full is the bar" but "which side of 75% am I on", and
 * that needs the threshold drawn in the same coordinate space as the fill.
 */
@Composable
fun AttendanceBar(
    tally: Tally,
    target: Percent,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val percent = tally.percent
    val meets = percent != null && percent >= target
    val fill = when {
        percent == null -> MaterialTheme.colorScheme.outlineVariant
        meets -> bands.onFull
        else -> bands.onAbsent
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val notch = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        val fraction = percent?.asFraction ?: 0f
        if (fraction > 0f) {
            drawRoundRect(
                color = fill,
                size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                cornerRadius = radius,
            )
        }
        drawTargetNotch(target.asFraction, notch)
    }
}

private fun DrawScope.drawTargetNotch(fraction: Float, color: Color) {
    val width = 2.dp.toPx()
    val x = (size.width * fraction.coerceIn(0f, 1f) - width / 2f)
        .coerceIn(0f, size.width - width)
    drawRect(color = color, topLeft = Offset(x, 0f), size = Size(width, size.height))
}

/**
 * The one line of guidance under a percentage: how much can still be missed, or how much
 * must be attended to climb back.
 *
 * Both numbers are in hours, because that is the unit the rest of the app counts in — "you
 * can miss 4 more hours" is actionable in a way that "you can miss 2.7 classes" is not.
 */
@Composable
fun TargetAdviceLine(
    advice: TargetAdvice,
    modifier: Modifier = Modifier,
) {
    val text = when {
        advice.current == null ->
            "Target ${advice.target.format(0)}% — nothing held yet"
        !advice.targetReachable ->
            "A ${advice.target.format(0)}% target can no longer be reached"
        advice.meetsTarget && advice.unitsCanSkip == Int.MAX_VALUE ->
            "No target set"
        advice.meetsTarget ->
            "You can miss ${hours(advice.unitsCanSkip)} more and stay above ${advice.target.format(0)}%"
        else ->
            "Attend the next ${hours(advice.unitsMustAttend)} to reach ${advice.target.format(0)}%"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (advice.meetsTarget || advice.current == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            bands.onAbsent
        },
        modifier = modifier,
    )
}

/** The per-hour toggles — the app's most important control. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitToggles(
    labels: List<String>,
    isAttended: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val attended = isAttended(index)
            FilterChip(
                selected = attended,
                onClick = { onToggle(index) },
                enabled = enabled,
                label = { Text(label) },
                leadingIcon = if (attended) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = bands.full,
                    selectedLabelColor = bands.onFull,
                    selectedLeadingIconColor = bands.onFull,
                ),
            )
        }
    }
}

/** A dot in a [DayMark]'s colour, for legends and list rows. */
@Composable
fun MarkSwatch(
    mark: DayMark,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (mark == DayMark.NO_CLASS) Color.Transparent else bands.contentFor(mark))
            .then(
                if (mark == DayMark.NO_CLASS) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                } else {
                    Modifier
                }
            ),
    )
}

/** Says what the calendar's colours mean. Without it the grid is decoration. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BandLegend(
    grid: MonthGrid,
    modifier: Modifier = Modifier,
) {
    val shown = listOf(
        DayMark.FULL to "Full",
        DayMark.PARTIAL to "Part",
        DayMark.ABSENT to "Missed",
        DayMark.AWAITING_REVIEW to "To review",
        DayMark.ALL_CANCELLED to "Cancelled",
        // Only ever present when the student's attendance starts after the term did, or when
        // an archived semester's month is on screen. Named plainly, because a grey day the
        // legend does not explain reads as a bug in the app rather than as a date they set.
        DayMark.NOT_COUNTED to "Not counted",
    ).filter { (mark, _) -> grid.countOf(mark) > 0 }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { (mark, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MarkSwatch(mark)
                Text(
                    text = "$label ${grid.countOf(mark)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The colour-coded month grid.
 *
 * Each cell is filled with its [DayMark] band and carries a small dot when the day still
 * holds unreviewed classes, so a day that is *partly* recorded reads differently from one
 * that is finished.
 */
@Composable
fun MonthCalendar(
    grid: MonthGrid,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
    selected: LocalDate? = null,
    onSelect: (LocalDate) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            grid.weekdayOrder.forEach { day ->
                Text(
                    text = day.initial(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        grid.weeks.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { cell ->
                    val date = cell.date
                    if (date == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        DayCell(
                            date = date,
                            mark = cell.mark,
                            pending = (cell.day?.unitsAwaitingReview ?: 0) > 0 &&
                                (today == null || !date.isAfter(today)),
                            isToday = date == today,
                            isSelected = date == selected,
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    mark: DayMark,
    pending: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = bands.containerFor(mark)
    val content = if (mark == DayMark.NO_CLASS) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        bands.contentFor(mark)
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .then(
                when {
                    isSelected -> Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp),
                    )
                    isToday -> Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(8.dp),
                    )
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = content,
        )
        if (pending) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(bands.onPending),
            )
        }
    }
}

/** "(P)" / "(T)" as a quiet tag next to a slot. Lectures get nothing, as in the grid. */
@Composable
fun KindTag(kind: SessionKind, modifier: Modifier = Modifier) {
    if (kind == SessionKind.LECTURE) return
    Text(
        text = kind.timetableTag.trim(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
