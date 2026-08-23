package com.attendo.ui

import com.attendo.core.engine.DayMark
import com.attendo.core.model.CancellationReason
import com.attendo.core.model.ClassSession
import com.attendo.core.model.SessionKind
import com.attendo.core.model.SessionOrigin

/**
 * Which colour band a single session falls in.
 *
 * [DayMark] is defined for a whole day, but the bands mean the same thing for one class,
 * and reusing it keeps a session row and its calendar cell the same colour — which is what
 * makes the calendar legible after tapping through to a day.
 */
fun ClassSession.mark(): DayMark = when {
    isCancelled -> DayMark.ALL_CANCELLED
    isAwaitingReview -> DayMark.AWAITING_REVIEW
    unitsAttended == unitsPlanned -> DayMark.FULL
    unitsAttended == 0 -> DayMark.ABSENT
    else -> DayMark.PARTIAL
}

/**
 * The short status a row prints: "Present", "1 of 2 hours", "Missed", "Cancelled",
 * "To mark".
 *
 * The partial case spells out the fraction rather than saying "partial", because the
 * number is the information — half of a two-hour lab is the case the whole app is built
 * around.
 */
fun ClassSession.statusLabel(): String = when {
    isCancelled -> cancellationReason.label()
    isAwaitingReview -> "To mark"
    unitsAttended == unitsPlanned -> if (unitsPlanned == 1) "Present" else "All ${hours(unitsPlanned)}"
    unitsAttended == 0 -> "Missed"
    else -> "$unitsAttended of ${hours(unitsPlanned)}"
}

/** "Cancelled", "Holiday", "Moved" — why a session does not count. */
fun CancellationReason?.label(): String = when (this) {
    CancellationReason.FACULTY_CANCELLED -> "Cancelled"
    CancellationReason.HOLIDAY -> "Holiday"
    CancellationReason.RESCHEDULED -> "Moved"
    CancellationReason.OTHER, null -> "Cancelled"
}

/** "Extra class" for the ad-hoc rows, so a class nobody timetabled is obviously so. */
fun ClassSession.originLabel(): String? =
    if (origin == SessionOrigin.ADHOC && movedFromSessionId == null) "Extra class"
    else if (movedFromSessionId != null) "Moved here"
    else null

/**
 * "Lecture" / "Practical" / "Tutorial", spelt out.
 *
 * [KindTag][com.attendo.ui.components.KindTag] prints the grid's "(P)" instead — that is
 * right next to a slot a student is scanning, but a chip they are choosing between needs
 * the whole word.
 */
fun SessionKind.label(): String = name.lowercase().replaceFirstChar(Char::titlecase)
