package com.attendo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
/**
 * Source-reading pins (this repo's contract-test style) for the navigation and
 * first-paint regressions found during live-refresh verification on 2026-09-09.
 *
 * Each test here exists because a screen shipped in a state a student could see
 * and rightly call a bug: a Settings gear reachable from only one of the two
 * tabs, an empty-course flash shown to students who have courses, and a stock
 * 700 ms navigation crossfade that reads as a stall on a cold destination.
 */
class NavigationRegressionTest {

    @Test
    fun `settings is reachable from the rooms tab, not only attendance`() {
        // A student who lives on the Rooms tab had to change tabs to reach the term
        // shape, backups or "My community". The gear on the Rooms top bar is the fix;
        // this pin keeps it from being tidied away as a duplicate of the dashboard's.
        val rooms = source("src/main/kotlin/com/attendo/ui/rooms/RoomsScreen.kt")
        assertTrue(
            "RoomsScreen must take an onOpenSettings callback.",
            rooms.contains("onOpenSettings: () -> Unit"),
        )
        assertTrue(
            "The Rooms top bar must carry the gear, wired to that callback.",
            rooms.contains("Icons.Filled.Settings") && rooms.contains("onClick = onOpenSettings"),
        )
        val app = source("src/main/kotlin/com/attendo/ui/AttendoApp.kt")
        assertTrue(
            "The Rooms destination must navigate to Settings on that callback.",
            app.contains("onOpenSettings = { navController.navigate(Routes.SETTINGS) }"),
        )
    }

    @Test
    fun `courses shows loading before empty, never the empty flash`() {
        // The bug: the initial state's lists are empty by construction, so a student
        // with courses saw "Seed from timetable" for the beat the Room flows took to
        // emit. Empty must mean *known* empty: loaded first, spinner meanwhile.
        val viewModel = source("src/main/kotlin/com/attendo/ui/attendance/CoursesViewModel.kt")
        assertTrue(
            "isEmpty must require loaded, or the initial state reads as no courses.",
            viewModel.contains("get() = loaded && active.isEmpty() && archived.isEmpty()"),
        )
        val screen = source("src/main/kotlin/com/attendo/ui/attendance/CoursesScreen.kt")
        val branch = screen.substring(screen.indexOf("when {"), screen.indexOf("else -> LazyColumn"))
        assertTrue(
            "The loading branch must come first in the screen's when.",
            branch.indexOf("!state.loaded -> LoadingPane()") in 0 until branch.indexOf("state.isEmpty"),
        )
    }

    @Test
    fun `navigation hands over quickly instead of the stock crossfade`() {
        // Navigation Compose's default is a 700 ms fade — long enough that both
        // screens compose together for most of a second on a cold destination, which
        // reads as a dropped frame. The NavHost pins a 180 ms fade for every entry
        // and exit, push and pop.
        val app = source("src/main/kotlin/com/attendo/ui/AttendoApp.kt")
        listOf(
            "enterTransition",
            "exitTransition",
            "popEnterTransition",
            "popExitTransition",
        ).forEach { transition ->
            assertTrue(
                "$transition must be pinned on the NavHost, or it falls back to 700 ms.",
                app.contains("$transition = { fade"),
            )
        }
        assertFalse(
            "No transition may keep the 700 ms stock duration.",
            app.contains("tween(700)"),
        )
    }

    private fun source(relative: String): String {
        val file = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
        return file.readText()
    }
}
