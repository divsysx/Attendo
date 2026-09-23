# The Attendo Backup Format

**Status: normative specification of format version 2.**

This document defines the Attendo backup file format. It is written so that an
implementation can be built from it alone — no reading of the Kotlin source
required. The Android app's `core/backup/` package is the reference
implementation; where this document and that code disagree, the code is buggy
and this document wins.

A backup file is a single JSON document containing everything Attendo knows
about a student's semester: their courses, timetable slots, the complete
attendance history, the academic calendar, the semesters those belong to, and
the preferences that change what the numbers mean. It contains **no** Android,
Room, or Kotlin implementation detail — no row ids, no bitmask internals, no
platform-specific types.

- Current format version: **2**
- Oldest version a reader must accept: **1**
- File extension: `json` (suggested name `attendo-backup-YYYY-MM-DD.json`)
- Media type: `application/json`
- Encoding: UTF-8

---

## 1. Design rules the format is built on

These are the invariants behind every decision below. An implementation that
follows the letter of the schema but not these rules will still be wrong.

**The file is a complete snapshot, not a delta.** A backup carries the whole
logical state. Restoring means *replacing* the install's portable data with the
file's contents (§10) — there is no partial restore, no merge, and no "best
effort" reading.

**References, not row ids.** Every record carries a `ref` — an opaque string
unique within its kind inside the file — and points at other records by `ref`.
Primary keys belong to the database that issued them and all change on restore;
a reschedule is two rows linked to each other, so an id-based format would have
to renumber those links and hope.

**Attendance is a list of hours, not a bitmask.** `attendedUnits: [0, 1]` says
which hours were attended in a way a person reading the JSON can check, and
that does not depend on any bit-packing scheme never changing.

**Enums, dates and instants are strings.** Written as names and ISO-8601,
parsed defensively, so an unrecognised value is reported as a named problem
against a field path instead of a decoder exception — and a future kind of
session does not shift the meaning of an ordinal written to a thousand files.

**The reader trusts nothing.** A backup arrives from outside the app, possibly
across a year of versions, possibly truncated by whatever carried it, possibly
hand-edited. Reading is all-or-nothing: either the file is understood
completely or it is refused with reasons (§9). A reader MUST NOT return a
partial result, and MUST NOT repair a value it considers wrong — repairing
silently changes an attendance record, which is worse than refusing.

---

## 2. Envelope

```json
{
  "formatVersion": 2,
  "app":        { "name": "Attendo", "versionName": "1.1", "versionCode": 2 },
  "exportedAt": "2026-08-19T09:30:00Z",
  "checksum":   { "algorithm": "SHA-256", "value": "<64 hex chars>" },
  "payload": { ... }
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `formatVersion` | integer | yes | A JSON number, not a string. Decides whether the reader may read the file at all (§8). |
| `app.name` | string | yes | Provenance only. `"Attendo"` when written by this app. |
| `app.versionName` | string | yes | Provenance only, e.g. `"1.1"`. A reader MUST NOT make any decision based on it — the format version is the only compatibility signal. |
| `app.versionCode` | integer | yes | Provenance only. |
| `exportedAt` | string | yes | ISO-8601 instant with offset. Writers MUST write UTC with a `Z` suffix. Readers MUST accept any parseable offset. |
| `checksum.algorithm` | string | yes | Must be `"SHA-256"` (case-insensitive). Anything else: refuse. |
| `checksum.value` | string | yes | 64 hex characters, the SHA-256 of the canonical rendering of `payload` (§7). Comparison is case-insensitive. |
| `payload` | object | yes | Held opaque until the checksum verifies — nothing inside it may be trusted, or even parsed, before that. |

The reader processes the envelope in this order, and each stage may assume the
previous one succeeded:

1. Parse the whole file as JSON. Failure → refuse (`not JSON`).
2. The top level must be a JSON object. Failure → refuse (`not a backup`).
3. Read `formatVersion` **from the raw tree**, before decoding the envelope —
   a newer format is entitled to have reshaped the envelope itself. Missing,
   non-numeric, or unsupported → refuse (`unsupported format version`, §9).
4. Decode the envelope shape. Failure → refuse (`not a backup`).
5. Verify `checksum` against `payload` **exactly as written**, before any
   migration touches it — the hash belongs to the bytes the exporting build
   produced. Mismatch → refuse (`checksum mismatch`).
6. Migrate the payload forward if `formatVersion` < current (§8.3).
7. Decode and validate the payload (§3–§6). Any problem → refuse, reporting
   **all** problems found in one pass, not just the first.

## 3. Payload

```json
{
  "preferences": { ... },
  "calendar":    { ... },
  "semesters":   [ ... ],
  "courses":     [ ... ],
  "patterns":    [ ... ],
  "sessions":    [ ... ]
}
```

All six keys are required (a missing `semesters` in a v2 file is a malformed
payload; in a v1 file it is simply absent and the migration supplies it).
Every field below marked *default* may be omitted by a writer and takes that
default in a reader. A reader MUST ignore any field it does not recognise
(§8.1).

Throughout: a **date** is `YYYY-MM-DD` (proleptic Gregorian, ISO-8601); an
**instant** is ISO-8601 with offset; a **basis point count** is an integer
where 10 000 = 100.00%; a string is **blank** if it is empty or only
whitespace.

### 3.1 `preferences`

What a student would be upset to lose, and only that. Device behaviour
(theme, toggles) deliberately does not travel — see §11.

| Field | Type | Default | Rules |
|---|---|---|---|
| `overallTargetBasisPoints` | integer | required | 0–10 000. |
| `courseTargetBasisPoints` | integer | required | 0–10 000. |
| `section` | string \| null | `null` | Blank reads as `null`. A label for the seeding UI. |
| `batch` | string \| null | `null` | Blank reads as `null`. |
| `displayName` | string \| null | `null` | Trimmed on read; blank reads as `null`. A greeting label only — nothing keys off it. |
| `attendanceBasis` | string \| null | `null` (meaning `UNIVERSITY`) | Closed enum: `UNIVERSITY` \| `PERSONAL`. Which date attendance is counted from — this changes the percentage, which is why it travels. |
| `joinedOn` | date \| null | `null` | The admission date, written whichever basis is in force, so switching back and forth is free. `PERSONAL` with no date is a legal stored state (counts as the university reading until filled in); `UNIVERSITY` with a date is legal too. |

### 3.2 `calendar`

The shape of the term the file's sessions were generated inside.

| Field | Type | Default | Rules |
|---|---|---|---|
| `termStart` | date | required | — |
| `termEnd` | date | required | MUST NOT be before `termStart`. |
| `holidays` | array of date | `[]` | Dates on which no class is generated. |
| `workingSaturdays` | array of date | `[]` | Saturdays on which classes *are* generated. |

### 3.3 `semesters`

Each entry:

| Field | Type | Default | Rules |
|---|---|---|---|
| `ref` | string | required | Non-blank; unique among all `semesters` entries. |
| `year` | integer | required | The academic year, 1900–2999. |
| `type` | string | required | Closed enum: `ODD` \| `EVEN`. |
| `startDate` | date | required | — |
| `endDate` | date | required | MUST NOT be before `startDate`. |
| `archived` | boolean | `false` | — |

File-level rule: the pair (`year`, `type`) MUST be unique — a year has one odd
semester and one even one. Both live and archived semesters travel, because a
percentage is meaningless without knowing which term it belongs to.

An empty `semesters` array in a **v2** file is legal and means a fresh install
exported before any semester was set up. (It is not the same as the array
being absent, which only happens in v1 files.)

### 3.4 `courses`

| Field | Type | Default | Rules |
|---|---|---|---|
| `ref` | string | required | Non-blank; unique among all `courses` entries. |
| `name` | string | required | MUST NOT be blank. |
| `code` | string | required | MUST NOT be blank. |
| `targetBasisPoints` | integer | required | 0–10 000. This course's own attendance threshold. |
| `colorArgb` | integer | `0` | A 32-bit colour packed as `0xAARRGGBB` (alpha in the high byte, blue in the low), written as a **signed** 32-bit integer in two's complement — a colour with alpha ≥ 0x80 appears as a negative number (`0xFF3F51B5` is written as `-12627531`). The sign is an artefact of the representation and carries no meaning: a reader takes the value's low 32 bits. |
| `archived` | boolean | `false` | — |
| `semesterRef` | string \| null | `null` | If present, MUST resolve to a `semesters` entry. **Absent is legal and means "not known"** — the file does not claim which term the course belongs to, and the app adopts it into the running semester on restore. |

### 3.5 `patterns`

A recurring weekly timetable slot. Each entry:

| Field | Type | Default | Rules |
|---|---|---|---|
| `ref` | string | required | Non-blank; unique among all `patterns` entries. |
| `courseRef` | string | required | MUST resolve to a `courses` entry. |
| `dayOfWeek` | string | required | Closed enum: `MONDAY` `TUESDAY` `WEDNESDAY` `THURSDAY` `FRIDAY` `SATURDAY` `SUNDAY`. |
| `startHour` | integer | required | The teaching day is 9:00–18:00. `startHour` MUST be 9–17 and `startHour + units` MUST NOT exceed 18. |
| `units` | integer | required | 1–12 (the whole-day maximum; the slot rule above is tighter still). |
| `kind` | string | required | Closed enum: `LECTURE` \| `PRACTICAL` \| `TUTORIAL`. |
| `room` | string \| null | `null` | Blank reads as `null`. |
| `effectiveFrom` | date | required | When the slot began generating classes. |
| `effectiveTo` | date \| null | `null` | When the slot was retired. If present, MUST NOT be before `effectiveFrom`. `null` = still live. |

### 3.6 `sessions`

The complete history — every class, whatever its state, including cancelled,
rescheduled and ad-hoc ones. Restoring today's percentage is not enough; the
file must reproduce the history it was computed from.

| Field | Type | Default | Rules |
|---|---|---|---|
| `ref` | string | required | Non-blank; unique among all `sessions` entries. |
| `courseRef` | string | required | MUST resolve to a `courses` entry. |
| `patternRef` | string \| null | `null` | See §6 — the **one** reference allowed to dangle. `null` means an ad-hoc class (an extra class, or a reschedule's landing slot). |
| `date` | date | required | — |
| `startHour` | integer | required | Same teaching-day rule as `patterns.startHour`. |
| `unitsPlanned` | integer | required | Same bounds as `patterns.units`, checked against this session's own `startHour`. |
| `attendedUnits` | array of integers | `[]` | Zero-based hour offsets **within this session** that were attended. Every element MUST be in `0 .. unitsPlanned-1`. Interpreted as a set (duplicates are harmless). Only meaningful when `status` is `HELD`, but not an error otherwise. |
| `status` | string | required | Closed enum: `SCHEDULED` (not yet reviewed) \| `HELD` (took place; moves the attendance figure — a fully-absent class is `HELD` with an empty `attendedUnits`, which is genuinely different from `CANCELLED`) \| `CANCELLED`. |
| `cancellationReason` | string \| null | `null` | Closed enum: `FACULTY_CANCELLED` \| `HOLIDAY` \| `RESCHEDULED`. If present, `status` MUST be `CANCELLED` (a `CANCELLED` session without a reason is accepted). |
| `kind` | string | required | Closed enum: `LECTURE` \| `PRACTICAL` \| `TUTORIAL`. |
| `room` | string \| null | `null` | Blank reads as `null`. May differ from the pattern's room (a one-off room change). |
| `note` | string \| null | `null` | Blank reads as `null`. |
| `approvedAt` | instant \| null | `null` | When the student confirmed this session; `null` while still `SCHEDULED`. |
| `lastEditedAt` | instant \| null | `null` | — |
| `movedToRef` | string \| null | `null` | On a cancelled original: the session that replaced it. MUST resolve (§6). |
| `movedFromRef` | string \| null | `null` | On a reschedule's landing slot: the cancelled original it came from. MUST resolve (§6). |

## 4. Enum vocabularies (closed sets)

A value outside its set is a refusal naming the field path and the accepted
values. These sets only ever grow (a new value is an additive change, §8.1);
existing names never change meaning.

| Enum | Values |
|---|---|
| Day of week | `MONDAY` `TUESDAY` `WEDNESDAY` `THURSDAY` `FRIDAY` `SATURDAY` `SUNDAY` |
| Session kind | `LECTURE` `PRACTICAL` `TUTORIAL` |
| Session status | `SCHEDULED` `HELD` `CANCELLED` |
| Cancellation reason | `FACULTY_CANCELLED` `HOLIDAY` `RESCHEDULED` |
| Semester type | `ODD` `EVEN` |
| Attendance basis | `UNIVERSITY` `PERSONAL` |

## 5. Complete list of reader validation rules

A reader MUST enforce all of these, and MUST report every violation in one
pass rather than stopping at the first. None of them is repairable: a value
that does not make sense is reported, never guessed at.

Structural:

1. The file is JSON, the top level is an object, and the envelope decodes
   (§2).
2. `formatVersion` is numeric and supported (§8).
3. The checksum matches (§7).
4. Every `ref` is non-blank and unique within its own kind (semesters,
   courses, patterns, sessions each have their own namespace — `c1` and `s1`
   may coexist).
5. `courseRef` (patterns, sessions), `semesterRef` (courses, when present),
   and `movedToRef` / `movedFromRef` (sessions, when present) MUST resolve to
   an entry of the right kind in this file.

Value rules, with the field path each is reported against:

6. `preferences.overallTargetBasisPoints`, `preferences.courseTargetBasisPoints`,
   each course's `targetBasisPoints`: 0–10 000.
7. `calendar.termEnd` ≥ `calendar.termStart`.
8. Every semester: `endDate` ≥ `startDate`; `year` in 1900–2999; (`year`,
   `type`) unique across the file.
9. Every course: `name` and `code` non-blank.
10. Every pattern and session: `units`/`unitsPlanned` ≥ 1 and ≤ 12;
    `startHour` in 9–17 with `startHour + units` ≤ 18.
11. Every pattern: `effectiveTo` ≥ `effectiveFrom` when present.
12. Every session: every element of `attendedUnits` in `0 .. unitsPlanned-1`.
    An out-of-range hour is refused outright, not clamped — it is the one fault
    that would silently inflate a percentage.
13. Every session: `cancellationReason` present only when `status` is
    `CANCELLED`.
14. Every date is a valid `YYYY-MM-DD`; every instant is a valid ISO-8601
    instant; every enum value is in its closed set (§4).

Reschedule consistency (reported per offending session):

15. If a session's `movedToRef` resolves to a session that exists in the file,
    that target's `movedFromRef` MUST point back at the first session, and the
    first session's `status` MUST be `CANCELLED` — a live session claiming to
    have moved would count the class twice.
16. Symmetrically, if a session's `movedFromRef` resolves, the origin's
    `movedToRef` MUST point back.
17. A cancelled-as-rescheduled row with **no** link at all is legal: its
    replacement may have been deleted on its own, and the cancellation is
    still true.

A reader SHOULD also verify what it wrote: the reference implementation reads
the restored data back inside its write transaction and compares it to the
decoded snapshot for logical equality, refusing (and rolling back) on any
mismatch.

## 6. The one legal dangling reference

A session's `patternRef` may name a pattern that is **not** in the file, and
that is valid. Deleting a timetable slot keeps the classes already marked
against it, so a marked session can outlive its template — and it must stay a
*timetabled* session, because "was this ad-hoc?" is derived from whether a
pattern reference is present, and an empty (pattern, date) slot is one the
timetable generator would fill again. A reader therefore treats an unresolvable
`patternRef` as an opaque origin marker, not an error.

Every other reference in the file must resolve. A course's `semesterRef` is
absent rather than dangling in a file that predates semesters.

## 7. Checksum

A backup travels through chat apps, USB cables, cloud drives and pockets, and
any of them can hand back a file that is shorter than it went in — and JSON is
quite capable of parsing cleanly after losing a chunk from the middle of an
array. The checksum turns "restored 40 of your 220 classes and said it worked"
into a refusal.

It is a **corruption check, not a signature**: anyone who can edit the file can
recompute the hash. That is the right scope — the threat is a damaged file,
not a forged one, and the student is the only party involved.

**Algorithm.** SHA-256, hex-encoded, over the UTF-8 bytes of the *canonical
rendering* of the payload JSON tree.

**Canonical rendering.** Derived from the parsed tree, never from the file's
literal text — so reformatting a backup by hand (whitespace, key order) does
not break verification, and two backups of the same data hash the same.

- Objects: `{`, then entries sorted by key (lexicographic order over the key
  strings' Unicode code points), as `"key":value` joined by `,`, then `}`.
  No whitespace anywhere.
- Arrays: `[`, values in file order joined by `,`, then `]`.
- Strings: encoded as a JSON string — `"` and `\` escaped, control characters
  below U+0020 as the shortest escape (`\n` `\r` `\t` `\b` `\f`, otherwise
  `\u00XX`); all other characters emitted as themselves.
- Numbers and booleans: **the literal text the file was written with**, not a
  reparsed or renormalised form. `7500` and `7.5e3` hash differently, and that
  is deliberate: canonicalising them would mean deciding what a number *is*,
  and this format only ever writes integers.
- `null`: `null`.

The hash covers the payload **as written**, and is verified **before** any
migration runs — the hash belongs to the bytes the exporting build produced,
not to what the current build wishes they were.

## 8. Versioning and compatibility

### 8.1 The unknown-field rule

Readers MUST ignore unknown fields at every level of the document. A missing
*required* field is a fault; an *extra* one is only a newer build having
written something this reader has no use for. Refusing a file over a field
that would be ignored anyway would turn every additive change into a broken
restore.

Consequently, a writer MAY add a new **optional** field without bumping the
format version (`displayName` and `attendanceBasis` were added this way), and
old readers keep working. Anything else — renaming a field, changing its type
or units, changing its semantics, or adding a new *required* field — requires
bumping `formatVersion` and providing a migration step.

### 8.2 Version gates

- `formatVersion` above the reader's current version: **refuse**. A format
  this build has never seen may carry fields that change what the data means,
  and a partial reading of someone's semester is worse than an honest refusal.
  The refusal should tell the student to update the app.
- `formatVersion` below the reader's oldest supported version: **refuse**.
  Raising the supported floor abandons files and must only happen when a
  format is genuinely unmigratable — never to tidy up.

### 8.3 Migrations

A migration is a chain of forward transforms, one per step `v → v+1`, running
in ascending order from the file's version to the reader's current version.
The chain must be unbroken; a gap is a bug in the reader build, not a problem
with the file.

A step works on the raw JSON, not on decoded records — the decoded shape
describes only the *current* version, and a step that had to build old DTOs
would mean keeping every historical shape alive forever. A step never
validates: it reshapes what it can and leaves the rest alone, because the
reader validates every field afterwards and reports faults against their real
paths — a step that threw on a bad date would replace "termStart is not a
date" with "the payload could not be migrated", which tells the student less
about their own file.

**Version 1 → 2 (semesters).** A v1 file has no `semesters` array. The step
derives one semester from the v1 calendar — it is derived, not invented: the
calendar carries the exact start and end dates of the one term the file
describes. The semester's type comes from its start month (July–December →
`ODD`, January–May → `EVEN`, June → `ODD`, because a term *starting* in June
is the odd semester starting early); its year is the start date's year. Every
**live** course is linked to that semester (a live course in a v1 file can
only belong to the term the calendar describes); **archived** courses are left
unlinked, because the file does not say which term they belong to and claiming
otherwise would put last year's subject inside this year's percentage. If the
v1 file already contains a non-empty `semesters` array (hand-edited, or
written by a build that had the field before the version was bumped), the step
leaves it untouched. A calendar whose dates cannot be parsed yields an empty
array and no links — the reader's own validation reports the calendar far
better than the step could.

### 8.4 Cross-platform support window

Every implementation of this format — Android, Web, and anything after —
MUST support reading the same version window, currently **1 through 2**, and
MUST write the current version, **2**. A file written by any implementation
must be readable by every other implementation, at any version in the window.
A platform dropping support for an old version is a change to *this* section
and applies to all platforms together.

## 9. Rejection taxonomy

Every refusal is one of these named cases (implementations may word the
message for their platform, but MUST distinguish the cases):

| Case | Trigger |
|---|---|
| Not JSON | The file does not parse as JSON at all — wrong file, or truncated mid-write. |
| Not a backup | Parses as JSON but is not an Attendo backup: no envelope, non-object top level, missing/non-numeric `formatVersion`, envelope shape wrong. |
| Unsupported format version | `formatVersion` above the reader's current version, or below the oldest supported. The two directions get different messages ("update the app" vs "no longer reads"). |
| Unknown checksum algorithm | `checksum.algorithm` is not SHA-256. |
| Checksum mismatch | The payload does not match its checksum — truncated, corrupted in transit, or edited. Use another copy. |
| Malformed field | A field is missing, of the wrong type, or an unknown enum value. Reported with the field path. |
| Dangling reference | A `courseRef`/`semesterRef`/`movedToRef`/`movedFromRef` points at a record the file does not contain. (Only `patternRef` may dangle, §6.) |
| Duplicate reference | Two records of the same kind claim the same `ref`. |
| Invalid value | A value the domain would reject — a class outside the teaching day, a term ending before it starts, an attended hour outside the hours planned. |
| Broken reschedule | The two halves of a reschedule disagree (§5, rules 15–16). |

A refusal carries **all** the problems found, not only the first. What the
student is *shown* may be one short sentence (from where they stand there is
one situation — the file they picked is not the one they wanted — and one
remedy); the detailed reasons belong in a log or diagnostic surface.

## 10. Restore semantics: REPLACE, and only replace

Importing a backup **replaces the install's portable user data** (§11) with
the file's contents. It does not merge with what is already there.

This is deliberate, and it is the only mode the format defines. Merging two
independently modified attendance histories has no principled conflict rule:
when two copies disagree about which hours of a Tuesday morning were attended,
nothing in the data says which one is true — not timestamps (a mark edited
later is not more truthful than one recorded at the door), not completeness
(a copy with more classes may simply have been used longer), not majority
(there are only two copies). A merge would have to choose, silently, in every
such case. Refusing to choose is what "replace" means here. Merge mode, if it
is ever wanted, is a future format-neutral feature; nothing in this format
prevents a reader from implementing one above the replace primitive.

The reference implementation makes replace safe rather than merely trusted:

- The file is fully decoded and validated **before** anything is written; the
  thing restored is the exact thing that was previewed.
- A safety snapshot of the current install is written to private storage
  **before** the replacement begins; if the snapshot cannot be written, the
  restore does not happen.
- The replacement itself is one transaction: delete every row, insert the
  file's rows, read them back, and compare against what was meant to be
  written — compared for *logical* equality (canonical id renumbering), since
  row ids are an artefact of whichever database wrote them. A mismatch aborts
  and rolls the whole thing back.
- Settings are replaced only after the data transaction succeeds, and are
  cleared first so an absent preference reverts to its default rather than
  surviving from the replaced install.
- One level of undo: the safety snapshot can be put back after a restore. It
  is consumed by doing so — "undo" cannot become an accidental redo.
- After a successful restore, the timetable generator re-runs against the
  restored calendar, because a file from a different (or older) install does
  not carry every session the restored patterns imply up to today.

## 11. Portable user data vs device-specific settings

The format carries **portable user data** — what a student would be upset to
lose, and what makes the numbers mean the same thing on any device:

| Travels in a backup | Does not travel |
|---|---|
| Courses (with per-course targets) | Theme / appearance |
| Timetable patterns (live and retired) | The Android-automatic-backup toggle |
| The complete session history (reviewed, cancelled, rescheduled, ad-hoc) | Update-check state and declined-release memory |
| The academic calendar (term dates, holidays, working Saturdays) | The restore-undo safety snapshot |
| Semesters, live and archived | Analytics state |
| Preferences: targets, section, batch, display name, attendance basis, joined-on date | **The community reporting identity — see §12** |
| — | **Statistics — see below** |

**Statistics are never stored, anywhere, in any implementation.** Percentages,
per-course tallies, at-risk counts are always *derived* from the session
history. That is what makes "exact restoration of statistics" a property of
the data rather than a separate thing to keep in sync: restore the sessions
exactly and every figure recomputes to exactly what the old install showed.

Device-specific settings describe how a particular phone behaves, not what
the student recorded, and a restore that carried them would switch the new
device's behaviour behind the student's back. An implementation MUST NOT add
device settings to the backup format merely because they exist — the
portable/device split above is part of this specification, and extending the
*portable* side is a deliberate act (§8.1 for the mechanics, and the addition
must be justified under the "what a student would be upset to lose" rule).

## 12. Security and privacy boundary

**The community reporting identity never appears in any backup.** Attendo's
community feature (anonymous attendance reports) has its own, deliberately
separate identity and transfer mechanism (the `.atid` export code system).
That identity is *not* portable user data: it is a server-side reporting
credential, and bundling it into ordinary backups would turn a file students
pass around into a transfer of posting authority. The Android implementation
guards this with tests asserting that the identity file appears in no backup
include list and that the backup format has no community section; an
equivalent guard is REQUIRED in any other implementation. This boundary is
not negotiable and not extensible.

A backup file itself is **plaintext JSON by design**: it is the manual,
no-account, no-network fallback, and it must remain readable by the student
who exported it. Encryption is a *transport* concern — the planned
Google-Drive upload path encrypts before upload — and MUST NOT change the
on-disk format defined here.

The checksum is a corruption check, not an authenticity check (§7). A backup
contains the student's own academic record and their chosen display name, and
nothing else — no identifiers, no credentials, no analytics.

## 13. Golden fixtures

The format is pinned by a platform-neutral fixture suite: a directory of
versioned JSON files, with an index, that every implementation's test suite
MUST consume directly (not re-encoded copies — the actual files). The suite
lives in the repository at `fixtures/backup/` and contains, at minimum:

- **`v1-valid.json`** — a complete v1 file (no `semesters`), which exercises
  the migration path end to end.
- **`v2-valid.json`** — a representative v2 file covering every field,
  including the optional ones and a working reschedule pair.
- **v2 edge-case fixtures** — one per subtle rule: the dangling `patternRef`,
  the unlinked cancelled-as-rescheduled session, the archived course with no
  `semesterRef`, a personal attendance basis with no `joinedOn`, an empty
  `semesters` array.
- **One fixture per rejection category** in §9 — truncated JSON, checksum
  mismatch, each of the value rules, the broken reschedule, the future
  version, the retired version.

Each fixture's expected outcome is recorded in `fixtures/backup/index.json`: for
accept fixtures, the `originalFormatVersion` and a `summary` of the decoded
snapshot (row counts per kind, the number of courses carrying a resolvable
semester link, and the raw units-held / units-attended sums — percentages are
excluded because formatting them is a UI concern); for reject fixtures, the §9
`rejection` category (and, for the version gate, which `direction` — `future`
or `retired` — the file falls on). The index grows fields only; suite runners
must tolerate unknown keys in it, as they must everywhere else. A change to
the Kotlin implementation that breaks a fixture is a change to the format; a
change to the format that breaks a fixture is a version bump plus a migration
plus a new fixture. The future Web implementation runs the exact same files.

---

*Derived from the reference implementation (`core/backup/`: `BackupWire`,
`BackupCodec`, `BackupSnapshot`, `BackupMigrations`, `BackupChecksum`,
`BackupProblem`) and its test suite. Format version 2 is frozen as specified
here: no field renames, no cosmetic changes, no version bump without a
migration.*
