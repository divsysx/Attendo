package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of "is this downloaded file safe to install".
 *
 * The Android half ([com.attendo.data.update.UpdateVerifier]) can only be exercised with a
 * real package manager; every *decision*, which is the part that must never be wrong, is
 * made here from six plain facts, and is therefore tested here from six plain facts.
 *
 * The order matters as much as the outcomes: identity before version before signer before
 * checksum, cheapest and most decisive first. These tests walk each refusal in that order
 * with all other facts healthy, so a regression in the ordering shows up as the wrong
 * refusal, not as a pass.
 */
class ApkGateTest {

    private val installed = AppVersionRef(name = "1.1", code = 2)

    /** The certificate every Attendo release is signed with — [ApkGate.RELEASE_SIGNER_SHA256]. */
    private val signer = ApkGate.RELEASE_SIGNER_SHA256

    private fun manifest(
        versionCode: Long = 3,
        apkSha256: String? = null,
    ) = UpdateManifest(
        versionName = "1.2",
        versionCode = versionCode,
        apkUrl = "https://github.com/divsysx/Attendo/releases/download/v1.2/attendo-1.2.apk",
        apkSha256 = apkSha256,
    )

    private fun decide(
        manifest: UpdateManifest = manifest(),
        installed: AppVersionRef = this.installed,
        apkPackage: String? = ApkGate.EXPECTED_PACKAGE,
        apkVersionCode: Long? = manifest.versionCode,
        signerSha256: String? = signer,
        fileSha256: String? = manifest.apkSha256 ?: "f".repeat(64),
    ): ApkVerification = ApkGate.decide(
        manifest = manifest,
        installed = installed,
        apkPackage = apkPackage,
        apkVersionCode = apkVersionCode,
        signerSha256 = signerSha256,
        fileSha256 = fileSha256,
    )

    // ---- the pass ------------------------------------------------------------

    @Test
    fun `the right package, newer, signed correctly, with a matching checksum passes`() {
        val digest = "a".repeat(64)

        val verdict = decide(manifest = manifest(apkSha256 = digest), fileSha256 = digest)

        assertEquals(ApkVerification.Verified(manifest(apkSha256 = digest)), verdict)
    }

    @Test
    fun `a release that published no checksum still verifies on identity and signer`() {
        // The checksum is the strongest check but an optional one: verification must not
        // refuse an update because a sidecar file was forgotten at publish time.
        assertEquals(ApkVerification.Verified(manifest()), decide(manifest = manifest()))
    }

    @Test
    fun `the checksum comparison ignores case`() {
        // The digest arrives lowercase-hex from our own parser but the comparison should
        // not depend on that convention holding forever.
        val verdict = decide(manifest = manifest(apkSha256 = "B".repeat(64)), fileSha256 = "b".repeat(64))

        assertTrue(verdict is ApkVerification.Verified)
    }

    // ---- identity ------------------------------------------------------------

    @Test
    fun `a file that does not parse as an APK is not an APK`() {
        // Null facts are what "the platform could not read this file" looks like.
        assertEquals(ApkVerification.NotAnApk, decide(apkPackage = null))
        assertEquals(ApkVerification.NotAnApk, decide(apkVersionCode = null))
    }

    @Test
    fun `an APK for a different package is refused`() {
        // A repackaged impostor claiming to be Attendo is the attack this check exists
        // for; it must be refused on identity before anything else is even considered.
        assertEquals(ApkVerification.WrongPackage, decide(apkPackage = "com.not.attendo"))
    }

    // ---- version -------------------------------------------------------------

    @Test
    fun `an APK at the installed version is refused`() {
        assertEquals(ApkVerification.NotNewer, decide(manifest = manifest(), apkVersionCode = 2))
    }

    @Test
    fun `an APK older than the installed version is refused`() {
        assertEquals(ApkVerification.NotNewer, decide(manifest = manifest(), apkVersionCode = 1))
    }

    // ---- signing -------------------------------------------------------------

    @Test
    fun `an unsigned or unreadable signature is refused`() {
        assertEquals(ApkVerification.NotSignedByReleaseKey, decide(signerSha256 = null))
    }

    @Test
    fun `a signature by any other certificate is refused`() {
        assertEquals(ApkVerification.WrongSigner, decide(signerSha256 = "0".repeat(64)))
    }

    @Test
    fun `the expected signer is the release certificate's public digest`() {
        // Public key material by construction: the same 64 hex digits apksigner prints for
        // anyone holding a released APK. 64 lowercase hex digits, and nothing else — the
        // private half of this key never appears in the app.
        assertTrue(ApkGate.RELEASE_SIGNER_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertEquals("com.attendo", ApkGate.EXPECTED_PACKAGE)
    }

    // ---- checksum ------------------------------------------------------------

    @Test
    fun `a file whose digest differs from the published one is refused`() {
        assertEquals(
            ApkVerification.ChecksumMismatch,
            decide(manifest = manifest(apkSha256 = "a".repeat(64)), fileSha256 = "b".repeat(64)),
        )
    }

    @Test
    fun `identity outranks the checksum - a wrong package reports as a wrong package`() {
        // Both facts are broken here; the verdict must be the identity one, because
        // "wrong package" is the sentence that matters most.
        val verdict = decide(
            manifest = manifest(apkSha256 = "a".repeat(64)),
            apkPackage = "com.other.app",
            fileSha256 = "b".repeat(64),
        )

        assertEquals(ApkVerification.WrongPackage, verdict)
    }
}
