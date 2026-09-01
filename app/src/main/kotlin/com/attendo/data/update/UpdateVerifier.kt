package com.attendo.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.attendo.core.update.ApkGate
import com.attendo.core.update.ApkVerification
import com.attendo.core.update.AppVersionRef
import com.attendo.core.update.UpdateManifest
import java.io.File
import java.security.MessageDigest

/**
 * Gathers the facts about a downloaded APK and lets [ApkGate] judge them.
 *
 * This is the Android half of verification: reading a package's identity and its signer
 * out of an APK file is a platform question, and deciding what those facts mean is a pure
 * one that belongs in `:core`'s tests. Everything here uses public APIs only — no hidden
 * interfaces, no reflection into package internals.
 *
 * The expected certificate is [ApkGate.RELEASE_SIGNER_SHA256]: the *public* SHA-256 of
 * Attendo's release signing certificate, the same digest anyone can read off a released
 * APK with `apksigner verify --print-certs`. The private key never enters the app, the
 * repository, or this file.
 */
class UpdateVerifier(private val context: Context) {

    /**
     * Verifies [apk] against [manifest] and the installed build.
     *
     * The checks, in order: parses as an APK, is this app's package, is newer than what
     * is installed, is signed by Attendo's release certificate, and — when the release
     * published a checksum — is exactly the file that was published. The verdict's
     * sealed-subtype shape means the UI cannot show a halfway state: either the file is
     * ready to install or there is one honest sentence about why it is not.
     */
    fun verify(apk: File, manifest: UpdateManifest): ApkVerification {
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, signerFlags())
        }.getOrNull() ?: return ApkVerification.NotAnApk

        val packageOk = info.packageName
        val versionCode = info.longVersionCode
        val signer = signerOf(info)

        val digest = sha256Of(apk)
        return ApkGate.decide(
            manifest = manifest,
            installed = installed(),
            apkPackage = packageOk,
            apkVersionCode = versionCode,
            signerSha256 = signer,
            fileSha256 = digest,
        )
    }

    /** This installed build, as the update system's comparison type. */
    fun installed(): AppVersionRef {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return AppVersionRef(name = info.versionName ?: "", code = info.longVersionCode)
    }

    /**
     * The flags that make an *archive* parse carry signing information. API 28 grew
     * `GET_SIGNING_CERTIFICATES`; before it, `GET_SIGNATURES` is the only reader — and
     * only understands v1 (JAR) signatures, which is why release builds sign with v1
     * alongside v2.
     */
    private fun signerFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** The APK's signing certificate digest, or null when the file offers none. */
    private fun signerOf(info: PackageInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { sha256Of(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.let { sha256Of(it) }
        }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    /** The digest of an APK signing certificate, hex, lowercase — [ApkGate]'s comparison form. */
    private fun sha256Of(signature: android.content.pm.Signature): String =
        sha256Of(signature.toByteArray())

    private fun sha256Of(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
