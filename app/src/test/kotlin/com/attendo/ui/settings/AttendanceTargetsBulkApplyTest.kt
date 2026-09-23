package com.attendo.ui.settings

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.attendo.core.model.Course
import com.attendo.core.model.Percent
import com.attendo.data.AttendanceRepository
import com.attendo.data.db.AttendoDatabase
import com.attendo.data.db.CourseEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * "Apply to all active courses" — the one way a single percentage reaches courses that exist.
 *
 * The feature is a navigation row and a confirmation dialog, and both of those are worth
 * nothing if the write behind them is wrong in either direction. Writing to archived courses
 * would re-judge a finished term; skipping one would leave a subject silently on the old number
 * while the dialog said it had applied to six. And a write that did not mark the rows dirty
 * would look correct on this phone forever and never reach the account.
 *
 * These run against the real database and the real repository, because the exclusion being
 * tested is a SQL predicate (`archived = 0`) rather than a filter in the ViewModel — a fake
 * would test the filter and not the query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttendanceTargetsBulkApplyTest {

    private lateinit var context: Context
    private lateinit var db: AttendoDatabase
    private lateinit var attendance: AttendanceRepository

    private val at: Instant = Instant.parse("2026-09-23T09:30:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AttendoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        attendance = AttendanceRepository(db) { at }
    }

    @After
    fun tearDown() = db.close()

    // ---- what it writes ------------------------------------------------------

    @Test
    fun `every active course is set to the chosen target`() = runBlocking {
        addCourse("ADEC")
        addCourse("PSA")
        addCourse("DBMS")

        val changed = attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))

        assertEquals(3, changed)
        assertEquals(listOf(8500, 8500, 8500), rows().map { it.targetBasisPoints })
    }

    @Test
    fun `the count it answers is the number of rows it wrote`() = runBlocking {
        // The confirmation dialog promises a number before the write happens. This is the
        // number the write itself produces, and the two come from the same read.
        repeat(5) { index -> addCourse("C$index") }

        val changed = attendance.setTargetForActiveCourses(Percent.ofPercent(80.0))

        assertEquals(5, changed)
        assertEquals(5, rows().count { it.targetBasisPoints == 8000 })
    }

    @Test
    fun `an arbitrary whole percentage is accepted`() = runBlocking {
        // Not a preset: 63% is a perfectly ordinary answer, and the bulk control offers every
        // whole percentage from 0 to 100.
        addCourse("ADEC")

        attendance.setTargetForActiveCourses(Percent.ofWholePercent(63)!!)

        assertEquals(6300, targetOf("ADEC"))
    }

    @Test
    fun `both ends of the range are written`() = runBlocking {
        addCourse("ADEC")
        addCourse("PSA")

        attendance.setTargetForActiveCourses(Percent.ofWholePercent(0)!!)
        assertEquals(listOf(0, 0), rows().map { it.targetBasisPoints })

        attendance.setTargetForActiveCourses(Percent.ofWholePercent(100)!!)
        assertEquals(listOf(10_000, 10_000), rows().map { it.targetBasisPoints })
    }

    // ---- what it leaves alone ------------------------------------------------

    @Test
    fun `archived courses keep their own target`() = runBlocking {
        // A finished term is a closed record. Its target is part of what the figure was
        // measured against, so re-judging it would change history rather than a setting.
        addCourse("ADEC", targetPercent = 75.0)
        addCourse("OLD1", archived = true, targetPercent = 60.0)
        addCourse("OLD2", archived = true, targetPercent = 72.5)

        val changed = attendance.setTargetForActiveCourses(Percent.ofPercent(90.0))

        assertEquals(1, changed)
        assertEquals(9000, targetOf("ADEC"))
        assertEquals(6000, targetOf("OLD1"))
        assertEquals(7250, targetOf("OLD2"))
    }

    @Test
    fun `a fractional archived target is not rounded`() = runBlocking {
        addCourse("OLD1", archived = true, targetPercent = 72.5)

        attendance.setTargetForActiveCourses(Percent.ofPercent(90.0))

        assertEquals(7250, targetOf("OLD1"))
    }

    @Test
    fun `archived courses are not stamped as edited`() = runBlocking {
        // Not just the value: a row marked dirty would be pushed, and the account copy would
        // then carry a change nobody made.
        addCourse("OLD1", archived = true, targetPercent = 60.0, untouchedSince = 1_000L)

        attendance.setTargetForActiveCourses(Percent.ofPercent(90.0))

        val archived = row("OLD1")
        assertFalse(archived.dirty)
        assertEquals(1_000L, archived.clientUpdatedAt)
    }

    @Test
    fun `nothing but the target changes on an active course`() = runBlocking {
        addCourse("ADEC", colorArgb = 0xFF00FF00.toInt())
        addCourse("PSA", targetPercent = 70.0)

        attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))

        val adec = row("ADEC")
        assertEquals("ADEC", adec.code)
        assertEquals("ADEC course", adec.name)
        assertEquals(0xFF00FF00.toInt(), adec.colorArgb)
        assertFalse(adec.archived)
        assertNull(adec.semesterId)
    }

    @Test
    fun `nothing is applied until the bulk control is used`() = runBlocking {
        // The distinction the whole screen is built around: two courses with two targets stay
        // that way until something asks for them to move.
        addCourse("ADEC", targetPercent = 55.0)
        addCourse("PSA", targetPercent = 60.0)

        assertEquals(5500, targetOf("ADEC"))
        assertEquals(6000, targetOf("PSA"))
    }

    // ---- syncing -------------------------------------------------------------

    @Test
    fun `every changed course is marked for the next push`() = runBlocking {
        addCourse("ADEC", untouchedSince = 1_000L)
        addCourse("PSA", untouchedSince = 1_000L)
        addCourse("DBMS", untouchedSince = 1_000L)

        attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))

        rows().forEach { row ->
            assertTrue(row.code, row.dirty)
            assertEquals(row.code, at.toEpochMilli(), row.clientUpdatedAt)
        }
    }

    @Test
    fun `the cloud identity a row already had is carried through the change`() = runBlocking {
        // A bulk apply is an update, not a re-upload: a row that came back from the account
        // with an id must be pushed against that id, or the account ends up with two copies of
        // the same course and the older one still holds the old target.
        addCourse("ADEC")
        val stored = row("ADEC")
        db.courseDao().updateAll(listOf(stored.copy(cloudId = "cloud-adec", dirty = false)))

        attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))

        val updated = db.courseDao().all().single { it.id == stored.id }
        assertEquals("cloud-adec", updated.cloudId)
        assertEquals(8500, updated.targetBasisPoints)
        assertTrue(updated.dirty)
    }

    @Test
    fun `applying the same target twice converges rather than diverging`() = runBlocking {
        // A student who taps apply, reads the dialog again and applies the same number must
        // end up exactly where the first tap left them.
        addCourse("ADEC", targetPercent = 60.0)
        addCourse("PSA", targetPercent = 70.0)

        attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))
        val afterFirst = rows().map { it.copy(clientUpdatedAt = 0L) }

        val changedAgain = attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))

        assertEquals(2, changedAgain)
        assertEquals(afterFirst, rows().map { it.copy(clientUpdatedAt = 0L) })
    }

    // ---- nothing to do -------------------------------------------------------

    @Test
    fun `an install with no courses writes nothing`() = runBlocking {
        assertEquals(0, attendance.setTargetForActiveCourses(Percent.ofPercent(85.0)))

        assertTrue(rows().isEmpty())
    }

    @Test
    fun `an install whose only courses are archived writes nothing`() = runBlocking {
        addCourse("OLD1", archived = true, targetPercent = 60.0, untouchedSince = 1_000L)

        val changed = attendance.setTargetForActiveCourses(Percent.ofPercent(85.0))

        assertEquals(0, changed)
        assertEquals(6000, targetOf("OLD1"))
        assertEquals(1_000L, row("OLD1").clientUpdatedAt)
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Adds a course. [untouchedSince] rewrites the row's sync stamp afterwards, for tests that
     * need to tell "this write touched it" from "it was already like that".
     */
    private suspend fun addCourse(
        code: String,
        archived: Boolean = false,
        targetPercent: Double = 75.0,
        colorArgb: Int = 0,
        untouchedSince: Long? = null,
    ) {
        attendance.addCourse(
            Course(
                name = "$code course",
                code = code,
                targetPercent = Percent.ofPercent(targetPercent),
                colorArgb = colorArgb,
                archived = archived,
            ),
        )
        if (untouchedSince != null) {
            val stored = row(code)
            db.courseDao().updateAll(
                listOf(stored.copy(clientUpdatedAt = untouchedSince, dirty = false)),
            )
        }
    }

    private suspend fun rows(): List<CourseEntity> = db.courseDao().all()

    private suspend fun row(code: String): CourseEntity = rows().single { it.code == code }

    private suspend fun targetOf(code: String): Int = row(code).targetBasisPoints
}
