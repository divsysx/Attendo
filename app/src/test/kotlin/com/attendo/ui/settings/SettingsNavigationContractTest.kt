package com.attendo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shape of Settings, pinned as source.
 *
 * Three of the settings a student changes most — the target percentages, the holiday list, the
 * working Saturdays — each grew an editor inline over time, and the main screen became a form
 * you scroll rather than a list you read. Splitting them out is only worth anything if they
 * stay split: the failure mode is somebody quietly re-adding a chip row "for convenience",
 * after which there are two places to change a holiday and only one of them is the one the
 * next reader finds.
 *
 * The same test holds the other half of the split — that the three screens have not grown their
 * own storage. Each one is a view over the settings the app already had, opened through
 * [SettingsViewModel], so a change made on one of them is the same change the account syncs.
 */
class SettingsNavigationContractTest {

    private fun source(relative: String): String {
        val file = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
        return file.readText()
    }

    /** Every Kotlin file the app ships, for the "written down once" checks. */
    private fun allAppSources(): List<File> {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "src/main/kotlin") }
            .first(File::exists)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val app: String get() = source("src/main/kotlin/com/attendo/ui/AttendoApp.kt")
    private val settings: String get() = source("src/main/kotlin/com/attendo/ui/settings/SettingsScreen.kt")
    private val routes: String get() = source("src/main/kotlin/com/attendo/ui/Routes.kt")

    // ---- the three rows lead somewhere ---------------------------------------

    @Test
    fun `each of the three rows has a route of its own`() {
        listOf("TARGETS", "HOLIDAYS", "WORKING_SATURDAYS").forEach { name ->
            assertTrue("Routes must declare $name.", routes.contains("const val $name"))
        }
    }

    @Test
    fun `each of the three routes has a destination`() {
        assertTrue(app.contains("composable(Routes.TARGETS)"))
        assertTrue(app.contains("composable(Routes.HOLIDAYS)"))
        assertTrue(app.contains("composable(Routes.WORKING_SATURDAYS)"))
    }

    @Test
    fun `the rows navigate to their screens`() {
        assertTrue(app.contains("onOpenTargets = { navController.navigate(Routes.TARGETS) }"))
        assertTrue(app.contains("onOpenHolidays = { navController.navigate(Routes.HOLIDAYS) }"))
        assertTrue(
            app.contains(
                "onOpenWorkingSaturdays = { navController.navigate(Routes.WORKING_SATURDAYS) }",
            ),
        )
    }

    // ---- and the main screen does not also do the job ------------------------

    @Test
    fun `the holiday picker is not also on the main screen`() {
        // The regression this guards: an "Add" button creeping back into the Holidays heading,
        // which would give the term's holidays two editors that can disagree.
        assertFalse(settings.contains("viewModel::addHoliday"))
        assertFalse(settings.contains("SettingsDialog.AddHoliday"))
    }

    @Test
    fun `the target chips are not also on the main screen`() {
        // The preset row this replaced was the shape the whole change existed to remove: eight
        // percentages, none of them 63, and no way to see the value in the field.
        assertFalse(settings.contains("TARGET_OPTIONS"))
        assertFalse(settings.contains("PercentPicker"))
    }

    @Test
    fun `the working-Saturday list is not also on the main screen`() {
        assertFalse(settings.contains("saturdaysBetween"))
        assertFalse(settings.contains("viewModel::addWorkingSaturday"))
        assertFalse(settings.contains("viewModel::removeWorkingSaturday"))
    }

    @Test
    fun `the working Saturdays row only appears where the setting applies`() {
        // For a section the timetable teaches on Saturday every week there is nothing to
        // configure, and a row that opened an empty screen would be worse than no row.
        assertTrue(settings.contains("if (!state.saturdayIsRecurring)"))
        assertTrue(settings.contains("value = state.workingSaturdaysSummary"))
    }

    // ---- the three screens read the same settings ----------------------------

    @Test
    fun `each dedicated screen is a view over the settings the app already had`() {
        listOf(
            "src/main/kotlin/com/attendo/ui/settings/AttendanceTargetsScreen.kt",
            "src/main/kotlin/com/attendo/ui/settings/HolidaysScreen.kt",
            "src/main/kotlin/com/attendo/ui/settings/WorkingSaturdaysScreen.kt",
        ).forEach { path ->
            val screen = source(path)

            assertTrue("$path must use SettingsViewModel.", screen.contains("SettingsViewModel"))
            assertTrue("$path must open through the container's factory.", screen.contains("SettingsViewModel.Factory"))
        }
    }

    @Test
    fun `the holidays screen writes through the ViewModel that already synced them`() {
        val screen = source("src/main/kotlin/com/attendo/ui/settings/HolidaysScreen.kt")

        assertTrue(screen.contains("viewModel::addHoliday"))
        assertTrue(screen.contains("viewModel.removeHoliday("))
    }

    @Test
    fun `the working Saturdays screen writes through the ViewModel that already synced them`() {
        val screen = source("src/main/kotlin/com/attendo/ui/settings/WorkingSaturdaysScreen.kt")

        assertTrue(screen.contains("viewModel.addWorkingSaturday("))
        assertTrue(screen.contains("viewModel.removeWorkingSaturday("))
    }

    // ---- one target control, not two -----------------------------------------

    @Test
    fun `one percentage control is used everywhere a target is set`() {
        // §7 of the brief: the editor may keep a target control, but there must not be two
        // conflicting implementations. There is one component, and three callers.
        val editor = source("src/main/kotlin/com/attendo/ui/attendance/CourseEditorScreen.kt")
        assertTrue(
            "The course editor must use the shared picker.",
            editor.contains("PercentPicker("),
        )
        assertFalse(
            "The editor's preset chip row is what the shared picker replaced.",
            editor.contains("TARGET_OPTIONS"),
        )

        val targets = source("src/main/kotlin/com/attendo/ui/settings/AttendanceTargetsScreen.kt")
        assertTrue(targets.contains("PercentPicker("))

        val detail = source("src/main/kotlin/com/attendo/ui/attendance/CourseDetailScreen.kt")
        assertTrue(detail.contains("PercentPicker("))
    }

    @Test
    fun `the course detail target control is wired to the ViewModel function that already existed`() {
        // `CourseDetailViewModel.setTarget` shipped with no caller. The fix was to give it a
        // row, not to write a second way for a course's target to change.
        val detail = source("src/main/kotlin/com/attendo/ui/attendance/CourseDetailScreen.kt")

        assertTrue(detail.contains("viewModel.setTarget("))

        val viewModel = source("src/main/kotlin/com/attendo/ui/attendance/CourseDetailViewModel.kt")
        assertEquals(
            "There must be exactly one setTarget on the course detail ViewModel.",
            1,
            Regex("fun setTarget\\(").findAll(viewModel).count(),
        )
    }

    @Test
    fun `the course detail target row is not gated behind the edit screen`() {
        // A target is changed from the course it belongs to. Making the row a shortcut into
        // the full editor would put the timetable's slot list between the student and a
        // percentage, which is the interaction the brief asked to avoid.
        val detail = source("src/main/kotlin/com/attendo/ui/attendance/CourseDetailScreen.kt")

        assertTrue(detail.contains("TargetRow("))
        assertTrue(detail.contains("TargetDialog("))
    }

    // ---- the bulk apply asks first -------------------------------------------

    @Test
    fun `the bulk apply confirms before it writes`() {
        val targets = source("src/main/kotlin/com/attendo/ui/settings/AttendanceTargetsScreen.kt")

        assertTrue(targets.contains("ConfirmDialog("))
        assertTrue(targets.contains("viewModel.applyTargetToAllActiveCourses(bulk)"))
        assertTrue(
            "The dialog must say how many courses it will change.",
            targets.contains("\${courses(state.courseCount)}"),
        )
    }

    @Test
    fun `the default target is described as not reaching existing courses`() {
        // §5 of the brief. A student who moves the default and expects their courses to follow
        // has misread the screen, so the screen says otherwise in words.
        val targets = source("src/main/kotlin/com/attendo/ui/settings/AttendanceTargetsScreen.kt")

        assertTrue(targets.contains("courses you already have"))
    }

    // ---- the project footer under About --------------------------------------

    @Test
    fun `the project links point exactly where the brief says`() {
        assertTrue(
            "The repo link must be the exact address, not a near miss.",
            settings.contains("""const val GITHUB_REPO_URL = "https://github.com/divsysx/Attendo""""),
        )
        assertTrue(
            "The support link must be the exact address, not a near miss.",
            settings.contains("""const val SUPPORT_URL = "https://buymeacoffee.com/divsysx""""),
        )
    }

    @Test
    fun `each project link is written down exactly once`() {
        // The regression this guards is the second copy: a "Buy me a coffee" row added to the
        // Account screen or the community pane, which then has to be found and changed in two
        // places when the address moves. The search is for the *quoted* literal, so the issues
        // tracker's `.../Attendo/issues` is a different address and does not count as a match.
        listOf(
            "https://github.com/divsysx/Attendo",
            "https://buymeacoffee.com/divsysx",
        ).forEach { url ->
            val hits = allAppSources().filter { it.readText().contains("\"$url\"") }
            assertEquals(
                "Only SettingsScreen.kt may name $url — found it in ${hits.map { it.name }}",
                1,
                hits.size,
            )
            assertTrue(hits.single().name == "SettingsScreen.kt")
        }
    }

    @Test
    fun `the project links open outside the app`() {
        // The brief: reuse the app's existing URL mechanism, add no WebView. A WebView here
        // would be a second browser to maintain — and one that can lie about its address
        // while showing a login page.
        assertTrue(settings.contains("context.openUrl(GITHUB_REPO_URL)"))
        assertTrue(settings.contains("context.openUrl(SUPPORT_URL)"))
        assertFalse(settings.contains("WebView"))
        assertFalse(settings.contains("android.webkit"))
    }

    @Test
    fun `there is one way out of the app to a URL`() {
        // The footer reuses Feedback.kt's helper rather than writing its own intent, so the
        // "this phone has no browser" case stays handled in exactly one place.
        val helpers = allAppSources().filter {
            Regex("fun Context\\.openUrl\\(").containsMatchIn(it.readText())
        }

        assertEquals("Exactly one openUrl helper, in Feedback.kt.", 1, helpers.size)
        assertEquals("Feedback.kt", helpers.single().name)
        assertTrue(
            "It must stay reachable from SettingsScreen.kt.",
            helpers.single().readText().contains("internal fun Context.openUrl("),
        )
    }

    @Test
    fun `the footer keeps the version line and does not become a category`() {
        // The brief: the existing version information and layout stay intact, and this is a
        // footer rather than a fourth settings destination. The version block is unchanged;
        // the links were appended to it.
        assertTrue(settings.contains("""Text(text = "Attendo", style = MaterialTheme.typography.bodyLarge)"""))
        assertTrue(settings.contains("text = state.versionLabel"))
        assertEquals(
            "One About heading, and no second one written for the footer.",
            1,
            Regex("""SectionLabel\("About"\)""").findAll(settings).count(),
        )
        listOf("Star", "Support", "Project", "Links", "Donate").forEach { heading ->
            assertFalse(
                "The footer must not grow a heading of its own — found \"$heading\".",
                settings.contains("""SectionLabel("$heading"""),
            )
        }
    }

    @Test
    fun `the star request and the support row are both on screen`() {
        // Wording is behaviour here: a row that opened the repo without asking for a star
        // would not be the thing the brief asked for, and "Support development" with no
        // destination named tells the student nothing about where the tap goes.
        assertTrue(settings.contains("Enjoying Attendo? Star the project on GitHub"))
        assertTrue(settings.contains("View source code"))
        assertTrue(settings.contains("Support development"))
        assertTrue(settings.contains("Buy me a coffee"))
    }
}
