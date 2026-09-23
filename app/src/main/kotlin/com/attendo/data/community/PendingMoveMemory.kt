package com.attendo.data.community

import android.content.Context

/**
 * This phone's memory that an identity move was waiting on it — the piece the
 * transition notices need to survive their own screen.
 *
 * A move's other half is decided by a person on another phone, possibly hours
 * later, with nothing pushing to this one. The screen learns an outcome by
 * comparing two status checks: the one that saw the wait, and the one that
 * sees it gone. But the ViewModel holding the first check dies with its
 * screen (navigate away and back is a fresh one), so without this file a move
 * that resolved while nobody was looking just quietly stopped waiting — the
 * exact gap the two-device test hit. The memory is written whenever a status
 * check sees a wait, read before the next one, and cleared the moment a check
 * sees no wait; a fresh screen pairs it with the live answer and the outcome
 * is news again.
 *
 * It lives in [CommunityIdentityStore.FILE_NAME] on purpose: that file is
 * never in any backup (pinned by [CommunityIdentityBackupGuardTest]) and is
 * wiped wherever the identity itself dies (Clear All, AppReset, Start fresh —
 * all routes through [CommunityIdentityStore.signOutAndWipe]), which is
 * exactly the lifetime this memory should have. It holds report ids only:
 * no code, no passphrase, nothing from the identity file.
 */
class PendingMoveMemory(private val context: Context) {

    /** A wait this phone saw. [claimant] false means this phone was the owner. */
    data class Pending(
        val claimant: Boolean,
        val reportIds: Set<String>,
    )

    fun read(): Pending? {
        val prefs = context.getSharedPreferences(CommunityIdentityStore.FILE_NAME, Context.MODE_PRIVATE)
        val role = prefs.getString(KEY_ROLE, null) ?: return null
        return Pending(
            claimant = role == ROLE_CLAIMANT,
            reportIds = prefs.getStringSet(KEY_REPORT_IDS, emptySet())?.toSet() ?: emptySet(),
        )
    }

    /**
     * [commit], not apply: this memory has to survive a force stop, and an
     * apply() queued behind a process death is exactly the write that goes
     * missing — the two-device test lost a "move completed" notice to that
     * window between claiming and the phone dying.
     */
    fun write(pending: Pending) {
        context.getSharedPreferences(CommunityIdentityStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE, if (pending.claimant) ROLE_CLAIMANT else ROLE_OWNER)
            .putStringSet(KEY_REPORT_IDS, pending.reportIds)
            .commit()
    }

    /** Same durability argument as [write]. */
    fun clear() {
        context.getSharedPreferences(CommunityIdentityStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ROLE)
            .remove(KEY_REPORT_IDS)
            .commit()
    }

    companion object {
        private const val KEY_ROLE = "pendingMove.role"
        private const val KEY_REPORT_IDS = "pendingMove.reportIds"
        private const val ROLE_OWNER = "owner"
        private const val ROLE_CLAIMANT = "claimant"
    }
}
