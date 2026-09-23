package com.attendo.ui.settings

import com.attendo.core.sync.AttendanceSyncPolicy.ReturningReconciliation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTransitionUiTest {

    @Test
    fun `switch account prompt holds correct incoming and current owner ids`() {
        val prompt = AccountTransitionPrompt.SwitchAccount(
            incomingUserId = "account-b",
            ownerUserId = "account-a",
        )
        assertEquals("account-b", prompt.incomingUserId)
        assertEquals("account-a", prompt.ownerUserId)
    }

    @Test
    fun `reconcile returning prompt holds returning user id`() {
        val prompt = AccountTransitionPrompt.ReconcileReturning(
            userId = "account-a",
        )
        assertEquals("account-a", prompt.userId)
    }

    @Test
    fun `destructive replacement warning explicitly states clear of local attendance`() {
        assertTrue(ACCOUNT_SWITCH_CONFIRM.contains("cannot merge"))
        assertTrue(ACCOUNT_SWITCH_CONFIRM.contains("This cannot be undone"))
        assertTrue(ACCOUNT_SWITCH_CONFIRM.contains("cleared"))
        assertTrue(ACCOUNT_SWITCH_CONFIRM.contains("nothing is changed or deleted"))
    }

    @Test
    fun `reconciliation prompt text explicitly mentions local changes`() {
        assertTrue(RECONCILE_RETURNING_PROMPT.contains("local changes"))
        assertTrue(RECONCILE_RETURNING_PROMPT.contains("merge"))
        assertTrue(RECONCILE_RETURNING_PROMPT.contains("restore"))
    }

    @Test
    fun `ui state carries transition prompt`() {
        val prompt = AccountTransitionPrompt.SwitchAccount("b", "a")
        val state = AccountUiState(
            available = true,
            transitionPrompt = prompt,
        )
        assertNotNull(state.transitionPrompt)
        assertEquals(prompt, state.transitionPrompt)
    }
}
