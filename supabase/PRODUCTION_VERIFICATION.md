# Manual production verification plan (Part 15)

Everything below runs against **production**, after the remediation SQL from
Part 2 has been applied and migrations 0008–0010 are in. The two-session
Realtime test and the production probe steps are the only items that submit
real data — each is a controlled test with probe-marked rooms, per the
project's execution rules. Record every result in the verification log with
timestamps and row ids.

## 0. Preconditions (no data written)

| # | check | how | pass |
|---|---|---|---|
| 0.1 | remediation revoked the 20260908130016 breach | dashboard SQL editor: `select has_table_privilege('anon','community.observations','SELECT');` | `f` (column grants must remain: `has_any_column_privilege(...)` = `t`) |
| 0.2 | no client-role write privileges | `select relname, has_table_privilege('authenticated', c.oid,'INSERT'), has_table_privilege('authenticated',c.oid,'UPDATE'), has_table_privilege('authenticated',c.oid,'DELETE') from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='community' and relkind='r';` | all `f` |
| 0.3 | RPC allow-list | `select p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='community' and (has_function_privilege('authenticated',p.oid,'execute') or has_function_privilege('anon',p.oid,'execute')) order by 1;` | exactly: abort_identity_transfer, approve_identity_transfer, begin_identity_transfer, cast_vote, claim_reporting_identity, create_poll, is_authenticated, my_pending_reports, my_polls, my_reputation, poll_is_readable, poll_results, require_profile, submit_report, undo_poll, undo_report, verify, withdraw_poll, withdraw_report |
| 0.4 | schema version | `select value from community.app_meta where key='community_schema_version';` | `3` |
| 0.5 | app carries no secrets | inspect the release APK's `BuildConfig` fields | only `SUPABASE_URL` + `SUPABASE_PUBLISHABLE_KEY` |

## 1. Production REST probes (controlled tests)

Run `supabase/probes/` scripts with production URL/key (edit the hosts at the
top of each file first). Each writes one report and one poll in probe-marked
rooms (`RT-PROBE*` / `RT-OWNER`) — the explicitly-allowed controlled tests.

| # | check | pass |
|---|---|---|
| 1.1 | `node rt-privacy-probe.js` | `VERDICT=CLEAN`; events: observations INSERT + UPDATE, polls INSERT; no reporter_id/creator_id/idempotency_key in any record; refused subscriptions on verifications/poll_votes |
| 1.2 | delete a probe row via the dashboard, with `node rt-delete-probe.js` watching | DELETE events carry `old_record` = `{id}` only |
| 1.3 | `node rt-owner-probe.js` | `VERDICT=CLEAN`; owner sees their own withdrawal UPDATE, granted columns only |
| 1.4 | stranger column probe (curl) | `GET /rest/v1/observations?select=reporter_id` → 42501 permission denied |
| 1.5 | stranger private tables | `GET /rest/v1/verifications`, `/poll_votes`, `/reporter_profiles` → `[]` |
| 1.6 | audit probe cleanup | `delete from community.observations where room like 'RT-%'; delete from community.polls where room like 'RT-%';` — note the deleted row ids (incl. the earlier controlled probe a9f4a40c…, room PROBE-SEC) in the log |

## 2. Two-session Realtime test (the pending item)

Phone A (reporter) and phone B (watcher), both with the release APK:

| # | step | pass |
|---|---|---|
| 2.1 | B sits on the Rooms tab (Realtime channel open); A submits a report for the room B is viewing | B sees the report appear without a refresh, within ~10 s |
| 2.2 | A withdraws it (My Reports → Withdraw) | B's copy disappears (withdrawn rows leave the active read set) |
| 2.3 | A submits again; B kills the app and reopens it (channel drop) | after reopen + snapshot fetch, B sees A's report (snapshot is the recovery path) |
| 2.4 | A verifies B's report (B's report, A taps Verify) | the card's count updates on B's screen |

## 3. App behavior (two devices or one, no server data harmed)

| # | check | pass |
|---|---|---|
| 3.1 | offline submit | airplane mode → submit a report → "Waiting to send"; network back → sends within seconds (not 30) |
| 3.2 | undo window | submit → Undo offered for 60 s → tap Undo → row reads "Taken back", no reputation change visible |
| 3.3 | withdraw | submit, wait >60 s → Withdraw → row reads "Withdrawn"; server trust −3 exactly once (retry changes nothing) |
| 3.4 | My Polls | create a poll → appears in Settings → My polls; Undo within 60 s → "Taken back"; Withdraw after → "Withdrawn" |
| 3.5 | own-report verify block | your own report's card shows no verify buttons; a curl `verify` with your own token returns `own_report` |
| 3.6 | own-poll vote block | your own poll's card lets you vote? no — server returns `own_poll` if forced via curl |
| 3.7 | restart persistence | kill app, reopen → My Reports/My Polls intact; Room v5 auto-migration does not lose attendance data (fresh install from backup → upgrade path if available) |

## 3b. Reporting identity transfer (0015 — live-verified 2026-09-11)

`supabase/tests/reporter_transfer_live_demo.mjs` ran against production on
2026-09-11 (22/22 checks, probe-marked rooms `XFER-LIVE-*`, all probe rows and
probe users cleaned up afterwards). Both lifecycles it demonstrated:

| # | check | pass |
|---|---|---|
| 3b.1 | full transfer: A begins → B claims (24h window, A sees the pending flag) → A aborts → the code is dead (`transfer_aborted`) → A begins again → B claims → A approves → completed | yes |
| 3b.2 | the displaced owner: every A write raises `identity_superseded`; A's status carries `superseded` (the Start-fresh path) | yes |
| 3b.3 | B retains history and reputation (report count and corroborations travelled; A's observation is B's) | yes |
| 3b.4 | the used code cannot be replayed (`transfer_used`) | yes |
| 3b.5 | lost device: A2 begins and goes dark → B2 claims → 24h passes (deadline aged as postgres; the clock is never a client input) → B2's same claim completes → A2 superseded | yes |
| 3b.6 | in-app (two devices): the Settings → Reporting identity screen's export/import flow, the pending-transfer Keep/Let-it-move banner, and the superseded Start-fresh dialog | yes — user-verified on devices 2026-09-11 |

## 3c. Identity replacement, hard delete (0017 — live-verified 2026-09-11)

`supabase/tests/reporter_transfer_live_demo_replacement.mjs` ran against
production on 2026-09-11 (34/34 checks, probe-marked rooms/polls
`XFER-LIVE3-*`, all probe rows and probe users cleaned up afterwards;
rehearsed against the local stack first, 34/34). Migrations 0017 (replacement)
and 0018 (`obs_dedup_unique` target-slot fix — a pre-existing 0013 regression
where two future claims about different slots collided as `duplicate_report`)
were pushed and verified: migration history 0018 after 0017, the index rebuilt
with `COALESCE(target_start_hour, -1)`, no duplicate dedup index, the 19-RPC
execute allow-list and every RLS policy count unchanged.

The scenario: A holds an identity with history and reputation; B holds a
*different* identity with its own report, its own poll, a vote on a bystander's
poll, and an armed export code of its own. B imports A's `.atid` — the
unconfirmed claim is refused (`identity_not_fresh`), the destructive
confirmation carries `p_replace_claimant: true` — then A approves.

| # | check | pass |
|---|---|---|
| 3c.1 | the unconfirmed claim is refused with `identity_not_fresh`; the confirmed replacement claim is accepted (pending, `replace_claimant`, 24h window) | yes |
| 3c.2 | during the window B's community write raises `identity_frozen` and does NOT cancel the move (grant still `pending_claim`); B's reads still work and its status carries `claim_pending` + `claim_replaces_identity` | yes |
| 3c.3 | A's approval completes with `replaced: true` | yes |
| 3c.4 | admin-side zero-row proof (Management API, not RLS-visible): B's original report, its own poll, its vote, and ALL its historical transfer grants (including the armed export code) are gone — real row deletion; exactly one profile remains under B's uid (A's, re-parented) | yes |
| 3c.5 | A's complete history/reputation is now B's: A's report and poll answer to B's uid; report count and corroborations travelled | yes |
| 3c.6 | A's original device is superseded: every write raises `identity_superseded`; status carries `superseded`; A's uid tombstoned exactly once, B's not at all | yes |
| 3c.7 | B continues using the identity (freeze lifted on completion — B writes successfully) | yes |
| 3c.8 | the old B identity is unrecoverable: B's own armed export code answers `transfer_unknown` (its grant was hard-deleted); the used A→B code answers `transfer_used` | yes |
| 3c.9 | no partial state: every zero-row count is its exact expected value (one profile, zero B-owned rows, zero B grants, one tombstone, one completed grant) | yes |
| 3c.10 | in-app (two devices): the destructive confirmation dialog and the frozen banner on the claimant's phone | yes — user-verified on devices 2026-09-11 |

## 3d. Android backup and data integrity (device-verified)

The backup format itself (`docs/backup-format.md`, format v2, frozen) is
pinned by the JVM suites: round-trip integrity, the v1→v2 migration, checksum
canonicalisation, all ten rejection categories, restore-plan rewriting,
safety-snapshot behaviour and the backup-exclusion guards — 1,114 tests green
at the time of writing. What only a phone can prove is the parts Android
owns: the file picker, Clear storage, uninstall, Auto Backup's restricted-mode
agent and the process restart of Clear all Attendo data. Those were walked
through by hand, one phone, airplane mode acceptable throughout.

Builds: debug APK 1.1 (2). The sequence ran on SHA-256 `66ac0d48…fe557`;
the two checks the fixes below touched re-ran on `468497ed…11d25`. Both were
artifact-scanned and carry the production Supabase URL and publishable key
only — no service-role or secret material. Device: Moto G52 (rhode), Android
16; run date: 12 September 2026.

| # | check | pass |
|---|---|---|
| 3d.1 | export → system Clear storage → import: the full pre-export fingerprint (attendance incl. fractional and missed, the reschedule pair, archived course, retired-slot label, per-course targets, name, holiday, working Saturday, personal basis + joined date) reproduced exactly; first-run state confirmed after the clear; preview facts matched before Replace | yes |
| 3d.2 | restore over materially different data is replace-only: the incoming set complete, zero B-only courses/classes/settings surviving, totals the incoming set's — no merge | yes |
| 3d.3 | checksum-tampered copy refused: no preview, no partial write, existing data unchanged afterwards | yes |
| 3d.4 | the v1 golden fixture (`fixtures/backup/v1-valid.json`) imported through the normal SAF flow; preview showed the format-1 upgrade note; the migration derived the Odd 2026 semester and restored the archived course as archived | yes |
| 3d.5 | the future-version fixture (formatVersion 3) refused; existing data untouched | yes |
| 3d.6 | uninstall/reinstall: a genuine first run (no cloud restore offered — the toggle defaults off), the undo snapshot gone with the install, Downloads files intact; a fresh import reproduced the fingerprint | yes |
| 3d.7 | Auto Backup via `bmgr`: toggle-on `backupnow` succeeded; `pm clear` + `bmgr restore` returned the data with the Automatic-backup toggle and appearance preserved and the community reporting identity **absent**; a toggle-off `backupnow` stored nothing (the framework reports a package that contributes nothing as a Transport error), and restoring afterwards returned the prior dataset, not the toggle-off edits | yes |
| 3d.8 | Clear all Attendo data: process restart, first-run state, the Automatic-backup toggle and appearance preserved by design, community identity cleared, the Downloads file untouched | yes |
| 3d.9 | (fixed build) removing a timetable slot with marked history retires it and the row leaves the editor at once; the course detail keeps it with its until-date; the retired slot survives export → clear → restore | yes |
| 3d.10 | (fixed build) refusals name their fault family: the damaged file and the newer-version file each show their own reason and remedy, and the wrong-file message is unchanged | yes |

Two defects were found and fixed by this round, both re-verified on the
second build: the editor listed retired slots as if they still ran, so
removing a slot with history looked like a no-op (3d.9); and every refusal
shared one message, leaving a damaged backup and a future-format backup
indistinguishable dead ends (3d.10).

Community-identity exclusion, the non-negotiable boundary of the format, was
checked at all three places it could leak: the exported file's text (no token
or identity material anywhere in the payload), after an Auto Backup restore
(absent), and after Clear all Attendo data (cleared).

Recorded as not performed: device-to-device transfer. It needs a second
device; its rules are the same XML include-lists the cloud path exercised and
inspection pinned (`data_extraction_rules.xml`, `device-transfer` block, which
omits `attendo-community.xml` in both directions).

## 4. Sign-off

All of 0–3 pass, plus: full local test suites green at the recorded commits
(191 pgTAP in 7 files + 2 adversarial probe suites + 1227 Android tests),
security review complete (Parts 10–13), no critical/high issue open — then,
and only then, the project is production-ready.

Known accepted items (not blockers, documented): the
`is_authenticated` search_path warning is closed by 0009's pinned path;
`my_reputation` is executable but unused by the app (own-data only, harmless).
Realtime DELETE events carry only the primary key by platform design
("RLS is not applied to deletes") — verified live, no identity leak.
