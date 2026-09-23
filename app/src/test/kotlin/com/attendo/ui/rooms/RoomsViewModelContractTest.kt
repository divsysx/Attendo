package com.attendo.ui.rooms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The holiday-bug fix, pinned to structure in this repo's contract-test style (see
 * `AppResetContractTest`, `CommunityReadContractTest`).
 *
 * The bug lived in one line of `RoomsViewModel`: the non-teaching empty state was gated on
 * `slot.isRightNow`, so tapping an hour chip on a holiday cleared the gate and the weekly
 * grid — which has no bookings on a non-teaching day — answered "every room free". The
 * behavior itself needs an Android `Context` to exercise (SettingsStore), so the contract
 * pins the wiring instead: the verdict must come from the pure core pair
 * (`isNonTeachingDay` over `queriedDate`) for *every* query, both room lists must hide
 * inside the non-teaching branch, and the official timetable must remain the only input to
 * availability when the day does teach.
 */
class RoomsViewModelContractTest {

    @Test
    fun `the non-teaching verdict covers picked slots, not just the live view`() {
        val source = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomsViewModel.kt").readText()

        // The verdict is the core pair's, computed for the queried date whatever way the
        // date was reached — clock or chip.
        assertTrue(
            "The empty state must be decided by isNonTeachingDay(now, calendar, slot.query, " +
                "slot.isRightNow) — one rule for live and picked queries alike.",
            source.contains("isNonTeachingDay(now, calendar, slot.query, slot.isRightNow)"),
        )
        // And the old gate — the bug — must never come back.
        assertFalse(
            "The isRightNow gate was the holiday bug: a picked slot must not bypass " +
                "AcademicCalendar.",
            source.contains("isRightNow && !calendar.isTeachingDay"),
        )
        assertFalse(
            "The old field name isNonTeachingToday described the live view only; the new " +
                "one describes the queried day.",
            source.contains("isNonTeachingToday"),
        )
    }

    @Test
    fun `the official timetable is still the only input to availability`() {
        // The fix must not have touched what the lists are made of: bookings from the
        // bundled timetable, walked by the pure engine. Community data never appears.
        val source = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomsViewModel.kt").readText()
        assertTrue(
            "Availability must still be RoomAvailability.statusesAt(bookings, query, allRooms).",
            source.contains("RoomAvailability.statusesAt(bookings, query, allRooms)"),
        )
        assertFalse(
            "Community observations must never reach the rooms ViewModel — they are a " +
                "display-only overlay rendered by the screens.",
            source.contains("community") || source.contains("Community"),
        )
    }

    @Test
    fun `the screen hides both lists on a non-teaching day`() {
        val source = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomsScreen.kt").readText()

        val nonTeachingAt = source.indexOf("if (state.isNonTeachingDay)")
        assertTrue("The non-teaching branch must exist and use the new field.", nonTeachingAt >= 0)

        // The busy section renders inside the branch's else, so a mid-week holiday cannot
        // show that day-of-week's bookings under a "not a teaching day" line.
        val elseAt = source.indexOf("} else {", nonTeachingAt)
        val busyAt = source.indexOf("if (state.busy.isNotEmpty())")
        assertTrue("The busy list must follow the non-teaching branch's else.", elseAt in nonTeachingAt until busyAt)
        assertFalse(
            "The old field name must not survive in the screen.",
            source.contains("isNonTeachingToday"),
        )
    }

    @Test
    fun `the right-now view is labeled by isRightNow, not by isLive`() {
        // Outside teaching hours there is no live slot, so `isLive` is false on the default
        // view — the very view that *is* now. Anything that means "this is the present"
        // must read the picked-null verdict instead, or a Sunday afternoon (or a holiday,
        // where community reports about extra classes are the feature's whole point) gets
        // a "Back to now" button for a view already on now and loses its community section.
        val screen = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomsScreen.kt").readText()
        assertTrue(
            "Back-to-now must appear only when a slot was picked: gate on !isRightNow.",
            screen.contains("if (!state.isRightNow)"),
        )
        assertTrue(
            "The community section must include the right-now view, live or not.",
            screen.contains("(state.isLive || state.isRightNow) && community.available"),
        )
        val viewModel = sourceFile("src/main/kotlin/com/attendo/ui/rooms/RoomsViewModel.kt").readText()
        assertTrue(
            "RoomsUiState must carry isRightNow from the availability step.",
            viewModel.contains("isRightNow = slot.isRightNow"),
        )
    }

    // ------------------------------------------------------------------ plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
