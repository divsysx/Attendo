package com.attendo.data

import com.attendo.core.model.AcademicCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The greeting at the top of the Attendance tab.
 *
 * One derived string, tested on its own because it is the only place the display name is read
 * for the student to see, and because both of its cases are ordinary. Nobody has a name until
 * they open Settings and type one, so "Hi" is a finished sentence rather than a placeholder
 * waiting to be filled in — and a name typed with a trailing space, which phone keyboards hand
 * over constantly, must not become "Hi, Divyansh " on the screen.
 *
 * Nothing here touches [SettingsStore]: reading and writing SharedPreferences is Android's, and
 * what to say is not.
 */
class AppSettingsTest {

    @Test
    fun `a student who has set a name is greeted by it`() {
        assertEquals("Hi, Divyansh", AppSettings(displayName = "Divyansh").greeting)
    }

    @Test
    fun `a student who has not is greeted anyway`() {
        assertEquals("Hi", AppSettings().greeting)
        assertEquals("Hi", AppSettings(displayName = null).greeting)
    }

    @Test
    fun `a name of nothing but spaces is no name`() {
        assertEquals("Hi", AppSettings(displayName = "").greeting)
        assertEquals("Hi", AppSettings(displayName = "   ").greeting)
    }

    @Test
    fun `the keyboard's trailing space does not reach the screen`() {
        assertEquals("Hi, Divyansh", AppSettings(displayName = "Divyansh ").greeting)
        assertEquals("Hi, Divyansh", AppSettings(displayName = "  Divyansh  ").greeting)
    }

    @Test
    fun `a full name is greeted in full, because it is the student's to choose`() {
        assertEquals("Hi, Divyansh Kumar", AppSettings(displayName = "Divyansh Kumar").greeting)
    }

    @Test
    fun `the name is not tangled up with the section`() {
        // Two independent labels: seeding a section must not change the greeting, and setting a
        // name must not look like picking a section.
        val settings = AppSettings(section = "2nd Yr ECE-A", batch = "A1")

        assertEquals("Hi", settings.greeting)
        assertEquals("Hi, Divyansh", settings.copy(displayName = "Divyansh").greeting)
    }

    // ---- the calendar a section is taught under ------------------------------

    @Test
    fun `a section taught on Saturday every week gets every Saturday as a teaching day`() {
        val settings = AppSettings(
            calendar = TERM,
            section = "1st Yr EE-A",
            batch = "A2",
        )

        assertEquals(TERM.saturdaysInTerm().toSet(), settings.effectiveCalendar.workingSaturdays)
        TERM.saturdaysInTerm().forEach { saturday ->
            assertTrue(saturday.toString(), settings.effectiveCalendar.isTeachingDay(saturday))
        }
    }

    @Test
    fun `a section that is not gets the calendar the student configured`() {
        // The whole of the change, for everyone else: the stored calendar, unaltered.
        val settings = AppSettings(calendar = TERM, section = "3rd Yr CSE-A", batch = "A1")

        assertEquals(TERM, settings.effectiveCalendar)
    }

    @Test
    fun `a student who has not chosen a section is not enrolled on Saturdays`() {
        // A fresh install has no section, and its Saturdays must stay holidays until the
        // timetable says otherwise — which it cannot until a section is chosen.
        assertEquals(TERM, AppSettings(calendar = TERM).effectiveCalendar)
        assertEquals(TERM, AppSettings(calendar = TERM, section = "").effectiveCalendar)
    }

    @Test
    fun `the derivation does not reach into what is stored`() {
        // Settings edits `calendar`; everything that decides whether a class happens reads
        // `effectiveCalendar`. If the derivation wrote back, the Saturday picker would show
        // sixteen dates the student never ticked and re-ticking them would do nothing.
        val settings = AppSettings(calendar = TERM, section = "4th Yr EE")

        assertTrue(settings.calendar.workingSaturdays.isEmpty())
        assertTrue(settings.effectiveCalendar.workingSaturdays.isNotEmpty())
    }

    @Test
    fun `a holiday still empties a Saturday for a section taught on Saturday`() {
        val holiday = LocalDate.of(2026, 8, 8)
        val settings = AppSettings(
            calendar = TERM.copy(holidays = setOf(holiday)),
            section = "1st Yr CSE-A",
        )

        assertFalse(settings.effectiveCalendar.isTeachingDay(holiday))
        assertTrue(settings.effectiveCalendar.isTeachingDay(LocalDate.of(2026, 8, 15)))
    }

    @Test
    fun `a Saturday the student ticked by hand survives alongside the derivation`() {
        val ticked = LocalDate.of(2026, 8, 1)
        val settings = AppSettings(
            calendar = TERM.copy(workingSaturdays = setOf(ticked)),
            section = "1st Yr CSE-A",
        )

        assertTrue(ticked in settings.effectiveCalendar.workingSaturdays)
        assertEquals(TERM.saturdaysInTerm().toSet(), settings.effectiveCalendar.workingSaturdays)
    }

    private companion object {
        /** A term whose ends fall on ordinary weekdays, so the Saturdays are unambiguous. */
        val TERM = AcademicCalendar(
            termStart = LocalDate.of(2026, 7, 28),
            termEnd = LocalDate.of(2026, 11, 20),
        )
    }
}
