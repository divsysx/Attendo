package com.attendo.ui

import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.Percent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date and number labels, in one place so two screens never disagree about how a day
 * is written.
 *
 * [Locale.ENGLISH] rather than the device locale, matching [Percent.format]: the
 * timetable this app reads is printed in English, "Mon" is what the grid says, and a
 * localised abbreviation would sit oddly next to an untranslated subject code.
 */
private val LOCALE: Locale = Locale.ENGLISH

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", LOCALE)
private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", LOCALE)
private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", LOCALE)
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE)
private val FULL_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", LOCALE)
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", LOCALE)

/** "18 Aug" */
fun LocalDate.dayMonth(): String = format(DAY_MONTH)

/** "Mon 18 Aug" */
fun LocalDate.shortLabel(): String = format(WEEKDAY_DAY_MONTH)

/** "Monday, 18 August" */
fun LocalDate.longLabel(): String = format(LONG_DAY)

/** "18 Aug 2026" — for dates far from today, where the weekday is no help. */
fun LocalDate.fullLabel(): String = format(FULL_DATE)

/**
 * "Today", "Yesterday", "Tomorrow", or [shortLabel]. The review screen leans on this:
 * the day being marked is nearly always one of the three.
 */
fun LocalDate.relativeLabel(today: LocalDate): String = when (this) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> shortLabel()
}

/**
 * "18 Aug 2026, 21:04" — an instant shown in this phone's own time zone.
 *
 * A backup records when it was written as an instant, which is the right thing to store in a
 * file that may be read on the other side of the world, and the wrong thing to show somebody
 * deciding whether this is the file they exported last night.
 */
fun Instant.localLabel(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(DATE_TIME)

/** "August 2026" */
fun YearMonth.label(): String = atDay(1).format(MONTH_YEAR)

/** "Mon" */
fun DayOfWeek.shortLabel(): String = getDisplayName(TextStyle.SHORT, LOCALE)

/** "Monday" */
fun DayOfWeek.fullLabel(): String = getDisplayName(TextStyle.FULL, LOCALE)

/** "M" — the calendar's column headers, where there is room for one letter. */
fun DayOfWeek.initial(): String = getDisplayName(TextStyle.NARROW, LOCALE)

/**
 * "75%", or an em dash when there is no percentage to show.
 *
 * A null [Percent] means nothing has been held yet, which is not 0% — the dash is the
 * whole point of this function existing.
 */
fun Percent?.display(decimals: Int = 1): String = this?.let { "${it.format(decimals)}%" } ?: "—"

/** "1 hour" / "3 hours" — attendance is counted in hours, so it is always spelt out. */
fun hours(count: Int): String = "$count hour${if (count == 1) "" else "s"}"

/** "2 of 3 hours" for the row under a session. */
fun unitsOf(attended: Int, planned: Int): String = "$attended of ${hours(planned)}"

/** "6 courses" / "1 course" */
fun courses(count: Int): String = "$count course${if (count == 1) "" else "s"}"

/** "6 classes" / "1 class" */
fun classes(count: Int): String = "$count class${if (count == 1) "" else "es"}"

/** "3 days" / "1 day" */
fun days(count: Int): String = "$count day${if (count == 1) "" else "s"}"

/**
 * Why the timetable does not apply to a date, in the words a student would use.
 *
 * Shared by the Attendance and Rooms empty states so the two tabs never disagree about a
 * reason. Pure delegation to the calendar's own predicates — it encodes term bounds, holidays,
 * Sundays, and working Saturdays, so this adds no calendar rule of its own.
 */
internal fun AcademicCalendar.notTeachingReason(date: LocalDate): String = when {
    !isWithinTerm(date) -> "Outside semester"
    date in holidays -> "Holiday"
    date.dayOfWeek == DayOfWeek.SUNDAY -> "Sunday"
    date.dayOfWeek == DayOfWeek.SATURDAY -> "Saturday (not a working one)"
    else -> "No classes"
}
