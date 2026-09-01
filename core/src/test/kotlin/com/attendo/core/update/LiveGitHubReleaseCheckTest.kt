package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * A live check against the real, public GitHub repo — run on demand, never in the suite.
 *
 * Everything else in this package tests the parser against captured documents, which is
 * right for a suite: deterministic, offline, fast. But the parser's whole job is to read
 * what `divsysx/Attendo` actually publishes, and the real-world bug this file exists for
 * was exactly a gap between the fixtures and the real release: the published v1.0 release
 * carried no parenthesised version code, the parser refused it, and "Check for updates"
 * answered "couldn't check" on real phones while every fixture said green.
 *
 * So this test asks the real endpoint and runs the real assertions, but only when asked:
 *
 * ```
 * ATTENDO_LIVE_UPDATE_CHECK=true ./gradlew :core:test --tests '*LiveGitHubReleaseCheckTest*'
 * ```
 *
 * Skipped otherwise, so an offline machine or a rate-limited hour never fails the build.
 * Because it reads the *latest* release, its expectations are about the shape of the
 * answer rather than which release is latest — the version-specific expectations live in
 * the verbatim v1.0 fixture test in [UpdateManifestTest].
 */
class LiveGitHubReleaseCheckTest {

    @Test
    fun `the real latest release parses and compares honestly`() {
        assumeTrue(
            "Set ATTENDO_LIVE_UPDATE_CHECK=true to run the live check",
            System.getenv("ATTENDO_LIVE_UPDATE_CHECK") == "true",
        )

        val body = getText("https://api.github.com/repos/divsysx/Attendo/releases/latest")
        val manifest = GitHubReleaseParser.parseLatest(body).getOrThrow()

        // Whatever is latest, the parser must have made sense of it.
        assertTrue(manifest.versionName.isNotEmpty())
        assertTrue(manifest.apkUrl.endsWith(".apk"))
        assertTrue(manifest.apkSizeBytes != null && manifest.apkSizeBytes > 0)

        // The release being latest does not make it an update. The device this was
        // verified on runs 1.1 (code 2): whatever GitHub returns, the verdict must be
        // "no update available" unless the release is genuinely newer — never a
        // downgrade, never a failure on good metadata.
        val installed = AppVersionRef(name = "1.1", code = 2)
        if (manifest.versionCode != null) {
            assertEquals(manifest.versionCode > 2L, installed.isUpdateTo(manifest))
        } else {
            // The legacy shape: comparable by name only when the name is comparable.
            val newer = VersionNames.compare(manifest.versionName, "1.1")
            if (newer != null) {
                assertEquals(newer > 0, installed.isUpdateTo(manifest))
            } else {
                assertFalse(installed.isUpdateTo(manifest))
            }
        }

        // And the current reality, pinned while v1.0 is the latest release: a codeless
        // legacy release one version behind the installed build is not an update.
        if (manifest.versionName == "1.0" && manifest.versionCode == null) {
            assertFalse(installed.isUpdateTo(manifest))
        }
    }

    /** A plain GET, GitHub's required User-Agent included. */
    private fun getText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Attendo-Android")
        try {
            assertEquals(200, connection.responseCode)
            return connection.inputStream.readBytes().decodeToString()
        } finally {
            connection.disconnect()
        }
    }
}
