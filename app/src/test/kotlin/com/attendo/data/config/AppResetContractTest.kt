package com.attendo.data.config

import com.attendo.data.AndroidBackupStore
import com.attendo.data.AppearanceStore
import com.attendo.data.AppReset
import com.attendo.data.SettingsStore
import com.attendo.data.update.UpdateCheckStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contract of "Clear all Attendo data": everything the app has written on this phone,
 * and nothing that is a preference about the phone.
 *
 * The reset clears the two preferences files and the two `filesDir` directories the app
 * ever writes, plus the database and the cache. The lists below are not documentation of
 * that — they *are* it, the same values [AppReset] iterates at runtime — so this test
 * failing means the reset itself, not a comment, has drifted. The two device-level
 * preferences (Automatic backup, Appearance) are pinned as absent from them: a reset that
 * quietly switched Android backup back on, or dark mode back off, would be a reset with a
 * surprise in it, and a fresh-looking surprise is the worst kind.
 */
class AppResetContractTest {

    @Test
    fun `the reset clears exactly the two preference files the app writes`() {
        assertEquals(
            "Student data and update-system memory, and nothing else: the file names come " +
                "from the stores themselves, so a store renaming its file cannot leave the " +
                "reset clearing a name nobody uses.",
            listOf(SettingsStore.FILE_NAME, UpdateCheckStore.FILE_NAME),
            AppReset.clearedPreferenceFiles,
        )
    }

    @Test
    fun `the reset clears the undo snapshot and the downloaded update`() {
        // "backup" is where AppContainer puts SafetySnapshotStore; "updates" is UpdateFiles'
        // directory. Both recreate their directory on demand, so deleting them outright is
        // the reset's business.
        assertTrue(AppReset.clearedDirectories.contains("backup"))
        assertTrue(AppReset.clearedDirectories.contains("updates"))
    }

    @Test
    fun `the two device-level preferences survive the reset`() {
        val preserved = listOf(AndroidBackupStore.FILE_NAME, AppearanceStore.FILE_NAME)

        for (file in preserved) {
            assertFalse(
                "$file is a preference about this phone, not the student's data; clearing " +
                    "it along with the semester would be a silent settings change.",
                AppReset.clearedPreferenceFiles.contains(file),
            )
        }
    }

    @Test
    fun `the database itself is wiped, not just the preferences`() {
        // Attendance, patterns, courses and semesters live in Room; a reset that only
        // cleared preferences would leave the whole semester in place with default
        // settings on top of it. Room's clearAllTables is the one sanctioned way to empty
        // every table and keep the file consistent.
        val source = sourceFile("src/main/kotlin/com/attendo/data/AppReset.kt").readText()

        assertTrue(
            "AppReset must empty the database through clearAllTables.",
            source.contains("clearAllTables()"),
        )
        assertTrue(
            "AppReset must wipe the cache directory as well.",
            source.contains("cacheDir"),
        )
    }

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
