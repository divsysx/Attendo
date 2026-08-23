package com.attendo.ui.rollover

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.model.AcademicCalendar
import com.attendo.core.model.Semester
import com.attendo.core.rollover.RolloverDecision
import com.attendo.core.rollover.SemesterRecordRenderer
import com.attendo.core.rollover.detectRollover
import com.attendo.data.BackupRepository
import com.attendo.data.DocumentStore
import com.attendo.data.RolloverRepository
import com.attendo.data.RolloverResult
import com.attendo.data.SemesterRecordPdf
import com.attendo.data.SemesterRepository
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What the rollover screen is doing, if anything.
 *
 * A sealed type rather than a bag of flags because the states are genuinely exclusive: the screen
 * is either absent ([Idle]), showing the blocking choice ([Required]), busy with a non-dismissible
 * operation ([Rolling] or [Exporting]), or reporting an outcome ([Notice]). Compose's `when` over
 * this is the whole rendering decision, and the compiler checks it is exhaustive.
 *
 * [Required] carries the two semesters the screen names — the one on the device ([current]) and the
 * one this build carries ([bundled]) — plus whether the student has already exported something this
 * session, which is what softens the "start new semester" confirmation.
 */
sealed interface RolloverUiState {
    /** No rollover is needed, or "remind me later" was chosen: the overlay is not shown. */
    data object Idle : RolloverUiState

    /** The stored semester differs from the bundled one: the blocking screen must be answered. */
    data class Required(
        val current: Semester,
        val bundled: Semester,
        val exportedThisSession: Boolean,
    ) : RolloverUiState

    /** Clearing the old semester and starting the new one. Non-dismissible. */
    data object Rolling : RolloverUiState

    /** Writing a Semester Record PDF or a full backup. Non-dismissible. */
    data object Exporting : RolloverUiState

    /** An outcome — a successful export, or a failed one — to acknowledge before returning to the choice. */
    data class Notice(val message: String, val ok: Boolean) : RolloverUiState
}

/**
 * The one place the new-semester question is asked and answered.
 *
 * The app keeps exactly one active semester. This ViewModel is the boundary between *detecting*
 * that the device's semester and the build's bundled one disagree and *acting* on it — and it is
 * the only thing that ever asks a student to clear their data. Three operations, all delegating to
 * [RolloverRepository]: export the previous semester as a PDF (a pure read, never clears), export a
 * full JSON backup (the existing path, unchanged), and roll over (the destructive, transactional
 * clear-and-initialise).
 *
 * ### How the screen appears and disappears
 *
 * Detection is driven by the bundled calendar [AcademicCalendar.DEFAULT_2026_27] — a compile-time
 * constant that moves forward with each release — compared to the stored current semester by
 * [Semester.isSameTermAs] (identity, not dates). Collecting [semesters]' current flow runs
 * [detectRollover] on every emission, so the screen appears the moment an update lands on a device
 * still holding an older term, and disappears the moment a rollover commits and the flow re-emits
 * the bundled semester (→ [RolloverDecision.None] → [Idle]). There is no persisted "done" flag —
 * idempotent detection is what keeps a completed rollover from re-prompting.
 *
 * ### "Remind me later" is session-scoped by design
 *
 * [remindLater] is a plain field on this ViewModel, not a setting. The ViewModel lives for the
 * process, so the choice holds for the session; the process dies, the field resets, and the next
 * launch runs detection afresh — the gate reappears if the semester still differs. There is no way
 * to defer the rollover indefinitely, which is the point.
 */
class RolloverViewModel(
    private val semesters: SemesterRepository,
    private val rollover: RolloverRepository,
    private val backup: BackupRepository,
    private val documents: DocumentStore,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    /** The semester this build of the app carries, derived from the bundled calendar. */
    private val bundled: Semester = Semester.of(AcademicCalendar.DEFAULT_2026_27)

    private val _state = MutableStateFlow<RolloverUiState>(RolloverUiState.Idle)
    val state: StateFlow<RolloverUiState> = _state.asStateFlow()

    /** Session-scoped: cleared by process death, never written to disk. See the class doc. */
    private var remindLater = false

    /** True once a PDF or full backup has been written this session — softens the confirmation. */
    private var exportedThisSession = false

    /** The most recent current semester, so [dismissNotice] can re-decide without re-reading. */
    private var lastCurrent: Semester? = null

    init {
        viewModelScope.launch {
            // collect (not collectLatest): EstablishSilently is a database write, and cancelling
            // it mid-transaction to chase a newer emission would be worse than letting it finish —
            // the next emission is processed as soon as the write returns.
            semesters.current.collect { current ->
                lastCurrent = current
                applyDecision(current)
            }
        }
    }

    /** Maps a detection result onto [state], honouring the session-scoped remind-later flag. */
    private fun applyDecision(current: Semester?) {
        when (val decision = detectRollover(current, bundled)) {
            is RolloverDecision.EstablishSilently -> {
                // Nothing to lose. Adopt the bundled semester without a prompt; the insert
                // re-emits the current flow and drives state to None/Idle. Idle is also set here
                // so the brief window before re-emission is never left showing anything.
                viewModelScope.launch {
                    runCatching { rollover.establishSilently(bundled) }
                    _state.value = RolloverUiState.Idle
                }
            }

            is RolloverDecision.None -> _state.value = RolloverUiState.Idle

            is RolloverDecision.RolloverRequired -> {
                _state.value = if (remindLater) {
                    RolloverUiState.Idle
                } else {
                    RolloverUiState.Required(
                        current = decision.current,
                        bundled = decision.bundled,
                        exportedThisSession = exportedThisSession,
                    )
                }
            }
        }
    }

    // ---- exports (pure reads; never clear) -----------------------------------

    /**
     * Writes a Semester Record PDF of the *current* (previous) semester to [uri].
     *
     * A pure read followed by a file write — [RolloverRepository.snapshotFor] builds the doc from
     * data still on the device, [SemesterRecordPdf.export] lays it out, and [DocumentStore.write]
     * is the only I/O. Nothing is cleared, so a failed or cancelled export leaves the semester
     * exactly as it was. On success [exportedThisSession] is set, which weakens the confirmation
     * on "start new semester".
     */
    fun exportPdf(uri: Uri) {
        val required = _state.value as? RolloverUiState.Required ?: return
        _state.value = RolloverUiState.Exporting
        viewModelScope.launch {
            val result = runCatching {
                val doc = rollover.snapshotFor(required.current)
                val bytes = SemesterRecordPdf.export(doc)
                documents.write(uri, bytes)
                documents.displayName(uri)
            }
            if (result.isSuccess) exportedThisSession = true
            _state.value = RolloverUiState.Notice(
                message = result.fold(
                    onSuccess = { name -> "Semester record saved to ${name ?: "the file you chose"}." },
                    onFailure = { failure ->
                        "The semester record could not be written. ${failure.readable()}"
                    },
                ),
                ok = result.isSuccess,
            )
        }
    }

    /**
     * Writes a full JSON backup to [uri] — the existing, unchanged backup path.
     *
     * Delegates to [BackupRepository.exportBackup] and writes the text through [DocumentStore]. As
     * with the PDF, nothing on the device is touched; only the file is written.
     */
    fun exportBackup(uri: Uri) {
        _state.value = RolloverUiState.Exporting
        viewModelScope.launch {
            val result = runCatching {
                val text = backup.exportBackup()
                documents.write(uri, text)
                documents.displayName(uri)
            }
            if (result.isSuccess) exportedThisSession = true
            _state.value = RolloverUiState.Notice(
                message = result.fold(
                    onSuccess = { name -> "Full backup saved to ${name ?: "the file you chose"}." },
                    onFailure = { failure ->
                        "The backup could not be written. ${failure.readable()}"
                    },
                ),
                ok = result.isSuccess,
            )
        }
    }

    // ---- the destructive rollover --------------------------------------------

    /**
     * Clears the previous semester and starts the bundled one.
     *
     * The caller (the gate's confirmation dialog) is responsible for having offered an export
     * first; this method clears without further prompt. On [RolloverResult.Done] the current flow
     * re-emits the bundled semester and detection returns None → Idle, so the gate dismisses
     * itself; Idle is also set here so the working dialog clears the instant the work finishes.
     * On [RolloverResult.Failed] the outcome is reported — and because the transaction either
     * rolled back (data unchanged) or committed rows without settings (data changed), the message
     * from [RolloverRepository] already says which.
     */
    fun rollOver() {
        if (_state.value is RolloverUiState.Rolling) return
        _state.value = RolloverUiState.Rolling
        viewModelScope.launch {
            when (val result = rollover.rolloverTo(bundled)) {
                is RolloverResult.Done -> _state.value = RolloverUiState.Idle
                is RolloverResult.Failed -> _state.value = RolloverUiState.Notice(
                    message = result.message,
                    ok = false,
                )
            }
        }
    }

    /** Hides the gate for this session only. Reappears on next launch if the semester still differs. */
    fun remindLater() {
        remindLater = true
        _state.value = RolloverUiState.Idle
    }

    /**
     * Acknowledges a [RolloverUiState.Notice] and returns to the choice.
     *
     * A notice never follows a successful rollover — that path goes straight to Idle — so the
     * semester on the device still differs from the bundled one, and re-running detection lands
     * back on [RolloverUiState.Required], carrying whatever was exported this session.
     */
    fun dismissNotice() = applyDecision(lastCurrent)

    // ---- file names ----------------------------------------------------------

    /** The name to suggest for a Semester Record PDF — the previous semester, not the new one. */
    fun suggestedPdfFileName(): String {
        val current = (_state.value as? RolloverUiState.Required)?.current ?: bundled
        return SemesterRecordRenderer.suggestedFileName(current, clock())
    }

    /** The name to suggest for a full JSON backup — the existing backup naming convention. */
    fun suggestedBackupFileName(): String = backup.suggestedBackupFileName()

    private fun Throwable.readable(): String =
        message?.trim()?.ifBlank { null } ?: (this::class.simpleName ?: "")

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = container
                RolloverViewModel(
                    semesters = app.semesters,
                    rollover = app.rollover,
                    backup = app.backup,
                    documents = app.documents,
                )
            }
        }
    }
}
