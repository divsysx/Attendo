package com.attendo.core.update

/**
 * How this particular install of Attendo got onto the phone.
 *
 * The update system needs this because the right way to update an app depends on where it
 * came from: a build installed from GitHub Releases can be updated by downloading the next
 * release's APK inside the app, while a build installed from Google Play must be updated
 * by Play or not at all — Play owns that install, and offering to overwrite it with a
 * sideloaded APK is exactly the flow that must not happen.
 *
 * The decision is made from the installer of record, which Android tracks per install:
 *
 * - `com.android.vending` — Google Play installed it, so only Play may update it.
 * - Anything else — a browser, a file manager, `com.android.packageinstaller`, an `adb
 *   install` (which records no installer at all) — is a direct APK install, which is how
 *   Attendo is distributed today. Attendo publishes nowhere else, so no installer other
 *   than Play's can be some other store's claim on this build.
 */
enum class DistributionSource {

    /** Installed outside any store, from an APK — GitHub Releases today. Ours to update. */
    DIRECT_APK,

    /** Installed by Google Play. Play's in-app updates are the only correct provider. */
    GOOGLE_PLAY;

    companion object {

        /** The installer of record on every Play Store install. */
        const val PLAY_STORE_INSTALLER: String = "com.android.vending"

        /**
         * Reads an installer package name the way Android reports it — null when there is
         * none, which is what a manual or `adb` install looks like — into a source.
         *
         * There is deliberately no "unknown" outcome: the only installer that changes the
         * answer is Play's, because it is the only channel with an existing claim on the
         * install. Everything else, including no installer at all, is a direct APK.
         */
        fun fromInstallerPackage(installer: String?): DistributionSource =
            if (installer == PLAY_STORE_INSTALLER) GOOGLE_PLAY else DIRECT_APK
    }
}
