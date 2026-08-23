package com.attendo.core.rollover

import com.attendo.core.model.Semester

/**
 * What the app should do about a semester change, given the semester currently stored and the
 * one this build of the app carries.
 *
 * Attendo keeps exactly one active semester. The bundled timetable moves forward with each
 * release, so the question "is the semester on this device the one this build expects?" has three
 * answers, and only one of them is destructive. This type is the output of [detectRollover] —
 * pure, no Android, no I/O — so the decision can be tested in isolation from the database writes
 * it sometimes triggers.
 *
 * ### Why a sealed type and not a boolean
 *
 * A plain "rollover needed" flag would lose the distinction between "there is no semester yet,
 * adopt the bundled one" and "there is an old semester that must be exported before it is
 * cleared". The first is silent and safe; the second is the one place the app deletes a
 * student's data, so it must be explicit and blocking.
 */
sealed interface RolloverDecision {
    /**
     * Nothing is stored yet. A fresh install, or one that has only ever been seeded with courses
     * (which are orphans until a semester adopts them). There is nothing to lose, so the bundled
     * semester is established without any prompt — see
     * [com.attendo.data.RolloverRepository.establishSilently].
     */
    data object EstablishSilently : RolloverDecision

    /**
     * The stored semester is the bundled one (same year and type — see
     * [Semester.isSameTermAs]). The app is up to date; no screen, no work.
     */
    data object None : RolloverDecision

    /**
     * The stored semester differs from the bundled one: the device still holds an older semester
     * while this build carries a newer one. The blocking rollover screen must be shown, offering
     * a PDF export and a JSON backup before [current]'s data is cleared and [bundled] is put in
     * its place.
     */
    data class RolloverRequired(
        val current: Semester,
        val bundled: Semester,
    ) : RolloverDecision
}

/**
 * Decides what to do about a semester change.
 *
 * Comparison is on identity ([Semester.isSameTermAs]: year + type), never on dates. The student
 * may have corrected their term dates, and a semester whose end date was fixed in March is still
 * the semester it was in July — that is the whole reason [Semester.isSameTermAs] ignores dates.
 * Detecting a rollover on dates would re-prompt every time a date is edited.
 *
 * Detection is driven by the *bundled* calendar (a compile-time constant), not by the student's
 * customised term dates and not by the app version. Comparing versions would break the moment a
 * student skips an update; comparing the bundled semester to the stored one is correct for as
 * long as the bundled calendar tracks the real academic calendar.
 */
fun detectRollover(current: Semester?, bundled: Semester): RolloverDecision = when {
    current == null -> RolloverDecision.EstablishSilently
    current.isSameTermAs(bundled) -> RolloverDecision.None
    else -> RolloverDecision.RolloverRequired(current, bundled)
}
