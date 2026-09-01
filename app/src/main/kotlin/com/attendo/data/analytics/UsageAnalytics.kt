package com.attendo.data.analytics

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent

/**
 * Attendo's entire analytics vocabulary, in one file.
 *
 * Firebase Analytics is used for what it is good at and nothing else: aggregate, anonymous
 * usage information — daily, weekly and monthly active users, app versions in the wild,
 * first opens, and broad engagement trends. All of that is automatic; the SDK reports it
 * from an install-scoped id that Android and Firebase themselves reset on reinstall, and
 * this app adds nothing to it: no user id, no custom identifiers, no cross-install
 * identity of any kind. A reinstall counts as a new installation, which is the honest
 * reading of what it is.
 *
 * The deliberate boundary: **nothing a student records is ever an event or a parameter.**
 * No student names, no attendance percentages or records, no courses, no timetable data,
 * no room searches or availability history, no backup contents, no feedback text. Every
 * event below is about the update system alone, and carries no parameters at all — the
 * only fact worth attaching to "update available" is that one was seen, and the version
 * in question is already in the automatic `app_version` dimension.
 *
 * Keeping every call behind this class is what makes that boundary checkable: the rest of
 * the app has no route to Firebase, and a review of what is collected is a review of this
 * one file.
 */
class UsageAnalytics(context: Context) {

    private val analytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context)

    /** The student pressed "Check for updates" in Settings. */
    fun manualUpdateCheck() = analytics.logEvent(EVENT_MANUAL_CHECK) {}

    /** An update check surfaced a newer release. */
    fun updateAvailable() = analytics.logEvent(EVENT_AVAILABLE) {}

    /** The student started downloading an update. */
    fun updateDownloadStarted() = analytics.logEvent(EVENT_DOWNLOAD_STARTED) {}

    /** A download ran to completion. Verification follows. */
    fun updateDownloadCompleted() = analytics.logEvent(EVENT_DOWNLOAD_COMPLETED) {}

    /** The downloaded file failed one of the safety checks and was discarded. */
    fun updateVerificationFailed() = analytics.logEvent(EVENT_VERIFICATION_FAILED) {}

    /** The verified APK was handed to Android's package installer. */
    fun updateInstallLaunched() = analytics.logEvent(EVENT_INSTALL_LAUNCHED) {}

    private companion object {
        // Update-flow events only, snake_case per Firebase's naming convention, and
        // deliberately parameterless — see the class doc.
        const val EVENT_MANUAL_CHECK: String = "manual_update_check"
        const val EVENT_AVAILABLE: String = "update_available"
        const val EVENT_DOWNLOAD_STARTED: String = "update_download_started"
        const val EVENT_DOWNLOAD_COMPLETED: String = "update_download_completed"
        const val EVENT_VERIFICATION_FAILED: String = "update_verification_failed"
        const val EVENT_INSTALL_LAUNCHED: String = "update_install_launched"
    }
}
