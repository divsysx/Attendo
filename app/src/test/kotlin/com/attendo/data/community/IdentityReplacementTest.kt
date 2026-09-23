package com.attendo.data.community

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The identity replacement's client-side timing, pinned structurally — the same
 * contract style the resilience suite uses for behavior that lives in a class
 * needing Android runtime pieces.
 *
 * The whole design turns on WHERE two things happen:
 *  * the outbox is cleared only after finalize_transfer commits (a Completed with
 *    `replaced`), never at claim time — while the move is reversible, this phone's
 *    unsent reports are this phone's, and an aborted or expired move must leave
 *    them exactly where they were;
 *  * `identity_frozen` is a state, not a verdict — a report refused during the
 *    pending window is kept and retried, never marked rejected, because the
 *    server will not "say the same thing again" once the window resolves.
 */
class IdentityReplacementTest {

    @Test
    fun `a pending claim never touches the outbox - the move is still reversible`() {
        // B has unsent reports → the claim is accepted (pending window starts) →
        // the outbox must still be there. The only code path that clears community
        // rows on import is inside the Completed branch, and the ClaimPending
        // branch must stay clear of every clearing call.
        val source = repository()
        val pending = source.substringAfter("is RemoteReportApi.TransferResult.ClaimPending ->")
            .substringBefore("is RemoteReportApi.TransferResult.Completed ->")

        for (call in listOf("clearOutbox()", "clearCachedObservations()", "clearDismissed()")) {
            assertFalse(
                "A pending claim must not clear the $call — an aborted or expired move " +
                    "has to leave this phone's unsent reports intact.",
                call in pending,
            )
        }
    }

    @Test
    fun `a completed replacement clears the outbox before refreshing into the new identity`() {
        // B has unsent reports → the move completes → the outbox rows belong to an
        // identity that no longer exists and must go, atomically with the cache
        // clear, and only on the replaced path (a plain restore keeps its outbox:
        // those rows belong to the identity that just moved in).
        val source = repository()
        val completed = source.substringAfter("is RemoteReportApi.TransferResult.Completed ->")
            .substringBefore("is RemoteReportApi.TransferResult.Rejected ->")

        val guard = completed.indexOf("if (claimed.replaced)")
        val transaction = completed.indexOf("database.withTransaction")
        val outbox = completed.indexOf("dao.clearOutbox()")
        val refresh = completed.indexOf("refresh()")

        assertTrue("The clear must sit inside the replaced guard.", guard >= 0)
        assertTrue("The clear must be a transaction — a half-cleared state is a bug.", transaction > guard)
        assertTrue("The outbox clear must be inside that transaction.", outbox in transaction until refresh)
        assertTrue(
            "The cache must be refreshed only after the clear, so the next render is " +
                "the moved history, not the replaced identity's leftovers.",
            refresh > outbox,
        )
    }

    @Test
    fun `a frozen report is kept and retried, never marked rejected`() {
        // B attempts a community action during the pending window → the server
        // refuses with identity_frozen → the row keeps its schedule. The sync
        // engine's rejection arm must branch on the code BEFORE any
        // markOutboxRejected, and the frozen arm must be markOutboxFrozen: the
        // counters keep the retry schedule alive while the frozen stamp is what
        // "My Reports" renders as the identity-move sentence — markOutboxFailure
        // alone let the attempt cap age a frozen row into "Couldn't send — network",
        // a failure the report never had.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/CommunitySync.kt").readText()
        val rejection = source.substringAfter("is RemoteReportApi.SubmitResult.Rejected ->")
            .substringBefore("RemoteReportApi.SubmitResult.Unreachable ->")

        val frozenAt = rejection.indexOf("\"identity_frozen\"")
        val markFrozenAt = rejection.indexOf("dao.markOutboxFrozen")
        val rejectedAt = rejection.indexOf("dao.markOutboxRejected")

        assertTrue("The rejection arm must know identity_frozen.", frozenAt >= 0)
        assertTrue(
            "The frozen branch must keep the row's schedule and stamp the state " +
                "(markOutboxFrozen), not kill it (markOutboxRejected).",
            markFrozenAt in frozenAt until rejectedAt,
        )
        // The state must outrank the attempt cap in the UI's eyes, or a long freeze
        // still reads as a network failure.
        val repo = sourceFile("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()
        val derivation = repo.substringAfter("suspend fun myReports()")
        val frozenBranch = derivation.indexOf("row.frozenAt != null -> OutboxUiState.Status.FROZEN")
        val failedBranch = derivation.indexOf("OutboxPolicy.MAX_ATTEMPTS")
        assertTrue(
            "A frozen row must derive FROZEN before the attempt cap can call it FAILED.",
            frozenBranch in 0 until failedBranch,
        )
        // And the sentence it renders as must be the move, not the network.
        val cards = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt").readText()
        assertTrue(
            "A frozen row must say the identity is moving, not that the send failed.",
            cards.contains("Your identity is moving phones. It sends when the move finishes."),
        )
    }

    @Test
    fun `the destructive confirmation is the only road to a replacing claim`() {
        // The replace flag must never ride an ordinary import: the ViewModel sets
        // it in exactly one place, and that place is the confirmed path; the
        // refusal that triggers the dialog (identity_not_fresh) must park the file
        // in pendingReplacement rather than show a dead-end notice; and cancelling
        // must clear it without touching anything else.
        val viewModel = sourceFile(
            "src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt",
        ).readText()

        val confirm = viewModel.substringAfter("fun confirmReplaceFrom")
            .substringBefore("fun cancelReplacement")
        assertTrue(
            "The confirmed path must claim with replaceClaimant = true.",
            confirm.contains("replaceClaimant = true"),
        )

        val ordinary = viewModel.substringAfter("fun importFrom")
            .substringBefore("fun confirmReplaceFrom")
        assertFalse(
            "An ordinary import must never claim with replacement.",
            "replaceClaimant" in ordinary,
        )

        val refused = viewModel.substringAfter("is CommunityRepository.AtidImportResult.Refused ->")
            .substringBefore("CommunityRepository.AtidImportResult.Unreachable ->")
        assertTrue(
            "The identity_not_fresh refusal must park the file for the destructive " +
                "dialog, not show a dead end.",
            refused.contains("result.code == \"identity_not_fresh\"") &&
                refused.contains("pendingReplacement = source"),
        )

        val cancel = viewModel.substringAfter("fun cancelReplacement")
            .substringBefore("private fun handleImportResult")
        assertTrue(
            "Cancelling must clear the parked file and nothing else.",
            cancel.contains("pendingReplacement = null") && "importIdentityBackup" !in cancel,
        )
    }

    @Test
    fun `a superseded phone is directed to start fresh, never offered the replace dialog`() {
        // The two-device retest of 2026-09-12: A's identity had already been claimed
        // by B, so A sat in superseded_identities — and the claim RPC refuses a
        // tombstoned uid even WITH the replace flag (0017: "a tombstoned uid has
        // nothing left to replace"). The client still parked the file on
        // identity_not_fresh and offered the destructive dialog, which could never
        // succeed: confirming just re-refused and re-parked, a dialog loop with a
        // delete button in it. The refusal on such a phone is a direction — start
        // fresh, then import the same file again — never an offer.
        val viewModel = sourceFile(
            "src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt",
        ).readText()

        val refused = viewModel.substringAfter("is CommunityRepository.AtidImportResult.Refused ->")
            .substringBefore("CommunityRepository.AtidImportResult.Unreachable ->")
        assertTrue(
            "The replace dialog must be parked only on a phone that still holds the " +
                "history the dialog offers to delete, and never on a confirmed call.",
            refused.contains("if (result.code == \"identity_not_fresh\" &&") &&
                refused.contains("!confirmedReplacement &&") &&
                refused.contains("!_state.value.superseded"),
        )
        assertTrue(
            "The superseded direction must say so and point at Start fresh.",
            refused.contains("This phone's identity already moved") &&
                refused.contains("Start fresh below"),
        )

        val confirm = viewModel.substringAfter("fun confirmReplaceFrom")
            .substringBefore("fun cancelReplacement")
        assertTrue(
            "The confirmed call must mark itself as such, so its own not-fresh refusal " +
                "cannot re-park into the dialog again.",
            confirm.contains("confirmedReplacement = true"),
        )
    }

    @Test
    fun `the passphrase is asked again for the confirmed replacement`() {
        // The first claim's passphrase was wiped by the repository call — the
        // destructive confirm re-reads the file, so it needs the passphrase again,
        // asked through the same dialog (never stored anywhere in between).
        val screen = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityScreen.kt").readText()

        val replaceDialog = screen.substringAfter("state.pendingReplacement?.let { source ->")
            .substringBefore("state.working?.let")
        assertTrue(
            "The destructive dialog must hand the file to the passphrase dialog, " +
                "not claim on its own.",
            replaceDialog.contains("PassphraseRequest.Replace(source)"),
        )
        assertTrue(
            "The passphrase dialog must route the Replace request to the confirmed call.",
            screen.contains("viewModel.confirmReplaceFrom(request.source, passphrase)"),
        )
    }

    @Test
    fun `the screen refreshes where the identity stands on every resume`() {
        // The two-device test 2026-09-11: the pending banner appeared only after
        // navigating away and back, because nothing on the screen asked for a status
        // after construction — and the other phone can approve, cancel, or let the
        // 24-hour window pass at any moment, with no push to say so. ON_RESUME makes
        // the banner true the moment the screen is looked at.
        val screen = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityScreen.kt").readText()
        assertTrue(
            "The screen must refresh the identity status on every resume.",
            screen.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)") &&
                screen.contains("viewModel.refreshStatus()"),
        )
    }

    @Test
    fun `a foregrounded screen polls - a paused one stops`() {
        // The second half of the same two-device finding: even with the resume
        // refresh, a screen that is OPEN while the other phone claims or decides
        // showed nothing — no push exists for any of it, so the only live source
        // of truth is asking. The poll must be bounded by the screen's foreground
        // span (ON_PAUSE stops it), never the ViewModel's life.
        val screen = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityScreen.kt").readText()
        val resumed = screen.substringAfter("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)")
            .substringBefore("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)")
        assertTrue(
            "Resuming must both refresh now and start the poll.",
            resumed.contains("viewModel.startStatusPolling()"),
        )
        assertTrue(
            "Pausing must stop the poll — a backgrounded screen has no business asking.",
            screen.substringAfter("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)")
                .contains("viewModel.stopStatusPolling()"),
        )

        val viewModel = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt").readText()
        assertTrue(
            "The poll must be a delay loop, not a one-shot.",
            viewModel.contains("while (isActive)") && viewModel.contains("delay(STATUS_POLL_INTERVAL_MS)"),
        )
        // 10 seconds: instant next to a human on the other phone, cheap on a screen
        // nobody leaves open. Pin the number so a future edit has to argue with
        // this comment.
        assertTrue(viewModel.contains("STATUS_POLL_INTERVAL_MS = 10_000L"))
    }

    @Test
    fun `a move that resolves while nobody is looking is said out loud`() {
        // The third finding of the same test: after the other phone decided, the
        // waiting banner vanished on the next refresh and nothing replaced it —
        // the screen got quieter exactly when it should have gotten louder. A
        // refresh that witnesses a remembered wait resolve must say which way it
        // went, and the paths whose notices already own the story (this screen
        // just decided or just claimed) must not have theirs overwritten.
        val viewModel = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt").readText()
        for (pin in listOf(
            "fun resolvedMoveNotice(",
            "notice = resolved ?: before.notice",
            "if (!remembered.claimant && !after.transferPending)",
            "if (remembered.claimant && !after.claimPending)",
            "if (after.reportIds != remembered.reportIds)",
            "refreshStatus(reportTransitions = false)",
        )) {
            assertTrue("The transition notice needs this: $pin", viewModel.contains(pin))
        }
        // The owner's completed case is told apart from the cancelled one by the
        // server's own superseded record; the claimant's, by the report ids the
        // same read returns.
        assertTrue(
            "Only a wait this phone actually saw resolving is news.",
            viewModel.contains("if (after == null || remembered == null) return null"),
        )
    }

    @Test
    fun `the memory of a wait outlives the screen that saw it`() {
        // The two-device test, round two: B claimed, left the screen, A approved,
        // B came back — the banner was gone and nothing said why. The transition
        // lived in ViewModel fields, and this ViewModel dies with its screen
        // (viewModel() scoped to the nav entry). So the wait is remembered in
        // PendingMoveMemory instead: written by every status check that sees one,
        // cleared by the first that doesn't, read before the next status call.
        // It shares the identity file's own preferences, so it is never in any
        // backup and dies wherever the identity does (Clear All, reset, fresh).
        val repository = repository()
        val statusFn = repository.substringAfter("suspend fun identityStatus()")
            .substringBefore("suspend fun startFreshIdentity()")
        for (pin in listOf(
            "status.claimPending -> memory.write(",
            "status.transferPending -> memory.write(",
            "else -> memory.clear()",
            "claimant = true",
            "claimant = false",
        )) {
            assertTrue("The status check must maintain the memory like this: $pin", statusFn.contains(pin))
        }
        assertTrue(
            "The claimant's wait must be written with the report ids the same read returned.",
            statusFn.contains("reportIds = status.reportIds"),
        )

        // Round three of the same test: the memory was written only by a later
        // status read, so a phone that died between the claim and that read (a
        // force stop, or a refresh that raced the claim) remembered nothing.
        // The write now happens in the claim's own call, and it commits to disk
        // synchronously — an apply() queued behind a process death is exactly
        // the write that goes missing.
        val claimBranch = repository.substringAfter("suspend fun importIdentityBackup")
            .substringBefore("/** The owner's explicit consent")
        val claimPending = claimBranch.substringAfter("is RemoteReportApi.TransferResult.ClaimPending ->")
            .substringBefore("is RemoteReportApi.TransferResult.Completed ->")
        assertTrue(
            "The claim itself must record the wait — every later read is optional.",
            claimPending.contains("identityStatus()"),
        )
        val memory = sourceFile("src/main/kotlin/com/attendo/data/community/PendingMoveMemory.kt").readText()
        assertFalse(
            "The memory must commit, not apply — it has to survive a force stop.",
            memory.contains(".apply()"),
        )

        val viewModel = sourceFile(
            "src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt",
        ).readText()
        val refresh = viewModel.substringAfter("statusJob = viewModelScope.launch")
            .substringBefore("private fun resolvedMoveNotice")
        val readAt = refresh.indexOf("val remembered = repository.pendingMoveMemory()")
        val askAt = refresh.indexOf("val status = runCatching { repository.identityStatus() }")
        assertTrue(
            "The memory must be read before the status call — the call consumes it.",
            readAt in 0 until askAt,
        )
    }

    @Test
    fun `a wait says when it ends - from the server's deadline, not a local guess`() {
        // The "24 hours" both screens used to say in the abstract is a real
        // moment: the grant's claim_deadline (0019), delivered by the same read
        // that says a wait exists. Until it, nothing moves without the owner's
        // answer; past it, the other side can finish alone. A countdown built
        // from anything the phone measured itself (when it first saw the wait)
        // would lie to a phone opened late — the moment must come from the
        // server, and only the ticking remainder is local.
        val api = sourceFile("src/main/kotlin/com/attendo/data/community/RemoteReportApi.kt").readText()
        for (pin in listOf(
            "transferClaimDeadline = obj.str(\"transfer_claim_deadline\")",
            "claimDeadline = obj.str(\"claim_deadline\")",
        )) {
            assertTrue("The status read must parse 0019's deadlines: $pin", api.contains(pin))
        }

        val viewModel = sourceFile(
            "src/main/kotlin/com/attendo/ui/settings/ReportingIdentityViewModel.kt",
        ).readText()
        assertTrue(
            "The refresh must carry both deadlines into the state.",
            viewModel.contains("transferClaimDeadline = status?.transferClaimDeadline,") &&
                viewModel.contains("claimDeadline = status?.claimDeadline,"),
        )

        val screen = sourceFile("src/main/kotlin/com/attendo/ui/settings/ReportingIdentityScreen.kt").readText()
        assertTrue(
            "The owner's banner must show the deadline when there is one.",
            screen.contains("state.transferClaimDeadline?.let { deadline ->") &&
                screen.contains("Nothing moves without your answer until"),
        )
        assertTrue(
            "The claimant's banner must show the deadline when there is one.",
            screen.contains("state.claimDeadline?.let { deadline ->") &&
                screen.contains("The old phone can cancel until"),
        )
        // The countdown must tick on its own, every second, as hours:minutes:seconds —
        // the poll only re-renders on change, so a static "from now" would go stale on
        // a screen left open, and a minute-level "23 h 5 m" looked frozen for most of
        // every minute.
        val deadlineLine = screen.substringAfter("private fun DeadlineLine(")
        assertTrue(
            "The countdown must refresh itself every second while the screen is open.",
            deadlineLine.contains("while (true)") && deadlineLine.contains("delay(1_000)"),
        )
        assertTrue(
            "The countdown must read as a clock: hours, minutes, seconds.",
            deadlineLine.contains("remaining.toHours()") &&
                deadlineLine.contains("remaining.toMinutesPart()") &&
                deadlineLine.contains("remaining.toSecondsPart()") &&
                deadlineLine.contains("\"%d:%02d:%02d left\""),
        )
        assertTrue(
            "A passed deadline must say so, not count negative.",
            deadlineLine.contains("that time has passed"),
        )
    }

    @Test
    fun `the file's key stretching never runs on the caller's thread`() {
        // The two-device test of 2026-09-12: every path into a `.atid` decode — the
        // ordinary import, the re-import after "keep this phone's identity", the
        // confirmed "delete and replace" — froze the app until Android offered its
        // "App isn't responding / Close or wait" dialog, and the export did the
        // same on the other phone. The cause was not the network or the file: the
        // codec's 600,000 PBKDF2 iterations are seconds of pure CPU, and they ran
        // on Dispatchers.Main because the ViewModel launches there and the codec
        // was a plain function. The stretching must be dispatched inside the codec
        // itself — Default, for CPU work — because that is the one place every
        // caller passes through.
        val codec = sourceFile(
            "src/main/kotlin/com/attendo/data/community/ReportingIdentityTransfer.kt",
        ).readText()

        val encode = codec.substringAfter("suspend fun encode(")
            .substringBefore("suspend fun decode(")
        val decode = codec.substringAfter("suspend fun decode(")
            .substringBefore("// ---- internals")
        for (fn in listOf("encode" to encode, "decode" to decode)) {
            assertTrue(
                "The codec's ${fn.first} must be suspend — its seconds of key " +
                    "stretching cannot be a plain call a main-thread caller makes.",
                fn.second.isNotBlank(),
            )
            assertTrue(
                "The codec's ${fn.first} must stretch the key on Dispatchers.Default.",
                fn.second.contains("withContext(Dispatchers.Default)"),
            )
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun repository() =
        sourceFile("src/main/kotlin/com/attendo/data/community/CommunityRepository.kt").readText()

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
