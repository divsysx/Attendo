package com.attendo.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That "Automatic backup" is a real switch, not a saved boolean doing nothing.
 *
 * `android:allowBackup` is fixed at install time, so the toggle cannot be in the
 * manifest. What Android does support is a backup agent on the auto-backup path whose
 * [android.app.backup.BackupAgent.onFullBackup] decides, pass by pass, whether anything
 * is stored. The contract therefore spans four files — the manifest declares the
 * mechanism, the agent holds the gate, the store holds the default, and the rules say
 * what a permitted pass may copy — and every one of them is the kind of thing a
 * well-meaning cleanup or a template can silently flip, so this test reads the real
 * files and refuses to let that happen.
 *
 * The explicit backup file (Settings → Data & backup → Export backup) is untouched by
 * all of this and keeps its own round-trip tests over in `:core`.
 */
class AutoBackupConfigTest {

    private val manifest: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, "src/main/AndroidManifest.xml") }
            .first(File::exists)

    /** The module directory: the manifest's `src/main` parent. */
    private val module: File = manifest.parentFile!!.parentFile!!.parentFile!!

    private fun source(relative: String): File = File(module, relative)

    @Test
    fun `the manifest enables the mechanism and points it at the agent`() {
        val text = manifest.readText()

        assertTrue(
            "android:allowBackup must be \"true\": the switch is the agent's to make, " +
                "per pass, and off is the agent contributing nothing — not the manifest.",
            Regex("android:allowBackup=\"true\"").containsMatchIn(text),
        )
        assertTrue(
            "The backup agent is what Android lets decide per pass; without it the " +
                "manifest is a permanent yes or a permanent no.",
            Regex("android:backupAgent=\"com\\.attendo\\.data\\.AttendoBackupAgent\"")
                .containsMatchIn(text),
        )
        assertTrue(
            "android:fullBackupOnly keeps the agent on the file-based auto-backup path; " +
                "without it Android would expect key/value backup and never call onFullBackup.",
            Regex("android:fullBackupOnly=\"true\"").containsMatchIn(text),
        )
        assertTrue(
            "Android 12+ reads dataExtractionRules; it must point at the rules file.",
            Regex("android:dataExtractionRules=\"@xml/data_extraction_rules\"").containsMatchIn(text),
        )
        assertTrue(
            "Android 11 and lower read fullBackupContent; it must point at the rules file.",
            Regex("android:fullBackupContent=\"@xml/backup_rules\"").containsMatchIn(text),
        )
    }

    @Test
    fun `the agent gates every pass on the toggle and defers to the system when on`() {
        val text = source("src/main/kotlin/com/attendo/data/AttendoBackupAgent.kt").readText()

        assertTrue(
            "The gate: a pass with the toggle off must contribute nothing, which is an " +
                "onFullBackup that returns before super.",
            text.contains("if (!AndroidBackupStore.isEnabled(this)) return"),
        )
        assertTrue(
            "With the toggle on the system's own rule-following implementation must run, " +
                "or the toggle would be off in every direction.",
            text.contains("super.onFullBackup(data)"),
        )
    }

    @Test
    fun `the toggle defaults to off`() {
        val text = source("src/main/kotlin/com/attendo/data/AndroidBackupStore.kt").readText()

        assertEquals(
            "Every read of the toggle must pass the same default — false — so that a fresh " +
                "install, and any backup pass on it, contributes nothing until the student " +
                "says otherwise.",
            2,
            Regex("getBoolean\\(KEY_ENABLED, false\\)").findAll(text).count(),
        )
    }

    @Test
    fun `the rules carry the database its log and the settings and nothing else`() {
        val expected = listOf(
            "attendo.db",
            "attendo.db-wal",
            "attendo-settings.xml",
            "attendo-android-backup.xml",
            "attendo-appearance.xml",
        )

        val legacy = source("src/main/res/xml/backup_rules.xml").readText()
        val modern = source("src/main/res/xml/data_extraction_rules.xml").readText()

        for (path in expected) {
            assertTrue(
                "backup_rules.xml (Android 11 and lower) must include $path.",
                legacy.contains("<include domain=\"database\" path=\"$path\" />") ||
                    legacy.contains("<include domain=\"sharedpref\" path=\"$path\" />"),
            )
            assertTrue(
                "data_extraction_rules.xml (Android 12+) must include $path.",
                modern.contains("<include domain=\"database\" path=\"$path\" />") ||
                    modern.contains("<include domain=\"sharedpref\" path=\"$path\" />"),
            )
        }

        // Both destinations on Android 12+: what the cloud may copy and what a device
        // transfer may copy are decided by the same toggle, so they must say the same.
        val cloudBackup = modern.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val deviceTransfer = modern.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertEquals(
            "Cloud backup and device transfer are gated by one toggle, so their rules " +
                "must not promise different things.",
            cloudBackup.trim(),
            deviceTransfer.trim(),
        )

        // The WAL's scratch twin is deliberately absent: SQLite rebuilds it, and a stale
        // copy is worse than none.
        assertFalse("attendo.db-shm must not be backed up.", legacy.contains("attendo.db-shm"))
        assertFalse("attendo.db-shm must not be backed up.", modern.contains("attendo.db-shm"))
    }
}
