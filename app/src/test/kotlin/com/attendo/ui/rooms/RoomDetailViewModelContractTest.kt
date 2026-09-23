package com.attendo.ui.rooms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The RoomDetail half of the holiday fix, pinned to structure in this repo's contract-test
 * style (see `RoomsViewModelContractTest`).
 *
 * The screen's week list is a *pattern* — "when is 204 ever free" — and may keep reading the
 * weekly grid on any day. But its "Now:" and "Nothing until …" lines are claims about today,
 * and before the fix both were computed from the grid alone: a holiday Wednesday at 10 AM
 * could say "Now: in use" for a class that was not running. The behavior needs an Android
 * `Context` to exercise (SettingsStore, TimetableRepository), so the pure verdict is tested
 * in `:core` (`roomNowClaim`) and this file pins the wiring: both claims must come from the
 * claim, the week pattern must be untouched, and the raw grid call must not come back.
 */
class RoomDetailViewModelContractTest {

    @Test
    fun `the now-claims come from the calendar-aware claim, never the raw grid`() {
        val source = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomDetailViewModel.kt").readText()

        assertTrue(
            "The claim must be roomNowClaim(now, settings.current.calendar, days) — the calendar's veto.",
            source.contains("roomNowClaim(now, calendar, days)"),
        )
        assertTrue(
            "statusNow may only be set on a teaching day the claim confirms (NowClaim.InSlot).",
            source.contains("statusNow = (claim as? NowClaim.InSlot)"),
        )
        assertTrue(
            "nextSlot may only be set from NowClaim.Later — never the grid's own nextSlot.",
            source.contains("nextSlot = (claim as? NowClaim.Later)?.next"),
        )
        assertFalse(
            "The raw grid call must not come back: RoomAvailability.nextSlot(now, days) is the bug.",
            source.contains("RoomAvailability.nextSlot(now, days)"),
        )
    }

    @Test
    fun `the week pattern is untouched by the calendar`() {
        // The fix must not have narrowed the week list: it is a pattern, not a claim about
        // today, and it keeps rendering on holidays exactly as on any other day.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomDetailViewModel.kt").readText()

        assertTrue(
            "The week view must still be RoomAvailability.weekFor(bookings, room, days).",
            source.contains("RoomAvailability.weekFor(bookings, room, days)"),
        )
        assertTrue(
            "The factory must hand the ViewModel the settings store the calendar lives in.",
            source.contains("container.settings"),
        )
    }

    @Test
    fun `the screen says why before it says anything the grid computed`() {
        val source = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomDetailScreen.kt").readText()

        // nowLine's non-teaching branch must exist; on that day neither "Now:" nor
        // "Nothing until …" may render, which the ViewModel already guarantees by leaving
        // both null — this pin keeps the screen from re-deriving either from the week.
        val nowLineAt = source.indexOf("private fun nowLine")
        assertTrue("nowLine must exist.", nowLineAt >= 0)
        assertTrue(
            "nowLine must have a non-teaching branch that names the reason.",
            source.substring(nowLineAt).contains("state.isNonTeachingToday"),
        )
    }

    // ------------------------------------------------------------------ plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
