package com.attendo.data

import com.attendo.core.model.Semester
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one place the app reads semesters, and the half of writing them that is non-destructive.
 *
 * The arithmetic that decides what a semester *means* — which courses are in it, which dates
 * bound it, how a tally is kept inside it — is all in `:core`; this class keeps the "current
 * semester" the rest of the app reads through [current], and flips the archived flag a student
 * sets from the course list.
 *
 * ### Where a semester row is created
 *
 * This class reads and archives semester rows; it does not create them. A term is established
 * in exactly one place — `RolloverRepository`, whether silently on a fresh install (nothing to
 * lose) or as the clear-and-initialise of starting a new semester — and that is deliberate: the
 * destructive half of a new term (deleting the old one's courses, then writing the new row)
 * belongs with the detection and confirmation that gate it, not with a read/write helper. What
 * remains here is the read of the row and the adoption of courses that predate it, the latter
 * now driven from the same establishing path.
 */
class SemesterRepository(private val database: AttendoDatabase) {

    private val semesterDao = database.semesterDao()

    // ---- reads --------------------------------------------------------------

    /** Every semester, newest first — how the attendance screen groups its courses. */
    val semesters: Flow<List<Semester>> =
        semesterDao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** The semester in use: the newest unarchived one, or null on an install that has none. */
    val current: Flow<Semester?> = semesterDao.observeCurrent().map { it?.toModel() }

    suspend fun setArchived(semesterId: Long, archived: Boolean) =
        semesterDao.setArchived(semesterId, archived)
}
