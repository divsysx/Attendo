package com.attendo.core.backup

import com.attendo.core.model.Semester
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Carries an old backup's payload forward to the format this build understands.
 *
 * ### Why this exists
 *
 * A backup is written once and read whenever it is needed, which may be after the app has moved
 * on. Deciding that old files are *migrated* rather than *rejected* is what makes the format's
 * promise real — the alternative is discovering the need on the day a student who has not opened
 * the app in four months tries to restore, when their file is already written and the only
 * options are refusing it or writing the migration in a hurry.
 *
 * ### How a step is added
 *
 * When the payload shape changes, bump [CURRENT] and add the transform that takes the previous
 * shape to the new one:
 *
 * ```kotlin
 * const val CURRENT = 3
 * private val STEPS = mapOf<Int, (JsonObject) -> JsonObject>(
 *     1 to ::v1ToV2,
 *     2 to { payload -> /* v2 -> v3 */ },
 * )
 * ```
 *
 * A step works on the raw [JsonObject], not on decoded DTOs, and that is deliberate: the DTOs in
 * [BackupWire] describe the *current* shape only. A v1 step that had to build v1 DTOs would mean
 * keeping every historical DTO alive forever.
 *
 * A step also never validates. It reshapes what it can and leaves the rest alone, because
 * [BackupCodec] validates every field afterwards and reports faults against their real paths — a
 * step that threw on a malformed date would replace "calendar.termStart is not a date" with
 * "the payload could not be migrated", which tells the student less about their own file.
 *
 * Steps run in ascending order and the chain must be unbroken — a gap is a programming error,
 * not a bad file, so [walk] reports it as one. `BackupMigrationsTest` checks the chain covers
 * [OLDEST_SUPPORTED] to [CURRENT] on every build, which is the guard that catches a bumped
 * version with no step behind it.
 */
internal object BackupMigrations {

    /** The format this build writes. */
    const val CURRENT: Int = 2

    /**
     * The oldest format this build still reads.
     *
     * Raising this abandons files, so it should only ever move when a format is genuinely
     * unmigratable — not to tidy up.
     */
    const val OLDEST_SUPPORTED: Int = 1

    val supported: IntRange get() = OLDEST_SUPPORTED..CURRENT

    fun isSupported(version: Int): Boolean = version in supported

    private val STEPS: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        1 to ::v1ToV2,
    )

    /** True when every version from [OLDEST_SUPPORTED] to [CURRENT] has a way forward. */
    fun chainIsComplete(): Boolean = chainIsComplete(OLDEST_SUPPORTED, CURRENT, STEPS)

    fun migrate(payload: JsonObject, from: Int): JsonObject = walk(payload, from, CURRENT, STEPS)

    // ---- 1 -> 2: semesters ---------------------------------------------------

    /** The ref given to the semester derived from a v1 file's calendar. */
    internal const val V1_SEMESTER_REF: String = "m1"

    /**
     * Gives a v1 payload the semester it always had.
     *
     * A v1 file has no `semesters` array, but it is not silent about the term either: its
     * calendar carries the exact start and end dates of the one semester the file describes, and
     * every session in it was generated inside those dates. So the semester is *derived*, not
     * invented — [Semester.spanning] reads the odd/even identity off the start date using the
     * same rule the rest of the app uses, rather than this step having an opinion of its own.
     *
     * ### Why archived courses are left unlinked
     *
     * Because the file does not say which term they belong to. A live course in a v1 file can
     * only be a course of the term the calendar describes; an archived one may be a leftover from
     * a semester that ended before the file was written, and claiming otherwise would put last
     * year's subject inside this year's percentage. An absent `semesterRef` means "not known",
     * which is a state with a defined outcome — the app adopts an unlinked course into the
     * current semester when the student is there to see it — and that is the honest answer.
     *
     * A file whose calendar dates cannot be read gets an empty array and no links: nothing to
     * derive from, and the decoder reports the calendar itself far better than this step could.
     */
    private fun v1ToV2(payload: JsonObject): JsonObject {
        // A v1 file with semesters in it was hand-edited or written by a build that had the field
        // before the version was bumped. Either way it already says what this step would derive,
        // and overwriting it would discard the more specific answer.
        val existing = payload["semesters"] as? JsonArray
        if (existing != null && existing.isNotEmpty()) return payload

        val derived = semesterOf(payload["calendar"] as? JsonObject)
            ?: return payload.with("semesters" to JsonArray(emptyList()))

        return payload
            .with("semesters" to JsonArray(listOf(derived.toWire())))
            .with("courses" to linkLiveCourses(payload["courses"] as? JsonArray))
    }

    private fun semesterOf(calendar: JsonObject?): Semester? {
        val start = calendar?.date("termStart") ?: return null
        val end = calendar.date("termEnd") ?: return null
        // Nothing is repaired here: an end before a start, or an implausible year, is a fault the
        // decoder names precisely, and this step's job is only to stop short of guessing.
        return runCatching { Semester.spanning(start, end) }.getOrNull()
    }

    private fun linkLiveCourses(courses: JsonArray?): JsonArray = JsonArray(
        courses.orEmpty().map { element ->
            val course = element as? JsonObject ?: return@map element
            val archived = (course["archived"] as? JsonPrimitive)?.booleanOrNull ?: false
            when {
                archived -> course
                course["semesterRef"] != null -> course
                else -> course.with("semesterRef" to JsonPrimitive(V1_SEMESTER_REF))
            }
        },
    )

    private fun Semester.toWire(): JsonObject = buildJsonObject {
        put("ref", V1_SEMESTER_REF)
        put("year", year)
        put("type", type.name)
        put("startDate", startDate.toString())
        put("endDate", endDate.toString())
        put("archived", archived)
    }

    private fun JsonObject.with(entry: Pair<String, JsonElement>): JsonObject =
        JsonObject(this + entry)

    private fun JsonObject.date(key: String): LocalDate? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    // ---- the walk, parameterised so it can be tested without inventing a format ----

    internal fun chainIsComplete(
        from: Int,
        to: Int,
        steps: Map<Int, (JsonObject) -> JsonObject>,
    ): Boolean = (from until to).all { it in steps }

    /**
     * Applies each step from [from] to [to] in order.
     *
     * @throws IllegalStateException if a version in the chain has no step. Callers have
     *   already established that [from] is a supported version, so a missing step means this
     *   build shipped with an incomplete chain — a bug here, not a problem with the file, and
     *   silently returning an unmigrated payload would hand the decoder the wrong shape.
     */
    internal fun walk(
        payload: JsonObject,
        from: Int,
        to: Int,
        steps: Map<Int, (JsonObject) -> JsonObject>,
    ): JsonObject = (from until to).fold(payload) { current, version ->
        val step = steps[version]
            ?: error("no backup migration from format version $version to ${version + 1}")
        step(current)
    }
}
