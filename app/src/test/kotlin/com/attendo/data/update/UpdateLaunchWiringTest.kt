package com.attendo.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the launch-time update behaviour the student experiences is the behaviour
 * [com.attendo.core.update.UpdateLaunch] promises — pinned against the real sources,
 * because every piece of it lives in a class plain JVM tests cannot construct
 * (SharedPreferences, PackageManager, a running process).
 *
 * The decision logic itself is tested purely in `:core`; what this file pins is the
 * wiring around it: the check runs on every app open, the cached card rises before the
 * network is asked anything, only a found update ever moves the automatic state, the
 * card is on the screen the app opens on, and the manual check from Settings stays the
 * manual check.
 */
class UpdateLaunchWiringTest {

    private fun source(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)

    private fun manager(): String =
        source("src/main/kotlin/com/attendo/data/update/UpdateManager.kt").readText()

    @Test
    fun `every app open runs the launch check`() {
        val application = source("src/main/kotlin/com/attendo/AttendoApplication.kt").readText()

        assertTrue(
            "autoCheck in Application.onCreate is what makes availability automatic on " +
                "launch rather than on a visit to Settings.",
            application.contains("container.updates.autoCheck()"),
        )
    }

    @Test
    fun `a build with no update source never phones home`() {
        assertTrue(
            "A Play-installed build must decline the automatic check before any work — " +
                "an update flow that tried to replace another channel's install would be " +
                "worse than none.",
            manager().contains("if (provider == null) return"),
        )
    }

    @Test
    fun `the cached card rises before the network is asked anything`() {
        val text = manager()

        assertTrue(
            "Both halves of the open-time decision must come from the tested plan, not " +
                "be re-decided inline.",
            text.contains("UpdateLaunch.plan("),
        )

        val raised = text.indexOf("plan.cachedUpdateToRaise?.let")
        val gate = text.indexOf("if (!plan.networkCheckDue) return@launch")

        assertTrue("The card must be raised from the plan.", raised >= 0)
        assertTrue("The due gate must be the plan's.", gate >= 0)
        assertTrue(
            "Raising the cached card must happen before the due question, so an offline " +
                "student still sees the update a previous check found.",
            raised < gate,
        )
    }

    @Test
    fun `only a found update ever moves the automatic state`() {
        val text = manager()
        // The check function, from its declaration to its return, is the whole automatic
        // path's effect on the state.
        val body = text.substringAfter("private suspend fun check(").substringBefore("return outcome")

        assertEquals(
            "Exactly one state write — the Available branch. Up-to-date and failed checks " +
                "must stay silent: being offline or current is not an event in an " +
                "attendance app.",
            1,
            Regex("_state\\.value =").findAll(body).count(),
        )
        assertTrue(
            "The one write must be the available-update card.",
            body.contains("_state.value = UpdateState.Available"),
        )
        assertTrue(
            "Whether an automatic find may surface is the tested decision's to make.",
            body.contains("UpdateLaunch.surfacesAutomatically("),
        )
        assertTrue(
            "A failed check is recorded nowhere, so it retries on the next open.",
            body.contains("Unit // Not recorded"),
        )
    }

    @Test
    fun `the card is on the screen the app opens on`() {
        val screen = source("src/main/kotlin/com/attendo/ui/attendance/DashboardScreen.kt").readText()
        val viewModel = source("src/main/kotlin/com/attendo/ui/attendance/DashboardViewModel.kt").readText()

        assertTrue(
            "The dashboard must render the update card from the update state.",
            screen.contains("UpdateCard(") && screen.contains("viewModel.updateState"),
        )
        assertTrue(
            "Dismissal from the dashboard must go through the same call Settings makes, " +
                "so \"Not now\" is remembered the same way from either place.",
            screen.contains("viewModel::dismissUpdate"),
        )
        assertTrue(
            "The dashboard's update state must be the manager's own flow — one truth for " +
                "both cards, not a copy.",
            viewModel.contains("updates?.state"),
        )
        assertTrue(
            "The dashboard's ViewModel must be handed the update manager.",
            viewModel.contains("container.updates"),
        )
    }

    @Test
    fun `both cards are the one shared card`() {
        val settings = source("src/main/kotlin/com/attendo/ui/settings/UpdateSection.kt").readText()
        val shared = source("src/main/kotlin/com/attendo/ui/components/UpdateCard.kt").readText()

        assertTrue(
            "The Updates section must render the shared card, so the card at app open and " +
                "the card in Settings cannot disagree.",
            settings.contains("UpdateCard("),
        )
        assertTrue(
            "The shared card must render nothing on Idle — that is what lets a screen " +
                "call it unconditionally.",
            shared.contains("UpdateState.Idle -> Unit"),
        )
    }

    @Test
    fun `the manual check from settings stays the manual check`() {
        val settings =
            source("src/main/kotlin/com/attendo/ui/settings/SettingsViewModel.kt").readText()

        assertTrue(
            "Check for updates must keep calling the manager's own manual path — always a " +
                "fresh check, never the policy's to throttle.",
            settings.contains("manager.checkNow()"),
        )
    }
}
