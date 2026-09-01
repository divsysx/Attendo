package com.attendo.data.update

import com.attendo.core.update.AppVersionRef
import com.attendo.core.update.DistributionSource
import com.attendo.core.update.ReleaseChannel
import com.attendo.core.update.UpdateCheckOutcome
import com.attendo.core.update.UpdateManifest

/**
 * How an update for this build is discovered and delivered.
 *
 * One interface, because the *experience* of being told about an update — a card, a
 * changelog, a size, a button — is the same whatever the source, while *what can be
 * done about it* is entirely the source's business. The UI talks to
 * [UpdateManager] and never to a provider.
 *
 * Two implementations are planned:
 *
 * - [DirectApkUpdateProvider] — the GitHub releases channel this app ships through today.
 *   Attendo downloads the APK itself, verifies it, and hands it to Android's installer.
 *
 * - Google Play's in-app updates — when and if Attendo is ever distributed through the
 *   Play Store. Play owns discovery, download and installation; a provider around it
 *   would surface the same [UpdateManifest]-shaped facts from Play's own API and let
 *   Play drive the mechanics. Nothing here couples the UI to GitHub, so adding that
 *   provider is a new class plus a different wiring in [com.attendo.AppContainer] — no
 *   screen changes.
 *
 * Which provider a build gets is decided by how it was installed — see
 * [DistributionSource] and [InstallSourceReader]. A Play-installed build gets no direct
 * APK provider at all: [UpdateManager] runs with a null provider, reports itself
 * unsupported, and Settings shows no update section, because the one thing worse than no
 * update flow is one that tries to overwrite another channel's install.
 */
interface UpdateProvider {
    /** The channel this provider serves. Reserved for the Stable/Beta split. */
    val channel: ReleaseChannel

    /**
     * The latest release this provider knows about, resolved on the network.
     * Null means "nothing to say" — offline, unreachable, or unusable metadata are all
     * the same state to the caller, and none of them is the student's problem.
     */
    suspend fun latestRelease(): UpdateManifest?
}

/**
 * The GitHub releases channel: Attendo's own distribution path for APKs installed
 * directly from the Releases page.
 *
 * A thin class on purpose — [GitHubApi] holds the mechanics — so what it mainly adds to
 * the system is the *name* of the channel and the boundary the UI is written against.
 */
class DirectApkUpdateProvider(
    private val api: GitHubApi = GitHubApi(),
) : UpdateProvider {
    override val channel: ReleaseChannel = ReleaseChannel.STABLE

    override suspend fun latestRelease(): UpdateManifest? = api.latestRelease()
}

/**
 * Turns provider results into the verdicts the UI shows, against the installed version.
 *
 * Pure, so the "is this an update" decision sits in the same tested place as the rest of
 * the update logic rather than in a ViewModel.
 */
object UpdateDecision {
    fun decide(installed: AppVersionRef, manifest: UpdateManifest?): UpdateCheckOutcome =
        when {
            manifest == null -> UpdateCheckOutcome.Failed("no release could be read")
            installed.isUpdateTo(manifest) -> UpdateCheckOutcome.Available(manifest)
            else -> UpdateCheckOutcome.UpToDate
        }
}
