# Attendo

Attendance tracking and room lookup for students of the Faculty of Technology,
University of Delhi.

⭐ If you find Attendo useful, consider leaving a star on the repository — it helps the project and is greatly appreciated.

## What is Attendo?

Attendo is a free Android app that does two things a FoT student does every week:

- **Keeps your attendance** — for every class your timetable says you had, you record
  whether you were there. Attendance is counted in **hours**, so leaving a
  two-hour lab halfway through counts as exactly half of it, not as a coin-flip between
  present and absent. Each course gets its own percentage and a target, and the app tells
  you how many classes you can still afford to miss — or how many you must attend to get
  back to your target.
- **Finds rooms** — which rooms are free right now, and what any room's whole week looks
  like. The faculty's printed timetable ships inside the app.

Attendo uses anonymous, aggregate usage analytics to help understand whether and how
widely the app is used. Everything you record about your attendance stays on your phone
until you export it yourself. Two optional online features are the exception, and neither
does anything until you use it: the **Community** feature, where you contribute room
reports, polls and votes (see _Finding a room_ below), and **syncing**, where signing in
with GitHub gives your attendance a private cloud copy of your own so a second phone or a
reinstall picks up where you left off (see _Your account_ below).

## What can it do?

- **A normal day in one tap.** Open the app, tap _Mark all present_, done.
- **Missed the whole day?** One action marks every class that day as missed — and it
  counts honestly, on both sides of the fraction.
- **Everything else about a class**: it was cancelled (and why), it ran short, it moved to
  another hour or another day, it was an extra class nobody timetabled, a note on anything
  unusual.
- **A backlog that tells the truth.** Unmarked classes are shown as awaiting review, named
  for what they actually are: today's classes, a single past date, or a range of dates
  (through today, when today is in the queue). You can review them day by day, mark the
  whole lot as missed, or cancel the whole lot. There is deliberately no way to just
  _ignore_ them: unmarked hours are not a rounding problem. The card is not shown when
  nothing is waiting.
- **A colour-coded calendar** of your semester, per course and overall.
- **Semester management**: semester dates, holidays, working Saturdays, a personal joining
  date if you arrived mid-semester. Holidays and working Saturdays each have their own
  screen, reachable from Settings, and each shows what is configured in one line on the
  Settings row itself. Nine sections — every first-year section, plus 4th Yr EE, 4th Yr
  ECE-A and 4th Yr ECE-B — are timetabled on Saturday every week; for those a Saturday
  class is simply part of the week, so the Working Saturdays screen is not offered at all
  rather than offering a list the app would ignore. See `RecurringSaturdays`.
- **Attendance targets in one place**: an overall target, the default applied to new
  courses, and a single action that applies the default to every active course. Each course
  keeps its own target too, reachable from that course's screen.
- **Optional sign-in, and syncing.** Sign in with GitHub and your courses, timetable,
  attendance and settings get a private cloud copy under your own account, so a second
  phone — or the same phone after a reinstall — carries on where you left off. Syncing is
  automatic whenever the phone is online, and **Sync now** in the account screen asks for a
  pass immediately. Without an account none of this happens and the app is purely local.
- **Backup and restore** to a single file you control — see below.
- **A CSV spreadsheet** of your whole history, whenever you want it, and a **semester record
  PDF** offered when a semester ends — for showing anyone who asks.
- **Light or dark, or however the phone is set** — with Android 12's wallpaper colours
  when the device supports them.

## Installation

Attendo is not on any app store. Install the APK from
[GitHub Releases](https://github.com/divsysx/Attendo/releases):

1. Download the `.apk` file on your phone.
2. Open it. Android will ask for permission to install from that source (your browser or
   file manager) — allow it once.
3. Open Attendo. Android 8 (Oreo) or newer is required.

Updates arrive the same way. Attendo checks quietly in the background — at most once a
day — and when a newer release is published the update card appears on the app's main
screen. Settings → Updates can check sooner; a release you dismiss with _Not now_ is not
offered again until a genuinely new one is published.

## Getting started

1. **Seed your courses.** On first open, tap _Seed from timetable_, pick your section and
   your lab batch, and tick the subjects you actually take. Attendo proposes courses from
   the faculty timetable, one course per subject — labs and tutorials are separate
   courses, because they are taught, examined and attended separately. Anything it cannot
   make sense of (two subjects at the same hour, a lab split across batches) is flagged
   for you rather than guessed at. You can also add courses by hand.
2. **Set your targets.** **Settings → Attendance Targets** holds the overall target, the
   default applied to new courses, and a bulk action that applies that default to every
   active course; 75% is the usual university requirement. An individual course's target
   can also be changed from its own screen, and it stays that course's own.
3. **Mark today.** That's the whole daily routine. Open the app, and if you went to
   everything, tap _Mark all present_.

## Attendance

**Attendance is counted by the hour.** A two-hour class is worth two hours; attending one
of them is 50% of that class. A course's percentage is `hours attended ÷ hours held`, never
an
average of class percentages — so a two-hour lab weighs twice what a one-hour lecture
does. Your overall figure works the same way across all courses.

- **A class you marked** counts on both sides: attended hours on top, held hours below.
- **A cancelled class** counts on neither side. A class that never happened must not count
  against you.
- **An unmarked class** counts on neither side _yet_. It stays in your backlog until you
  deal with it.

The day screen shows each class as a strip of one-hour chips — tap the hours you attended
and confirm. Anything unusual about a class (cancelled, shortened, moved, extra) lives
behind the ⋮ menu on that class.

The dashboard warns you when classes are still unmarked, in the words that match the
dates waiting: _"Today's classes are awaiting review."_ when only today is pending;
the named date when exactly one past day is pending; a range when several are; _"to
today"_ when the range includes today. There is no card when the queue is empty. Review
them one day at a time, or use _I missed all of them_ / the cancel action to deal with
the whole backlog at once. These actions only ever touch classes that are still unmarked
— anything you already recorded is left alone.

If you joined mid-semester, Settings → _Attendance counted from_ lets you count from your
joining date instead of the semester start. Nothing is deleted either way; the earlier
classes stay in your history and this only decides which of them count towards your
percentage.

## Finding a room

The **Rooms** tab shows which rooms are free right now and which are in use, with what.
Search by room number, or tap a room to see its whole week — the timetable covers
9 AM to 6 PM, Monday to Saturday. Anything outside those hours is unbooked, which is not
the same as being open.

The timetable ships inside the app as a CSV, transcribed from the faculty's printed grid.
When the faculty prints a new one, the repository updates it and a new release carries it
in.

### Live reports from other students

The timetable is the plan; the **Community** feature is what is happening now. From any
room's screen you can report what you see — that a room the timetable calls free is
actually occupied, that a busy one is free, an extra class, or a class that moved in — and
ask a quick poll of the people in the room. Everything is filed under a random anonymous
ID: no name, no email, nothing that identifies you.

Community works without an account. If you do sign in with GitHub (Settings → Account),
your Community identity and its history attach to that login and are restored on any phone
you sign in on — and the same account carries your attendance, as the next section
describes.

## Your account, and syncing

**Optional, and off until you use it.** Attendo works completely without an account: mark
your attendance for a whole semester and nothing about it leaves the phone. Signing in is
for the student with two devices, or one device and a habit of reinstalling.

**Signing in.** Settings → Account offers two GitHub paths, and they are deliberately
different buttons rather than one:

- **Create or connect an account** attaches a GitHub login to the identity this phone
  already holds, so the Community history on this phone stops being throwaway.
- **Sign in to an existing account** authenticates an account that already exists — on
  another phone, or on this one before you reinstalled — and brings it back.

Both end at GitHub's own consent page in your browser; Attendo never sees your GitHub
password. The deep link GitHub returns to hands the session back to the app. What the
app takes from GitHub is only _which account this is_ — the identity it resolves the
session to. Your GitHub name, email address and picture are not read, stored or shown;
the name the app greets you with is the one you type in Settings → You.

**What syncs.** Once you are signed in, the account holds a private copy of:

- your courses and their individual targets, and your archived courses;
- your timetable patterns, every class generated from them, and your attendance on each
  one — including notes, cancelled, shortened, moved and extra classes;
- your semesters, including archived ones, and the term dates, holidays, working
  Saturdays, section and joining date;
- your display name, and the overall and default attendance targets.

**How it runs.** Syncing is a background reconcile, not a form you submit: whenever the
phone comes online with a signed-in account, the app pushes what changed here and pulls
what changed elsewhere. **Sync now** on the account screen asks for a pass immediately and
tells you what happened. If the app is offline for a week, nothing is lost — the edits
queue up and go out at the next pass.

**Two devices editing the same term.** Both phones' changes are kept; where the same thing
was changed on both while offline, the later edit is the one that stands. A phone that
already holds attendance belonging to a _different_ account is never silently rewritten:
Attendo asks before replacing anything, and cancelling changes nothing. Editing while
signed out is allowed too — the app notices on your next sign-in and asks whether to merge
those changes into the account or discard them and take the account's copy.

**Signing out** removes the GitHub login from this phone and deletes nothing: your
attendance stays here, and the account keeps its own copy on the server. Sign in again with
the same GitHub login and you are back where you were.

**Deleting the account and its copy** is done on request, by email — see the
[privacy policy](https://divsysx.github.io/Attendo/privacy/).

## Backup and restore

Your attendance records are the one thing that cannot be reconstructed — not from the
timetable, and not from memory. So there is an explicit export, and it is not the same
thing as Android's automatic backup (which Attendo ships switched off, and which you can
turn on if you want it — see _Moving to a new phone_ below).

Everything here lives under **Settings → Data & backup**.

### Export a backup

1. Tap **Export backup**.
2. Android's file picker opens with a name filled in — `attendo-backup-2026-08-19.json`.
   Put it wherever you like: Drive, Downloads, a folder that syncs.
3. Move it off the phone. A backup that only exists on the phone you lose is not a backup.

The file holds everything the app knows: every course, every timetable slot (including
retired ones), every class with _which hours_ you attended, every cancellation and its
reason, both halves of every moved class, semester dates, holidays, targets, your section
and your name.

### Import a backup

1. Tap **Import backup** and pick the file.
2. The whole file is checked before anything changes — JSON, format version, SHA-256
   checksum, every value and reference.
3. A preview shows what is in the file next to what is on the phone now.
4. **Replace** deletes the current data and writes the backup's. A copy of what was
   replaced is saved first, so **Restore previous data** can undo the import once.

An import either happens completely or not at all. A malformed, truncated, failed-checksum
or internally inconsistent file is refused and nothing is touched. Older format versions
are upgraded on the way in.

### Moving to a new phone

Two ways, and they are not exclusive:

1. **With an account.** Sign in on the new phone (Settings → Account → _Sign in to an
   existing account_) and the account's copy of your attendance, courses and settings
   comes down on its own. This is the way that does not need you to remember anything —
   see _Your account, and syncing_ above.
2. **With a file.** Old phone: **Export backup**, and save the file somewhere the new
   phone can reach. Install Attendo on the new phone and open it once. New phone:
   **Import backup**, check the preview, then **Replace**.

Either way, compare the overall percentage on both phones before you wipe the old one.

Attendo can also take part in Android's own automatic backup, through the **Automatic
backup** switch on the same screen. It is off by default: while it is off, uninstalling
Attendo really removes the data, and reinstalling starts fresh. Turned on, Android _may_
copy your data to your Google account and put it back when you reinstall Attendo or set
up a new phone — whether it ever does is up to Android and the phone's backup settings
(it runs about once a day, while the phone is idle, charging and on Wi-Fi), and there is
no way to check from inside Attendo that a copy exists. Turning the switch off again
stops new copies, but cannot delete one Android has already stored. Treat it as luck
rather than a plan — the export is the copy you control.

### Starting fresh

**Settings → Data & backup → Clear all Attendo data** removes everything Attendo keeps
on this phone — your attendance records, your courses, your settings — and restarts the
app as a fresh install. It does not delete any backup Android or Google may already be
holding (nothing inside the app can), and it deliberately leaves two settings alone: the
Automatic backup switch and Appearance, which say how this phone behaves rather than
anything you recorded.

### The spreadsheet and the PDF

The same screen writes **Export attendance CSV** — one row per class, for Excel or for
anyone who wants to check the record. It cannot be imported back; flattening a semester
into rows loses information a restore would have to guess at.

At the end of a semester, Attendo offers to export a **semester record PDF** — per-course
figures and the full class history, the same numbers the app has been showing all
semester.

## Feedback

Found a bug, or have an idea? **Settings → Feedback → Report a bug or suggest an
improvement** opens your email app with the message started for you. Nothing is sent from
inside Attendo — your email app does the sending, and feedback needs no account. You can
also [open an issue on GitHub](https://github.com/divsysx/Attendo/issues).

## Screenshots

![Screenshot 01](screenshots/01.jpg)
![Screenshot 02](screenshots/02.jpg)
![Screenshot 03](screenshots/03.jpg)
![Screenshot 04](screenshots/04.jpg)
![Screenshot 05](screenshots/05.jpg)
![Screenshot 06](screenshots/06.jpg)
![Screenshot 07](screenshots/07.jpg)
![Screenshot 08](screenshots/08.jpg)
![Screenshot 08](screenshots/09.jpg)
![Screenshot 08](screenshots/10.jpg)
![Screenshot 08](screenshots/11.jpg)
![Screenshot 08](screenshots/12.jpg)
![Screenshot 08](screenshots/13.jpg)
![Screenshot 08](screenshots/14.jpg)
![Screenshot 08](screenshots/15.jpg)

## Contributors

- **Divyansh Sharma** — Project maintainer · [GitHub](https://github.com/divsysx)
- **Shubham Prasad** — Ideas, codebase improvements, suggestions, and testing · [GitHub](https://github.com/Shu6hamPrasad)
- **Adrija Roy** — Ideas, extensive testing, and suggestions · [GitHub](https://github.com/anshuroy11012007-hash)

---

## For developers

The sections above are for Attendo's users. The rest of this file is for anyone building
it or reading its code.

### Requirements

|             |                                                    |
| ----------- | -------------------------------------------------- |
| JDK         | 17                                                 |
| Android SDK | API 35 (compile), minSdk 26                        |
| Gradle      | 8.11.1 — supplied by the wrapper, don't install it |
| Kotlin      | 2.1.0                                              |

### Build and run

```bash
# Debug APK
./gradlew :app:assembleDebug

# Install on a connected device or running emulator
./gradlew :app:installDebug

# The calculation engine's tests — no Android SDK needed (see "Modules")
./gradlew :core:test

# Same thing, by intent rather than by path
./gradlew engineCheck

# The app's own JVM tests (backup ids, entity mapping) — SDK needed, device not
./gradlew :app:testDebugUnitTest
```

If `JAVA_HOME` points at anything other than 17:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :core:test
```

The debug APK lands in `app/build/outputs/apk/debug/`.

### Online features in a local build

The Community feature, attendance syncing and the account they hang off all talk to one
Supabase project, whose endpoint and publishable key are read from `local.properties` (that
file is untracked; nothing secret lives in a Gradle script):

```properties
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_PUBLISHABLE_KEY=<your publishable key>
```

Leave them out and everything still builds and runs: `CommunitySupabaseConfig` reports the
install as unconfigured, and every screen that would have offered an online control says so
instead of showing one that cannot work. The attendance engine, the timetable and the whole
offline app are unaffected — that is the same path a student takes who never signs in.

The publishable key is the one designed to ship in a client; row-level security in the
`community` and `attendance` schemas is what actually protects the data, and the database
migrations that establish it are under `supabase/migrations/`.

### Release build

Release builds are signed with a keystore that is **not** in this repository. The
credentials come from `keystore.properties` at the repo root, which is untracked — as is
any `*.p12`, `*.jks` or `*.keystore` (see `.gitignore`) — so neither the passwords nor the
key itself can be committed by accident. Nothing secret appears in any Gradle script.

To produce a signed release, supply your own keystore and your own `keystore.properties`:

```properties
storeFile=path/to/your-release.p12
storePassword=<your store password>
keyAlias=<your key alias>
keyPassword=<your key password>
```

Point `storeFile` at a location outside the working tree. If the keystore is PKCS12,
`storePassword` and `keyPassword` have to be the same string — the format has no separate
key password.

```bash
./gradlew :app:assembleRelease   # lands in app/build/outputs/apk/release/
```

With any of those four keys missing, the release build fails during configuration rather
than quietly producing an unsigned APK. Debug builds are independent of all of it: they
keep using AGP's own generated debug keystore, so a fresh checkout with no
`keystore.properties` can still build, install and test debug.

On CI, write `keystore.properties` and the keystore file from your secret store before
invoking Gradle, and keep both out of the checkout and out of the build log.

### Publishing a release

The in-app updater reads GitHub Releases through the official API — nothing is scraped,
and there is no backend of our own. A release that the app can update from needs three
things, all of them parts of a normal GitHub Release:

1. **The version code in the release title.** Title the release `Attendo 1.2 (3)` — the
   parenthesised number is the Android `versionCode`, which is the only thing the updater
   compares ("1.10" versus "1.9" is not decidable from version names). A release whose
   title has no parenthesised number is skipped: there is no honest way to call it newer
   or older.
2. **The APK as a release asset**, any `.apk` name.
3. **A checksum asset next to it**, named exactly the APK's name plus `.sha256` — for
   `attendo-1.2.apk`, the asset `attendo-1.2.apk.sha256`:

   ```bash
   shasum -a 256 attendo-1.2.apk > attendo-1.2.apk.sha256
   ```

   One line, the `shasum` format; the app reads the first 64 hex digits. GitHub's release
   metadata cannot carry a checksum, which is why the checksum ships as an asset. A
   release without one still updates — the installer then relies on the package identity
   and signing-certificate checks alone — but every release should publish one.

Tag the release `v1.2` (the tag is where the version name comes from). Leave
**"Set as a pre-release"** unticked for stable releases: the updater asks the API for
`releases/latest`, which never returns drafts or pre-releases. That flag is also the
seam a future Beta/Early-Access channel would hang off — a pre-release is a beta by
another name, and a future beta provider would read the `releases` list and filter by it.

Before installing anything, the app checks that the downloaded file is newer than what is
installed, is actually Attendo (`com.attendo`), is signed by the release certificate, and
matches the published checksum. The certificate check compares against the public digest
in `ApkGate.RELEASE_SIGNER_SHA256` — the same fingerprint `apksigner verify --print-certs`
prints — never against anything private.

Checking is automatic and quiet. On every open the app raises the card for an update a
previous check found (a student offline since a release came out still hears about it),
and it asks GitHub at most once a day. A release dismissed with "Not now" is not offered
again until a genuinely different release is published, and the manual "Check" in Settings
is never throttled. A check that finds nothing, or cannot complete, says nothing — the
card appears on the app's main screen only when there is an update to act on.

### Modules

```
attendo/
├── core/     pure Kotlin/JVM — models, calculation engine, CSV import. No Android.
└── app/      Android — Room database, Compose UI, community client, account and sync.
```

`:core` holds everything where being wrong is expensive: the fractional-attendance
arithmetic, session generation from recurring patterns, and the room-availability
queries. It has no Android dependencies at all, so its tests run in a couple of seconds
on the JVM.

`:app` is where the device and the network live — the Room database, the Compose UI, the
community client, and the account and sync machinery under `data/account/` and
`data/sync/`. None of that has a counterpart in `:core`, and none of it runs unless a
student signs in.

`:app` is **conditionally included** in `settings.gradle.kts` — only when an Android SDK
is actually present (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in
`local.properties`). The Android Gradle Plugin fails during _configuration_ without an
SDK, which would take `:core:test` down with it. This way you can check out the repo on
any machine with a JDK and run `./gradlew engineCheck`.

### How attendance is counted

Everything follows from one decision: **the unit is one hour**.

- A 2-hour class is worth 2 units. Attending one of them is 50% of that session.
- A course's percentage is `units attended ÷ units held`, not the mean of its session
  percentages — so a 2-hour lab weighs twice what a 1-hour lecture does.
- Your overall percentage is likewise `total units attended ÷ total units held` across
  all courses. Averaging the per-course percentages would let a 1-hour seminar cancel out
  a 4-hour workshop.

Percentages are stored as **basis points** (integers, 1 bp = 0.01%) and threshold
comparisons are cross-multiplied rather than divided. This is not fussiness: in floating
point, 60 units of 80 is not reliably ≥ 75%, and being told you have 74.99999% when you
have exactly three quarters is the one bug in this app nobody would forgive.

### Patterns, sessions, exceptions

Three things, deliberately kept separate:

|                  |                                                                                                              |
| ---------------- | ------------------------------------------------------------------------------------------------------------ |
| `SessionPattern` | A recurring weekly slot — _"ADEC, Monday 9 AM, 2 units, room 204"_. A template. Never a thing that happened. |
| `ClassSession`   | One dated instance. Carries which units you attended, as a bitmask.                                          |
| —                | Exceptions are just sessions in a non-default state; there is no separate exception table.                   |

Sessions are generated from patterns, keyed by `(patternId, date)`. That key is what makes
generation idempotent — you can re-run it every time the app opens and never get
duplicates.

Three kinds of exception, and why each is shaped the way it is:

- **Cancelled** — status becomes `CANCELLED`, which drops the session out of _both_ sides
  of the fraction. A class that never happened must not count against you.
- **Shortened** — `resize` lowers `unitsPlanned` and re-clamps the attended mask. Without
  the re-clamp, a fully-present 2-hour class shortened to 1 hour would report 2 units
  attended out of 1 planned: 200% attendance.
- **Rescheduled** — recorded as _two_ rows: the original cancelled, plus an ad-hoc session
  on the new date, linked back to it. Mutating the original's date would vacate its
  `(patternId, date)` slot, and the next generation run would helpfully recreate the class
  you just moved.

Editing a pattern mid-semester works the same way: the old pattern is _retired_
(`effectiveTo` set to its last valid date) and a new one starts the next day, so sessions
already recorded keep pointing at the pattern that actually produced them. History stays
truthful.

The bulk actions work on the same primitives: "miss the whole day" and "I missed all of
them" are `markAbsent` applied to every still-pending row (HELD with an empty mask — the
hours count as held, so the denominator stays honest), and the bulk cancel is `cancel`
applied the same way. Rows already dealt with are never touched.

### Status, and what counts

| Status      | In the denominator?                                          |
| ----------- | ------------------------------------------------------------ |
| `SCHEDULED` | No — generated but not yet reviewed. Invisible to the maths. |
| `HELD`      | Yes.                                                         |
| `CANCELLED` | No, on either side.                                          |

### Updating the timetable each semester

The room timetable ships as a CSV in `app/src/main/assets/`, transcribed from the
faculty's printed grid.

```
day,start,units,room,subject,section,faculty,kind,batch
Mon,9,2,212,EW,1st Yr EE-A,JP,L,A2
Tue,14,1,203,ADEC,2nd Yr CSE-A,AP,L,
```

- `day` — `Mon`…`Sat`
- `start` — the slot's start hour, 24-hour clock: `9` is 9 AM, `14` is 2 PM. **Valid 9–17.**
  The grid's first teaching hour is 9 AM, and there is no 8 AM slot to put a row into —
  see below for what the printed grid's 8–9 AM column is and why it is dropped.
- `units` — length in hours. **A 2-hour block is one row with `units=2`**, not two rows.
  (The printed grid splits blocks across two columns; if you transcribe it that way
  instead, `TimetableCsv.mergeAdjacent` will join them.)
- `room` — as printed. **Leave it empty when the grid prints no room** (the sports hour, and
  anything else that meets on a field rather than in a hall). An empty room is a real class
  that occupies no room, not a bad line: it seeds like any other subject and never shows up
  in Rooms.
- `kind` — `L`, `P` or `T` (lecture, practical, tutorial). A tutorial can equally be written
  by tagging the subject `-T` (`ECA-T`); the seeder reads either.
- `batch` — for split labs (`A1`, `A2`, `B1`, `B2`); leave empty when the whole section
  attends.

Two companion files map the grid's acronyms to real names, same `code,name` shape:
`subjects.csv` and `faculty.csv`. Lookup tries the exact code first and then the part
before its last hyphen, so `NN-A` and `NN-B` both find `NN` while `DE-1` and `EM-I` keep
resolving to themselves. A code that's missing from either file costs you a long name, not
a blank screen.

**To update:** replace the three files, keeping the column order, then run:

```bash
./gradlew :core:test --tests '*BundledTimetableTest*' --tests '*TimetableConflictsTest*'
```

The first parses the file you just wrote and fails with the offending line number on
anything off the hour grid, any bad `kind` code, any truncated row — and, more usefully, on
any subject or faculty acronym you used but didn't add to the key. The second is the
room-and-faculty sweep described below: it reports every hour the new file over-subscribes,
sorts them into clubbed classes and genuine clashes, and pins the genuine ones by name, so a
clash you introduce is a failing test rather than a diff to skim past.

#### Three things about the source data

**A class attended by several cohorts is printed on each cohort's page**, so it arrives as
several identical rows differing only by `section`. That's not a double-booking, and it is
the normal case on this timetable rather than the exception. Rows are transcribed
faithfully, one per page, and folded together for display by
`RoomAvailability.groupBookings` — which matches on everything _except_ section, and so
shows one line naming all the cohorts.

That folding is not enough on its own for validation, which is why `TimetableConflicts`
exists: it asks the same question of every room and every teacher across the week, and
classifies each busy hour as either a **clubbed class** (one class, printed once per page;
or one shared hour with each cohort listed once) or a **genuine clash** (a room holding two
different classes, a teacher in two places at once, or one cohort given two classes in the
same hour). A shared practical whose two halves number their batches differently — 4th Yr
ECE-A's DASIC is batch `A1`, 4th Yr ECE-B's is `B1`, same room, same hours — is clubbed and
stays clubbed, because the batch is deliberately not part of what makes two rows the same
class. The shipped timetable has **116 clubbed hours and exactly one genuine clash**, which
the test names: 4th Yr CSE-A's Tuesday page prints its 3 PM ECFC twice.

**The printed grid's 8–9 AM column is not transcribed at all.** On the pages that carry it
it holds the first hour of a block that continues into 9–10 — the same class written twice —
and 8 AM is not a slot this app has, from `TimeGrid` up through generation, attendance and
the Rooms tab. Twelve printed cells are dropped for that reason, and the classes they belong
to are all present at their real hours. Do not "fix" this by teaching `TimeGrid` an 8 AM
slot; a ninth teaching hour is a change to the whole app, not a transcription.

**Cells printed without a room** — the second-year sports hour, printed as `FIT INDIA`, is
the one group on this timetable — are transcribed with the `room` column left **empty**.
They are real classes: a section that has its sports hour on Wednesday afternoon has it
whether or not the printed grid names a hall for it, and the seeder proposes it like any
other subject. What they are not is room bookings, so they never appear in the Rooms tab,
and they cannot clash with anything — `RoomBooking.room` is nullable and `occupiesARoom` is
what Room Availability filters on. Leave the column empty rather than inventing a room or
dropping the row: inventing one puts a phantom booking on a real hall, and dropping it
loses a course the student actually takes.

### Revising the timetable mid-semester

Swapping the CSV under an install that has already seeded would leave every course pointing
at slots the faculty no longer teaches — silently, for the rest of the term. So a revision
is a migration, not a replacement: `TimetableMigration.plan` takes the student's courses and
patterns plus the new page's proposals for _their_ section and batch, and returns what has
to change for them.

- **A slot that moved** is closed the day before the revision and reopened on it, through
  the same `effectiveFrom`/`effectiveTo` model the rest of the app uses. Sessions already
  marked keep pointing at the pattern that produced them; nothing is edited in place. The
  room is part of a slot's identity, so a lab that moved room is reopened too — a student
  standing outside the wrong door is the failure this exists to prevent.
- **A renamed subject is a rename, not a withdrawal plus a new course.** `RENAMED` maps
  `SFL` to `FIT INDIA` and the mapping carries the seeder's ` Lab` and ` Tutorial` suffixes
  with it, so the sports hour keeps its history and its one course instead of the student
  ending up with a frozen old one and a fresh empty one.
- **A subject the new page stops teaching keeps its course and every class recorded
  against it**; only the slots stop, from the revision date. The course is the student's to
  archive.
- **A course with no live slot is not the revision's business**, and neither is an archived
  one — a finished term's slots are part of what its figures were measured against.
- **New subjects are offered, never created.** They come back in `MigrationPlan
.newlyAvailable` for the seed screen to offer, because being in a section is not the same
  as taking an elective, and the app has never claimed to tell those apart.
- Running it twice is a no-op: the plan is the difference between what the student has and
  what the page says, so applying it leaves nothing to do.

`TimetableMigration.EFFECTIVE_FROM` is **2026-09-17**, the date of the last revision. It is
deliberately not `AcademicCalendar.termStart` (still 2026-07-28): the term began under the
July timetable, and everything recorded before the revision stays as it was recorded.
`TimetableMigrationTest` covers the rename, the moved-slot arithmetic, the discontinued
course and the second run.

### Setting up your own courses

Either type them in by hand, or seed them from the timetable: pick your section, pick your
lab batch, and `SectionSeeder` proposes the courses that section is timetabled for, each
with a pattern per weekly slot. It only ever _proposes_ — tick what you actually take, and
any hour where your section is booked into two subjects at once comes back in
`SeedPlan.clashes` for you to resolve.

Three things the seeder does with the printed page, because reading it literally gets them
wrong:

**A subject's practicals and tutorials each become their own course.** `ECA`, `ECA Lab`
and `ECA Tutorial` are separate rows, with separate targets and separate percentages,
because they're taught, examined and attended separately — a semester of missed labs
shouldn't disappear into a healthy lecture figure. A row is a lab when its `kind` is `P`,
and a
tutorial when its `kind` is `T` or its subject is tagged `-T`.

**A trailing `-A`/`-B` on a subject is dropped, and so is `-T`.** `-A`/`-B` names the
lecture group a pooled course is split into (`PSCS-A` and `PSCS-B` are one subject in two
halls) and a section sits in exactly one, so within one page it says nothing. Keep it and a
student whose lectures read `PSCS-B` and whose lab reads `PSCS` gets two unrelated courses
for one subject. `-T` leaves the code too, but unlike a group tag it isn't noise: it's what
marks the row a tutorial, and it wins over a `kind` column that says `L`. Only those three
suffixes go — `DE-1`, `EVS-2`, `EM-I` and `AEW-I` are whole codes.

**Only the section's own batch numbering is offered.** 2nd Yr ECE-A splits its branch labs
`A1`/`A2` — that's the choice — but its statistics lab is batch `B3`, pooled with 2nd Yr
EE-A. `B3` isn't a question that section answers, and a plain batch filter would delete
that lab the moment `A1` was picked. So a subject batched outside the offered scheme is
kept whichever batch you choose, and one still split two ways after your answer is flagged
`needsBatchCheck` for you to look at rather than guessed at.

### Your name

**Settings → You → Your name** is one text field, and everything it changes is cosmetic: the
Attendance tab greets you with your chosen name rather than a generic greeting, and a backup
you export after setting it uses that name instead of the default **Attendo** attribution.
Leave it empty and both fall back — no name is a normal state, not an unfinished setup step.

Your name is a label, not a login: no table is keyed by it, nothing is filed under it, and
it is stored in `SharedPreferences` beside the targets and the term dates. It travels inside
a backup for one reason: so that restoring on a new phone greets the same person, and so the
import preview can say whose file you are looking at. If you sign in, it travels with the
account's copy of your settings too — it is one of the settings, not an identity.

### The backup file format

**The normative specification is [`docs/backup-format.md`](docs/backup-format.md)** — written so
an implementation (the planned Web port included) can be built from it without reading the
Kotlin source. The summary below is an orientation, not the definition.

The format — frozen at **version 2** — is written by hand in `core/backup/BackupWire.kt`,
versioned, and deliberately independent of both Room's schema and the domain classes, so a
Room migration or a new field on `ClassSession` cannot stop last semester's file from loading:

```json
{
  "formatVersion": 2,
  "app": { "name": "Attendo", "versionName": "1.1", "versionCode": 2 },
  "exportedAt": "2026-08-19T09:30:00Z",
  "checksum": { "algorithm": "SHA-256", "value": "<64 hex chars>" },
  "payload": {
    "preferences": {},
    "calendar": {},
    "semesters": [],
    "courses": [],
    "patterns": [],
    "sessions": []
  }
}
```

- **Refs, not ids.** Every record carries a `"ref": "s1"` and points at others by ref. Primary
  keys belong to the database that issued them and all change on restore; a reschedule is two
  rows linked to each other, so an id-based format would have to renumber those links and hope.
- **Attendance as a list of hours.** `"attendedUnits": [0, 1]` rather than the bitmask `3`, so
  it stays readable and does not depend on `UnitMask`'s internals.
- **Enums, dates and instants as strings**, parsed defensively rather than by the serialiser,
  so an unrecognised value is reported against a field path instead of throwing out of a decoder.
- **The checksum covers the payload as written**, and is verified before any migration touches it.
- **Version migration**: `BackupMigrations` holds a chain of forward transforms from
  `OLDEST_SUPPORTED` (1) to `CURRENT` (2), and a test asserts every step in that range exists.
  Readers ignore unknown fields, so a new _optional_ field needs no version bump; any change to
  an existing field's shape or meaning requires a new version, a migration step, and a new
  fixture.
- **Semesters travel with the courses that belong to them**, live and archived — a restore
  that dropped them would leave every course orphaned and make "never mix semesters" (the one
  guarantee the app makes about percentages) unenforceable.
- **Restore is replace-only.** Importing a backup replaces the portable user data rather than
  merging it: merging two independently marked histories has no principled conflict rule.
  A safety snapshot and a one-level undo make the replacement reversible.
- **Portable user data only.** Courses, patterns, the full session history, the calendar,
  semesters, and the preferences that change what the numbers mean travel; theme and other
  device-specific settings, statistics (always derived, never stored), and the community
  reporting identity do not — see `docs/backup-format.md` §11–§12.

One dangling reference is legal, and it is the subtle one: a session's `patternRef` may name a
pattern that isn't in `patterns`. Deleting a slot keeps the classes already marked against it,
so a marked session can outlive its template — and it has to stay a _timetabled_ class,
because origin is derived from whether a pattern id is present, and an empty `(pattern, date)`
slot is one the generator will fill in again. The importer gives such a reference an id of its
own. Every other reference must resolve.

Every way a backup can be refused reads the same on screen — _"That file isn't a valid Attendo
backup. Choose an Attendo backup (.json) file and try again."_ — because they all have the same
remedy, and because a parser offset is not something anyone can act on. The specific reason is
not lost: it goes to logcat under the tag `AttendoBackup` (`adb logcat -s AttendoBackup`). See
`BackupMessages`.

### Layout

```
core/src/main/kotlin/com/attendo/core/
├── model/
│   ├── Percent.kt          basis-point percentage, exact integer arithmetic
│   ├── UnitMask.kt         which hours of a session you attended, as bits
│   ├── TimeGrid.kt         the 9-to-6 hourly slot grid and its labels
│   ├── Course.kt           courses and SessionKind (L/P/T)
│   ├── SessionPattern.kt   recurring weekly slots, with effective dates
│   ├── ClassSession.kt     one dated instance and its status
│   ├── RoomBooking.kt      one row of the room timetable
│   ├── AcademicCalendar.kt term dates and holidays
│   └── RecurringSaturdays.kt which nine sections are taught on Saturday every week
├── engine/
│   ├── AttendanceEngine.kt per-session, per-course and overall percentages; target advice
│   ├── SessionGenerator.kt patterns + dates -> sessions; the daily review's DayPlan
│   ├── SessionOps.kt       mark, approve, cancel, resize, reschedule, ad-hoc, bulk
│   ├── CourseHistory.kt    one course's sessions, newest first, grouped by month
│   ├── CalendarBuilder.kt  the colour-coded month grid
│   ├── RoomAvailability.kt free rooms now, a room's week, the day+time grid
│   └── TimetableConflicts.kt clubbed class vs. genuine clash, over rooms and teachers
├── auth/
│   └── AuthLifecycle.kt   what a removed account means for the data on the phone
├── sync/
│   └── AttendanceSyncPolicy.kt when a sync pass runs, and what one pass may pull
├── rollover/
│   └── SemesterRecordRenderer.kt  the semester record PDF's contents, as pure data
├── backup/
│   ├── BackupSnapshot.kt   the whole logical state, as one value; normalised(), summarise()
│   ├── BackupWire.kt       the on-disk JSON schema, written by hand and versioned
│   ├── BackupCodec.kt      encode; decode with checksum, migration and full validation
│   ├── BackupChecksum.kt   SHA-256 over the canonicalised payload
│   ├── BackupMigrations.kt the chain of forward transforms between format versions
│   ├── BackupProblem.kt    every way a backup can be refused, with a field path
│   ├── BackupMessages.kt   the one sentence a refused file gets, and why it is one
│   └── AttendanceCsv.kt    the human-readable export — one row per class, not importable
└── data/
    ├── TimetableCsv.kt     CSV import/export, block merging, problem reporting
    ├── Glossary.kt         acronym -> full name
    ├── SectionSeeder.kt    a section's page -> proposed courses and patterns
    └── TimetableMigration.kt what a revised timetable changes for the courses you have
```

```
app/src/main/kotlin/com/attendo/
├── AttendoApplication.kt        the AppContainer — database, repositories, settings
├── MainActivity.kt              one activity, one Compose entry point
├── data/
│   ├── db/                      Room entities, DAOs, type converters, the database
│   ├── AttendanceRepository.kt   the only place attendance is read or written
│   ├── BackupRepository.kt       snapshot out, verified transactional restore in
│   ├── SafetySnapshotStore.kt    the copy taken before a restore, for the one undo
│   ├── SemesterRecordPdf.kt      paints the renderer's document onto a real PDF
│   ├── DocumentStore.kt          reading and writing the picker's URIs
│   ├── TimetableRepository.kt    parses the bundled CSVs once per process
│   └── SettingsStore.kt          targets, term dates, holidays, your name (SharedPreferences)
└── ui/
    ├── AttendoApp.kt            the two tabs and the whole navigation graph
    ├── Routes.kt                every destination and its arguments
    ├── Format.kt                date, instant and count labels, in one place
    ├── SessionLabels.kt         status labels, in one place
    ├── components/              top bar, chips, date picker, bars, month calendar
    ├── attendance/              dashboard, day review, courses, editor, seeder
    ├── rooms/                   what's free now, and one room's week
    ├── rollover/                the new-semester gate: record, backup, switch
    ├── settings/                term dates, the target/holiday/Saturday screens,
    │                            Data & backup, the account, reporting identity
    └── theme/                   colours, the six attendance bands, type
```

`app/src/main/kotlin/com/attendo/data/` also carries `analytics/UsageAnalytics.kt` — the
app's entire Firebase surface, one parameterless event per update-system step and nothing
else, so a review of what is collected is a review of that one file — and
`account/AccountManager.kt` with `sync/AttendanceSyncEngine.kt`, which own the session and
the reconcile pass respectively.

Every mutation in `AttendanceRepository` is a thin wrapper over a pure function in
`:core`'s `SessionOps`: the rule is computed where it is unit-tested, and the repository
only decides when rows are written. ViewModels take plain constructor parameters and are
built by `viewModelFactory` initializers reading the `AppContainer` out of
`CreationExtras` — that is the whole of the dependency injection.

### Tests

`:core` carries the tests, because `:core` carries the arithmetic. The ones worth knowing
about:

- `PercentTest` — rounding, and that exactly 75% reads as meeting a 75% target
- `UnitMaskTest` — the clamping that prevents >100% sessions
- `AttendanceEngineTest` — unit-weighted aggregation, and "how many can I still miss"
- `SessionGeneratorTest` — idempotent generation, holidays, retired patterns
- `SessionOpsTest` — cancel, resize, reschedule-as-two-rows, and the bulk actions
- `SemesterRecordRendererTest` — the record's figures and labels, including "Missed"
- `BundledTimetableTest` — the shipped CSV itself, not a fixture: the hours it prints, the
  sections it teaches, and the three things that must not be "fixed" (the 8–9 AM column,
  the clubbed rows, the room-less sports hour)
- `TimetableConflictsTest` — the room and faculty sweep of that same file, with the clubbed
  hours counted and the one genuine clash named, so a re-import cannot add a clash quietly
- `TimetableMigrationTest` — a revised timetable against courses already seeded: the rename,
  the moved slot's two dates, the discontinued course that keeps its history, and the
  second run that does nothing
- `RecurringSaturdaysTest`, `CalendarSaturdaysTest` — the nine sections that are taught on
  Saturday every week, and that every other section keeps the working-Saturday mechanism
- `WholePercentTargetTest`, `PercentFullRangeTest` — whole-number targets 0–100, an
  out-of-range value refused rather than clamped, and a legacy fractional target preserved
- `AttendanceSyncPolicyTest`, `AuthLifecyclePolicyTest` — when a sync pass runs, and the
  phone's data when the account behind it is gone
- `BackupRoundTripTest` — export → import gives back _logically the same semester_, not
  merely the same percentage: fractional attendance, shortened, cancelled, rescheduled
  and ad-hoc
  classes, retired patterns, unmarked classes, the calendar and the settings
- `BackupRejectionTest` — corrupt JSON, a truncated file, a missing field, a bad checksum, an
  unsupported future version, a reference that resolves to nothing
- `BackupMessagesTest` — the other half of that: sixteen kinds of unusable file, all refused in
  the same two sentences, with no parser wording anywhere near the screen
- `BackupChecksumTest`, `BackupMigrationsTest`, `BackupSummaryTest`, `AttendanceCsvTest`

```bash
./gradlew :core:test
open core/build/reports/tests/test/index.html
```

`:app` carries the few tests that are about the database boundary rather than the arithmetic,
and they still run on the JVM — no device, no emulator:

- `RestorePlanTest` — the ids a restore hands Room, including the negative ids given to
  patterns that were deleted while their marked classes survived
- `EntityRoundTripTest` — a snapshot mapped to Room rows and back is the same snapshot
- `SafetySnapshotStoreTest` — the undo copy is complete or absent, never truncated
- `AttendanceSyncDatasetTest`, `AttendanceSyncRowsTest` — what a pass puts on the wire, table
  by table, and that it is the same data the backup format carries
- `SettingsNavigationContractTest` — every Settings row opens what it says it opens, and the
  Working Saturdays row is the one that is conditionally absent
- `AttendanceTargetsBulkApplyTest` — the bulk target action touches active courses only and
  leaves an archived course's own target alone
- `AccountSettingsWordsTest` — the account screen's sentences: what an account carries, what
  signing out does not delete, and that nothing implies a merge
- `AppSettingsTest`, `SettingsUiStateTest` — the greeting with and without a name, the
  concise Settings summaries, and that the version label stays a version rather than
  becoming part of the product's name

```bash
./gradlew :app:testDebugUnitTest
open app/build/reports/tests/testDebugUnitTest/index.html
```
