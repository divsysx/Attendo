# Attendance Sync — Diagnosis (2026-09-22)

Scope: the real-world scenario "Device A (weeks of use) → GitHub OAuth → cloud →
Device B (fresh) → same-account sign-in → pull → converge". Mandate: diagnose
first, edit only the minimum, do not touch auth lifecycle / account
deletion-quarantine / community identity / migrations / timetable assets.

## Verdict

**No code defect is demonstrable.** The failure in the field matches a
deployment-configuration gap (the `attendance` schema not listed in the Supabase
project's *Exposed schemas*, as migration 0020's operator note at lines 432–436
warns), which today's live probe confirms has since been closed in production.
All code layers were inspected end-to-end and are healthy.

## Evidence trail

1. **Policy — `:core` `AttendanceSyncPolicy.kt`**: `boundary`, floor=0 fresh
   state, LWW `<=`-rejects (`remoteWins`), `ownershipVerdict` (CLAIM / PROCEED /
   REFUSE / QUARANTINE_NEW_LIFECYCLE), tests cover cursor/ownership.
2. **Engine — `AttendanceSyncEngine.kt`**: pull-before-push; cursor advanced
   after reads, before push; `Unreachable` early-return on any unreachable pull;
   heal paths (`claimAttendance`, `releaseTerminatedClaim`); `syncNow` also
   drains community before the pass.
3. **Store — `AttendanceSyncStore.kt`**: `preparePush` initial=`!initialPushDone`
   ⇒ `assignIdentity` over `all()` (null `clientUpdatedAt` stamped with the push
   stamp, so pass-two rows are not skipped); dirty-only otherwise; tombstones
   filtered by `tombstonePushableBy`; `markPushed` via `clearDirtyIfUnchanged`;
   `advanceCursor` never backwards; restore sets cursor=0/initialPushDone=true.
4. **Wire — `AttendanceSyncRows.kt`**: explicit `@SerialName` columns matching
   migration 0020; `wireBodyOf` drops server-owned `revision`/`updated_at`;
   enums resolve-or-null; `toEntity` clips mask to planned units; accounts
   settings checkpoint by `fingerprint`.
5. **Transport — falsified last code hypothesis**: postgrest-kt 3.0.3 bytecode
   (decompiled from the shipped runtime jar) emits `Accept-Profile: <schema>` on
   GET/HEAD and `Content-Profile: <schema>` otherwise when the schema is
   non-blank, so `from("attendance", …)` reaches PostgREST per-request exactly as
   the raw-curl probes did.
6. **Live production probes**: fresh anonymous JWT accepted on `attendance` —
   pull 200 `[]`, insert 201, same-uid read-back returned the row (sequence live
   at revision 2948); second anonymous JWT read `[]` (cross-account RLS
   isolation confirmed); bogus-schema probe returns PGRST106 listing exposed
   schemas `public, graphql_public, community, attendance` (**attendance exposed
   now**); publishable-key-only request → `42501` (auth required, as designed).
7. **DAO surface — `Daos.kt`**: per-table `all()/dirty()/clearDirtyIfUnchanged/
   cloudIdentities()` present; no sync-side stub. Non-sync writers of attendance
   (`BackupRepository`, `AttendanceRepository:705 patternDao.insertAll`,
   `RolloverRepository`, `SeedScreen`) do not assign cloud ids, so a weeks-old
   device's rows are all `cloudId=null` and the initial upload assigns clean ids.

## Root cause

Pre-fix production → `pulls.any { !it.reachable }` → `SyncPassResult.Unreachable`
→ account UI "Couldn't sync just now. Try again later." The gap is recorded in
memory ("exposed-schemas step pending", 2026-09-15) and is now resolved.

## Fix

Configuration, already applied. No code change. If a device still fails today,
capture `AttendanceSyncDiag` logcat — the per-operation log lines are already
present in the engine and API.

## Two-device verification

1. A (≥2 weeks data) → link GitHub → Sync now → initial push
   (semesters→courses→patterns→sessions, FK order) → `markInitialPushDone`.
2. B fresh → sign in same GitHub → link → Sync now → pull from floor 0, apply
   all rows, empty push, `markInitialPushDone`.
3. Edit/delete on A, Sync now; Sync now on B → rows/tombstones converge (LWW).
4. Different account on B → refusal, no local mutation. Admin-deleted account →
   quarantine, data preserved.