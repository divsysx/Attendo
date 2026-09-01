package com.attendo.core.update

import java.time.Instant

/**
 * Whether it is time to ask the network about updates.
 *
 * The rule lives in `:core` because it is the part of update checking that must never be
 * wrong, and `:core` is where logic that must never be wrong lives. An app that phoned
 * home on every cold start would be the one network behaviour this app has never had, so
 * the cadence is a value with a name rather than a number buried in a ViewModel.
 *
 * Manual checks from Settings bypass this entirely — a student pressing "Check for
 * updates" is an instruction, not a poll.
 */
data class UpdateCheckPolicy(val interval: java.time.Duration = DEFAULT_INTERVAL) {

    /**
     * True when at least [interval] has passed since [lastChecked]. Never having checked
     * counts: the first run of the app is a check that is due.
     */
    fun isDue(lastChecked: Instant?, now: Instant): Boolean = when (lastChecked) {
        null -> true
        else -> !now.isBefore(lastChecked.plus(interval))
    }

    companion object {
        /** Once a day: enough to catch a release within a day of publishing, cheap enough to forget about. */
        val DEFAULT_INTERVAL: java.time.Duration = java.time.Duration.ofHours(24)
    }
}

/** What one round of update checking concluded. */
sealed interface UpdateCheckOutcome {

    /** A newer release exists and is worth offering. */
    data class Available(val manifest: UpdateManifest) : UpdateCheckOutcome

    /** This build is the latest. What "You're using the latest version" is built from. */
    data object UpToDate : UpdateCheckOutcome

    /**
     * The check could not complete. Carries no user-facing message on purpose: being
     * offline is not an error state for an attendance app, and the only honest thing to
     * show a student about a failed background check is nothing.
     */
    data class Failed(val reason: String) : UpdateCheckOutcome
}

/**
 * The verdict on a downloaded APK, from strongest failure to pass.
 *
 * Every refusal is one sentence in the app's voice — "We couldn't verify this update, so
 * it wasn't installed." — with the specific reason kept for logs. A student does not need
 * to know *which* check caught it; they need to know nothing was installed and why in
 * general terms, and to be able to tell the maintainer the detail if they report it.
 */
sealed interface ApkVerification {

    /** Every check passed; the file is the release and it is newer than what is installed. */
    data class Verified(val manifest: UpdateManifest) : ApkVerification

    /** The downloaded file's SHA-256 is not what the release published. */
    data object ChecksumMismatch : ApkVerification

    /** Signed, but by a certificate that is not Attendo's release key. */
    data object WrongSigner : ApkVerification

    /** Not signed with Attendo's release certificate at all — or not readable as an APK. */
    data object NotSignedByReleaseKey : ApkVerification

    /** A valid Attendo build, but the same version or older than the one installed. */
    data object NotNewer : ApkVerification

    /** Reads as an app, but not this app — a different package name. */
    data object WrongPackage : ApkVerification

    /** Not readable as an APK at all. */
    data object NotAnApk : ApkVerification
}

/**
 * The pure half of the "is this file safe to install" decision.
 *
 * The Android side ([com.attendo.data.update.UpdateVerifier]) gathers the facts — the
 * file's digest, its signing certificate, the package and versionCode Android reads out
 * of it — and this object turns those facts into an [ApkVerification]. Split this way, the
 * decision logic runs in the same JVM tests as everything else that must never be wrong.
 */
object ApkGate {

    /**
     * The full decision, in order of cheapest and most decisive first.
     *
     * The checksum is evaluated after identity not because it is less important but
     * because it is *more* specific: a file that is the right package, signed by the right
     * key, but has a different digest is the strangest failure of all, and "checksum
     * mismatch" is only a useful sentence when everything else about the file was right.
     */
    fun decide(
        manifest: UpdateManifest,
        installed: AppVersionRef,
        apkPackage: String?,
        apkVersionCode: Long?,
        signerSha256: String?,
        fileSha256: String?,
    ): ApkVerification {
        // A null package or version code means the file did not parse as an APK at all.
        if (apkPackage == null || apkVersionCode == null) return ApkVerification.NotAnApk
        if (apkPackage != expectedPackage(manifest)) return ApkVerification.WrongPackage
        if (apkVersionCode <= installed.code) return ApkVerification.NotNewer
        if (signerSha256 == null) return ApkVerification.NotSignedByReleaseKey
        if (!signerSha256.equals(releaseSignerSha256(manifest), ignoreCase = true)) {
            return ApkVerification.WrongSigner
        }
        manifest.apkSha256?.let { expected ->
            if (!fileSha256.equals(expected, ignoreCase = true)) return ApkVerification.ChecksumMismatch
        }
        return ApkVerification.Verified(manifest)
    }

    /** Attendo's application id, which the manifest's asset URL is published under. */
    fun expectedPackage(manifest: UpdateManifest): String = EXPECTED_PACKAGE

    /**
     * The public SHA-256 of the certificate that signs Attendo's releases, lowercase hex.
     *
     * This is public key material — the same digest `apksigner verify --print-certs`
     * prints for anyone holding the APK — and is all the verification needs. The private
     * half of that key never appears in the app, in the repo, or in this file.
     */
    fun releaseSignerSha256(manifest: UpdateManifest): String = RELEASE_SIGNER_SHA256

    /** True when [hex] is 64 hex digits — the shape a digest takes, before any comparison. */
    fun looksLikeSha256(hex: String?): Boolean =
        hex != null && hex.lowercase().matches(Regex("[0-9a-f]{64}"))

    /**
     * Attendo's application id. Stated here so the check is visible as a constant rather
     * than a coincidence of build configuration.
     */
    const val EXPECTED_PACKAGE: String = "com.attendo"

    /**
     * The release signing certificate's SHA-256, hex, lowercase.
     *
     * Public by definition — it is the digest `apksigner verify --print-certs` prints for
     * anyone holding a released APK — and it is the only thing about release signing the
     * app is entitled to know. The private half of that key never appears in the app or
     * in this repository.
     */
    const val RELEASE_SIGNER_SHA256: String =
        "c214a32129ab3f943352d977f9ee88f208b08fcb7a7210e640b9e8b894570671"
}
