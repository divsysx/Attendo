package com.attendo.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
