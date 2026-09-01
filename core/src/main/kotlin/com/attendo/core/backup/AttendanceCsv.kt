package com.attendo.core.backup

import com.attendo.core.model.SessionStatus
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The attendance record as a spreadsheet.
 *
 * ### Not a backup
 *
 * This is the other half of exporting, and it answers a different question. A backup is for
 * the app: complete, checksummed, and unreadable in the sense that matters — you cannot
 * usefully open one in Excel. This is for the student and anyone they have to show it to. It
 * flattens the whole record to one row per class, so a pivot table can produce a per-course
 * summary, an attendance certificate can be checked against it, and an argument about a
 * particular Tuesday can be settled by looking at that Tuesday.
 *
 * It deliberately cannot be imported. Reconstructing Attendo's state from this file would
 * mean guessing at everything the flattening dropped — which pattern generated a class, which
 * cancelled original a makeup class replaced — and a restore that guesses is worse than no
 * restore. [BackupCodec] is the file you keep; this is the file you send.
 *
 * ### What it includes
 *
 * Every session, in date order, whatever its state — including the ones that do not count.
 * A cancelled class is a row with an empty percentage rather than an absent row, because
 * "this class did not happen" is the single most common thing a student needs to prove, and a
 * gap in a spreadsheet proves nothing.
 */
object AttendanceCsv {

    const val MIME_TYPE: String = "text/csv"

    // Column names are words the user has seen: the app says "hours planned" and "hours
    // attended" everywhere, and this file is the one export a student opens in a spreadsheet.
    const val HEADER: String = "date,day,course_code,course_name,kind,start_hour,slot," +
        "hours_planned,hours_attended,percent,status,cancellation_reason,origin,room,note"

    /** `attendo-attendance-2026-08-19.csv` */
    fun suggestedFileName(on: LocalDate): String = "attendo-attendance-$on.csv"

    fun export(snapshot: BackupSnapshot): String {
        val courses = snapshot.courses.associateBy { it.id }

        val rows = snapshot.sessions.sortedWith(
            compareBy(
                { it.date },
                { it.startHour },
                { courses[it.courseId]?.code ?: "" },
            ),
        )

        return buildString {
            append(HEADER).append('\n')
            rows.forEach { session ->
                val course = courses[session.courseId]
                appendRow(
                    session.date.toString(),
                    session.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    course?.code.orEmpty(),
                    course?.name.orEmpty(),
                    session.kind.name.lowercase(Locale.ROOT),
                    session.startHour.toString(),
                    session.slotLabel,
                    session.unitsPlanned.toString(),
                    // Only a held class has attended hours worth reporting. A scheduled one is
                    // pre-filled present in the UI but has not been confirmed, and printing that
                    // as attendance would export a guess as a record.
                    if (session.status == SessionStatus.HELD) session.unitsAttended.toString() else "",
                    session.sessionPercent?.format() ?: "",
                    session.status.name.lowercase(Locale.ROOT),
                    session.cancellationReason?.name?.lowercase(Locale.ROOT).orEmpty(),
                    session.origin.name.lowercase(Locale.ROOT),
                    session.room.orEmpty(),
                    session.note.orEmpty(),
                )
            }
        }
    }

    private fun StringBuilder.appendRow(vararg fields: String) {
        fields.forEachIndexed { index, field ->
            if (index > 0) append(',')
            append(escape(field))
        }
        append('\n')
    }

    /**
     * Quotes a field only when it has to be — a comma, a quote, or a line break in a room name
     * or a note. Matches [com.attendo.core.data.TimetableCsv]'s reader, which is the other place
     * in this app that speaks CSV.
     */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
