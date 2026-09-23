# Timetable Migration Audit — OLD (28 Jul 2026) → NEW (17 Sep 2026)

> **STATUS: PAUSED. THIS IS NOT A MIGRATION SPECIFICATION.**
>
> This is a working research document. It records the state of a forensic comparison
> between the two printed timetables that ship alongside this repository. The migration
> it describes is **intentionally not being performed**, because several new
> courses/classes in `new tt.pdf` need to be confirmed with actual students before a
> final `timetable.csv` can safely be produced.
>
> Nothing in this document authorises a change to `timetable.csv`, `subjects.csv`,
> `faculty.csv`, `TimeGrid`, `AcademicCalendar`, or any test. See §12.

**Document created:** 18 September 2026
**Last updated:** 18 September 2026
**Tracked by git:** No — this file is intentionally untracked.

---

## Confidence states used throughout

Every factual claim below carries one of three markers. Nothing is stated without one.

| Marker | Meaning |
|---|---|
| **CONFIRMED** | Established by direct reading of the source PDF, its rendered pixels, or the repository's own files. Verified twice where the method allowed. |
| **LIKELY / CROSS-CHECKED** | Derived by a stated inference rule from confirmed inputs; consistent with all sources checked, but not printed literally in any source. |
| **UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION** | Not resolvable from any available source. **Must not be silently promoted to a fact.** |

> A parser-derived result is never automatically **CONFIRMED**. Where the parser and
> visual inspection of the rendered page disagree, the rendered page wins (§6).

---

## 1. SOURCE FILES

### 1.1 `old-tt.pdf` — the outgoing timetable

| Property | Value | State |
|---|---|---|
| Path | `old-tt.pdf` (repo root, **untracked**) | CONFIRMED |
| Size | 226,454 bytes | CONFIRMED |
| SHA-256 | `5e9f23b8b21c0ab90fa98142c576673267aef7f1f7381f8b1d31659f55bdf5b0` | CONFIRMED |
| Page count | 23 | CONFIRMED |
| Media box | 612 × 792 user units | CONFIRMED |
| `/Rotate` | 90 | CONFIRMED |
| Display geometry | 792 × 612 display units (transform: `x_display = y_user`, `y_display = x_user`) | CONFIRMED |
| Time columns | 9 — `9–10 AM` … `5–6 PM` | CONFIRMED |
| Day rows | 5 — Mon–Fri. A Saturday row is present but **empty on every page**. | CONFIRMED |
| Effective date | `w.e.f.: 28th July 2026` | CONFIRMED |
| Academic session | 2026-27 | CONFIRMED |
| Section pages | 22 (pages 1–22) | CONFIRMED |
| Page 23 | **Master sheet** — subject-acronym list + faculty list | CONFIRMED |

### 1.2 `new tt.pdf` — the incoming timetable

> Note the space in the filename. `new tt.pdf`, not `new-tt.pdf`.

| Property | Value | State |
|---|---|---|
| Path | `new tt.pdf` (repo root, **untracked**) | CONFIRMED |
| Size | 144,916 bytes | CONFIRMED |
| SHA-256 | `41bcb310d60c049ff2572398075634882625d3f0f889c37f5394000bdc53b3ce` | CONFIRMED |
| Page count | 22 | CONFIRMED |
| Media box | 612 × 1008 user units | CONFIRMED |
| `/Rotate` | 90 | CONFIRMED |
| Display geometry | 1008 × 612 display units (same transform) | CONFIRMED |
| Document title | `v2_Timetable_2026-27_Odd_v2.xlsx` — i.e. produced from a spreadsheet, not a publishing pipeline | CONFIRMED |
| Time columns | **10** — `8–9 AM` … `5–6 PM` | CONFIRMED |
| Day rows | **6** — Mon–Sat. Saturday is **populated on 9 of the 22 pages**. | CONFIRMED |
| Effective date | `w.e.f.: 17th September 2026` | CONFIRMED |
| Academic session | 2026-27 (**unchanged from OLD**) | CONFIRMED |
| Section pages | 22 — **identical section names to OLD**, in the same order | CONFIRMED |
| Master sheet | **ABSENT.** There is no subject-acronym page. Each page carries a "Faculty Acronym(s)" footer table — *faculty only*. | CONFIRMED |

> **This absence is the root cause of the entire pause.** OLD ships a subject-acronym
> key; NEW ships only a faculty key. See §5 and §7.

### 1.3 Section structure — identical across both PDFs

**CONFIRMED.** All 22 pages of NEW carry the same 22 section names as OLD:

```
1st Yr EE-A   1st Yr EE-B   1st Yr ECE-A   1st Yr ECE-B   1st Yr CSE-A   1st Yr CSE-B
2nd Yr EE-A   2nd Yr EE-B   2nd Yr ECE-A   2nd Yr ECE-B   2nd Yr CSE-A   2nd Yr CSE-B
3rd Yr EE     3rd Yr ECE-A   3rd Yr ECE-B   3rd Yr CSE-A   3rd Yr CSE-B
4th Yr EE     4th Yr ECE-A   4th Yr ECE-B   4th Yr CSE-A   4th Yr CSE-B
```

Note the three "lone" sections with no paired division — `3rd Yr EE`, `4th Yr EE`,
`4th Yr ECE-?` — the same asymmetry exists in both PDFs. **The section/division
architecture does not change.** See §9 for the requirement to preserve it.

### 1.4 Session and effective dates

| | OLD | NEW |
|---|---|---|
| Academic session | 2026-27 | 2026-27 (**same**) |
| Effective from | 28 July 2026 | 17 September 2026 |

**CONFIRMED.** Because the session is unchanged and only the effective date moves, this
is a **mid-session timetable revision**, not a semester rollover. That distinction
matters and is carried through §10.

---

## 2. CURRENT ATTENDO TIMETABLE

### 2.1 The shipped asset

**CONFIRMED** — read from `app/src/main/assets/timetable.csv`.

| Property | Value |
|---|---|
| Path | `app/src/main/assets/timetable.csv` |
| Size | 23,160 bytes |
| Total lines | 606 |
| Header + comment lines | 20 |
| **Data rows** | **542** |
| Column order | `day,start,units,room,subject,section,faculty,kind,batch` |
| Days used | `Mon Tue Wed Thu Fri` — **no Saturday** |
| Start hours used | `9 10 11 12 13 14 15 16 17` — **9 slots, no 8 AM** |
| Distinct sections | 22 |
| Distinct subject codes | 59 |
| Distinct rooms | 21 |

Rooms currently in the file:

```
203 204 211 212 213 214 217 219 303 304 311 312 313 314 317 513 Basement Lab R1 R2 R3 R4
```

### 2.2 Current vocabulary

**CONFIRMED.**

- `app/src/main/assets/subjects.csv` — **54** entries. Includes the `SFL,Sports for Life / Fit India` entry, and hyphenated codes `AEW-I`, `DE-1`, `EM-I`, `EVS-2`, `ECA`.
- `app/src/main/assets/faculty.csv` — **44** entries.

The header comment of `timetable.csv` states the intended reading, and is worth
preserving verbatim as the format contract:

> `# To update for a new semester: replace this file, keeping the column order.`

and, on the room-less case:

> `# Every cell on every page is transcribed, including the ones printed without a room`
> `# in brackets — SFL (Sports for Life / Fit India). Those leave ``room`` empty: they are`
> `# classes the section attends, so they must be here for course seeding, but they`
> `# occupy no room and the Rooms screens skip them.`

### 2.3 Assumptions currently encoded by tests

**CONFIRMED** — read from `core/src/test/kotlin/com/attendo/core/data/BundledTimetableTest.kt`.
This test is the audit harness. It pins the current world, and **every one of the
following will need revisiting if the import ever proceeds**:

| # | Assumption pinned | Where |
|---|---|---|
| 1 | Import produces **zero** `ImportProblem`s | `the bundled timetable imports without a single problem` |
| 2 | Import yields more than 300 bookings | same test |
| 3 | `mergeAdjacent` is a **no-op** on the shipped file (every multi-hour block is already one row with `units=N`) | `the bundled file already writes a two hour block as one row` |
| 4 | Exactly **21 rooms**, in the exact order `203 … R4` | `every room in the building is accounted for` |
| 5 | Exactly **22 sections**, in a fixed sorted order | `all twenty two cohorts are present and named consistently` |
| 6 | **The week is Monday–Friday**; `SATURDAY !in weekdaysFor(...)` | `the timetable is a five day week` |
| 7 | Every subject code resolves in `subjects.csv` | `every subject code used resolves in the subject key` |
| 8 | Every faculty code resolves in `faculty.csv` | `every faculty code used resolves in the faculty key` |
| 9 | Group suffixes `-A`/`-B` resolve to the base code (`NN-B`, `DIP-A`, `PSCS-B`, `RL-A`, `EDGE-B`) | `an elective's group suffix still finds its subject name` |
| 10 | A jointly-taught workshop renders `"Prof. A.K. Tandon & Dr. Arjun Tyagi"` | `a jointly taught workshop names both teachers` |
| 11 | `termStart = LocalDate.of(2026, 7, 28)` | `private val termStart` |
| 12 | **Six** `SFL` blocks, one per 2nd-year section, each with a fixed day/hour/faculty and **no room** | `the six pages of the printed grid that carry an SFL block` + 3 tests |
| 13 | `SFL` is the **only** room-less subject in the file | `the sports hour is the only class with no room, and it books none` |
| 14 | `SFL`'s full name is `"Sports for Life / Fit India"` | `a section with a sports hour is offered it as a course to keep` |
| 15 | Tuesday 11 AM is the **only** slot with no free room all week | `tuesday at eleven is the one slot with nowhere free` |
| 16 | Free + booked hours cover `days * 9` per room per week | `every room's week adds up to the hours it is not booked` |
| 17 | The source's own **R4 Wednesday 2 PM `DBMS`/`DSD` double-booking** is preserved and visible | `the source timetable's own double booking stays visible` |
| 18 | `ECA` is a single shared 3rd-year elective in room 214, Wed 16 | `a shared third year elective folds its cohorts into one line` |
| 19 | `AVLSI` occupies room 204 as **one** class not four clashes | `the tuesday morning VLSI elective is one class not four clashes` |

### 2.4 Current timetable date

**CONFIRMED.** The shipped file declares `# Academic Session 2026-27, w.e.f. 28th July 2026`,
and `AcademicCalendar.DEFAULT_2026_27` in
`core/src/main/kotlin/com/attendo/core/model/AcademicCalendar.kt` pins:

```kotlin
termStart = LocalDate.of(2026, 7, 28)
termEnd   = LocalDate.of(2026, 11, 20)
```

`termEnd` is documented as the dispersal / prep-leave boundary, chosen over the
4 December theory-exam start so that the attendance denominator stops when classes stop.

---

## 3. OLD-vs-NEW FINDINGS

### 3.1 Extraction method, and why the day axis is trustworthy

The single hardest problem in this comparison is **assigning a printed cell to the
correct day**. The printed grid merges day cells vertically, so a day boundary and a
within-day sub-row divider are both just horizontal rules in the PDF, and
`pdftotext -bbox-layout` gives coordinates but **not** which day a row belongs to.

The method actually used, and its validation:

1. Render each page to a 72 dpi greyscale raster (`pdftoppm -gray`).
2. Detect horizontal rules from the raster. A row is a rule when **>700 of the columns
   spanning display-X 120–945 are dark**.
3. Classify: a rule that **also** darkens the **day-label column (display-X 66–113)** is
   a **DAY BOUNDARY**; a rule that does not is a **within-day sub-row divider**.
4. Day bands = the strips between consecutive day boundaries. Content rows = the strips
   between *all* consecutive rules. Each content row belongs to the day band that
   contains it.
5. **Validation:** each printed day label's y-centre must equal its band's geometric
   centre. **Measured error < 1.0 pt on all 132 bands (22 pages × 6 days); most under
   0.3 pt.** **CONFIRMED.**

This method was independently validated by rendering pages to PNG and reading them by
eye. Page 1 (1st Yr EE-A), page 7 (2nd Yr EE-A) and page 12 (2nd Yr CSE-B) were checked
**cell by cell**; every cell matched, including multi-sub-row days and cells containing
several simultaneous classes.

> **Rule that governs this whole document:** where the parser and the rendered page
> disagree, **the rendered page wins**. See §6.

### 3.2 Structural differences

| Dimension | OLD | NEW | State |
|---|---|---|---|
| Pages / sections | 22 | 22, same names | CONFIRMED |
| Time columns | 9 (`9–10 AM` … `5–6 PM`) | **10** (`8–9 AM` … `5–6 PM`) | CONFIRMED |
| Day rows populated | Mon–Fri | Mon–Sat (**Sat on 9 of 22 pages**) | CONFIRMED |
| Rows per day cell | one | **multiple sub-rows** on many days | CONFIRMED |
| Session tags | position/legend | **explicit `(P)` / `(T)` printed inline** | CONFIRMED |
| Batch tags | inline | inline, sometimes glued to section (`CSE-B2`) | CONFIRMED |
| Subject-acronym key | **present** (page 23) | **absent** | CONFIRMED |
| Faculty key | page 23 | per-page footers | CONFIRMED |
| Raw grid cells extracted | — | **1049** | CONFIRMED |
| Distinct subject codes | — | **67** | CONFIRMED |
| Rooms | 21 in current CSV | **23** | CONFIRMED |

### 3.3 The room list

**CONFIRMED.**

```
NEW rooms:  203 204 211 212 213 214 216 217 219 303 304 311 312 313 314 317 503 513
            Basement Lab G01 R1 R2 R3
```

- **`R4` is gone.** It appears nowhere in NEW.
- **`216`, `503`, `G01` are new.** `216` and `503` are ordinary room numbers in the
  existing numbering scheme (**LIKELY / CROSS-CHECKED** — they fit the building's
  floor-number convention). **`G01` is not** — see §5.
- The `Basement Lab` and `R1`–`R3` named rooms persist.

### 3.4 The `R4` double booking resolves

**CONFIRMED.** The current shipped timetable contains a genuine printed clash that
`BundledTimetableTest` deliberately preserves:

> 2nd Yr CSE-A has `DSD` (batch A2) and `DBMS` (batch A1) **both in `R4` on Wednesday at
> 2 PM**. The test asserts both remain visible as separate groups.

In NEW, the two classes are in different rooms — `DSD` → **513**, `DBMS` → **R2**.
**The clash does not exist in NEW.** Test assumption #17 in §2.3 therefore becomes
obsolete, and its replacement expectation must be written from the confirmed NEW grid
rather than by deleting the test.

### 3.5 The 8–9 AM column

**CONFIRMED.** NEW has a tenth time column at `8–9 AM`. It carries **12 cells**:

| Section | Subject | Days |
|---|---|---|
| 4th Yr EE | `MIC` | Tue, Wed |
| 4th Yr ECE-A | `MIC` | Tue, Wed |
| 4th Yr ECE-B | `MIC` | Tue, Wed |
| 3rd Yr ECE-B | `ACS` | Tue, Wed, Thu |
| 1st Yr EE-B | `FMB A (P)` | Fri |
| 1st Yr ECE-A | `FMB A (P)` | Fri |
| 1st Yr ECE-B | `FMB A (P)` | Fri |

This column **cannot be represented by the current code**. See §7.3.

### 3.6 The populated Saturday

**CONFIRMED.** NEW prints a Saturday row, and it is **not** empty:

- The **six 1st-year pages carry a full Saturday day** (not a token hour).
- Saturday content also appears on some 3rd- and 4th-year pages.

This directly contradicts two current facts: the shipped CSV has **no Saturday rows at
all**, and `BundledTimetableTest` asserts **`the timetable is a five day week`**. See §5.

### 3.7 New faculty codes — all ten resolved

**CONFIRMED** from NEW's own per-page "Faculty Acronym(s)" footer tables.

| Code | Expansion printed in NEW | Notes |
|---|---|---|
| `AK` | Dr. Amit Kumar | clean |
| `ANK` | Dr. Ankit (p3) / **Dr. Ankita** (p4) | **internal conflict** — see §5 |
| `KJL` | Dr. Kajol | clean |
| `MS` | Ms. Manisha Singh | clean |
| `RH` | Ms. Reha | clean |
| `RTS` | Ms. Ritika Sharma | clean |
| `TK` | Ms. Tanya Khaneja | clean |
| `TNK` | Ms. Tanishka | clean |
| `VKG` | Dr. Vikram Kumar Gedi | clean |

### 3.8 Footer inconsistencies inside NEW

**CONFIRMED** — these are printed contradictions within NEW itself, not parser errors.

| Code | Conflict | Current `faculty.csv` |
|---|---|---|
| `ANK` | `Dr. Ankit` (p3) vs `Dr. Ankita` (p4) | absent |
| `VA` | `Dr. Vijay Azad` (p2, p4) vs `Dr. Vandana Kumari` (p3) | `VA = Dr. Vijay Azad`, `VK = Dr. Vandana Kumari` |
| `ART` | `Dr. Anurada Tomar` (p1, missing *h*) vs `Dr. Anuradha Tomar` (p9, p21, p22) | `Dr. Anuradha Tomar` — the correct spelling |
| `AKS`, `GB`, `RHS`, `RR` | minor spelling variants across pages | — |

The `VA` conflict is the consequential one: the page-3 **footer** disagrees with the
page-3 **grid usage** (`HBT ECE-A [VA]`), and agrees with nothing else. See §5.

---

## 4. CONFIRMED CHANGES

Everything in this section is **CONFIRMED** from the PDFs by the validated method in
§3.1, or from the repository's own files. This is the set of things that *would* change
if the import proceeded.

### 4.1 Confirmed structural changes

1. **A tenth time slot, 8–9 AM, exists and is used** (12 cells, §3.5).
2. **Saturday is populated** — a full day on the six 1st-year pages, plus content on some
   3rd/4th-year pages (§3.6).
3. **`R4` is removed from the building's room set.**
4. **`216`, `503` are added** to the room set.
5. **`G01` appears** as a room code with no counterpart in OLD and no obvious place in the
   building's numbering scheme (**UNCONFIRMED** as to what it is — §5).
6. **Days now carry multiple sub-rows**, so one printed day cell can contain several
   simultaneous or sequential classes.
7. **`(P)` / `(T)` are printed inline**, making practical/tutorial kind explicit rather
   than positional.
8. **NEW has no subject-acronym page.** This is the migration's blocking defect.

### 4.2 Confirmed content changes

9. **The `R4` Wednesday-2-PM `DBMS`/`DSD` clash is resolved** — `DSD` → 513, `DBMS` → R2 (§3.4).
10. **`SFL` is replaced by `FIT INDIA`** on the six 2nd-year pages. **LIKELY / CROSS-CHECKED**
    that these are the same class: OLD's master sheet entry is
    `SFL,Sports for Life / Fit India`, and OLD's `SFL` sat on exactly the six sections
    where NEW now prints `FIT INDIA`. **The code itself changed from `SFL` to `FIT INDIA`**,
    which is a vocabulary change the current `subjects.csv` does not carry.
11. **`ECA` is joined by `ECA-A` / `ECA-B` grouping.** **LIKELY / CROSS-CHECKED** that these
    resolve to the existing `ECA,Energy Conservation and Audit` entry via the established
    group-suffix convention already pinned by test assumption #9.
12. **Ten new faculty codes** enter the file (§3.7).
13. **Seven new subject codes** enter the file (§5.1). **These are the blocker.**

### 4.3 Explicitly *not* claimed

The following are **not** asserted anywhere in this document, and must not be inferred
from it:

- That any specific class has "moved" rather than being "removed and added". The two
  printed timetables are different documents; a cell that appears in both is not proof of
  continuity, and a cell that appears in only one is not proof of intent.
- That the 8–9 AM column, the Saturday rows, or the multi-sub-row days are *new
  requirements* rather than *corrections of OLD's simpler print*. Nothing in either PDF
  states this.
- That `FIT INDIA`, `FMB`, `FL`, `DE`, `EF-1`, `DADV`, `MLDL` mean anything in particular.
  See §5.

---

## 5. UNRESOLVED / HUMAN CONFIRMATION REQUIRED

> **Nothing in this section may be resolved by inference from the PDFs.** Every item
> below was checked against all five permissible sources — NEW's text, NEW's rendered
> pages, OLD's text, OLD's master sheet, and Attendo's existing data/conventions — and
> **none of them answers it.**

### 5.1 The blocking set: seven subject codes with no expansion anywhere

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

These codes appear in NEW's grid. They have **no expansion in NEW** (no master sheet
exists), **no occurrence in OLD**, and **no entry in OLD's master sheet**. They are also
absent from `subjects.csv`.

| Code | Printed on | Cells | In OLD PDF? | In OLD master sheet? | Resolvable by Attendo convention? |
|---|---|---|---|---|---|
| `FMB` (as `FMB A` / `FMB B`) | 1st Yr, all 6 sections | 30 | No | No | No |
| `EF-1` | 1st Yr, all 6 sections | 30 | No | No | No |
| `DE` | 1st Yr, all 6 sections | 24 | No | No | **No — and `DE-I` also exists separately** |
| `FL` (as `FL A` / `FL B`) | 1st Yr, all 6 sections | 18 | No | No | No |
| `DADV` | 4th Yr EE, ECE-A, ECE-B | 15 | No | No | No |
| `MLDL` | 4th Yr CSE-A, CSE-B | 14 | No | No | No |
| `FIT INDIA` | 2nd Yr, all 6 sections | 12 | No | Partial (see below) | Partial |

**Total: 143 of 1049 cells (13.6%) touch an unresolved code.**

Sections affected: **all six 1st-year** (`FMB`, `EF-1`, `DE`, `FL`), **all six 2nd-year**
(`FIT INDIA`), **both 4th-year CSE** (`MLDL`), **all three 4th-year** (`DADV`). The 1st
year is unusable without a ruling.

**The `DE` / `DE-I` distinction is a specific hazard.** OLD's master sheet contains
`DE-I,Digital Electronics-I`, and `DE-1` is live in the current `subjects.csv` and in the
shipped CSV (2nd Yr ECE-A/B). NEW prints **both** `DE` (1st year, 24 cells) **and** `DE-I`.
Whether `DE` is a *different* subject, an *abbreviation of* `DE-I`, or a *typo for* it is
**not determinable from any source**. Auto-resolving `DE` → `DE-I` would be a guess with
24 cells behind it.

**`FIT INDIA`'s partial trail.** OLD's master sheet does establish
`SFL,Sports for Life / Fit India`. But NEW's code is `FIT INDIA`, not `SFL`, and the
question of whether to (a) keep the printed code and name it from the master sheet, or
(b) normalise it back to `SFL`, is a **decision, not a lookup**.

**`FMB A` / `FMB B` and `FL A` / `FL B`.** These *do* follow Attendo's established
group-suffix convention (**LIKELY / CROSS-CHECKED** that they should be written
hyphenated as `FMB-A` / `FMB-B` / `FL-A` / `FL-B` so `Glossary`'s strip-last-hyphen rule
finds the base). But **there is no base code for them to land on**, so the convention
only fixes the *encoding*, never the *name*.

> Once the expansions are supplied, the *encoding* for these is mechanical. The *names*
> are the blocker.

### 5.2 `G01`

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

`G01` appears in NEW as a room and has no counterpart in OLD and no place in the existing
numeric room scheme (`203`–`513`, `Basement Lab`, `R1`–`R3`). Whether it is a new
classroom, a seminar hall, a lab, or a room outside the department is unknown.

Its presence matters beyond naming: `RoomAvailability.rooms()` derives the room list from
the bookings and sorts numbered rooms first, then everything else alphabetically
(`ROOM_ORDER`). `G01` would therefore sort among the *named* rooms, next to
`Basement Lab`, not among the numeric ones — which may or may not be what a student
expects. It also becomes a row on the Rooms screens.

### 5.3 `R4` disappearing

**CONFIRMED** that `R4` is absent from NEW. **UNCONFIRMED — REQUIRES HUMAN/STUDENT
CONFIRMATION** as to *why*: decommissioned, renumbered, temporarily unavailable, or a
conversion artefact. This matters because test assumption #4 pins the exact 21-room list,
and because a room vanishing silently is exactly the kind of change a student would want
explained rather than absorbed.

### 5.4 `FL A` / `FL B` interpretation

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

- What does `FL` stand for? (No expansion exists in either PDF or the master sheet.)
- Does the `A`/`B` suffix denote a **batch split of one class** (the convention used
  throughout the rest of the timetable) or **two distinct electives a student chooses
  between**? These have different consequences for course seeding.

### 5.5 `FMB B`

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

- What does `FMB` stand for?
- `FMB A` and `FMB B` appear across the 1st-year pages. Is `B` a batch, a division, or a
  separate elective stream?
- The 8–9 AM Friday cells are printed `FMB A (P)` — a *practical* for group A only.
  **Does group B have a corresponding practical, and is it simply not on the printed
  grid?** This is exactly the kind of gap that must not be filled by inference.

### 5.6 `DE` / `DE-1` distinction

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

See §5.1. Specifically: is the 1st-year `DE` the same course as the 2nd-year `DE-I`
(`Digital Electronics-I`), a prerequisite, or an unrelated course that happens to share
two letters?

### 5.7 New subject codes — full list

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.** The complete set requiring a
ruling: **`FMB`, `EF-1`, `DE`, `FL`, `DADV`, `MLDL`, `FIT INDIA`**.

For completeness, the codes that *do* resolve and need no ruling: `ECA-A` / `ECA-B`
(→ `ECA`, via the group-suffix convention) and every code already present in
`subjects.csv`.

### 5.8 New faculty codes

**CONFIRMED** for all ten (§3.7) — with **one exception requiring confirmation**:
**`ANK`** (§3.8). And **one confirmation of a resolution**: whether `VA` should remain
`Dr. Vijay Azad` given NEW's page-3 footer says otherwise.

### 5.9 Batch / group semantics

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

NEW prints batches in several forms, some of which are genuinely ambiguous:

- **Glued to the section**: e.g. `CSE-B2`, `CSE-B1` printed as a single token.
- **Standalone group letters**: `FMB A`, `FL B`, `ECA-A`, `ECA-B`.
- **Pooled batches across sections**: 2nd Yr ECE-A's statistics lab is batch `B3`, pooled
  with 2nd Yr EE-A — a batch label that is *not* a choice the section makes. The existing
  `SectionSeeder.batchesFor` exists precisely to handle this pattern.
- **`(P)` / `(T)` inline tags**: confirmed to mean practical / tutorial, but the
  interaction between an inline `(P)` and a printed batch on the same cell needs
  confirming per case rather than by rule.

Test assumptions #12–#14 and #19 in §2.3 all encode batch/group behaviour and will need
re-derivation, not mechanical editing.

### 5.10 Possible printed double-bookings

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

The OLD grid contained one genuine clash (`R4` Wed 14 `DBMS`/`DSD`), which resolved in
NEW (§3.4). **NEW was not exhaustively swept for equivalent clashes.** A complete
double-booking sweep of NEW — same room, same day, same hour, different subject — has
**not** been run and is a required pre-import step. Any clash found must be reported, not
filtered: `BundledTimetableTest`'s existing stance is that a source clash is *shown*, not
silently deduplicated, and the same stance must hold for NEW.

Related, and also unresolved: NEW's multi-sub-row days mean a single day cell can contain
several classes, some simultaneous. **Whether a printed cell containing two classes is a
clash, an elective choice, or a batch split cannot be decided by rule** and must be
confirmed per instance.

### 5.11 Other ambiguous cells discovered during the audit

**UNCONFIRMED — REQUIRES HUMAN/STUDENT CONFIRMATION.**

- **The `8–9 AM` column's status.** §7.3 shows the code cannot currently express it. But
  separately: is an 8 AM start *real teaching*, or an artefact of the spreadsheet the
  timetable was produced from (title: `v2_Timetable_2026-27_Odd_v2.xlsx`)? **Not
  determinable from the PDF.**
- **Saturday's status.** §3.6. Is Saturday a *weekly* teaching day (which would change
  `AcademicCalendar`'s model) or the *occasional working Saturday* the current model
  already describes? The PDF prints a Saturday column; it does not say how often it runs.
- **The `MIC` 8 AM classes** on three 4th-year pages (Tue + Wed) are the only 8 AM rows
  outside 1st year. Whether these are genuinely 8 AM or a shifted print is unconfirmed.

---

## 6. PARSER ARTIFACTS

> **Parser output is NOT authoritative. Where parser output conflicts with visual
> inspection of the rendered page, VISUAL INSPECTION WINS.**

This section exists so that a future reader does not mistake a known extraction defect for
a timetable finding. Every item below was encountered and diagnosed during this audit.

### 6.1 Confirmed parser defects and their fixes

| # | Defect | Root cause | Resolution |
|---|---|---|---|
| 1 | **Day assignment was wrong** — cells attributed to the wrong day. | `pdftotext -bbox-layout` gives coordinates but no day membership. Bounding boxes alone cannot distinguish a day boundary from a within-day sub-row divider. | Replaced with the raster rule-classification method in §3.1. **Bounding boxes alone must never be used for day assignment.** |
| 2 | **Content-stream rules did not match the rendered grid.** Extracted rules at y ≈ 160.56 / 186.84 / 213.12 / 239.4 / 265.68 / 291.96 / 318.12 did not exist in the render; the render's real rules at y ≈ 174 / 226 / 279 / 331 / 358 / 371 were absent from the extracted list. | Selected content-stream objects by `b'Day/Time' in stream` and **sorted by object number**. Object-number order ≠ page order — object 35 contained `NN-B B2 (P) [US]`, a 3rd-year page being treated as page 12. | Abandoned content-stream rule extraction entirely. Rules are derived from the correctly-paged raster. |
| 3 | **Faculty-footer tables leaked into cell data on pages 18–20.** | Those pages' grids extend lower, so the footer table's rows fell inside the assumed content region. | Skip any content row whose top is below the last day-band bottom. |
| 4 | **`FIT INDIA` parsed as two tokens.** | Two space-separated words in one cell; the tokeniser split on whitespace. | Token pre-pass joining the known two-word form. |
| 5 | **`CSE-B2` / `CSE-B1` glued to neighbouring text.** | Batch tag printed flush against the section token. | Token pre-pass in `split_tokens`. |
| 6 | **`IndexError: no such group`** in the token splitter. | `re.fullmatch(r'(CSE\|ECE\|EE)-([AB])([1-4])', x)` has 3 groups; the code referenced `m.group(4)`. | Fixed to `m.group(2)+m.group(3)`. |
| 7 | **`json.dump` → `TypeError: Object of type bool is not JSON serializable`** on the tuple `(yc, is_boundary)`. | Tuples not JSON-serialisable. | Switched the intermediate store to `pickle`. |
| 8 | **`pdftoppm -r 72 -gray -ppm` exited 99** with a usage dump. | `-ppm` is not a valid flag; output format is chosen by `-png` / `-jpeg` / `-tiff` / `-mono` / `-gray`. | Used `-gray` alone, producing PGM (P5). |
| 9 | **No PIL available** for reading rendered PNGs. | Environment limitation. | Used PGM P5 with a manual numpy header parse. |

### 6.2 Standing rules derived from these artefacts

- **Bounding boxes alone never determine day membership.** §3.1's day-label-centre check
  is the acceptance test; it passed at < 1.0 pt on all 132 bands.
- **A parser-derived difference is not a timetable difference.** Two cells that extract
  differently are not thereby changed.
- **"Removed + added" is not "changed".** These are two different printed documents.
- **Do not silently discard.** Any extraction anomaly is recorded here rather than
  dropped, so a later reader can tell a real cell from a known defect.
- **Nothing parsed from NEW has been written to any repository file.** All extraction work
  is confined to `/tmp/ttwork` and `/tmp/ttaudit`.

---

## 7. IMPORT BLOCKERS

### 7.1 Blocker 1 — seven subject codes with no authoritative expansion (HARD)

**This is the blocker.** §5.1.

143 of 1049 cells (13.6%) carry a code that cannot be named from any permissible source.
Writing placeholder names would:

- ship wrong data to every 1st-year, 2nd-year, and 4th-year student;
- **fail `BundledTimetableTest`'s `every subject code used resolves in the subject key`**
  — the test exists precisely to catch this;
- violate the standing rule against inventing a subject.

Dropping the affected rows instead would fail the "missing class" half of the release
gate just as hard.

**Both branches are worse than stopping.** This is why the migration is paused.

### 7.2 Blocker 2 — batch/group semantics undetermined (HARD)

§5.9. `SectionSeeder`'s whole purpose is to convert printed batch labels into batch
*choices* a student makes, and to preserve the batches the student's answer did not cover.
Getting the semantics wrong does not produce a visible error — it silently deletes labs
from a student's course list. This is exactly the failure mode `BundledTimetableTest`'s
`the most heavily double numbered section loses no lab to its batch choice` guards against.

### 7.3 Blocker 3 — the 8–9 AM column is not representable (HARD, code-level)

**CONFIRMED** by reading the code. Three independent obstacles, each sufficient on its own:

1. **`TimeGrid.FIRST_START_HOUR = 9`.** `core/src/main/kotlin/com/attendo/core/model/TimeGrid.kt`
   defines the grid as 9 AM–6 PM. `RoomBooking`'s `init` requires
   `TimeGrid.fits(startHour, units)`, and `isValidStartHour(hour)` is
   `hour in 9..17`. **An 8 AM booking cannot be constructed at all.**

2. **`TimetableCsv.parseHour` maps a bare `8` to the afternoon.** The current rule reads:

   ```kotlin
   // No meridiem: 1-8 can only be the afternoon on a 9-to-6 grid.
   hour in 1..8 -> hour + 12
   ```

   So a CSV field of `8` becomes `20`, which `isValidStartHour` rejects → the row becomes
   an `ImportProblem`.

3. **`8am` does not rescue it.** With `8am`, the meridiem branch returns `8`, which
   `isValidStartHour(8)` still rejects → still an `ImportProblem`.

So the 8 AM column cannot enter the CSV in any current encoding.

Downstream ripple if `FIRST_START_HOUR` ever changes to 8:

- `RoomAvailability.freeRunsFrom` iterates `FIRST_START_HOUR..LAST_START_HOUR`;
- `RoomAvailability.statusOf` walks to `LAST_END_HOUR`;
- `RoomAvailability.nextSlot` rolls to `FIRST_START_HOUR`;
- `RoomStatus.summary`'s `"Free all day"` branch compares `queriedFrom == FIRST_START_HOUR`;
- `SlotQuery`'s `require` message;
- test assumption #16 (`days * 9` → `days * 10`);
- the Rooms-screen "Free all day" copy and the Dashboard/DayReview grid;
- README's stated valid range.

**Note this is a decision, not a lookup** (§5.11): the code change is mechanical, but
*whether* 8 AM is real teaching must be confirmed first.

### 7.4 Blocker 4 — the Saturday model is undecided (HARD, model-level)

**CONFIRMED** that NEW populates Saturday (§3.6). **UNCONFIRMED** whether it is weekly or
occasional (§5.11).

`AcademicCalendar.isWorkingDay` currently returns:

```kotlin
date.dayOfWeek == DayOfWeek.SATURDAY -> date in workingSaturdays
```

i.e. **Saturday is a holiday unless a specific date is listed**. The doc comment states
the intent: *"a Saturday pattern must not quietly generate a class on every Saturday of
the term."*

If Saturday becomes a weekly teaching day, this model is wrong for those sections. If
Saturday is the occasional working Saturday the model already describes, the model is
right and the Saturday rows merely describe what a working Saturday looks like.

**These two readings produce different attendance denominators**, so the choice cannot be
deferred past the import.

### 7.5 Blocker 5 — test expectations must be rebuilt, not edited (MEDIUM)

§2.3 lists 19 pinned assumptions. Several are not mechanical updates:

- #4 (room list) — depends on Blocker §5.2 (`G01`) and §5.3 (`R4`).
- #6 (five-day week) — depends on Blocker §7.4.
- #12–#14, #19 (`SFL`) — depend on the `FIT INDIA` ruling (§5.1) and on batch semantics.
- #16 (`days * 9`) — depends on Blocker §7.3.
- #17 (`R4` clash) — the expectation changes from "one clash" to "no clash", but the test's
  *purpose* (a source clash is shown, not filtered) must be preserved for whatever NEW
  actually contains (§5.10).

### 7.6 Summary of the gate

The migration **cannot** produce a correct `timetable.csv` today. Blockers 1 and 2 are
data questions that only students can answer; blockers 3 and 4 are decisions that depend
on the answers to §5.11. **No amount of further PDF parsing resolves any of them** — every
permissible source has already been exhausted.

---

## 8. STUDENT CONFIRMATION QUEUE

> **These questions are recorded, not answered.** Each is to be filled in as students or
> staff provide information. The audit must be updated with the answers *before* any OLD →
> NEW reconstruction is attempted.

### Q1 — `FMB`: expansion, and the A/B split
- **Question:** What does `FMB` stand for on the 1st-year timetable, and is the `A`/`B` suffix a batch split of one course or two separate streams?
- **PDF evidence:** `FMB A` and `FMB B` printed across all six 1st-year pages; 30 cells total. `FMB A (P)` at 8–9 AM on Friday for 1st Yr EE-B, ECE-A, ECE-B. No expansion in either PDF; OLD's master sheet has no entry.
- **What needs confirmation:** The full subject name, and the meaning of the group letters.
- **Possible interpretations:** (a) one course split into teaching batches A and B, following the `NN-A`/`NN-B` convention; (b) two distinct elective streams; (c) a course plus an associated practical group.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q2 — `EF-1`: expansion
- **Question:** What does `EF-1` stand for?
- **PDF evidence:** `EF-1` printed across all six 1st-year pages; 30 cells. Absent from OLD entirely and from OLD's master sheet.
- **What needs confirmation:** The full subject name. Note the hyphenated-digit form matches existing convention (`DE-1`, `EM-I`, `EVS-2`), so the *encoding* is settled — only the name is missing.
- **Possible interpretations:** (a) a course literally named `EF-1`; (b) a typo for an existing master-sheet code.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q3 — `DE` vs `DE-I`: are they the same course?
- **Question:** Is the 1st-year code `DE` the same course as `DE-I` (`Digital Electronics-I`, which is live for 2nd Yr ECE-A/B)?
- **PDF evidence:** NEW prints **both** `DE` (1st year, 24 cells) **and** `DE-I`. OLD's master sheet contains `DE-I,Digital Electronics-I` and no `DE`. The current `subjects.csv` and shipped CSV both use `DE-1` for 2nd Yr ECE-A/B.
- **What needs confirmation:** Whether `DE` is a distinct course, an abbreviation of `DE-I`, or a mis-print. **This is a 24-cell decision and must not be inferred** — if `DE` were folded into `DE-I` and they are in fact different courses, every 1st-year student gets a wrong course name.
- **Possible interpretations:** (a) `DE` = `DFE-I` for 1st years, same course as the 2nd-year `DE-1`; (b) a genuinely different course sharing two letters; (c) a typo.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q4 — `FL`: expansion, and the A/B split
- **Question:** What does `FL` stand for, and is `A`/`B` a batch split or two electives?
- **PDF evidence:** `FL A` and `FL B` across all six 1st-year pages; 18 cells. No expansion anywhere.
- **What needs confirmation:** The full subject name and the semantics of the group letters.
- **Possible interpretations:** (a) one course split into batches; (b) two elective streams; (c) a foreign-language or lab course.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q5 — `DADV`: expansion
- **Question:** What does `DADV` stand for?
- **PDF evidence:** `DADV` on 4th Yr EE, 4th Yr ECE-A and 4th Yr ECE-B; 15 cells. Absent from OLD and from OLD's master sheet.
- **What needs confirmation:** The full subject name.
- **Possible interpretations:** (a) a new 4th-year elective; (b) an abbreviation of an existing master-sheet code (no master-sheet code matches cleanly).
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q6 — `MLDL`: expansion
- **Question:** What does `MLDL` stand for?
- **PDF evidence:** `MLDL` on 4th Yr CSE-A and 4th Yr CSE-B; 14 cells. Absent from OLD and from OLD's master sheet.
- **What needs confirmation:** The full subject name. If it is a machine-learning elective, confirm the exact printed title students see.
- **Possible interpretations:** (a) a new 4th-year CSE elective; (b) an abbreviation of an existing code.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q7 — `FIT INDIA` vs `SFL`: same class, and which code to keep?
- **Question:** Are NEW's `FIT INDIA` rows the same class as OLD's `SFL` rows, and if so should the CSV keep the printed code `FIT INDIA` or normalise to `SFL`?
- **PDF evidence:** OLD's master sheet has `SFL,Sports for Life / Fit India`. OLD's `SFL` sits on exactly the six 2nd-year sections where NEW prints `FIT INDIA`; 12 cells in NEW. The current `subjects.csv` carries `SFL,Sports for Life / Fit India`, and three tests pin `SFL` behaviour (room-less, 2 units, one pattern).
- **What needs confirmation:** (1) Is it the same class? (2) Which code ships? (3) Is it still room-less in NEW?
- **Possible interpretations:** (a) same class, keep `FIT INDIA` as printed and add a `subjects.csv` entry; (b) same class, normalise back to `SFL` so no test changes; (c) a different course.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q8 — `G01`: what is this room?
- **Question:** What is `G01`, where is it, and what sort of space is it?
- **PDF evidence:** `G01` appears in NEW's room brackets and has no counterpart in OLD. It does not fit the numeric scheme (`203`–`513`) or the named scheme (`Basement Lab`, `R1`–`R3`).
- **What needs confirmation:** Its existence, its identity, and whether it should be listed among the numbered rooms or the named ones on the Rooms screen (`RoomAvailability.ROOM_ORDER` sorts numbered rooms first, then everything else alphabetically — so `G01` would land next to `Basement Lab`).
- **Possible interpretations:** (a) a new ground-floor classroom; (b) a seminar hall or lab; (c) a shorthand for a room that already exists under another name.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q9 — `R4`: why has it gone, and is the clash really resolved?
- **Question:** Why does `R4` no longer appear, and are `DSD` → 513 and `DBMS` → R2 in NEW genuinely the same classes that previously clashed in `R4` on Wednesday at 2 PM?
- **PDF evidence:** `R4` is absent from NEW. The current shipped CSV and `BundledTimetableTest` deliberately preserve an `R4` Wed-14 `DBMS`/`DSD` double-booking. NEW places `DSD` in 513 and `DBMS` in R2.
- **What needs confirmation:** Whether `R4` is decommissioned, renumbered, or temporarily out of service — and hence whether the Rooms screen should still offer it.
- **Possible interpretations:** (a) `R4` decommissioned and its classes rehoused; (b) `R4` renamed to one of the new codes; (c) temporary.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q10 — The 8–9 AM column: is it real teaching?
- **Question:** Are the 8–9 AM classes real, or an artefact of the spreadsheet NEW was produced from?
- **PDF evidence:** NEW's document title is `v2_Timetable_2026-27_Odd_v2.xlsx`. 12 cells sit at 8–9 AM: `MIC` on 4th Yr EE/ECE-A/ECE-B (Tue+Wed), `ACS` on 3rd Yr ECE-B (Tue/Wed/Thu), `FMB A (P)` on 1st Yr EE-B/ECE-A/ECE-B (Fri). The current code cannot represent this at all (§7.3).
- **What needs confirmation:** Whether the college genuinely runs 8–9 AM classes from 17 September 2026.
- **Possible interpretations:** (a) real — the teaching day now starts at 8 AM; (b) a spreadsheet artefact — the column should be ignored; (c) real for some sections only.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q11 — Saturday: weekly or occasional?
- **Question:** From 17 September 2026, is Saturday a weekly teaching day, or the occasional working Saturday the app already models?
- **PDF evidence:** NEW has a populated Saturday row on 9 of 22 pages — a **full** day on all six 1st-year pages, plus content on some 3rd/4th-year pages. OLD's Saturday row was empty on every page. `AcademicCalendar` currently treats Saturday as a holiday unless the date is in `workingSaturdays`, and its doc comment explains why that inversion exists.
- **What needs confirmation:** The frequency and which sections it applies to.
- **Possible interpretations:** (a) weekly for some sections → `AcademicCalendar`'s model needs rethinking for those sections; (b) occasional working Saturdays → the model is already correct and the rows describe a working Saturday; (c) per-section variation.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q12 — `ANK`: Ankit or Ankita?
- **Question:** Which is correct?
- **PDF evidence:** NEW page 3's footer says `Dr. Ankit`; page 4's says `Dr. Ankita`. Absent from the current `faculty.csv`.
- **What needs confirmation:** The correct name (and title).
- **Possible interpretations:** (a) Ankit; (b) Ankita; (c) two different people sharing the code — which would itself be a defect to report.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q13 — `VA`: Vijay Azad or Vandana Kumari?
- **Question:** Which faculty member does `VA` denote?
- **PDF evidence:** NEW page 2 and page 4 footers say `Dr. Vijay Azad`; page 3's says `Dr. Vandana Kumari`. The current `faculty.csv` already carries `VA = Dr. Vijay Azad` **and** a separate `VK = Dr. Vandana Kumari`. Page 3's *grid usage* (`HBT ECE-A [VA]`) is consistent with the Vijay Azad reading.
- **What needs confirmation:** Whether page 3's footer is a typo, i.e. whether `VA` should remain Vijay Azad.
- **Possible interpretations:** (a) typo — keep `VA = Dr. Vijay Azad`; (b) page 3 genuinely teaches a different person under the same code.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q14 — Batch / group semantics across NEW
- **Question:** For each printed batch form, is it a batch of one class, a division, or a separate elective?
- **PDF evidence:** NEW prints batch tags in several forms — glued to the section (`CSE-B2`, `CSE-B1`), standalone group letters (`FMB A`, `FL B`, `ECA-A`, `ECA-B`), pooled cross-section batches (2nd Yr ECE-A's statistics lab is `B3`, pooled with 2nd Yr EE-A), and inline `(P)`/`(T)` tags on cells that may also carry a batch.
- **What needs confirmation:** The semantics of each form, per section where they differ.
- **Possible interpretations:** (a) all group letters are batch splits of one course; (b) some denote electives a student chooses between; (c) mixed, varying by section.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q15 — Double-bookings in NEW
- **Question:** Does NEW contain any room/hour/class clash, and where?
- **PDF evidence:** A **complete clash sweep of NEW has not been run** (§5.10). The one clash present in OLD (`R4` Wed 14 `DBMS`/`DSD`) is resolved in NEW.
- **What needs confirmation:** The result of a full sweep, and — for any clash found — whether it is a genuine printed clash (to be shown, as OLD's was) or an extraction artefact.
- **Possible interpretations:** (a) no clashes in NEW; (b) one or more printed clashes to be preserved and shown; (c) an apparent clash that is really two simultaneous batch rows of one class.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

### Q16 — Multi-class day cells
- **Question:** When NEW's single day cell contains several classes, are they simultaneous (a clash or an elective choice) or sequential (a sub-row split)?
- **PDF evidence:** NEW's days carry multiple sub-rows (§3.2). The raster method (§3.1) separates sub-rows geometrically, but the *meaning* of two classes printed side by side within one sub-row is not printed anywhere.
- **What needs confirmation:** Per instance.
- **Possible interpretations:** (a) two classes at the same hour → a clash or an elective pair; (b) batch groups meeting in different rooms; (c) a genuine extraction artefact.
- **Status:** UNCONFIRMED
- **Evidence/source to record later:**

---

## 9. FUTURE IMPORT REQUIREMENTS

> These are requirements for the eventual import, **not** a plan to do it now. They are
> recorded so that whoever resumes this work does not have to rediscover them.

### 9.1 Format

- `timetable.csv` **must remain in Attendo's existing format**: the column order
  `day,start,units,room,subject,section,faculty,kind,batch`, with the same
  comment/header preamble and the same `# To update for a new semester: replace this
  file, keeping the column order.` contract. **CONFIRMED** as the current contract in the
  file's own header.

### 9.2 Multi-hour blocks

- Merged multi-hour classes **must follow the existing `mergeAdjacent` expectations**: a
  2-hour block is **one row with `units=2`**, never two hourly rows.
  `BundledTimetableTest` pins that `mergeAdjacent` finds **nothing to do** on the shipped
  file, and `TimetableRepository` runs `mergeAdjacent` on every load as a safety net. The
  new file must satisfy the same invariant, or the Rooms screens silently start reporting
  "busy until 11" as two separate one-hour bookings.

### 9.3 Vocabulary

- **Every subject code used must resolve in `subjects.csv`.** Enforced by
  `every subject code used resolves in the subject key`.
- **Every faculty code used must resolve in `faculty.csv`.** Enforced by
  `every faculty code used resolves in the faculty key`. Note `Glossary` strips the last
  hyphen segment for lookup, so batch suffixes must be written **hyphenated**
  (`FMB-A`, not `FMB A`) or they will not resolve.
- **Every room must be one the building actually has**, and no new room may be introduced
  without confirmation (§5.2, §5.3).
- **Batch/group vocabulary must match what the seeder expects** (§5.9, §7.2).

### 9.4 Saturday

- Saturday must be handled **if and only if confirmed** (§5.11, §7.4). Adding Saturday
  rows changes `RoomAvailability.weekdaysFor` from `MONDAY_TO_FRIDAY` to
  `MONDAY_TO_SATURDAY`, which changes every room's reported free hours and the Rooms
  screen's week layout. If Saturday is confirmed weekly, `AcademicCalendar` needs a
  corresponding change; if it is the occasional working Saturday, the model stands and
  only the asset changes.

### 9.5 The 8–9 AM column

- **8–9 AM artifacts must not enter the CSV** unless 8 AM is confirmed as real teaching
  *and* `TimeGrid` is extended to support it. In the current code an 8 AM row is either
  silently rewritten to 20:00 (`8`) or rejected as an `ImportProblem` (`8am`) — see §7.3.
  A file containing 8 AM rows would **fail
  `the bundled timetable imports without a single problem`**.

### 9.6 Room-less classes

- **Room-less classes must remain room-less.** `SFL` is currently written with an empty
  `room` column, and three tests pin that: it is still a class the section attends (so it
  must reach `SectionSeeder`), but it books no room (so the Rooms screens must skip it).
  Naming a room a class does not use would book a room that is actually free. If
  `FIT INDIA` / `SFL` changes, the same treatment must be confirmed for its NEW form.

### 9.7 Parallel batch rows

- **Parallel batch rows must not be deduplicated.** When two batches of one section meet
  in different rooms at the same hour, both rows belong in the file. When one class is
  attended by several cohorts, it appears **once per cohort** — and the Rooms screens fold
  those back together via `RoomAvailability.groupBookings`. Deduplicating either kind
  silently deletes a class.

### 9.8 Attendance data

- **Existing attendance data must not be implicitly reseeded or destroyed.** §10 covers
  why this is currently safe and what could break it.

---

## 10. CODE / ATTENDANCE SAFETY FINDINGS

> **CONFIRMED** findings about how the timetable relates to attendance. These are the
> reasons a timetable-asset change is *safe in principle* — and the reasons it must still
> be done deliberately.

### 10.1 `timetable.csv` is a read-only bundled asset

**CONFIRMED.** It lives at `app/src/main/assets/timetable.csv` and is read through
`AssetManager.open(name).bufferedReader().use { it.readText() }` in
`app/src/main/kotlin/com/attendo/data/TimetableRepository.kt`. **Nothing in the app writes
it.** It is a compile-time input, replaced by rebuilding the APK.

`AppReset`'s own doc comment states the consequence directly:

> *"The bundled timetable ships as a read-only asset and needs no clearing."*

### 10.2 Its consumers

**CONFIRMED** by tracing the code.

**The only loader** is `TimetableRepository` (`app/src/main/kotlin/com/attendo/data/TimetableRepository.kt`).
It parses all three assets into one immutable value:

```kotlin
data class Timetable(
    val bookings: List<RoomBooking>,
    val subjects: Glossary,
    val faculty: Glossary,
    val problems: List<ImportProblem> = emptyList(),
)
```

It also runs `TimetableCsv.mergeAdjacent(imported.bookings)` on every load, and memoises
the result in a `Deferred` under a `Mutex` for the life of the process — *"the asset never
changes at runtime, so the memo is good for the life of the process."*

**Everything that reads it:**

| Consumer | What it does |
|---|---|
| `RoomsViewModel` | Room list / room-first browse |
| `RoomDetailViewModel` | One room's week |
| `SeedViewModel` | Feeds `SectionSeeder.plan(...)` to propose courses |
| `SettingsViewModel` | Reports import problems / timetable state |
| `AttendoApplication` | Provides the repository |

Note that **`problems` is carried, not thrown** — a mistyped line costs that line and
surfaces on the settings screen rather than taking the Rooms tab down. That is a
deliberate design choice, and it means **a bad import degrades quietly rather than
loudly**. `BundledTimetableTest` is the compensating control.

### 10.3 `AttendanceRepository` does not depend on `TimetableRepository`

**CONFIRMED** by reading `AttendanceRepository`'s import block. It imports
`SeedProposal`, `DayPlan`, `SessionGenerator`, `SessionOps`, `AcademicCalendar`,
`ClassSession`, `Course`, `SessionPattern`, `SessionKind`, `SessionStatus`, `UnitMask`,
and Room's `AttendoDatabase` — and **nothing from the timetable**. No `TimetableRepository`,
no `TimetableCsv`, no `Glossary`, no `RoomBooking`.

The dependency runs **one way**:

```
timetable.csv  →  TimetableRepository  →  SeedProposal  →  (student confirms)  →  Course + SessionPattern  →  ClassSession
```

Once sessions exist, they are ordinary rows in the database. **Changing the asset cannot
retroactively alter them.**

### 10.4 Manual reseeding implications

**CONFIRMED.** Seeding is an **explicit, student-initiated action** via
`SeedScreen` / `SeedViewModel`, which calls `SectionSeeder.plan(...)`. It is not automatic
and not triggered by asset replacement.

Consequences:

- Replacing `timetable.csv` **does not touch existing courses or sessions.**
- A student who re-runs seeding **after** the asset changes will be offered the NEW
  proposals against their EXISTING data. `SectionSeeder` already handles this class of
  problem — `needsBatchCheck`, the batch-vote logic, and the "keeps the lab it is pooled
  into, whichever batch it picks" behaviour all exist to stop a re-seed quietly deleting
  courses a student didn't mean to touch.
- **But** that machinery was built for a *re-import of the same semester*. A genuinely
  different grid is a different proposition, and §5.9/§7.2 (batch semantics) is the
  specific place where a wrong answer silently deletes labs.

### 10.5 Rollover implications

**CONFIRMED** — and this is the most reassuring finding.

`RolloverViewModel` holds the bundled semester as:

```kotlin
private val bundled: Semester = Semester.of(AcademicCalendar.DEFAULT_2026_27)
```

and `RolloverDecision`'s own documentation states:

> *"Comparison is on identity (`Semester.isSameTermAs`: year + type), never on dates."*

`Semester.isSameTermAs` is exactly:

```kotlin
fun isSameTermAs(other: Semester): Boolean = year == other.year && type == other.type
```

**So:**

- The session is **unchanged** (2026-27, ODD) between OLD and NEW — **CONFIRMED** in §1.4.
- Therefore **editing `termStart` does not trigger a rollover**, and neither does replacing
  the timetable asset.
- A rollover — the one place the app deletes a semester's data — fires only on a
  **year or type change**.
- `AppReset`'s doc comment reinforces this: the destructive clear is *"the one place the
  app deletes a semester"*, and it is explicitly a student-chosen action.

**The 17 September revision is a mid-session correction, which is exactly the case
`isSameTermAs` was written to *not* treat as a new semester.**

### 10.6 Backup / restore implications

**CONFIRMED.** `BackupCodec` (in `core/src/main/kotlin/com/attendo/core/backup/`)
serialises `semesters`, `courses`, `patterns`, `sessions` and `preferences` —
**not** the timetable asset. A backup restored on a build with a different
`timetable.csv` therefore restores the student's own courses and sessions, and the bundled
asset is irrelevant to the restore.

Related, from `AttendanceCsv`'s own documentation:

> *"It deliberately cannot be imported. Reconstructing Attendo's state from this file
> would mean guessing at everything the flattening dropped … and a restore that guesses is
> worse than no restore."*

`BackupScreen` displays an incoming backup's semester dates
(`Fact("Semester dates", "${incoming.termStart.fullLabel()} – ${incoming.termEnd.fullLabel()}")`),
so a `termStart` change is **visible** on the restore-confirmation screen — it is shown,
not silently applied.

### 10.7 What this means for the paused migration

- **Nothing needs to be undone or protected before the import resumes.** No attendance
  data is at risk from replacing an asset.
- **The real risk is quiet, not loud.** `problems` is carried rather than thrown
  (§10.2), so a bad CSV degrades silently on the Rooms and Seed screens. The compensating
  controls are `BundledTimetableTest` and the "zero import problems" assertion — which is
  precisely why §7.1's blocker is fatal rather than cosmetic.
- **`AcademicCalendar.termStart` is a genuine open question** (§7.4 / Q11 territory), but
  for a different reason than rollover: `termStart` is the attendance denominator's
  starting point (`AttendanceRepository` uses `calendar.termStart`, and `SettingsViewModel`
  computes `teachingDaysBetween(termStart, termEnd)`). Changing it **changes every
  student's attendance percentage.** It is therefore **not** to be touched as a
  side effect of a timetable import.

---

## 11. CURRENT REPOSITORY STATE

**CONFIRMED** — captured 18 September 2026.

| Fact | Value |
|---|---|
| Branch | `main` |
| **HEAD** | `a8f024c235477b62a038c7c01ef3c0eac7443d5c` |
| HEAD subject | `feat: complete community reporting hardening and future claims` |
| HEAD date | 2026-09-05 16:13:41 +0530 |
| **Staged changes** | **None** — `git diff --cached` is empty |
| `git status --short` entries | **117** |
| — modified tracked files | 57 |
| — untracked paths | 60 |

### 11.1 There are substantial pre-existing uncommitted changes

The 117 entries are **the user's own intentional in-flight development work**. They
include a full community-reporting feature, an account/identity subsystem, 15 Supabase
migrations (`0008`–`0021` plus an out-of-band hardening migration), new test suites, new
schema snapshots (`5.json`–`9.json`), and documentation directories.

**None of it was created by this timetable audit.** The two PDFs were already untracked
before the audit began, and their hashes are unchanged (§1.1, §1.2).

### 11.2 This audit must not cause those changes to be committed or cleaned

**Binding constraints for all future work on this repository:**

- Do **not** commit.
- Do **not** stage.
- Do **not** stash, reset, clean, rebase, amend, or otherwise rewrite history.
- Do **not** modify `timetable.csv`, `faculty.csv`, `subjects.csv`, `README.md`, or Supabase.
- Do **not** overwrite the PDFs.
- The working tree is to be preserved **exactly as found**.

### 11.3 This document

- Path: `docs/timetable-migration-audit.md`
- **Intentionally untracked.** `docs/` was already untracked before this file existed
  (it also holds `backup-format.md` and `free-tier-escape-runbook.md`).
- Contains no secrets and no student data.

---

## 12. NEXT DECISION POINT

> # TIMETABLE IMPORT PAUSED UNTIL STUDENT CONFIRMATION.

The next timetable-specific task will be to **update this audit with confirmed answers**
(from §8's queue), **then perform the final OLD → NEW reconstruction**, and **only after
that** modify timetable assets.

**Until then:**

- `docs/timetable-migration-audit.md` is the **authoritative parking lot** for the
  unresolved timetable migration.
- The timetable is **not** to be resolved or modified unless explicitly requested.
- `timetable.csv` is **not** to be modified merely because other development work touches
  related code.
- The existing working tree is **not** to be cleaned, stashed, reset, or committed.
- Other Attendo development proceeds **independently**, with the timetable migration kept
  isolated from it.

### 12.1 Resuming checklist

When student confirmation arrives, in order:

1. Fill in §8 (Q1–Q16) with the confirmed answers and dated sources.
2. Re-run the full double-booking sweep of NEW (§5.10 / Q15) — **not yet done**.
3. Decide §7.3 (8 AM) and §7.4 (Saturday) on the strength of Q10 and Q11.
4. Only then reconstruct the CSV, update the two glossaries, and rebuild the 19 test
   expectations in §2.3 against the confirmed grid.
5. `./gradlew test` and `./gradlew :app:assembleDebug` must both pass, and the timetable
   audit must pass — a green Gradle build alone is **not** sufficient evidence.
6. Verify no attendance history was reseeded or destroyed (§10).

### 12.2 What must never happen

- Never guess a subject name, a faculty name, or a room.
- Never invent a subject that is not in the source or the master reference.
- Never promote an **UNCONFIRMED** item in this document to a fact without a recorded
  source.
- Never change the section/division architecture.
- Never destroy or implicitly reseed attendance history.

---

*End of audit. This document is a research artefact and a parking lot. It is not a
migration specification and does not authorise any change.*
