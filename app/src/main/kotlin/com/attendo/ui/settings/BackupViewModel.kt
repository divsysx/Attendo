package com.attendo.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.core.backup.Backup
import com.attendo.core.backup.BackupMessages
import com.attendo.core.backup.BackupReadResult
import com.attendo.core.backup.BackupSummary
import com.attendo.data.AndroidBackupStore
import com.attendo.data.AppReset
import com.attendo.data.AttendanceRepository
import com.attendo.data.BackupRepository
import com.attendo.data.DocumentStore
import com.attendo.data.RestoreResult
import com.attendo.data.SettingsStore
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Something to tell the student once an operation has finished.
 *
 * [hint] is the second line — what to do next, when there is something to do. A refused file
 * carries its plain reason here; the individual technical reasons behind it name parser
 * offsets and field paths, and stay with the log. See [BackupMessages].
 */
data class BackupNotice(
    val message: String,
    val ok: Boolean,
    val hint: String? = null,
)

/** The one long operation the screen is in the middle of, if any. */
enum class BackupTask { EXPORTING, WRITING_CSV, READING, RESTORING, UNDOING, CLEARING }

/** A backup file read and validated, waiting for the student to say yes. */
data class PendingImport(
    val backup: Backup,
    val fileName: String?,
) {
    val summary: BackupSummary get() = backup.summary
}

data class BackupUiState(
    val loaded: Boolean = false,
    /** What is on this phone right now, for comparing against a file that would replace it. */
    val current: BackupSummary? = null,
    val task: BackupTask? = null,
    val pending: PendingImport? = null,
    val notice: BackupNotice? = null,
    /** Whether the last replacement can still be undone. */
    val canUndo: Boolean = false,
    /**
     * The "Automatic backup" toggle: whether Attendo's data may take part in Android's
     * own backup. Off until the student turns it on, and the screen asks before turning
     * it on — because it changes what an uninstall means.
     */
    val androidBackupEnabled: Boolean = false,
) {
    val busy: Boolean get() = task != null
}

/**
 * Export, import and undo.
 *
 * The order the import goes in is the whole point of this class: a file is read and validated
 * *before* the confirmation is asked for, so the student confirms a specific backup — 3
 * courses, 84 classes, exported on the 14th — rather than confirming a file picker's word for
 * it. Nothing is written until [confirmRestore], and [BackupRepository] takes a safety copy
 * before it writes.
 *
 * All the file access is through [DocumentStore], so this class never sees a path and the app
 * never asks for a storage permission.
 */
class BackupViewModel(
    private val backup: BackupRepository,
    private val documents: DocumentStore,
    private val checkpoint: () -> Unit,
    private val attendance: AttendanceRepository,
    private val settings: SettingsStore,
    private val androidBackup: AndroidBackupStore,
    private val appReset: AppReset,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState(androidBackupEnabled = androidBackup.enabled.value))

    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Flips the "Automatic backup" toggle.
     *
     * The screen asks for confirmation before enabling, not before disabling: turning it
     * on is what makes an uninstall no longer a clean slate, and turning it off is the
     * cautious direction. There is nothing to trigger on either side — Android decides
     * when a pass runs — so this only records the choice that [com.attendo.data.AttendoBackupAgent]
     * will read the next time one does.
     */
    fun setAndroidBackupEnabled(value: Boolean) {
        androidBackup.setEnabled(value)
        _state.update { it.copy(androidBackupEnabled = value) }
    }

    /**
     * "Clear all data", confirmed.
     *
     * The screen's confirmation dialog has already been answered by the time this runs.
     * The order is the whole safety story: everything is wiped and written to disk first,
     * and only then does [AppReset.restartApp] kill the process and relaunch over a clean
     * task stack — so nothing half-cleared is ever left for a screen to draw, and no
     * ViewModel, repository or in-memory cache from before the reset survives it.
     */
    fun clearAllData() {
        if (_state.value.busy) return
        _state.update { it.copy(task = BackupTask.CLEARING, notice = null, pending = null) }
        viewModelScope.launch {
            appReset.clearEverything()
            appReset.restartApp()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val summary = runCatching { backup.currentSummary() }.getOrNull()
            _state.update {
                it.copy(loaded = true, current = summary, canUndo = backup.canUndo)
            }
        }
    }

    // ---- export -------------------------------------------------------------

    /** [target] is the document the student created in the system file picker. */
    fun exportBackup(target: Uri) = export(target, BackupTask.EXPORTING) { backup.exportBackup() }

    fun exportCsv(target: Uri) =
        export(target, BackupTask.WRITING_CSV) { backup.exportAttendanceCsv() }

    fun suggestedBackupFileName(): String = backup.suggestedBackupFileName()

    fun suggestedCsvFileName(): String = backup.suggestedCsvFileName()

    private fun export(target: Uri, task: BackupTask, text: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(task = task, notice = null) }
        viewModelScope.launch {
            val result = runCatching {
                documents.write(target, text())
                documents.displayName(target)
            }
            _state.update { state ->
                state.copy(
                    task = null,
                    notice = result.fold(
                        onSuccess = { name ->
                            BackupNotice(
                                message = "Saved to ${name ?: "the file you chose"}.",
                                ok = true,
                            )
                        },
                        onFailure = { failure ->
                            BackupNotice(
                                message = "That file could not be written. ${failure.readable()}",
                                ok = false,
                            )
                        },
                    ),
                )
            }
        }
    }

    // ---- import -------------------------------------------------------------

    /**
     * Reads and validates a chosen file, and shows what it contains.
     *
     * Writes nothing. A file that fails here never gets near the database.
     *
     * Each refusal says which family of thing went wrong, in one short line — the wrong file
     * says so, a damaged backup says so, a backup from a newer build says so and points at
     * updating. What never reaches the student is the parser's vocabulary; see
     * [BackupMessages] for where those words go instead.
     */
    fun preview(source: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(task = BackupTask.READING, notice = null, pending = null) }
        viewModelScope.launch {
            val read = runCatching { documents.read(source) }
            val text = read.getOrElse { failure ->
                _state.update {
                    it.copy(
                        task = null,
                        notice = BackupNotice(
                            message = "That file could not be opened.",
                            ok = false,
                            hint = BackupMessages.CHOOSE_A_BACKUP,
                        ),
                    )
                }
                return@launch
            }

            val name = documents.displayName(source)
            when (val result = backup.read(text)) {
                is BackupReadResult.Ok -> _state.update {
                    it.copy(
                        task = null,
                        pending = PendingImport(result.backup, name),
                    )
                }

                is BackupReadResult.Failed -> {
                    // The student sees the plain reason: which family of fault the file has,
                    // and what to do about it. "Unexpected JSON token at offset 3" is exactly
                    // what someone debugging a corrupted export needs, and exactly what nobody
                    // choosing the wrong file does, so it stays with the log.
                    _state.update {
                        it.copy(
                            task = null,
                            notice = BackupNotice(
                                message = result.userMessage,
                                ok = false,
                                hint = result.userHint,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun cancelPending() = _state.update { it.copy(pending = null) }

    /** Replaces this install's data with the previewed backup. */
    fun confirmRestore() {
        val pending = _state.value.pending ?: return
        if (_state.value.busy) return
        _state.update { it.copy(task = BackupTask.RESTORING, notice = null) }
        viewModelScope.launch {
            finish(backup.restore(pending.backup), restored = true)
        }
    }

    /** Puts back what the last replacement overwrote. */
    fun undoLastRestore() {
        if (_state.value.busy || !_state.value.canUndo) return
        _state.update { it.copy(task = BackupTask.UNDOING, notice = null) }
        viewModelScope.launch {
            finish(backup.undoLastRestore(), restored = false)
        }
    }

    private suspend fun finish(result: RestoreResult, restored: Boolean) {
        val notice = when (result) {
            is RestoreResult.Restored -> {
                // Fold the write-ahead log into the database file now rather than whenever the
                // app next goes to the background: a device transfer or an Android backup taken
                // in between would otherwise copy a database missing the restore.
                checkpoint()
                // A restore writes the rows the file carried, but a file built from a different
                // install — or an older one — does not have every session the restored patterns
                // imply up to today. The dashboard regenerates these on open; a restore must do
                // the same so the review queue reflects the timetable the student just brought
                // in rather than waiting for them to leave the Backup screen. syncSessions is
                // idempotent (insertGenerated IGNORE on (patternId, date)) and never marks a row
                // reviewed, so restored past/today classes that already exist are untouched and
                // missing SCHEDULED ones appear as review work purely by their status and date —
                // the same rule FutureReviewTest pins for the seeded path. The settings were
                // replaced inside write() before this branch runs, so this calendar is the
                // restored one.
                attendance.syncSessions(settings.current.effectiveCalendar, clock())
                val summary = result.summary
                BackupNotice(
                    message = if (restored) {
                        "Restored ${summary.courses} course(s) and ${summary.sessions} " +
                            "class(es)." + summary.overallPercent?.let { " Overall: $it." }.orEmpty()
                    } else {
                        "Your previous data is back."
                    },
                    ok = true,
                )
            }

            // Only the undo path can land here: the saved copy of the previous data failed the
            // same validation any other file goes through. There is nothing for the student to
            // fix in a file they never chose, so the short message is all they see.
            is RestoreResult.Refused -> {
                BackupNotice(
                    message = "The saved copy of your previous data could not be read, so " +
                        "nothing was changed.",
                    ok = false,
                )
            }

            is RestoreResult.Failed -> BackupNotice(message = result.message, ok = false)
        }

        val summary = runCatching { backup.currentSummary() }.getOrNull()
        _state.update {
            it.copy(
                task = null,
                pending = null,
                notice = notice,
                current = summary,
                canUndo = backup.canUndo,
            )
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun Throwable.readable(): String =
        message?.trim()?.ifBlank { null } ?: (this::class.simpleName ?: "")

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = container
                BackupViewModel(
                    backup = app.backup,
                    documents = app.documents,
                    checkpoint = app::checkpoint,
                    attendance = app.attendance,
                    settings = app.settings,
                    androidBackup = app.androidBackup,
                    appReset = app.appReset,
                )
            }
        }
    }
}
