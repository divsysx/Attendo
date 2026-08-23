package com.attendo.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The backup file's on-disk schema.
 *
 * ### Why these are separate types
 *
 * Nothing here is a domain model, and that is the point. A backup written today has to be
 * readable by a build whose `ClassSession` has gained a field, whose `UnitMask` stores its
 * bits differently, or whose Room schema has been migrated three times. Serialising the
 * domain classes directly would tie the file format to every one of those decisions and
 * turn a routine refactor into a day someone's semester stopped loading. So the wire format
 * is written out by hand, changes only deliberately, and is versioned.
 *
 * ### The schema
 *
 * ```json
 * {
 *   "formatVersion": 2,
 *   "app":        { "name": "Attendo", "versionName": "1.0", "versionCode": 1 },
 *   "exportedAt": "2026-08-19T09:30:00Z",
 *   "checksum":   { "algorithm": "SHA-256", "value": "<64 hex chars>" },
 *   "payload": {
 *     "preferences": { "overallTargetBasisPoints": 7500, "courseTargetBasisPoints": 7500,
 *                      "section": "2nd Yr ECE-A", "batch": "A1", "displayName": "Divyansh",
 *                      "attendanceBasis": "PERSONAL", "joinedOn": "2026-08-12" },
 *     "calendar":    { "termStart": "2026-07-28", "termEnd": "2026-12-15",
 *                      "holidays": ["2026-10-02"], "workingSaturdays": ["2026-09-12"] },
 *     "semesters": [ { "ref": "m1", "year": 2026, "type": "ODD",
 *                      "startDate": "2026-07-28", "endDate": "2026-12-15",
 *                      "archived": false } ],
 *     "courses":  [ { "ref": "c1", "semesterRef": "m1", "name": "…", "code": "…",
 *                     "targetBasisPoints": 7500, "colorArgb": 0, "archived": false } ],
 *     "patterns": [ { "ref": "p1", "courseRef": "c1", "dayOfWeek": "MONDAY",
 *                     "startHour": 9, "units": 2, "kind": "LECTURE", "room": "204",
 *                     "effectiveFrom": "2026-07-28", "effectiveTo": "2026-09-30" } ],
 *     "sessions": [ { "ref": "s1", "courseRef": "c1", "patternRef": "p1",
 *                     "date": "2026-07-28", "startHour": 9, "unitsPlanned": 2,
 *                     "attendedUnits": [0, 1], "status": "HELD",
 *                     "cancellationReason": null, "kind": "LECTURE", "room": "204",
 *                     "note": null, "approvedAt": "2026-07-28T12:00:00Z",
 *                     "lastEditedAt": null, "movedToRef": null, "movedFromRef": null } ]
 *   }
 * }
 * ```
 *
 * ### Three choices worth explaining
 *
 * **References, not row ids.** Every record carries a `ref` — an opaque string unique within
 * the file — and points at others by `ref`. Primary keys belong to the database that issued
 * them; the moment a backup is restored onto another phone they all change. A reschedule is
 * two rows linked to each other, so an id-based format would have to renumber those links
 * on the way in and hope. References make the file self-contained.
 *
 * **Attendance as a list of hours, not a bitmask.** `[0, 1]` says which hours were attended
 * in a way a person reading the JSON can check; `3` says it in a way that depends on
 * [com.attendo.core.model.UnitMask]'s internals never changing.
 *
 * **Enums, dates and instants as strings.** Written as names and ISO-8601, parsed
 * defensively in [BackupCodec] rather than by the serialiser, so an unrecognised value is
 * reported as a named problem against a field path instead of surfacing as a decoder
 * exception. Strings also mean a future kind of session does not shift the meaning of an
 * ordinal already written to a thousand files.
 *
 * ### One legitimate dangling reference
 *
 * A session's `patternRef` may name a pattern that is **not** in `patterns`, and that is
 * valid. Deleting a pattern keeps the classes already marked against it, so a marked session
 * can outlive its template — and it has to stay a *timetabled* session, because the app
 * derives "was this ad-hoc?" from whether a pattern id is present, and an empty
 * `(patternId, date)` slot is one the generator will fill again. The importer therefore gives
 * such a reference an id of its own rather than treating it as an extra class. Every other
 * reference in the file must resolve — including a course's `semesterRef`, which is absent
 * rather than dangling in a file written before version 2.
 */
@Serializable
internal data class WireEnvelope(
    val formatVersion: Int,
    val app: WireApp,
    val exportedAt: String,
    val checksum: WireChecksum,
    /**
     * Held raw rather than decoded, because the checksum is computed over the payload and
     * has to be verified before anything inside it is trusted — and because a format
     * migration rewrites the payload before it is decoded.
     */
    val payload: JsonObject,
)

@Serializable
internal data class WireApp(
    val name: String,
    val versionName: String,
    val versionCode: Long,
)

@Serializable
internal data class WireChecksum(
    val algorithm: String,
    val value: String,
)

@Serializable
internal data class WirePayload(
    val preferences: WirePreferences,
    val calendar: WireCalendar,
    val courses: List<WireCourse>,
    val patterns: List<WirePattern>,
    val sessions: List<WireSession>,
    /**
     * Empty in a file written before version 2. An empty list is not the same as "no
     * semesters" being an error: the v1→v2 migration puts the calendar's own term in here, so
     * a file that reaches the decoder with none was written by a v2 app that genuinely had
     * none — a fresh install exported before any semester was set up.
     */
    val semesters: List<WireSemester> = emptyList(),
)

@Serializable
internal data class WirePreferences(
    val overallTargetBasisPoints: Int,
    val courseTargetBasisPoints: Int,
    val section: String? = null,
    val batch: String? = null,
    /**
     * The student's display name, absent in files written before there was one — which is
     * why it has a default, and why adding it did not need a format version.
     */
    val displayName: String? = null,
    /** "UNIVERSITY" or "PERSONAL". Absent means the university reading, which is the default. */
    val attendanceBasis: String? = null,
    /** The admission date, kept even under the university reading so switching back is free. */
    val joinedOn: String? = null,
)

@Serializable
internal data class WireSemester(
    val ref: String,
    val year: Int,
    /** "ODD" or "EVEN". */
    val type: String,
    val startDate: String,
    val endDate: String,
    val archived: Boolean = false,
)

@Serializable
internal data class WireCalendar(
    val termStart: String,
    val termEnd: String,
    val holidays: List<String> = emptyList(),
    val workingSaturdays: List<String> = emptyList(),
)

@Serializable
internal data class WireCourse(
    val ref: String,
    val name: String,
    val code: String,
    val targetBasisPoints: Int,
    val colorArgb: Int = 0,
    val archived: Boolean = false,
    /** The semester it is taught in. Absent in a file written before version 2. */
    val semesterRef: String? = null,
)

@Serializable
internal data class WirePattern(
    val ref: String,
    val courseRef: String,
    val dayOfWeek: String,
    val startHour: Int,
    val units: Int,
    val kind: String,
    val room: String? = null,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
)

@Serializable
internal data class WireSession(
    val ref: String,
    val courseRef: String,
    /** Null for an ad-hoc session — an extra class, or a reschedule's landing slot. */
    val patternRef: String? = null,
    val date: String,
    val startHour: Int,
    val unitsPlanned: Int,
    /** Zero-based hour offsets within the session that were attended. */
    val attendedUnits: List<Int> = emptyList(),
    val status: String,
    val cancellationReason: String? = null,
    val kind: String,
    val room: String? = null,
    val note: String? = null,
    val approvedAt: String? = null,
    val lastEditedAt: String? = null,
    /** On a cancelled original: the session that replaced it. */
    val movedToRef: String? = null,
    /** On the replacement: the cancelled original it came from. */
    val movedFromRef: String? = null,
)
