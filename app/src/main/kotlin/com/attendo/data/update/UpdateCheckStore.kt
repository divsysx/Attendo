package com.attendo.data.update

import android.content.Context
import com.attendo.core.update.UpdateManifest
import com.attendo.core.update.releaseKey
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * What the update system remembers between app starts: when the last successful check
 * was, what it found, and which release the student has already said "not now" to.
 *
 * Without the timestamp, a 24-hour policy would restart with every cold start and the app
 * would ask GitHub on every open — exactly the eager polling the policy exists to prevent.
 * Without the cached manifest, a student who was offline at the last open before a
 * release would hear nothing about the update until connectivity and a due check aligned.
 * And without the dismissal, "not now" would last exactly until the next app start.
 *
 * Failed checks are deliberately *not* recorded: only a check that completed gets a
 * timestamp, so a phone that could not reach GitHub tries again next open rather than
 * waiting out the interval on a failure it never saw the result of.
 */
class UpdateCheckStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** When the last *successful* check ran. Null when there has never been one. */
    fun lastChecked(): Instant? =
        prefs.getLong(KEY_LAST_CHECKED, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    /**
     * Records a completed check: its time, and what it found. A null manifest means
     * "up to date" and clears any cached update — the cache holds *available* updates,
     * not history.
     */
    fun recordCheck(manifest: UpdateManifest?, now: Instant) {
        prefs.edit()
            .putLong(KEY_LAST_CHECKED, now.toEpochMilli())
            .putString(
                KEY_MANIFEST,
                manifest?.let { json.encodeToString(UpdateManifest.serializer(), it) },
            )
            .apply()
    }

    /** The update a previous successful check found, when it found one. */
    fun cachedManifest(): UpdateManifest? =
        prefs.getString(KEY_MANIFEST, null)?.let { body ->
            runCatching { json.decodeFromString(UpdateManifest.serializer(), body) }.getOrNull()
        }

    /** The release the student dismissed, so it is not re-offered unprompted. */
    fun dismissedRelease(): String? = prefs.getString(KEY_DISMISSED, null)

    /** Marks [manifest]'s release as seen-and-declined. A genuinely new release differs. */
    fun dismiss(manifest: UpdateManifest) {
        prefs.edit().putString(KEY_DISMISSED, manifest.releaseKey).apply()
    }

    internal companion object {
        /** The file "Clear all Attendo data" wipes; AppReset reads it. */
        const val FILE_NAME: String = "updates"

        const val KEY_LAST_CHECKED: String = "lastCheckedAt"
        const val KEY_MANIFEST: String = "cachedManifest"
        const val KEY_DISMISSED: String = "dismissedRelease"
    }
}
