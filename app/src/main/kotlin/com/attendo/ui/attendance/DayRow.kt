package com.attendo.ui.attendance

import com.attendo.core.engine.DayMark
import com.attendo.core.engine.DayPlan
import com.attendo.core.engine.SessionGenerator
import com.attendo.core.model.ClassSession
import com.attendo.core.model.SessionKind
import com.attendo.core.model.TimeGrid
import com.attendo.ui.mark
import com.attendo.ui.statusLabel

/**
 * One line of a day: either a session already stored, or one the timetable says should
 * exist and that nobody has committed yet.
 *
 * The distinction matters because a [Pending] row has no database id — the review screen
 * materialises it on first touch rather than up front, so browsing forward through the
 * term does not litter the table with rows for classes that have not happened.
 */
sealed interface DayRow {

    val courseId: Long
    val startHour: Int
    val unitsPlanned: Int
    val kind: SessionKind
    val room: String?

    /** Stable enough to key a list on: a slot can hold only one class per course. */
    val key: String get() = "$courseId-$startHour-${(this as? Stored)?.session?.id ?: "draft"}"

    data class Stored(val session: ClassSession) : DayRow {
        override val courseId: Long get() = session.courseId
        override val startHour: Int get() = session.startHour
        override val unitsPlanned: Int get() = session.unitsPlanned
        override val kind: SessionKind get() = session.kind
        override val room: String? get() = session.room
    }

    data class Pending(val draft: SessionGenerator.Draft) : DayRow {
        override val courseId: Long get() = draft.courseId
        override val startHour: Int get() = draft.startHour
        override val unitsPlanned: Int get() = draft.unitsPlanned
        override val kind: SessionKind get() = draft.kind
        override val room: String? get() = draft.room
    }
}

val DayRow.slotLabel: String get() = TimeGrid.rangeLabel(startHour, unitsPlanned)

val DayRow.unitLabels: List<String>
    get() = (0 until unitsPlanned).map { TimeGrid.unitLabel(startHour, it) }

fun DayRow.mark(): DayMark = when (this) {
    is DayRow.Stored -> session.mark()
    is DayRow.Pending -> DayMark.AWAITING_REVIEW
}

fun DayRow.statusLabel(): String = when (this) {
    is DayRow.Stored -> session.statusLabel()
    is DayRow.Pending -> "To mark"
}

/**
 * Whether hour [unitIndex] reads as attended.
 *
 * Pending rows report every hour attended because that is what
 * [SessionGenerator.Draft.toSession] pre-fills them with — the toggles show what
 * approving would commit, not an empty row.
 */
fun DayRow.isUnitAttended(unitIndex: Int): Boolean = when (this) {
    is DayRow.Stored -> session.isUnitAttended(unitIndex)
    is DayRow.Pending -> unitIndex < unitsPlanned
}

/** Stored rows and drafts merged into one list, in slot order. */
fun DayPlan.rows(): List<DayRow> =
    (stored.map { DayRow.Stored(it) } + missing.map { DayRow.Pending(it) })
        .sortedWith(compareBy({ it.startHour }, { it.courseId }))
