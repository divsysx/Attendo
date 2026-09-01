package com.attendo.data.update

import com.attendo.core.update.GitHubReleaseParser
import com.attendo.core.update.UpdateManifest

/**
 * Reads Attendo's releases from the GitHub API — the structured, official source the
 * Direct APK update channel is built on. No HTML is scraped anywhere.
 *
 * The endpoint of record is `GET /repos/{owner}/{repo}/releases/latest`, which never
 * returns a draft or a pre-release: exactly the Stable channel's promise, for free.
 * A future Beta channel reads `releases` and filters by a prerelease flag — the same
 * client, a different call, which is all the architecture needs to leave open.
 */
class GitHubApi(
    private val http: HttpClient = HttpClient(),
    private val owner: String = OWNER,
    private val repo: String = REPO,
) {

    /**
     * The latest Stable release, with its checksum attached when one was published.
     *
     * Every failure is null rather than an exception. A student offline, GitHub down, a
     * malformed response — all of it is "no update available", because none of it is
     * something an attendance app should put on screen.
     */
    suspend fun latestRelease(): UpdateManifest? {
        val body = http.getText("$API_BASE/$owner/$repo/releases/latest") ?: return null
        val parsed = GitHubReleaseParser.parseLatest(body).getOrNull() ?: return null
        return withChecksum(parsed)
    }

    /**
     * Fetches the release's `.sha256` asset, when there is one, and returns the manifest
     * carrying it.
     *
     * A missing or unreadable checksum is not fatal: verification still has the package
     * identity, the signer and the version to fall back on. The checksum is the strongest
     * check, so the release process publishes one — but the app must not refuse to update
     * a student because a sidecar file was forgotten.
     */
    private suspend fun withChecksum(manifest: UpdateManifest): UpdateManifest {
        val assetUrl = checksumAssetUrl(manifest) ?: return manifest
        val body = http.getText(assetUrl) ?: return manifest
        val digest = GitHubReleaseParser.parseSha256(body) ?: return manifest
        return manifest.copy(apkSha256 = digest)
    }

    /** The checksum asset's URL, derived from the APK asset's URL by suffix. */
    private fun checksumAssetUrl(manifest: UpdateManifest): String? =
        manifest.apkUrl.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?.let { it + GitHubReleaseParser.SHA256_ASSET_SUFFIX }

    private companion object {
        const val API_BASE: String = "https://api.github.com/repos"
        const val OWNER: String = "divsysx"
        const val REPO: String = "Attendo"
    }
}
