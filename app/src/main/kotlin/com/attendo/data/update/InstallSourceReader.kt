package com.attendo.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.attendo.core.update.DistributionSource

/**
 * Asks Android who installed this build, and hands the answer to
 * [DistributionSource.fromInstallerPackage].
 *
 * API 30 grew `getInstallSourceInfo`, which distinguishes the app that *initiated* the
 * install from the store that physically performed it (a browser that downloaded the APK
 * and handed it to a package installer reports both). The initiating package is the right
 * fact for this question — a browser-initiated install is a direct APK install whoever
 * finished the mechanical part. Before API 30 there is only `getInstallerPackageName`,
 * which reports the same store when there was one and null otherwise.
 *
 * Both calls can misreport on some OEM builds, so the read is total: anything Android
 * cannot say becomes null, and null is a direct APK install as far as this app is
 * concerned — which is the safe answer here, because it only *offers* updates; every
 * install still passes through Android's own approval.
 */
class InstallSourceReader(private val context: Context) {

    /** How this build was installed. */
    fun current(): DistributionSource =
        DistributionSource.fromInstallerPackage(installerPackage())

    private fun installerPackage(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName)
                .initiatingPackageName
                ?: context.packageManager.getInstallSourceInfo(context.packageName)
                    .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
}
