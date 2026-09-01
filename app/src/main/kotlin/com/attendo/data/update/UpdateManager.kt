package com.attendo.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.attendo.core.update.ApkVerification
import com.attendo.core.update.UpdateCheckOutcome
import com.attendo.core.update.UpdateCheckPolicy
import com.attendo.core.update.UpdateLaunch
import com.attendo.core.update.UpdateManifest
import com.attendo.data.analytics.UsageAnalytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/**
 * The update system's head: everything the UI talks to, and nothing it shouldn't.
 *
 * One [StateFlow] describes the whole flow — an available update, a download with
 * progress, verification, readiness to install — and the actions are one call each. The
 * UI never learns where the update came from; that is the provider's business, which is
 * the whole point of the [UpdateProvider] boundary.
 *
 * A null [provider] means this build is not served by the direct APK channel (see
 * [com.attendo.core.update.DistributionSource]): the manager reports itself unsupported,
 * every check declines without touching the network, and Settings shows no update
 * section. Failing safe is quieter than failing loud here — an update flow that could
 * try to replace another channel's install is worse than no update flow.
 *
 * The automatic check is quiet by construction: it runs when [autoCheck] is called, only
 * when the [UpdateCheckPolicy] says one is due — against the timestamp persisted in
 * [UpdateCheckStore], so the 24-hour interval survives cold starts — and any failure
 * simply leaves the state as it was. Being offline is not an event in an attendance app.
 */
class UpdateManager(
    private val context: Context,
    private val provider: UpdateProvider?,
    private val files: UpdateFiles,
    private val verifier: UpdateVerifier,
    private val analytics: UsageAnalytics,
    private val store: UpdateCheckStore,
    private val scope: CoroutineScope,
    private val policy: UpdateCheckPolicy = UpdateCheckPolicy(),
    private val clock: () -> Instant = Instant::now,
    private val http: HttpClient = HttpClient(),
) {

    /** Whether this build has an update source at all. The UI hides itself when false. */
    val supported: Boolean get() = provider != null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = _state

    private var downloadJob: Job? = null

    /**
     * The quiet background check, run once per app open at most.
     *
     * Both halves of the open-time decision — raise the cached card, ask the network —
     * come from [UpdateLaunch.plan] against the *persisted* state, so the interval
     * survives cold starts and the card does not wait for a network answer. This method
     * only acts on that plan, because the one thing an automatic check must never
     * produce is a message about itself.
     */
    fun autoCheck() {
        if (provider == null) return
        scope.launch {
            val plan = UpdateLaunch.plan(
                cachedManifest = store.cachedManifest(),
                dismissedRelease = store.dismissedRelease(),
                lastChecked = store.lastChecked(),
                installed = verifier.installed(),
                now = clock(),
                policy = policy,
            )
            // Raised before the due question is even asked: showing what the last
            // successful check found costs nothing, and a student who was offline since
            // a release came out still hears about it.
            plan.cachedUpdateToRaise?.let { cached ->
                if (_state.value is UpdateState.Idle) {
                    _state.value = UpdateState.Available(cached)
                }
            }
            if (!plan.networkCheckDue) return@launch
            check(manual = false)
        }
    }

    /** An explicit check from Settings. Returns the outcome so the screen can answer with it. */
    suspend fun checkNow(): UpdateCheckOutcome {
        analytics.manualUpdateCheck()
        return check(manual = true)
    }

    private suspend fun check(manual: Boolean): UpdateCheckOutcome {
        val installed = verifier.installed()
        val outcome = UpdateDecision.decide(installed, provider?.latestRelease())
        when (outcome) {
            is UpdateCheckOutcome.Available -> {
                // A completed check gets recorded whatever it found, so the interval and
                // the cache move together.
                store.recordCheck(outcome.manifest, clock())
                // A manual check answers the question the student asked, dismissal or no;
                // an automatic one respects an earlier "not now" and stays quiet.
                if (manual || UpdateLaunch.surfacesAutomatically(outcome.manifest, store.dismissedRelease())) {
                    analytics.updateAvailable()
                    _state.value = UpdateState.Available(outcome.manifest)
                }
            }

            is UpdateCheckOutcome.UpToDate ->
                store.recordCheck(manifest = null, now = clock())

            is UpdateCheckOutcome.Failed ->
                Unit // Not recorded: a failed check retries on the next open.
        }
        return outcome
    }

    /** Starts the download for [manifest]. Cancels and clears whatever was there before. */
    fun download(manifest: UpdateManifest) {
        downloadJob?.cancel()
        files.clear()
        val target = files.apkFor(manifest.versionName)
        _state.value = UpdateState.Downloading(manifest, bytesSoFar = 0, totalBytes = manifest.apkSizeBytes)
        analytics.updateDownloadStarted()
        downloadJob = scope.launch {
            val ok = http.download(manifest.apkUrl, target) { soFar, _ ->
                _state.value = UpdateState.Downloading(manifest, soFar, manifest.apkSizeBytes)
            }
            if (!ok) {
                // Cancelled and failed are told apart by who moved the state first: a
                // cancellation has already returned the card to Available, so a download
                // that ended with no file and the state still on Downloading is a failure.
                files.clear()
                if (_state.value is UpdateState.Downloading) {
                    _state.value = UpdateState.DownloadFailed(manifest)
                }
                return@launch
            }
            analytics.updateDownloadCompleted()
            verify(target, manifest)
        }
    }

    private suspend fun verify(apk: File, manifest: UpdateManifest) {
        _state.value = UpdateState.Verifying(manifest)
        // Verification hashes tens of megabytes; the delay of one frame keeps the state
        // from being drawn and replaced before the student ever sees it.
        delay(50)
        val verdict = verifier.verify(apk, manifest)
        when (verdict) {
            is ApkVerification.Verified -> {
                _state.value = UpdateState.ReadyToInstall(manifest)
            }

            else -> {
                files.clear()
                analytics.updateVerificationFailed()
                _state.value = UpdateState.VerificationFailed(manifest)
            }
        }
    }

    /**
     * Hands the verified APK to Android's package installer.
     *
     * Android then asks the student to approve the installation — that approval is the
     * user's explicit consent, and this app can and does nothing further with it. The
     * downloaded file is kept: if the student backs out of the installer, tapping
     * "Install" again should not re-download.
     */
    fun install() {
        val current = _state.value
        val manifest = when (current) {
            is UpdateState.ReadyToInstall -> current.manifest
            else -> return
        }
        val apk = files.existingApk(manifest.versionName) ?: run {
            _state.value = UpdateState.Available(manifest)
            return
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            analytics.updateInstallLaunched()
            _state.value = UpdateState.Installing(manifest)
        }
    }

    /**
     * Dismisses the update card until a genuinely different release is published — the
     * dismissal is remembered per version, so "not now" means not now for this update,
     * not never for the feature.
     */
    fun dismiss() {
        _state.value.manifest?.let(store::dismiss)
        _state.value = UpdateState.Idle
    }

    /** Cancels an in-flight download and returns to the update-available state. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        files.clear()
        (_state.value as? UpdateState.Downloading)?.let { downloading ->
            _state.value = UpdateState.Available(downloading.manifest)
        }
    }
}

/** What the update system is doing right now. The UI's entire vocabulary. */
sealed interface UpdateState {

    /** The release this state is about, when there is one. */
    val manifest: UpdateManifest? get() = null

    /** Nothing to say. The default, and what every failure quietly returns to. */
    data object Idle : UpdateState

    /** A newer release exists and hasn't been acted on. */
    data class Available(override val manifest: UpdateManifest) : UpdateState

    /** Downloading, with progress. [totalBytes] null when the server didn't say. */
    data class Downloading(
        override val manifest: UpdateManifest,
        val bytesSoFar: Long,
        val totalBytes: Long?,
    ) : UpdateState

    /**
     * The download ended without a file — connection dropped, server failed mid-transfer.
     * Distinct from a cancellation, which the student caused and needs no report of.
     */
    data class DownloadFailed(override val manifest: UpdateManifest) : UpdateState

    /** The download finished and the file is being checked. */
    data class Verifying(override val manifest: UpdateManifest) : UpdateState

    /**
     * The file is verified and ready. Installation only ever starts from here, because
     * [UpdateState.Installing] must mean "the checks passed".
     */
    data class ReadyToInstall(override val manifest: UpdateManifest) : UpdateState

    /**
     * A check failed — the file was not what it claimed, in one of the ways
     * [ApkVerification] enumerates. One sentence on screen; the file is already deleted.
     */
    data class VerificationFailed(override val manifest: UpdateManifest) : UpdateState

    /** Android's installer has been handed the file. The rest is the OS's to ask about. */
    data class Installing(override val manifest: UpdateManifest) : UpdateState
}
