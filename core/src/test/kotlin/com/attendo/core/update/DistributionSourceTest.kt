package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How an install's installer of record becomes a distribution source.
 *
 * The stakes are specific: the one wrong answer this mapping could give is DIRECT_APK for
 * a build Google Play installed, because that is the answer that leads to offering a
 * sideloaded APK over Play's own update path. Every other installer — browsers, file
 * managers, the manual package installer, `adb` with no installer recorded at all — really
 * is a direct APK install, which is the only way Attendo is distributed today.
 */
class DistributionSourceTest {

    @Test
    fun `the Play Store's installer means Google Play owns the install`() {
        assertEquals(
            DistributionSource.GOOGLE_PLAY,
            DistributionSource.fromInstallerPackage("com.android.vending"),
        )
    }

    @Test
    fun `no installer at all means a direct APK install`() {
        // An `adb install`, or a file manager that records nothing. The direct APK flow is
        // the only one this app has, so this is the answer a development build gets too.
        assertEquals(DistributionSource.DIRECT_APK, DistributionSource.fromInstallerPackage(null))
    }

    @Test
    fun `a browser or file manager that performed the install means a direct APK`() {
        // The three ways a student actually sideloads Attendo: a browser that hands the
        // APK to the installer and records itself, the stock package installer, and a
        // file manager. None of them is a store with a claim on the build.
        assertEquals(
            DistributionSource.DIRECT_APK,
            DistributionSource.fromInstallerPackage("com.android.chrome"),
        )
        assertEquals(
            DistributionSource.DIRECT_APK,
            DistributionSource.fromInstallerPackage("com.android.packageinstaller"),
        )
        assertEquals(
            DistributionSource.DIRECT_APK,
            DistributionSource.fromInstallerPackage("com.sec.android.app.myfiles"),
        )
    }

    @Test
    fun `the check is exact, not prefix-matched`() {
        // A package name that merely starts with Play's is not Play's.
        assertEquals(
            DistributionSource.DIRECT_APK,
            DistributionSource.fromInstallerPackage("com.android.vending.fake"),
        )
    }
}
