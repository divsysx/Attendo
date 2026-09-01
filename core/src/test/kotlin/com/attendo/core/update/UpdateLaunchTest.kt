package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What happens when the app opens, decided from remembered state alone — [UpdateLaunch].
 *
 * Every test here is one of the promises the automatic update experience rests on: a
 * cached update is raised without a network round-trip, a dismissal sticks, the 24-hour
 * interval gates only the network, a fresh find surfaces unless it was declined, and a
 * cached release at or below the installed build never shows. The manager on the Android
 * side only carries this plan out; the wiring that it does is pinned separately in `:app`.
 */
class UpdateLaunchTest {

    private val now: Instant = Instant.parse("2026-09-01T09:00:00Z")

    /** This installed build: 1.1, code 2 — the release the app is on as these tests are written. */
    private val installed = AppVersionRef(name = "1.1", code = 2)

    private fun manifest(versionCode: Long? = 3, versionName: String = "1.2") = UpdateManifest(
        versionName = versionName,
        versionCode = versionCode,
        apkUrl = "https://github.com/divsysx/Attendo/releases/download/v$versionName/attendo-$versionName.apk",
    )

    private fun plan(
        cachedManifest: UpdateManifest? = manifest(),
        dismissedRelease: String? = null,
        lastChecked: Instant? = now.minusSeconds(60 * 60),
        installed: AppVersionRef = this.installed,
    ) = UpdateLaunch.plan(
        cachedManifest = cachedManifest,
        dismissedRelease = dismissedRelease,
        lastChecked = lastChecked,
        installed = installed,
        now = now,
    )

    @Test
    fun `a cached newer update is raised without waiting for the network`() {
        // One hour since the last check — not due, no network — and the card still rises.
        // This is the student who was offline since a release came out.
        val plan = plan(lastChecked = now.minusSeconds(60 * 60))

        assertEquals(manifest(), plan.cachedUpdateToRaise)
        assertFalse("Inside the interval, nothing asks GitHub.", plan.networkCheckDue)
    }

    @Test
    fun `the first open of a fresh install is a check that is due`() {
        // No lastChecked at all: the policy's own rule, restated here because it is the
        // first experience of the feature — a new install hears about an update on its
        // first open, not its second.
        val plan = plan(lastChecked = null)

        assertTrue(plan.networkCheckDue)
    }

    @Test
    fun `a dismissed cached update is not raised again`() {
        // The dismissal is remembered by release key — "code:3" for a code-carrying release.
        val plan = plan(dismissedRelease = "code:3")

        assertNull("Not now means not now, on every later open.", plan.cachedUpdateToRaise)
    }

    @Test
    fun `a codeless dismissed release is not raised again either`() {
        // The legacy shape is dismissed by name; the same key must come back out of the
        // cached manifest for the comparison to see it.
        val legacy = manifest(versionCode = null, versionName = "1.2")

        val plan = plan(cachedManifest = legacy, dismissedRelease = "name:1.2")

        assertNull(plan.cachedUpdateToRaise)
    }

    @Test
    fun `a cached update newer than the dismissed one is raised`() {
        // 1.2 was declined; 1.3 is a genuinely different release with a different key.
        val plan = plan(cachedManifest = manifest(versionCode = 4, versionName = "1.3"), dismissedRelease = "code:3")

        assertEquals(manifest(versionCode = 4, versionName = "1.3"), plan.cachedUpdateToRaise)
    }

    @Test
    fun `opening inside the interval does not ask the network`() {
        assertFalse(plan(lastChecked = now.minusSeconds(23 * 60 * 60)).networkCheckDue)
    }

    @Test
    fun `opening after the interval asks the network`() {
        assertTrue(plan(lastChecked = now.minusSeconds(25 * 60 * 60)).networkCheckDue)
        // Exactly the interval counts as due: the policy is "at least", not "more than".
        assertTrue(plan(lastChecked = now.minusSeconds(24 * 60 * 60)).networkCheckDue)
    }

    @Test
    fun `a cached release at or below the installed build is never raised`() {
        // Equal code, older code, and the legacy name-only comparison — none of these is
        // an update, however each came to be cached, and none may ever offer a downgrade.
        assertNull(plan(cachedManifest = manifest(versionCode = 2, versionName = "1.1")).cachedUpdateToRaise)
        assertNull(plan(cachedManifest = manifest(versionCode = 1, versionName = "1.0")).cachedUpdateToRaise)
        assertNull(plan(cachedManifest = manifest(versionCode = null, versionName = "1.0")).cachedUpdateToRaise)
    }

    @Test
    fun `nothing cached means nothing to raise, however due the check`() {
        // A fresh install, or the last check having found nothing. The plan has no card
        // to show before the network answers, and only the network answer can change that.
        val plan = plan(cachedManifest = null, lastChecked = now.minusSeconds(48 * 60 * 60))

        assertNull(plan.cachedUpdateToRaise)
        assertTrue(plan.networkCheckDue)
    }

    @Test
    fun `an update an automatic check just found is surfaced unless it is the dismissed one`() {
        assertTrue(
            "Nothing dismissed: a fresh find is shown.",
            UpdateLaunch.surfacesAutomatically(manifest(), dismissedRelease = null),
        )
        assertFalse(
            "The release the student declined: shown by no automatic path.",
            UpdateLaunch.surfacesAutomatically(manifest(), dismissedRelease = "code:3"),
        )
        assertTrue(
            "A different release from the dismissed one: a genuinely new release is heard about.",
            UpdateLaunch.surfacesAutomatically(
                manifest(versionCode = 4, versionName = "1.3"),
                dismissedRelease = "code:3",
            ),
        )
    }

    @Test
    fun `a release key is its code when it states one and its name otherwise`() {
        assertEquals("code:3", manifest(versionCode = 3).releaseKey)
        assertEquals("name:1.2", manifest(versionCode = null, versionName = "1.2").releaseKey)
    }
}
