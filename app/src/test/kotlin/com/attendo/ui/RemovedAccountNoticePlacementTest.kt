package com.attendo.ui

import com.attendo.ui.settings.REMOVED_ACCOUNT_DIALOG_TITLE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Section C, at the level where it can actually be got wrong: **where the removal notice is
 * rendered.**
 *
 * The words are pinned in `RemovedAccountNoticeTest`. This pins the one thing the words cannot
 * pin — that they are reachable at the moment they are needed. The deletion is carried out by a
 * background validation at app start or on returning to the foreground, which is not a moment
 * the student is looking at any particular screen:
 *
 *  * Rendered inside a *screen*, the sentence reaches only the students who navigate there.
 *    Someone whose account was removed while they were asleep would keep using an app that is
 *    silently signed out and no longer syncing, and be told nothing until they happened to open
 *    Settings and scroll — the failure this notice exists to prevent.
 *  * Rendered at the *root*, over the nav graph, it reaches everyone on the first frame after
 *    the flag is set, whatever screen they are on, and the app underneath stays usable.
 *
 * Asserted as source because that is what "at the root" is: a Compose test would have to build
 * the whole graph and the whole container to observe placement the file already states. It is
 * also the assertion that survives refactors a screenshot test would not notice, since moving
 * the call one composable deeper changes the file and not the pixels.
 */
class RemovedAccountNoticePlacementTest {

    private val app = sourceFile("app/src/main/kotlin/com/attendo/ui/AttendoApp.kt").readText()

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)

    /**
     * The UI source tree, resolved rather than assumed.
     *
     * A relative walk from the test's working directory would find nothing when the directory
     * does not exist, and `assertFalse(emptyList.isNotEmpty())` would pass while checking no
     * file at all — the vacuous green this assertion cannot afford, since the thing it forbids
     * is a one-line change that looks harmless in review.
     */
    private fun uiSourceRoot(): File {
        val root = sourceFile("app/src/main/kotlin/com/attendo/ui")
        assertTrue("the UI source tree must be findable from the test", root.isDirectory)
        assertTrue(
            "the walk must actually see the screens, or this proves nothing",
            root.walkTopDown().count { it.extension == "kt" } > 10,
        )
        return root
    }

    @Test
    fun `the notice is rendered from the app root`() {
        assertTrue(
            "AttendoApp must render RemovedAccountNotice. If nothing renders it, the " +
                "deletion is durable, applied, and completely silent.",
            app.contains("RemovedAccountNotice(viewModel ="),
        )

        // The root here is the same Box RolloverGate is composed in — the app's existing
        // pattern for a sentence that belongs to the install rather than to a destination.
        // Both are asserted together, because "at the root" is only meaningful as a
        // comparison, and a later move of one without the other is the change to catch.
        val rootAt = app.indexOf("RolloverGate(viewModel =")
        val noticeAt = app.indexOf("RemovedAccountNotice(viewModel =")
        assertTrue("The root Box must compose RolloverGate.", rootAt >= 0)
        assertTrue(
            "The notice must sit beside RolloverGate in the same root composition. " +
                "Composed later, inside the scaffold or a destination, it would only reach " +
                "the students who navigate there.",
            noticeAt > rootAt,
        )

        // The view model is hoisted to the root too — the same viewModel(factory = …) call
        // RolloverGate's is — rather than created inside the composable, which would give it
        // the lifetime of whatever screen happens to compose it.
        assertTrue(
            "The notice's view model must be hoisted at the app root, so the " +
                "acknowledgement survives navigation. Created inside the composable, a " +
                "student who opened Settings and came back would be told again.",
            app.contains("viewModel(factory = RemovedAccountNoticeViewModel.Factory)"),
        )
    }

    @Test
    fun `the notice is not rendered from a screen`() {
        // Composables under ui/settings and ui/attendance are destinations. Rendering the
        // notice from one of them is the failure mode above, and it is a one-line change
        // that would look harmless in review.
        val screens = uiSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AttendoApp.kt" }
            // The composable's own declaration is not a composition of it.
            .filter { it.name != "RemovedAccountNotice.kt" }
            .filter { it.readText().contains("RemovedAccountNotice(") }
            .map { it.name }
            .toList()

        assertFalse(
            "Only the app root may compose RemovedAccountNotice — a screen is a place the " +
                "student has to go. Found: $screens",
            screens.isNotEmpty(),
        )
    }

    @Test
    fun `the account screen still explains the removal where a student would look for it`() {
        // The notice says it once, at the moment it happens. The Account screen says it for as
        // long as it stays true, which is where the buttons that do something about it live.
        // Neither replaces the other, and the two renderings are one sentence — see
        // AccountSettingsWordsTest.
        val accountScreen = sourceFile("app/src/main/kotlin/com/attendo/ui/settings/AccountSettingsScreen.kt").readText()
        assertTrue(
            "The Account screen must still carry the removal card.",
            accountScreen.contains("accountRemovedExplanation()"),
        )
        assertTrue(
            "And it must be driven by the same flag the notice reads, so the two cannot " +
                "disagree about whether an account was removed.",
            accountScreen.contains("accountRemoved"),
        )
        assertTrue(
            "The notice's title must remain the sentence the Account screen opens with — " +
                "they are one statement in two places. Found: \"$REMOVED_ACCOUNT_DIALOG_TITLE\"",
            accountScreen.contains("$REMOVED_ACCOUNT_DIALOG_TITLE."),
        )
    }
}
