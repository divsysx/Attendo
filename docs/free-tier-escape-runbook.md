# Free-Tier Escape Runbook — What to Do When Supabase Free Stops Being Enough

*Measured 2026-09-12 against project `sfstqlkspphdjgxstdmt` (ap-southeast-1, Free plan).*

## TL;DR

Every metric sits at 0.02–6% of quota. **Do nothing now.** Watch **Realtime messages / month** on the dashboard Usage page. When it crosses **400,000** (20% of the 2M quota), start optimizing — **C first, then B**. Only consider Pro ($25/mo) after both optimizations are landed and messages still exceed 1.6M/month. P2P stays shelved until post-optimization spend sustains ~$100/month.

---

## 1. Current readings (2026-09-12)

| Metric | Current | Act at (20%) | Quota (the wall) |
|---|---|---|---|
| Realtime messages / mo | 342 | **400,000** | 2,000,000 |
| Peak concurrent connections | 3 | **40** | 200 |
| DB CPU sustained | ~13% of one core | **40%** | shared 2 vCPU |
| Database size | 28 MB | **250 MB** (50%) | 500 MB → read-only |
| REST requests / day | ~900 typical | 500,000 | rate-capped/sec |
| Egress / mo | 22 MB | 1 GB | 5 GB |
| MAU | 10 | 10,000 | 50,000 |
| 429s / rate limits / errors | 0 since launch | — | — |

API requests since 2026-09-03 launch: 9,345 total (peak day 6,057 — the two-device verification day, not organic load). Top DB query by time is `community.sweep_expired()` at ~12ms × 1,011 calls. Index and table hit rates are both 1.00.

## 2. Why Realtime messages is the one metric that scales badly

Every community write bills once **per watching client**, and every watcher answers by re-reading the **whole campus**:

```
write (report/verify/vote/undo/withdraw)
  → trigger bumps community.activity_pulses   (one row, content-free)
  → 1 realtime message to EVERY connected client, campus-wide
  → each client refetches FULL active_observations + open_polls + my_polls
  → 2s REFETCH_FLOOR_MS coalesces bursts into one fetch

messages / month ≈ writes × concurrent subscribers
capacity today ≈ 300–500 DAU, or 40–60 concurrent community viewers
```

This is the only superlinear cost in the system — which is why it binds long before DB size, MAU, or egress. The design is deliberately correctness-first: the pulse table exists because RLS makes withdrawals invisible to direct subscriptions (a withdrawn report is unreadable to everyone but its owner, so the disappearance event itself was silently dropped). **Every optimization below must preserve that property.**

## 3. The escalation ladder

**Stage 1 — Do nothing (now).** Everything at 0.02–6%. Cost $0, effort none. Review Usage monthly (§9).

**Stage 2 — Optimize the realtime architecture.** Trigger: messages > 400k/mo ∨ connections > 40 ∨ DB CPU > 40%. Implement §5 in effort-to-impact order: C first, then B, then A only if per-fetch payloads are the remaining pain. All free; together they cut the superlinear term ~95%+. After C+B, 1,000 DAU becomes *plausible* on Free — a squeeze, not comfort.

**Stage 3 — Upgrade to Pro ($25/mo).** Trigger: after C+B, messages still > 1.6M/mo ∨ connections > 160. Pro multiplies every quota 2.5–16× (5M messages, 500 connections, 8 GB DB, 100 GB egress), removes the inactivity pause, adds daily backups. Upgrade when optimization is spent and numbers still climb — not as a substitute for it.

**Stage 4 — Beyond Pro.** Trigger: post-optimization spend trending past ~$100/mo. Enable Pro spend-cap overages ($2.50/1M messages, $10/1k connections) to smooth peaks; Team ($599/mo) only if an institution requires SSO/SOC2; only then reopen the P2P evaluation (~150M messages/month — far beyond 1,000 DAU).

## 4. What tripping a quota actually does

- **DB size > 500 MB is the only hard cliff** — the database goes **read-only immediately**; every write, vote, and identity mint fails. (Hence its act-at line sits at 50%, not 20%.)
- **Messages / connections / egress exceeded** → notification email + a Fair-Use grace period; service continues. Not a 3am outage, but execute the playbook that week, not eventually.
- **MAU has no overage path on Free** — a cliff at 5,000× current usage. If you ever approach it, the app has real users and $25/mo is not the hard part.
- **Inactivity pause (~1 week without traffic)** — pre-launch risk only; resumable from dashboard; a weekly app open prevents it.

## 5. The three optimizations (order: C → B → A)

### C — Gate the realtime channel to visible Community surfaces
*~80% fewer connections, 50–80% fewer messages · low effort · client-only*

Today the channel is process-lifetime: `CommunityViewModel.onShown()` subscribes once and the channel stays joined for the whole app life, even on Dashboard or a Course screen. Collect `RealtimeObservations.changes()` only while a Community surface is visible; the existing collector-cancels → `removeChannel` teardown does the rest.

- **Where:** `CommunityViewModel.kt` (channel lifecycle). `RealtimeObservations.kt` already tears down on collector exit — nothing there changes.
- **Safety:** snapshot-first discipline already covers the gap — `ACTIVATED` fires on every subscribe; the 30s resubscribe drumbeat (`REALTIME_RESUBSCRIBE_MS`) survives.
- **Watch:** Room detail currently updates while other screens sit on top (app-wide channel). Gating changes that to "updates while any Community surface is visible." Re-run the two-device probes in `supabase/probes`.
- **Debounce** re-subscribe a few seconds so screen transitions don't churn connections.

### B — Room/section-scoped pulses and snapshots
*~90–95% fewer messages · medium-high effort · schema + client*

A pulse today says "the observations table changed, campus-wide." Scope both the pulse and the refetch to the room the viewer is in:

1. **Schema:** extend `activity_pulses` from one row per table to one per (table, room[, section]); `pulse_row()` already runs per-row with the room in `NEW`.
2. **Channel:** subscribe with a room filter parameter instead of the table-wide `postgresChangeFlow`; free-tier budget stays one channel.
3. **Read path:** replace the unfiltered `active_observations` select with a room-parameterized RPC or view; RLS story unchanged.
4. **Open decision:** the all-rooms Community home feed — keep one coarse campus pulse (periodic refresh, no per-write fan-out) or aggregate room pulses on a timer.
5. **Verify:** two-device test both in-room and cross-room — disappearances in *other* rooms must still arrive as pulses to that room's subscribers only.

With ~30 rooms, a write reaches ~1/30th of subscribers; each refetch shrinks from campus-wide to a handful of rows (egress and DB CPU fall with it).

### A — Delta-based refetch
*80–95% of fetch bytes/CPU · **0% of messages** · medium effort*

Fetch only what changed since the last snapshot (cursor on `updated_at` or a change-log table) and merge client-side. Comes last: it shrinks each fetch's payload, not the count of fetches or messages — it pays off only after B has made snapshots room-sized and a busy room is still heavy.

- **The hard case is deletion:** withdrawals are exactly why the pulse table exists — a delta protocol needs tombstones so disappearances still arrive. Do not trade the 0012 guarantee for bandwidth.
- **Cursors must be server-clock based** — the codebase already distrusts device clocks (duplicate reconciliation leans on server time for the same reason).

**Not interchangeable:** A does nothing for connections or messages; C does nothing for payload size; only B moves every column at once — hence B leads on impact despite the most work.

## 6. Per-metric playbooks

**Realtime messages climbing** — (1) Confirm shape: healthy writes×viewers growth, or one client hammering (check logs for a single identity's refetch pattern). (2) Implement C; re-measure after two weeks. (3) If still climbing, implement B (one migration + client change + two-device verification). (4) Only then consider Pro — paying $25/mo to keep an O(writes × all clients) fan-out buys 2.5×, not a fix.

**Peak connections climbing** — C is the entire lever (B doesn't reduce connections). After C, debounce the resubscribe drumbeat so screen transitions don't churn. Pro's cap is 500 — cross 160 after C and Free is honestly outgrown.

**DB CPU / query latency climbing** — Known gap, index it first: `active_observations` runs two correlated count subqueries per row over `verifications`, which has an index on `user_id` but **none on `(observation_id, verdict)`**:
```sql
create index verifications_by_observation
  on community.verifications (observation_id, verdict);
```
Then B (shrinks each execution and their count). Check the `sweep_expired()` cron cadence if CPU is high at idle. If CPU stays > 40% after both, it's shared-instance saturation — Pro's dedicated compute, not more indexes.

**Database size climbing** — Retention is designed to self-bound this: `sweep_expired()` deletes verifications after 30 days, resolved observations and closed polls after 7, grants after 7, abuse events after 180. Steady-state ≈ one retention window of data — **growth outrunning that means a sweep has stopped; check `cron.job_run_details` first.** Watch WAL separately (80 MB today): fast-growing WAL with flat tables = replication slots holding dead tuples (`supabase inspect db replication-slots`). The 500 MB wall is the hard read-only cliff.

**MAU / egress climbing** — MAU is success, not risk (anonymous identities count; 50k is 5,000× current; no overage path anyway — if approached, the question is revenue, not quota). Egress is a shadow of the refetch architecture: B shrinks both; nothing else needs to touch egress directly.

**Inactivity pause** — pre-launch only. One weekly app open with a community surface visible prevents it; 10 MAU already keep the project alive post-launch; Pro removes it entirely.

## 7. Costs — what each rung buys

| | Free (now) | Pro | Team |
|---|---|---|---|
| Price/mo | $0 | $25 | $599 |
| Realtime messages | 2M | 5M | 5M |
| Peak connections | 200 | 500 | 500 |
| DB size | 500 MB | 8 GB | 8 GB |
| Egress | 5 GB | 100 GB | 100 GB |
| MAU | 50k | 100k | 100k |
| Backups | limited | daily | daily + PITR |
| Inactivity pause | yes (~1 wk) | no | no |
| Overage | none — grace period | $2.50/1M msgs · $10/1k conns | same, with spend cap |
| SSO / SOC2 | — | — | yes |

Team exists in this table for one reason: an institution buying Attendo and requiring SSO/SOC2 is the plausible non-metric trigger that jumps past Pro's value math.

## 8. Monitoring — five minutes a month

Free doesn't alert at 20%; the calendar is the alert:

- **Monthly, on the 1st (5 min):** dashboard → Usage, filtered to `sfstqlkspphdjgxstdmt`. Read six numbers: realtime messages, peak connections, DB size, egress, MAU, storage. Compare against §1.
- **After spike events:** new-campus launch, exam week, viral share / Play Store feature — these move writes × viewers simultaneously, the one scenario where a month is too long between readings.
- **Quarterly:** `supabase inspect db outliers` and `db-stats` — the query-performance view the Usage page doesn't show (the `verifications` index gap was found exactly there).
- **After each optimization lands:** re-run the two-device probes (`supabase/probes`) and re-baseline the trigger table — thresholds set against the old fan-out are stale the moment C or B ships.
- **The single number that matters most:** Realtime messages / month. The day it crosses **400,000**, open Stage 2 — not the month after.
