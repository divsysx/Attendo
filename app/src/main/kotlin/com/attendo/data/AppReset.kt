package com.attendo.data

import android.content.Context
import android.content.Intent
import androidx.room.RoomDatabase
import com.attendo.data.community.CommunityClient
import com.attendo.data.community.CommunityIdentityStore
import com.attendo.data.update.UpdateCheckStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Clear all Attendo data": everything the app has ever written on this phone, gone, and the
 * install back to its first-run state.
 *
 * Not just attendance. The [clearedPreferenceFiles] and [clearedDirectories] lists are the
 * whole of the app's writable state, kept as data — a file the reset does not know about is a
 * file nothing writes, and a test pins that the two device-level preferences are absent from
 * them. Those two, "Automatic backup" ([AndroidBackupStore]) and Appearance
 * ([AppearanceStore]), survive on purpose: they say how *this phone* behaves, not what the
 * student recorded, and a reset that quietly switched Android backup back on would be a
 * reset with a surprise in it.
 *
 * The Firebase SDK's own internal files are not touched: analytics here is aggregate and
 * anonymous, holds nothing a student created, and has no documented per-app local store to
 * clear. The bundled timetable ships as a read-only asset and needs no clearing.
 */
class AppReset(
    private val context: Context,
    private val database: () -> RoomDatabase,
    private val settings: SettingsStore,
    private val communityIdentity: (suspend () -> Unit)? = null,
) {

    /**
     * Wipes everything and returns once it is all on disk.
     *
     * The preference clears use `commit()`, not `apply()`, because [restartApp] exits the
     * process right after this returns — an `apply()` writes to disk from a background
     * thread that a hard exit never waits for, and a reset that did not survive the restart
     * it caused would be worse than none. The rest of the app uses `apply()` everywhere
     * because nothing else kills the process.
     */
    suspend fun clearEverything() = withContext(Dispatchers.IO) {
        // The database first, and with no safety net: it is the one step that can fail
        // (a corrupted database file), and it is first precisely so that a failure here
        // aborts the reset with everything still intact, rather than leaving default
        // settings sitting on top of a semester. Room's own API empties every table and
        // keeps the file consistent with itself.
        database().clearAllTables()

        // The community reporter's anonymous identity dies with the reset too — signing
        // the session out server-side *before* the local file is wiped, so the revocation
        // and the wipe land together or the wipe alone (best effort: unreachable Supabase
        // must not abort the reset, and a signed-out token file is worthless anyway).
        // The identity prefs file itself is cleared below, with the others.
        communityIdentity?.invoke()

        // Preferences, by the same process-wide instances the stores hold — Android caches
        // SharedPreferences per file per process, so this is a clear the live stores see.
        // (SettingsStore's flow is refreshed below; the update system's store re-reads on
        // every call and needs no nudge.)
        for (name in clearedPreferenceFiles) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        settings.reload()

        // Files: the restore-undo snapshot, any downloaded update APK, and the cache. The
        // "backup" and "updates" directories match what AppContainer hands SafetySnapshotStore
        // and UpdateFiles; deleting the directories outright is safe because both recreate
        // them on demand.
        for (name in clearedDirectories) {
            File(context.filesDir, name).deleteRecursively()
        }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Restarts the app process, which is what makes the reset *complete* rather than merely
     * written: every repository, ViewModel and in-memory cache dies with the process, and
     * the launch intent brings the app back over a clean task stack with nothing of the old
     * state left to recompose. Resetting in place would mean chasing every holder of stale
     * data through the whole graph — the honest way to guarantee none is left is not to
     * keep any.
     */
    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }

    companion object {
        /**
         * Every preferences file holding student data. `attendo-settings` is the term, the
         * targets, the section, the name; `updates` is the update system's memory of its
         * last check and the release the student declined; `attendo-community` is the
         * anonymous community identity — and, since Phase 2, the account session, which
         * is the same session in the same file (linking attached a GitHub identity to it;
         * it never made a second one). A reset ends it for good: a reset that kept it
         * would leave one reporter — or one signed-in account — alive on a phone with no
         * recollection of reporting.
         */
        val clearedPreferenceFiles: List<String> = listOf(
            SettingsStore.FILE_NAME,
            UpdateCheckStore.FILE_NAME,
            CommunityIdentityStore.FILE_NAME,
        )

        /**
         * Every directory under `filesDir` the app writes. `backup` holds the undo copy of
         * the data an import replaced; `updates` holds a downloaded APK on its way to the
         * installer; `quarantine` holds the copy of a deleted account's attendance kept
         * when the phone was claimed by the account that signed in next
         * ([AttendanceQuarantineStore]).
         *
         * The quarantine directory is listed here for the same reason the other two are:
         * a clear-all must not leave a copy of a term's attendance behind on a phone its
         * owner has asked to be emptied. It is also why nothing has to delete that file
         * anywhere else — the set-aside copy is meant to outlive the account that owned it,
         * and only this path is meant to outlive it.
         */
        val clearedDirectories: List<String> = listOf(
            "backup",
            "updates",
            AttendanceQuarantineStore.DIRECTORY_NAME,
        )
    }
}
