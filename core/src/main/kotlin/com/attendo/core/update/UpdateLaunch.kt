package com.attendo.core.update

import java.time.Instant

/**
 * What the update system should do when the app opens, decided from what it remembers
 * alone — no network, no clock but the one handed in.
 *
 * This is the logic behind the manager's launch-time check, extracted to `:core` because
 * every line of it is a promise about what a student sees when the app opens: a cached
 * update is shown again without asking GitHub anything, a dismissed release stays
 * dismissed, the 24-hour interval gates only the *network* and never the cached card, and
 * a cached release at or below the installed build is never offered. Those are the rules
 * the whole automatic-update experience rests on, and they belong where the rest of the
 * must-never-be-wrong logic is tested.
 *
 * The two halves are deliberately independent: [UpdateLaunchPlan.cachedUpdateToRaise] is
 * decided whatever the interval says, so a student who was offline since a release came
 * out still hears about it, and [UpdateLaunchPlan.networkCheckDue] is decided whatever
 * the cache holds, so a fresh check happens exactly when the policy says.
 */
data class UpdateLaunchPlan(
    /**
     * The cached update whose card should be raised at open, before any network work.
     * Null when there is nothing cached, nothing *newer* than the installed build, or
     * exactly the release the student already dismissed.
     */
    val cachedUpdateToRaise: UpdateManifest?,

    /** Whether the policy says this open is the one that asks the channel for news. */
    val networkCheckDue: Boolean,
)

/** The launch decision itself: remembered state in, what to do at open out. */
object UpdateLaunch {

    fun plan(
        cachedManifest: UpdateManifest?,
        dismissedRelease: String?,
        lastChecked: Instant?,
        installed: AppVersionRef,
        now: Instant,
        policy: UpdateCheckPolicy = UpdateCheckPolicy(),
    ): UpdateLaunchPlan = UpdateLaunchPlan(
        cachedUpdateToRaise = cachedManifest
            // Never a downgrade: a cached release at or below the installed build is not
            // an update, however it came to be cached.
            ?.takeIf { installed.isUpdateTo(it) }
            // "Not now" sticks to exactly the release it was said about. A genuinely
            // different release has a different key and is raised.
            ?.takeUnless { it.releaseKey == dismissedRelease },
        networkCheckDue = policy.isDue(lastChecked, now),
    )

    /**
     * Whether an update an automatic check has just found should be surfaced, asked only
     * of fresh results: yes unless it is the release the student dismissed. A manual
     * check never asks this — it answers the question the student asked, dismissal or no.
     */
    fun surfacesAutomatically(manifest: UpdateManifest, dismissedRelease: String?): Boolean =
        manifest.releaseKey != dismissedRelease
}
