package com.attendo.data.update

import com.attendo.core.update.AppVersionRef
import com.attendo.core.update.UpdateCheckOutcome
import com.attendo.core.update.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a provider's answer into the verdict the UI shows.
 *
 * The decision is four lines, and every one of them is a promise: a provider that came
 * back with nothing is a failed check rather than "up to date" (offline is not current),
 * an advertised version must be *newer*, not merely different, and the installed build
 * being ahead of the published one is up to date, not an update.
 *
 * [UpdateDecision] is pure, so these run on the JVM like the `:core` tests even though
 * the class lives in `:app` — it sits there because it speaks provider types.
 */
class UpdateDecisionTest {

    private val installed = AppVersionRef(name = "1.1", code = 2)

    private fun manifest(versionCode: Long = 3, versionName: String = "1.2") = UpdateManifest(
        versionName = versionName,
        versionCode = versionCode,
        apkUrl = "https://github.com/divsysx/Attendo/releases/download/v$versionName/attendo-$versionName.apk",
    )

    @Test
    fun `a newer release is available`() {
        val outcome = UpdateDecision.decide(installed, manifest(versionCode = 3))

        assertEquals(UpdateCheckOutcome.Available(manifest(versionCode = 3)), outcome)
    }

    @Test
    fun `the same version is up to date`() {
        assertEquals(UpdateCheckOutcome.UpToDate, UpdateDecision.decide(installed, manifest(versionCode = 2)))
    }

    @Test
    fun `the installed build being ahead of the advertised one is up to date`() {
        // A local build, or a release rolled back. This must never offer a downgrade.
        assertEquals(UpdateCheckOutcome.UpToDate, UpdateDecision.decide(installed, manifest(versionCode = 1)))
    }

    @Test
    fun `a provider with nothing to say is a failed check, never up to date`() {
        // Offline, rate-limited, a malformed document — all null. "You're using the
        // latest version" would be a claim nobody made; a quiet failure is honest.
        val outcome = UpdateDecision.decide(installed, null)

        assertTrue(outcome is UpdateCheckOutcome.Failed)
    }

    @Test
    fun `the real legacy release leaves an installed 1 point 1 build up to date`() {
        // The actual situation on 2026-09-01: the repo's latest release is the v1.0
        // one, published as "Attendo 1.0" with no parenthesised version code, while the
        // installed build is 1.1 (code 2). The name-only comparison must read the
        // release as older and answer up to date — "No update available." — never a
        // downgrade offer and never a failed check.
        val release = UpdateManifest(
            versionName = "1.0",
            versionCode = null, // "Attendo 1.0" predates the code convention
            title = "Attendo 1.0",
            apkUrl = "https://github.com/divsysx/Attendo/releases/download/v1.0/Attendo-1.0.apk",
        )

        assertEquals(UpdateCheckOutcome.UpToDate, UpdateDecision.decide(installed, release))
    }

    @Test
    fun `a codeless release with a newer name is available`() {
        // The legacy shape does not cap updates at 1.1: a future release published
        // without its code is still offered, compared by name.
        val release = UpdateManifest(versionName = "1.2", versionCode = null, apkUrl = "u")

        assertEquals(
            UpdateCheckOutcome.Available(release),
            UpdateDecision.decide(installed, release),
        )
    }
}
