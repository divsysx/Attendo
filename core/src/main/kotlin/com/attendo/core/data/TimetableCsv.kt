package com.attendo.core.data

import com.attendo.core.model.RoomBooking
import com.attendo.core.model.SessionKind
import com.attendo.core.model.TimeGrid
import java.time.DayOfWeek

/** A line the importer could not use, kept so the import screen can say *why*. */
data class ImportProblem(val line: Int, val text: String, val reason: String) {
    /** "line 14: unknown day 'Funday'" */
    override fun toString(): String = "line $line: $reason"
}

/**
 * The outcome of an import: everything that parsed, plus everything that did not.
 *
 * A bad line never fails the whole import — one typo in a 300-row timetable should
 * cost you that row, not the semester. The screen shows [problems] so they can be
 * fixed and the file re-imported.
 */
data class ImportResult(
    val bookings: List<RoomBooking>,
    val problems: List<ImportProblem> = emptyList(),
) {
    val isClean: Boolean get() = problems.isEmpty()

    val rooms: List<String> get() = bookings.mapNotNull { it.room }.distinct().sorted()

    val sections: List<String> get() = bookings.map { it.section }.distinct().sorted()

    /** "312 classes, 21 rooms, 14 sections" — the confirmation line before saving. */
    val summary: String get() = buildString {
        append(bookings.size).append(if (bookings.size == 1) " class" else " classes")
        append(", ").append(rooms.size).append(if (rooms.size == 1) " room" else " rooms")
        append(", ").append(sections.size).append(if (sections.size == 1) " section" else " sections")
        if (problems.isNotEmpty()) {
            append(", ").append(problems.size).append(if (problems.size == 1) " problem" else " problems")
        }
    }
}

/**
 * Reads and writes the room timetable as CSV — the "update it next semester" path.
 *
 * CSV rather than JSON on purpose: the source is a printed grid that somebody has to
 * retype either way, and a spreadsheet is the tool for that job. It also diffs
 * legibly, so next semester's changes are reviewable line by line.
 *
 * ```
 * # day,start,units,room,subject,section,faculty,kind,batch
 * Mon,9,2,204,ADEC,2nd Yr CSE-A,RHS,L,
 * Mon,2,1,204,CN,2nd Yr ECE-A,SKG,L,
 * Tue,11,2,Basement Lab,ADEC,2nd Yr CSE-A,RHS,P,A1
 * Wed,15,2,,SFL,2nd Yr EE-A,RHS,L,
 * ```
 *
 * Parsing is deliberately forgiving, because the input is retyped by hand:
 * - `#` comments and blank lines are skipped, and a header row is recognised.
 * - Days take any of `Mon`, `Monday`, `MON`.
 * - Hours take `9`, `09`, `9:00`, `9 AM`, `14`, `2 PM` — and a bare `1`..`8` means the
 *   afternoon, since the teaching day starts at 9 and the printed grid says "2-3".
 * - `units` defaults to 1; `kind` defaults to a lecture; blank faculty/batch are null.
 * - A blank `room` is the sports hour and its like: a real class in no room, kept for
 *   course seeding and skipped by every room query. It is a value, not a missing field.
 *
 * What it will not do is guess past a real error: an unknown day or an hour outside
 * the grid becomes an [ImportProblem] rather than a silently misplaced class.
 */
object TimetableCsv {

    /** The header written by [write]; also accepted (and skipped) by [parse]. */
    const val HEADER: String = "day,start,units,room,subject,section,faculty,kind,batch"

    fun parse(text: String): ImportResult {
        val bookings = mutableListOf<RoomBooking>()
        val problems = mutableListOf<ImportProblem>()

        text.lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            if (isHeader(trimmed)) return@forEachIndexed

            val fields = splitCsv(trimmed)
            if (fields.size < MIN_FIELDS) {
                problems += ImportProblem(
                    line, trimmed,
                    "expected at least $MIN_FIELDS fields (day,start,units,room,subject,section) but found ${fields.size}",
                )
                return@forEachIndexed
            }

            val day = parseDay(fields[0])
            if (day == null) {
                problems += ImportProblem(line, trimmed, "unknown day '${fields[0]}'")
                return@forEachIndexed
            }
            val startHour = parseHour(fields[1])
            if (startHour == null) {
                problems += ImportProblem(line, trimmed, "cannot read start time '${fields[1]}'")
                return@forEachIndexed
            }
            val units = fields[2].ifBlank { "1" }.toIntOrNull()
            if (units == null || units < 1) {
                problems += ImportProblem(line, trimmed, "length '${fields[2]}' is not a whole number of hours")
                return@forEachIndexed
            }
            if (!TimeGrid.fits(startHour, units)) {
                problems += ImportProblem(
                    line, trimmed,
                    "${TimeGrid.slotLabel(startHour)} + ${units}h runs past the end of the teaching day",
                )
                return@forEachIndexed
            }
            val room = fields[3].trim().ifBlank { null }
            val subject = fields[4].trim()
            if (subject.isEmpty()) {
                problems += ImportProblem(line, trimmed, "no subject named")
                return@forEachIndexed
            }
            val kind = parseKind(fields.getOrNull(7))
            if (kind == null) {
                problems += ImportProblem(line, trimmed, "unknown class kind '${fields[7]}'")
                return@forEachIndexed
            }

            bookings += RoomBooking(
                dayOfWeek = day,
                startHour = startHour,
                units = units,
                room = room,
                subject = subject,
                section = fields[5].trim(),
                faculty = fields.getOrNull(6)?.trim()?.ifBlank { null },
                kind = kind,
                batch = fields.getOrNull(8)?.trim()?.ifBlank { null },
            )
        }

        return ImportResult(bookings, problems)
    }

    /** Writes bookings back out in weekly reading order, header first. */
    fun write(bookings: Iterable<RoomBooking>): String = buildString {
        append("# ").append(HEADER).append('\n')
        bookings
            .sortedWith(compareBy({ it.dayOfWeek.value }, { it.startHour }, { it.room }, { it.section }))
            .forEach { b ->
                append(dayName(b.dayOfWeek)).append(',')
                append(b.startHour).append(',')
                append(b.units).append(',')
                append(escape(b.room.orEmpty())).append(',')
                append(escape(b.subject)).append(',')
                append(escape(b.section)).append(',')
                append(escape(b.faculty.orEmpty())).append(',')
                append(kindCode(b.kind)).append(',')
                append(escape(b.batch.orEmpty())).append('\n')
            }
    }

    /**
     * Stitches consecutive one-hour rows of the same class into single blocks.
     *
     * The printed timetable splits a 2-hour class across two adjacent columns, so
     * whoever retypes it will often enter it as two rows — and the PDF converter, which
     * reads the grid one cell at a time, always produces them that way. Merging here means
     * the availability engine never has to reason about "is the next hour the same class?",
     * and "204 is busy until 11" comes out right either way.
     *
     * The ordering is by every field [isSameClass] compares, so two halves of a block are
     * guaranteed to land next to each other. Sorting by fewer fields very nearly works and
     * then quietly does not: a lab taught to `A1` at 9, `A2` at 10 and `A1` at 10 would sort
     * in that order on `(day, room, section, subject, hour)` alone, and the `A1` pair would
     * never meet. The result is re-sorted for output afterwards.
     */
    fun mergeAdjacent(bookings: Iterable<RoomBooking>): List<RoomBooking> {
        val ordered = bookings.sortedWith(
            compareBy(
                { it.dayOfWeek.value },
                { it.room },
                { it.section },
                { it.subject },
                { it.faculty },
                { it.kind },
                { it.batch },
                { it.startHour },
            ),
        )
        val merged = mutableListOf<RoomBooking>()
        for (booking in ordered) {
            val previous = merged.lastOrNull()
            if (previous != null && isSameClass(previous, booking) && previous.endHour == booking.startHour) {
                merged[merged.lastIndex] = previous.copy(units = previous.units + booking.units)
            } else {
                merged += booking
            }
        }
        return merged.sortedWith(compareBy({ it.dayOfWeek.value }, { it.startHour }, { it.room }, { it.section }))
    }

    // ---- field parsing ------------------------------------------------------

    private fun isSameClass(a: RoomBooking, b: RoomBooking): Boolean =
        a.dayOfWeek == b.dayOfWeek &&
            a.room == b.room &&
            a.subject == b.subject &&
            a.section == b.section &&
            a.faculty == b.faculty &&
            a.kind == b.kind &&
            a.batch == b.batch

    private fun isHeader(line: String): Boolean {
        val first = splitCsv(line).firstOrNull()?.trim()?.lowercase() ?: return false
        return first == "day" || first == "dayofweek"
    }

    internal fun parseDay(raw: String): DayOfWeek? = when (raw.trim().lowercase()) {
        "mon", "monday", "m" -> DayOfWeek.MONDAY
        "tue", "tues", "tuesday", "t" -> DayOfWeek.TUESDAY
        "wed", "weds", "wednesday", "w" -> DayOfWeek.WEDNESDAY
        "thu", "thur", "thurs", "thursday", "th" -> DayOfWeek.THURSDAY
        "fri", "friday", "f" -> DayOfWeek.FRIDAY
        "sat", "saturday", "s" -> DayOfWeek.SATURDAY
        "sun", "sunday" -> DayOfWeek.SUNDAY
        else -> null
    }

    /**
     * Reads a start time as a 24-hour hour, or null.
     *
     * A bare 1..8 is read as the afternoon: the teaching day runs 9 AM to 6 PM, so the
     * "1-2" and "2-3" columns of the printed grid can only mean 13 and 14.
     */
    internal fun parseHour(raw: String): Int? {
        val text = raw.trim().lowercase().replace(" ", "")
        if (text.isEmpty()) return null
        val pm = text.endsWith("pm")
        val am = text.endsWith("am")
        val digits = text.removeSuffix("pm").removeSuffix("am").substringBefore(':')
        val hour = digits.toIntOrNull() ?: return null
        val hour24 = when {
            pm && hour in 1..11 -> hour + 12
            am && hour == 12 -> 0
            am || pm -> hour
            // No meridiem: 1-8 can only be the afternoon on a 9-to-6 grid.
            hour in 1..8 -> hour + 12
            else -> hour
        }
        return hour24.takeIf { TimeGrid.isValidStartHour(it) }
    }

    /** Blank means a lecture; an unrecognised code is an error, not a guess. */
    private fun parseKind(raw: String?): SessionKind? = when (raw?.trim()?.lowercase()) {
        null, "", "l", "lec", "lecture" -> SessionKind.LECTURE
        "p", "prac", "practical", "lab" -> SessionKind.PRACTICAL
        "t", "tut", "tutorial" -> SessionKind.TUTORIAL
        else -> null
    }

    private fun kindCode(kind: SessionKind): String = when (kind) {
        SessionKind.LECTURE -> "L"
        SessionKind.PRACTICAL -> "P"
        SessionKind.TUTORIAL -> "T"
    }

    private fun dayName(day: DayOfWeek): String =
        day.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

    // ---- the CSV itself ----------------------------------------------------

    /**
     * Splits one line on commas, honouring `"quoted, fields"` and `""` as an escaped
     * quote. Hand-typed data will contain the odd `"Room 5, Annexe"`.
     */
    internal fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString().trim()
        return fields
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\"" else value

    private const val MIN_FIELDS = 6
}
