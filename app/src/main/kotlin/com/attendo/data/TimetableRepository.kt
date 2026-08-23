package com.attendo.data

import android.content.res.AssetManager
import com.attendo.core.data.Glossary
import com.attendo.core.data.ImportProblem
import com.attendo.core.data.TimetableCsv
import com.attendo.core.model.RoomBooking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The room timetable, plus the two acronym keys that make it readable.
 *
 * [problems] is carried rather than thrown. A mistyped line in next semester's file
 * should cost that line and be *visible* on the settings screen, not take the Rooms tab
 * down; the bundled file is checked by `BundledTimetableTest` precisely so this list is
 * empty in a shipped build.
 */
data class Timetable(
    val bookings: List<RoomBooking>,
    val subjects: Glossary,
    val faculty: Glossary,
    val problems: List<ImportProblem> = emptyList(),
) {
    val isEmpty: Boolean get() = bookings.isEmpty()

    /** The full subject name for a printed code, falling back to the code itself. */
    fun subjectName(code: String?): String = subjects.nameOf(code)

    /** "Prof. A.K. Tandon & Dr. Arjun Tyagi" for a `[AKT / AT]` cell. */
    fun facultyName(code: String?): String = faculty.label(code)

    companion object {
        val EMPTY = Timetable(emptyList(), Glossary.EMPTY, Glossary.EMPTY)
    }
}

/**
 * The timetable in use: the bundled CSV asset.
 *
 * The timetable ships in source as `assets/timetable.csv` and its two glossary files,
 * checked by `:core:test` (see `BundledTimetableTest`). Parsing ~600 CSV lines is fast but
 * not free, and the Rooms tab asks for the same data on every recomposition, so the result
 * is memoised in a [Deferred] created under a [Mutex]: concurrent first callers await one
 * parse instead of racing to do it twice. The asset never changes at runtime, so the memo
 * is good for the life of the process.
 */
class TimetableRepository(
    private val assets: AssetManager,
) {

    private val mutex = Mutex()
    private var cached: Deferred<Timetable>? = null

    /** The parsed timetable, loaded once per process. */
    suspend fun timetable(): Timetable = loader().await()

    private suspend fun loader(): Deferred<Timetable> = mutex.withLock {
        cached ?: CoroutineScope(Dispatchers.IO).async { parse() }.also { cached = it }
    }

    private suspend fun parse(): Timetable = withContext(Dispatchers.IO) {
        val imported = TimetableCsv.parse(read(TIMETABLE_ASSET))
        Timetable(
            // mergeAdjacent is a no-op on the shipped file, which already writes a 2-hour
            // block as one row (BundledTimetableTest pins that). It runs anyway so a
            // hand-retyped file that splits blocks across two columns still yields "204 is
            // busy until 11" rather than two one-hour bookings.
            bookings = TimetableCsv.mergeAdjacent(imported.bookings),
            subjects = Glossary.parse(read(SUBJECTS_ASSET)),
            faculty = Glossary.parse(read(FACULTY_ASSET)),
            problems = imported.problems,
        )
    }

    private fun read(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private companion object {
        const val TIMETABLE_ASSET = "timetable.csv"
        const val SUBJECTS_ASSET = "subjects.csv"
        const val FACULTY_ASSET = "faculty.csv"
    }
}
