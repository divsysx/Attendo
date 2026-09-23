package com.attendo.data

import android.content.Context
import android.content.SharedPreferences
import com.attendo.core.data.MigrationPlan
import com.attendo.core.data.SectionSeeder
import com.attendo.core.data.TimetableMigration
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Brings an install that seeded from an older printed timetable up to the edition now bundled.
 *
 * The faculty reissued the timetable on 17 September 2026, part-way through the term. An app
 * that shipped the new file and nothing else would leave every student who had already picked
 * their courses holding July's slots for the rest of the semester, with no way to notice — the
 * courses screen would keep saying "Mon 11–12, Room 314" and the room would be wrong. So the
 * file and the migration ship together.
 *
 * ### It runs once per edition, and only ever on top of what the student has
 *
 * The gate is [Timetable.revision], the digest of the timetable file: when it differs from the
 * one recorded here, this is a new edition. Nothing is written until the student has a section,
 * because the section is what says which page of the timetable is theirs — an install that has
 * not picked one yet has nothing to be migrated against and is left for the next app open.
 *
 * The work itself is [TimetableMigration]'s, and it only ever reconciles courses the student
 * already tracks. Every change is a retirement and an insertion through
 * [AttendanceRepository.applyTimetableMigration], so attendance already recorded survives, and a
 * course the new page has stopped teaching is left standing with its history rather than
 * deleted. Nothing is created: a subject this edition adds reaches the course list the same way
 * it always did, by being ticked on the seed screen.
 */
class TimetableMigrator(
    context: Context,
    private val attendance: AttendanceRepository,
    private val timetables: TimetableRepository,
    private val settings: SettingsStore,
    private val clock: () -> LocalDate = LocalDate::now,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The edition recorded as applied, or null on an install that has never run one. */
    val appliedRevision: String? get() = prefs.getString(KEY_REVISION, null)

    /**
     * Runs the migration if this edition has not been applied yet.
     *
     * @return what it changed, or null when there was nothing to do — either the edition was
     *   already applied, or the student has no section to be migrated against.
     */
    suspend fun run(): MigrationPlan? {
        val timetable = timetables.timetable()
        if (timetable.revision.isEmpty()) return null
        if (timetable.revision == appliedRevision) return null

        val current = settings.current
        val section = current.section?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val plan = TimetableMigration.plan(
            courses = attendance.courses.first(),
            patterns = attendance.patterns.first(),
            incoming = SectionSeeder.plan(
                bookings = timetable.bookings,
                section = section,
                // The migration date, not the term start: a replacement slot is a slot the
                // faculty printed in September, and dating it from July would have it claim
                // the six weeks that ran under the timetable it replaces.
                termStart = TimetableMigration.EFFECTIVE_FROM,
                batch = current.batch,
                subjects = timetable.subjects,
                defaultTarget = current.courseTarget,
            ).proposals,
            effectiveFrom = TimetableMigration.EFFECTIVE_FROM,
        )

        attendance.applyTimetableMigration(plan)
        // And immediately fill in what the new slots should already have produced. The days
        // between the revision date and today belong to the new patterns, and waiting for the
        // dashboard to generate them would leave a gap wherever the migration ran after the
        // dashboard's own pass — which, being a background coroutine racing the first frame,
        // is most of the time. Idempotent on `(patternId, date)`, like every other call.
        attendance.syncSessions(settings.current.effectiveCalendar, clock())
        // Recorded after the writes, so an interrupted run is retried rather than assumed
        // done. Re-running is free: what has been migrated no longer differs from the plan.
        prefs.edit().putString(KEY_REVISION, timetable.revision).apply()
        return plan
    }

    /**
     * Forgets which edition was applied, so the next [run] reconciles from scratch.
     *
     * Not called by the app — a reset clears the preferences file wholesale, and a restore
     * brings a fresh one. It exists so the behaviour is testable without reaching into the
     * preference file from outside.
     */
    fun forget() = prefs.edit().remove(KEY_REVISION).apply()

    companion object {
        /** Its own file: this is not a setting, and must not be cleared or synced as one. */
        const val FILE_NAME: String = "attendo-timetable"

        private const val KEY_REVISION = "applied_revision"
    }
}
