package com.attendo.core.model

/**
 * A subject the student is enrolled in. Courses are entered by hand — there is no
 * enrolment feed — so [code] is whatever short form the student recognises from
 * the timetable ("ADEC", "PSA") and [name] is the expansion.
 *
 * [targetPercent] is per-course because different papers occasionally carry
 * different thresholds; it defaults to the usual 75%.
 *
 * [semesterId] is what keeps two semesters' figures apart. A course is a semester's course —
 * next term is a different six subjects — so the link is on the course rather than on each
 * session, and a semester's attendance is the tally over the courses that belong to it. It is
 * nullable only because installs that predate semesters have courses with nothing to point at;
 * those are adopted into the current semester on first run rather than left dangling.
 */
data class Course(
    val id: Long = 0L,
    val name: String,
    val code: String,
    val targetPercent: Percent = Percent.DEFAULT_TARGET,
    val colorArgb: Int = 0,
    val archived: Boolean = false,
    /** The semester this course is taught in, or null on an install that has none yet. */
    val semesterId: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "course name must not be blank" }
        require(code.isNotBlank()) { "course code must not be blank" }
    }

    /** "ADEC — Analog and Digital Electronic Circuits", or just the name if identical. */
    val displayLabel: String get() = if (code == name) name else "$code — $name"

    /** True when this course belongs to [semester] — the filter behind per-semester figures. */
    fun isIn(semester: Semester): Boolean = semesterId == semester.id
}

/** Distinguishes the three session types the college timetable marks. */
enum class SessionKind {
    /** Plain lecture — untagged in the PDF. */
    LECTURE,

    /** Tagged "(P)" — lab/practical, usually a 2-hour block. */
    PRACTICAL,

    /** Tagged "(T)" — tutorial. */
    TUTORIAL,
    ;

    /** The suffix the timetable uses, e.g. " (P)". Empty for lectures. */
    val timetableTag: String
        get() = when (this) {
            LECTURE -> ""
            PRACTICAL -> " (P)"
            TUTORIAL -> " (T)"
        }
}
