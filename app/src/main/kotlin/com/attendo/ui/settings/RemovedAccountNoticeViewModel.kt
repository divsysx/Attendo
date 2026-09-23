package com.attendo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.attendo.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Whether the app should be telling the student, right now, that their account was deleted.
 *
 * ### Why this is not [AccountViewModel]
 *
 * The Account *screen* explains the deletion where a student would go looking for it, and
 * that is not enough on its own: a deletion is carried out by a background validation, at
 * app start or on returning to the foreground, with no screen open and nothing to navigate
 * to. A student whose account was removed while they were asleep would otherwise keep using
 * the app — signed out, unsynced, and told nothing — until they happened to open Settings
 * and scroll. The sentence has to reach them at the boundary the deletion happened on, which
 * is the app itself.
 *
 * ### Why the acknowledgement is session-scoped
 *
 * The deletion is a *situation*, not an event, and [AccountManager.accountRemoved] stays true
 * for as long as it is true — so "show it whenever that flag is set" would re-ask on every
 * recomposition and every foreground. Dismissing it is therefore remembered here, once, in
 * memory. That is deliberately the same lifetime as the flag it acknowledges:
 *
 *  - Within one run of the app, one deletion produces one dialog, however many times the
 *    app is foregrounded or whatever else re-emits. That is the requirement — repeated
 *    validations of the same gone account are not repeated announcements.
 *  - Across a restart, the dialog can appear again, because the flag it reads is rebuilt
 *    from the server the same way the session was. That is not a second announcement of a
 *    second deletion: the account is still gone, and the student may have opened the app
 *    days later to find they are signed out with no memory of being told why.
 *  - A new sign-in clears the flag ([AccountManager]'s Clear branch), which re-arms this.
 *
 * Nothing here is persisted, and nothing needs to be: what the deletion *did* is durable
 * already — the session is gone, the local attendance claim is marked terminated in Room, and
 * the Account screen's explanation is driven by the same non-persisted flag. This is the
 * acknowledgement of a sentence, and the durable record of the thing it is about is elsewhere.
 */
class RemovedAccountNoticeViewModel(
    removed: StateFlow<Boolean>,
) : ViewModel() {

    /**
     * Dismissed *this run*, for the deletion currently established.
     *
     * Not keyed on the uid, and it does not need to be: the flag this acknowledges can only
     * go true once per account (the manager announces once), and it is re-armed the moment
     * that flag goes false — which is what makes a second deletion, of a second account,
     * announced in its turn rather than swallowed by the first one's acknowledgement.
     */
    private val acknowledged = MutableStateFlow(false)

    /** True while the removed-account dialog should be on screen. */
    val visible: StateFlow<Boolean> = combine(removed, acknowledged) { isRemoved, dismissed ->
        removedNoticeVisible(removed = isRemoved, acknowledged = dismissed)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** The student has read it. */
    fun dismiss() {
        acknowledged.value = true
    }

    /**
     * Follows the manager's flag and re-arms the acknowledgement when it clears.
     *
     * The whole lifecycle of this one boolean, in a single line, because the alternative —
     * answering "should this be shown?" from the flag alone — is what makes a second deletion
     * silent: the flag is true again, and the acknowledgement from the first deletion is still
     * set. See [acknowledgementAfterRemovalChange].
     */
    init {
        viewModelScope.launch {
            removed.collect { isRemoved ->
                acknowledged.value = acknowledgementAfterRemovalChange(acknowledged.value, isRemoved)
            }
        }
    }

    companion object {
        /**
         * How long the flow stays hot after the last collector goes away.
         *
         * The dialog is composed by the app root and never unsubscribes while the process is
         * alive, so this only matters across a configuration change — and it is deliberately
         * short, because the only thing kept warm is a boolean that is re-derived from the
         * manager's flag in the gap between two collectors. Long enough that a rotation does
         * not re-show a dialog the student just dismissed.
         */
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RemovedAccountNoticeViewModel(
                    removed = container.accountManager?.accountRemoved ?: MutableStateFlow(false),
                )
            }
        }
    }
}

/**
 * Whether the removal notice belongs on screen, as a function of the two booleans that decide
 * it: the manager's flag, and whether this run has already acknowledged it.
 *
 * Extracted so the decision can be pinned without a device — the app's test source set has no
 * coroutines-test and no Robolectric, so a ViewModel holding this inline would be untestable
 * and the rule would live only in prose.
 */
internal fun removedNoticeVisible(removed: Boolean, acknowledged: Boolean): Boolean =
    removed && !acknowledged

/**
 * What an acknowledgement becomes when the manager's flag changes.
 *
 * The acknowledgement is scoped to *a* deletion, not to removals in general, and this is where
 * that is enforced:
 *
 *  * While the flag stands, the acknowledgement stands — so a second validation of the same
 *    gone account, a foreground, a recomposition, or a restart of the collector does not
 *    re-announce a deletion the student has already read. That is the requirement: repeated
 *    evidence of one deletion is one announcement.
 *  * The moment the flag clears — a valid account answered, which is the only thing that
 *    clears it — the acknowledgement clears with it. Without this, the first deletion's "OK"
 *    would silence the second deletion forever, and the student would be signed out with no
 *    explanation because they had once dismissed an unrelated one.
 *
 * The re-arm is keyed on the flag, and the flag is set by the account manager alone, so a
 * deletion can only be announced once per establishment — which is what makes this a lifetime
 * and not a counter.
 */
internal fun acknowledgementAfterRemovalChange(acknowledged: Boolean, removed: Boolean): Boolean =
    if (removed) acknowledged else false
