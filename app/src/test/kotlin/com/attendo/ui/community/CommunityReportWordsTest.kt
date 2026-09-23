package com.attendo.ui.community

import com.attendo.core.community.CommunityObservation
import com.attendo.core.community.CommunityObservationStatus
import com.attendo.core.community.CommunityObservationType
import com.attendo.core.community.CommunityReportKind
import com.attendo.core.community.ReportPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The report sheet's and cards' words — pinned the same way the poll words are (see
 * [CommunityPollWordsTest]), because both answer the question the feature is most easily
 * failed at: *what time is this about?*
 *
 * "Room 203 · right now" is the sheet saying out loud that a room report is a present
 * observation; "Today · 1–2 PM" is it naming the slot a class report attaches to; and a
 * time-change headline that read "9 PM" for a 9 AM change was a student being told the
 * wrong fact at the only moment they read it.
 */
class CommunityReportWordsTest {

    @Test
    fun `a room context says right now, because that is what it means`() {
        assertEquals(
            "Room 203 · right now",
            contextLine(ReportContext(room = "203")),
        )
    }

    // Class contexts have no composer anymore — reports and polls are about rooms only,
    // filed from the room screens. The class report kinds survive on the wire for rows
    // filed before this build, and My Reports still labels those rows.

    @Test
    fun `a room context never implies an hour`() {
        // A room report is a present observation; the words must not attach an hour —
        // "Room 203 · right now · 2–3 PM" would be a claim the report does not make.
        assertEquals(
            "Room 203 · right now",
            contextLine(ReportContext(room = "203")),
        )
    }

    // ------------------------------------------------------------- future claims

    @Test
    fun `a claim context names the slot it expects, in the future tense`() {
        // The whole point of the claim model: "expected 2–3 PM" says two things "right
        // now" cannot — that the statement is about later, and that the student *expected*
        // it rather than saw it. The date it is about is always today, by the server's
        // own rule, so the slot alone is the honest line.
        assertEquals(
            "Room 203 · expected 2–3 PM",
            contextLine(ReportContext(room = "203"), claimHour = 14),
        )
    }

    @Test
    fun `a claim headline reads as an expectation with its slot, never as a present report`() {
        // "Occupied, despite the timetable" alone would be a lie for a claim — the claim
        // is about 2 PM, and at 10 AM nobody has seen anything. The expect-phrasing plus
        // the slot is the only honest sentence.
        assertEquals(
            "Students expect it occupied 2–3 PM",
            claim(CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE).headline(),
        )
        assertEquals(
            "Students expect it free 2–3 PM",
            claim(CommunityReportKind.ROOM_FREE_DESPITE_BUSY).headline(),
        )
        // A claim with no target slot (a row this build should never see, but a newer
        // server might send one) still reads as a claim — just one that forgot its slot.
        assertEquals(
            "Students expect it occupied",
            claim(CommunityReportKind.ROOM_OCCUPIED_DESPITE_FREE, targetStartHour = null).headline(),
        )
    }

    private fun claim(
        kind: CommunityReportKind,
        targetStartHour: Int? = 14,
    ) = CommunityObservation(
        id = "claim",
        kind = kind,
        status = CommunityObservationStatus.REPORTED,
        room = "203",
        section = null,
        subject = null,
        classDate = null,
        startHour = null,
        payload = ReportPayload(),
        note = null,
        createdAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(3600),
        observationType = CommunityObservationType.FUTURE,
        eventDate = LocalDate.now(),
        targetStartHour = targetStartHour,
        targetEndHour = targetStartHour?.plus(1),
    )

    @Test
    fun `the invalid_target refusal redirects to a present report`() {
        // Migration 0007's new code: the slot the claim named has arrived, and the
        // refusal's job is to send the student to the report kind that fits now. It
        // belongs to the complete set below — a code a student will read raw is a bug.
        assertEquals(
            "That hour has already started. Report what you see instead.",
            reportFailureLine("invalid_target"),
        )
    }

    @Test
    fun `a claim cannot be sent before its hour is picked`() {
        // Structural pin, the same contract style as the double-send guard: the Send
        // button must stay off while "Later today" is chosen and no hour is — a claim
        // without a target is not a claim, and the server would only refuse it after
        // the round-trip this guard saves.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/ReportSheet.kt").readText()
        assertTrue(
            "Send must require a picked claim hour once Later today is chosen.",
            source.contains("enabled = !submitted && (!laterToday || claimHour in claimHours)"),
        )
        assertTrue(
            "The handler must re-derive isClaim from the same condition — the enabled " +
                "state can lag a click.",
            source.contains("val isClaim = claimsAvailable && laterToday && claimHour in claimHours"),
        )
    }

    @Test
    fun `claims are offered only for a room, today, with a slot still ahead`() {
        // Three gates, each load-bearing: class contexts are historical by construction
        // (no claims there), a claim about a started slot is a present report (the
        // server says invalid_target), and past 17:00 there is nothing later today to
        // claim about — the choice must disappear rather than offer the impossible.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/ReportSheet.kt").readText()
        assertTrue(
            "The claim choice must be gated to room contexts with later slots left.",
            source.contains(
                "context.room != null && context.classDate == null && claimHours.isNotEmpty()",
            ),
        )
        assertTrue(
            "Later-hour chips must only offer slots that have not started.",
            source.contains("TimeGrid.startHours.filter { it > nowHour }"),
        )
    }

    @Test
    fun `the claim travels from the sheet to the submit path`() {
        // The sheet's new lambda parameters must reach the ViewModel's submit — a claim
        // filed as a present report is the worst silent failure this feature could ship:
        // the student said "at 2" and the room detail would say "right now".
        val room = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomDetailScreen.kt").readText()
        assertTrue(
            "RoomDetailScreen must forward the claim type and target hour to submit().",
            room.contains("observationType = observationType") &&
                room.contains("targetStartHour = targetStartHour"),
        )
    }

    @Test
    fun `a time change headline reads the grid's own label, never a guessed meridiem`() {
        val observation = CommunityObservation(
            id = "1",
            kind = CommunityReportKind.CLASS_TIME_CHANGED,
            status = CommunityObservationStatus.REPORTED,
            room = null,
            section = null,
            subject = null,
            classDate = LocalDate.now(),
            startHour = 9,
            payload = ReportPayload(newStartHour = 9),
            note = null,
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(3600),
        )

        // The old helper printed every hour as PM — a 9 AM change read "9 PM".
        assertEquals("Students report a different hour: 9–10 AM", observation.headline())
    }

    @Test
    fun `the Rooms tab's card names the reporter only when the reporter is the reader`() {
        // The tab card's sentence was "Students report it occupied" on every device,
        // including the one that filed the report — noticed on a non-teaching day, when
        // the community section is the only thing the Rooms tab shows. The reporter's
        // own phone gets "You", or "You and other students" once strangers pile on
        // behind it; nobody else's card changes, and no reporter is ever named — the
        // reader is the only "You" the community UI has.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/CommunityCards.kt").readText()
        val tendency = source.substringAfter("private fun tendencyLine(")
        for (pin in listOf(
            "!own -> \"Students\"",
            "summary.reportCount == 1 -> \"You\"",
            "else -> \"You and other students\"",
            "\"\$who report it occupied\"",
            "\"\$who report it free\"",
        )) {
            assertTrue("The Rooms tab's own-report line needs this: $pin", tendency.contains(pin))
        }
    }

    @Test
    fun `every code submit_report can say becomes a line a student can act on`() {
        // The complete set, kept in step with `submit_report` in 0003_rpcs.sql and the
        // claim block 0007 added: the `{ok, code}` envelopes plus `not_authenticated`,
        // which is raised as a SQL exception and so arrives by a different road. A code
        // added to the RPC without a line here is a code a student will read raw.
        assertEquals("That day is too far off to report about", reportFailureLine("date_out_of_range"))
        assertEquals("You already reported this. It only counts once.", reportFailureLine("duplicate_report"))
        assertEquals("You're reporting a lot right now. Give it a minute.", reportFailureLine("rate_limited_short"))
        assertEquals("You've reported a lot today.", reportFailureLine("rate_limited_day"))
        assertEquals("You're temporarily unable to report", reportFailureLine("restricted"))
        assertEquals("That report type isn't recognised", reportFailureLine("invalid_kind"))
        assertEquals("That report was missing something", reportFailureLine("invalid_payload"))
        assertEquals("That hour isn't in the teaching day", reportFailureLine("invalid_start_hour"))
        assertEquals("The note was too long", reportFailureLine("note_too_long"))
        assertEquals("That room name is too long", reportFailureLine("room_too_long"))
        assertEquals(
            "You're signed out. Reopen the app and try again.",
            reportFailureLine("not_authenticated"),
        )
    }

    @Test
    fun `an unknown report refusal keeps its code instead of hiding it`() {
        // Same rule as the polls: tomorrow's server can send a code this build has never
        // heard of, and showing it raw is the honest fallback.
        assertEquals("some_future_code", reportFailureLine("some_future_code"))
    }

    @Test
    fun `the sheet cannot be sent twice`() {
        // A structural pin, in the contract style this repo uses for behavior that lives
        // in a composable: the Send button must consult a submitted guard both in
        // `enabled` (recomposition) and in the handler itself (two clicks can land inside
        // one frame, before any recomposition disables anything), so a second tap cannot
        // queue a twin report. And the guard must be transient — a persisted
        // `submitted = true` restored after process death would freeze a restored sheet
        // whose report may never have been queued.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/community/ReportSheet.kt").readText()
        assertTrue(
            "The Send button must be disabled once a submission is accepted.",
            source.contains("enabled = !submitted"),
        )
        assertTrue(
            "The handler itself must consult the guard — recomposition can lag a click.",
            source.contains("if (!submitted) {"),
        )
        assertTrue(
            "The guard must not survive process death.",
            source.contains("var submitted by remember { mutableStateOf(false) }"),
        )
        assertFalse(
            "A persisted submitted flag would freeze a restored sheet.",
            source.contains("submitted by rememberSaveable"),
        )
    }

    // ------------------------------------------------------------------ plumbing

    private fun sourceFile(relative: String): java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { java.io.File(it, relative) }
            .first(java.io.File::exists)
}
