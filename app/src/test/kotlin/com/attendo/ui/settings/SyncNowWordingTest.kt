package com.attendo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a finished Sync now says, held as words.
 *
 * The success line used to read "Attendance is up to date.", which was true of the app the sync
 * was first built for and stopped being true as the pass grew: it now carries the semesters, the
 * courses, their timetable patterns and the account's settings row as well as the class records.
 * A student whose timetable moved on the other phone and pressed Sync now was told their
 * attendance was current — technically the largest part of the answer, and not the question.
 *
 * "All data is up to date." is the whole of the fix. What these tests hold is that it stays the
 * whole of it: one sentence, in one place, covering the whole pass — and that the sentences which
 * really are about attendance were not swept up with it.
 */
class SyncNowWordingTest {

    private fun appSources(): List<File> {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "src/main/kotlin") }
            .first(File::exists)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `a finished sync says everything is current, not that attendance is`() {
        // Written out rather than compared against the constant, so that a rewrite of the
        // constant cannot quietly agree with itself.
        assertEquals("All data is up to date.", accountSyncResultLabel(AccountSyncResult.Synced))
        assertEquals("All data is up to date.", SYNC_NOW_SYNCED)
    }

    @Test
    fun `the pass is described by what it carries`() {
        // The reason the sentence changed, asserted rather than remembered. If a later change
        // ever narrows the pass back to attendance alone, this is where the wording question
        // comes up again instead of drifting.
        val engine = appSources().single { it.name == "AttendanceSyncEngine.kt" }.readText()

        assertTrue("the pass reads and writes every synced table", engine.contains("SyncTable.entries"))
        assertTrue("including the courses", engine.contains("SyncTable.COURSES"))
        assertTrue("and their patterns", engine.contains("SyncTable.PATTERNS"))
    }

    @Test
    fun `the sentence is written down once, and the old one nowhere`() {
        // One operation, one success line. A second string for part of the pass would be a
        // second thing to keep true, and the sentence this replaced is the one a future edit
        // is most likely to reintroduce by copy-paste.
        val offenders = appSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.contains("\"All data is up to date.\"") }
                .map { (index, _) -> "${file.name}:${index + 1}" }
        }

        assertEquals("The success line has exactly one definition.", 1, offenders.size)
        assertTrue("and it is the one the label reads from", offenders.single().startsWith("AccountSettingsScreen.kt:"))

        val retired = appSources().filter { it.readText().contains("Attendance is up to date") }
        assertEquals(
            "The old sentence described part of the answer, not the answer.",
            emptyList<String>(),
            retired.map { it.name },
        )
    }

    @Test
    fun `a failed sync still says what went wrong, and none of it says everything is fine`() {
        // The other half of changing a success line: the three ways the same press can come
        // back unhappy must not have been made generic along with it. Each names its own cause,
        // and the refusal names attendance because that is what the ownership check is about.
        assertEquals("Couldn't sync just now. Try again later.", accountSyncResultLabel(AccountSyncResult.Unreachable))
        assertEquals(
            "This phone's attendance belongs to a different account, so nothing was uploaded.",
            accountSyncResultLabel(AccountSyncResult.Refused),
        )
        assertEquals("Couldn't sync just now. Try again later.", accountSyncResultLabel(AccountSyncResult.Unavailable))

        for (result in listOf(AccountSyncResult.Unreachable, AccountSyncResult.Refused, AccountSyncResult.Unavailable)) {
            assertFalse(
                "a failure must never read as success",
                accountSyncResultLabel(result).contains("up to date"),
            )
        }
    }

    @Test
    fun `the attendance-specific copy elsewhere was left alone`() {
        // The brief's line: only the wording that stands for the whole pass moves. These all
        // mean attendance and only attendance — a backup export, the account switch warning,
        // the reconcile prompt, the reporting identity — and "data" in any of them would name
        // something broader than what the sentence is about.
        assertEquals(
            "Attendance from the removed account is kept on this phone.\n\n" +
                "The attendance that had been backed up to the removed account was set aside here " +
                "when you signed in, so that it could not be uploaded as this account's. A copy of it " +
                "is still on this phone, and it is not part of this account. Clearing Attendo's data " +
                "removes it.",
            attendanceSetAsideExplanation(),
        )
        assertTrue(ACCOUNT_SWITCH_CONFIRM.startsWith("This phone already holds attendance belonging to another account."))
        assertTrue(RECONCILE_RETURNING_PROMPT.contains("Attendance was recorded or edited on this phone while signed out."))
    }
}
