#!/usr/bin/env node
// Identity status signal chain — the server half of the live-update fix.
//
// The app now polls my_pending_reports every 10s while the reporting identity
// screen is foregrounded, and turns a witnessed transition into a notice:
//   * the owner's completed-vs-cancelled split comes from `superseded`;
//   * the claimant's completed-vs-cancelled split comes from diffing the
//     `reports[].id` set between polls (finalize_transfer re-parents rows to
//     the claimant on completion; a cancelled claim leaves the set alone).
//
// This script walks every stage that poll can land on, against the local dev
// stack, and asserts the flags and report-id sets are exactly what those
// notices were written against. Three lifecycles:
//
//   1. Fresh claimant, owner approves: A's flags go transfer_pending ->
//      superseded; B's report-id set goes empty -> A's report ids.
//   2. Fresh claimant, owner's proof-of-life write cancels: A's flags go
//      transfer_pending -> neither; B's report-id set is unchanged.
//   3. Replacing claimant (B already has an identity): B sees
//      claim_replaces_identity, is frozen mid-window, and on completion the
//      old identity's rows are gone while A's arrive.
//
// Publishable key only — the same credential the Android app holds. Probe
// rooms are XFER-SIG-*; probe users and rows are cleaned up at the end.

const BASE = process.env.SUPABASE_URL || "http://127.0.0.1:54321";
const KEY = process.env.SUPABASE_PUBLISHABLE_KEY;
if (!KEY) { console.error("missing SUPABASE_PUBLISHABLE_KEY"); process.exit(2); }

import { execSync } from "node:child_process";

const REST = `${BASE}/rest/v1`;
const H_ANON = {
  apikey: KEY, Authorization: `Bearer ${KEY}`,
  "Content-Type": "application/json",
  "Accept-Profile": "community", "Content-Profile": "community",
};

let pass = 0, fail = 0; const failures = [];
function check(name, ok, detail) {
  if (ok) { pass++; console.log(`ok ${pass + fail} - ${name}`); }
  else { fail++; failures.push({ name, detail }); console.log(`not ok ${pass + fail} - ${name}\n  ${detail}`); }
}

async function anonUser() {
  const res = await fetch(`${BASE}/auth/v1/signup`, {
    method: "POST", headers: { apikey: KEY, "Content-Type": "application/json" }, body: "{}",
  });
  const d = await res.json();
  return { token: d.access_token, id: d.user?.id };
}

const idem = () => crypto.randomUUID();
const auth = (u) => ({ ...H_ANON, Authorization: `Bearer ${u.token}`, apikey: KEY });

async function rpc(u, fn, body) {
  const res = await fetch(`${REST}/rpc/${fn}`, {
    method: "POST", headers: u === null ? H_ANON : auth(u),
    body: JSON.stringify(body ?? {}),
  });
  return { status: res.status, data: await res.json().catch(() => null) };
}

// Raised PL/pgSQL exceptions (errcode 28000) cross PostgREST as HTTP 403 with
// { code: "28000", message: "<the raised text>" }.
const raised = (r, text) =>
  (r.status === 403 || r.status === 400) && String(r.data?.code) === "28000" &&
  String(r.data?.message ?? "").includes(text);

// The app's IdentityStatus: the flags it reads plus the report-id set it diffs.
async function status(u) {
  const r = await rpc(u, "my_pending_reports", {});
  const d = r.data ?? {};
  return {
    ok: d.ok === true,
    reports: (d.reports ?? []).map((x) => x.id).sort(),
    transferPending: d.transfer_pending === true,
    superseded: d.superseded === true,
    claimPending: d.claim_pending === true,
    claimReplacesIdentity: d.claim_replaces_identity === true,
    transferClaimDeadline: d.transfer_claim_deadline ?? null,
    claimDeadline: d.claim_deadline ?? null,
    raw: JSON.stringify(d).slice(0, 200),
  };
}

// The server's now (parsed from the same read) minus a moment, for deadline math.
const TOLERANCE_MIN = 2;

const SR = (o) => ({ p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_payload: {}, p_note: null, p_client_version_code: 0, ...o });

// Out-of-band SQL through the local stack's own db container (probe cleanup).
function sql(q) {
  execSync(`docker exec supabase_db_attendo-community psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c ${JSON.stringify(q)}`,
    { stdio: ["pipe", "pipe", "pipe"] });
}

const probeIds = [];

(async () => {
// ==================================================== 1. fresh claim, approved
console.log("\n# 1. Fresh claimant -> owner approves -> the completed move, as two phones polling");
{
  const A = await anonUser();
  const B = await anonUser();
  probeIds.push(A.id, B.id);

  const seed = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-SIG-1", p_idempotency_key: idem() }));
  const obsA = seed.data?.id;
  check("1.1 A seeds a report", seed.status === 200 && seed.data?.ok === true, JSON.stringify(seed.data));

  const aBase = await status(A);
  check("1.2 A's baseline: nothing pending, nothing superseded, one report",
    aBase.ok && !aBase.transferPending && !aBase.superseded && !aBase.claimPending &&
      JSON.stringify(aBase.reports) === JSON.stringify([obsA]),
    aBase.raw);

  const bBase = await status(B);
  check("1.3 B's baseline: fresh identity, empty report set",
    bBase.ok && !bBase.claimPending && bBase.reports.length === 0,
    bBase.raw);

  const begin = await rpc(A, "begin_identity_transfer", {});
  const code = begin.data?.code;
  check("1.4 A begins the transfer", begin.status === 200 && begin.data?.ok === true && !!code,
    JSON.stringify(begin.data).slice(0, 150));

  // The poll on A after beginning. The grant is still 'armed' (nobody has
  // claimed), and transfer_pending only tracks 'pending_claim' — so A's poll
  // is quiet here by design: the export confirmation owns this moment locally,
  // and the waiting banner begins at the claim, which is what 1.8 pins.
  const aBegun = await status(A);
  check("1.5 A's poll between export and claim: nothing pending yet (grant is armed)",
    aBegun.ok && !aBegun.transferPending && !aBegun.superseded &&
      JSON.stringify(aBegun.reports) === JSON.stringify([obsA]),
    aBegun.raw);

  // The poll on B before claiming: nothing to see yet.
  const bBeforeClaim = await status(B);
  check("1.6 B's poll before claiming sees nothing",
    !bBeforeClaim.claimPending && !bBeforeClaim.claimReplacesIdentity && bBeforeClaim.reports.length === 0,
    bBeforeClaim.raw);

  const claim = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
  check("1.7 B claims -> pending window opens",
    claim.status === 200 && claim.data?.ok === true && claim.data?.state === "pending_claim",
    JSON.stringify(claim.data));

  // Both phones poll while the move waits. Reading must not cancel anything,
  // and the sets must be stable — a jittery set would fake a transition.
  const aPending1 = await status(A);
  const bPending1 = await status(B);
  const bPending2 = await status(B);
  check("1.8 while it waits: A polls transfer_pending, B polls claim_pending (plain claim)",
    aPending1.transferPending && !aPending1.superseded &&
      bPending1.claimPending && !bPending1.claimReplacesIdentity,
    `A=${aPending1.raw}\n  B=${bPending1.raw}`);
  check("1.9 consecutive polls return identical report-id sets (no false transitions)",
    JSON.stringify(bPending1.reports) === JSON.stringify(bPending2.reports) && bPending1.reports.length === 0,
    `${JSON.stringify(bPending1.reports)} vs ${JSON.stringify(bPending2.reports)}`);

  // 0019: the same reads say WHEN the window ends — the owner's deadline and
  // the claimant's are the same grant's claim_deadline, ~24h out, and both are
  // null once no wait is live.
  const ownerDeadline = Date.parse(aPending1.transferClaimDeadline);
  const claimantDeadline = Date.parse(bPending1.claimDeadline);
  const hoursOut = (t) => (t - Date.now()) / 3.6e6;
  check("1.10 while it waits: both sides see the deadline, about 24 hours out",
    Number.isFinite(ownerDeadline) && Number.isFinite(claimantDeadline) &&
      Math.abs(hoursOut(ownerDeadline) - 24) < TOLERANCE_MIN / 60 &&
      Math.abs(hoursOut(claimantDeadline) - 24) < TOLERANCE_MIN / 60,
    `owner=${aPending1.transferClaimDeadline} claimant=${bPending1.claimDeadline}`);

  const approve = await rpc(A, "approve_identity_transfer", {});
  check("1.11 A approves -> move completes",
    approve.status === 200 && approve.data?.state === "completed",
    JSON.stringify(approve.data));

  // The two notices the app derives from exactly these reads.
  const aAfter = await status(A);
  check("1.12 A's poll after: pending cleared, superseded set (the identity-has-moved signal)",
    !aAfter.transferPending && aAfter.superseded,
    aAfter.raw);

  const bAfter = await status(B);
  check("1.13 B's poll after: claim cleared, A's report has arrived (the move-completed signal)",
    !bAfter.claimPending && JSON.stringify(bAfter.reports) === JSON.stringify([obsA]),
    `obsA=${obsA}\n  ${bAfter.raw.slice(0, 160)}`);
  check("1.14 once no wait is live, both deadlines are null",
    aAfter.transferClaimDeadline === null && bAfter.claimDeadline === null,
    `owner=${aAfter.transferClaimDeadline} claimant=${bAfter.claimDeadline}`);
}

// ============================================ 2. fresh claim, owner stays alive
console.log("\n# 2. Fresh claimant -> owner's proof-of-life write cancels -> the cancelled move");
{
  const A2 = await anonUser();
  const B2 = await anonUser();
  probeIds.push(A2.id, B2.id);

  const seed = await rpc(A2, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-SIG-2", p_idempotency_key: idem() }));
  const obsA2 = seed.data?.id;
  check("2.1 A2 seeds a report", seed.status === 200 && seed.data?.ok === true, JSON.stringify(seed.data));

  const begin = await rpc(A2, "begin_identity_transfer", {});
  const claim = await rpc(B2, "claim_reporting_identity", { p_transfer_code: begin.data?.code });
  check("2.2 B2 claims -> pending window opens",
    claim.status === 200 && claim.data?.state === "pending_claim",
    JSON.stringify(claim.data));

  const bPending = await status(B2);
  check("2.3 B2's poll: claim_pending, empty report set",
    bPending.claimPending && bPending.reports.length === 0,
    bPending.raw);

  // The owner uses their phone — any write cancels the pending move. (A
  // distinct room, or the dedup guard refuses it as a duplicate of the seed.)
  const vetoWrite = await rpc(A2, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-SIG-2B", p_idempotency_key: idem() }));
  check("2.4 A2's own write succeeds (proof of life)", vetoWrite.status === 200 && vetoWrite.data?.ok === true,
    JSON.stringify(vetoWrite.data).slice(0, 150));

  const a2After = await status(A2);
  check("2.5 A2's poll after: pending cleared, NOT superseded (the cancelled-or-expired signal)",
    !a2After.transferPending && !a2After.superseded,
    a2After.raw);

  const b2After = await status(B2);
  check("2.6 B2's poll after: claim cleared, report-id set unchanged (the move-was-cancelled signal)",
    !b2After.claimPending && b2After.reports.length === 0 &&
      JSON.stringify(b2After.reports) === JSON.stringify(bPending.reports),
    `before=${JSON.stringify(bPending.reports)} after=${JSON.stringify(b2After.reports)}`);
}

// =========================================== 3. replacing claim, owner approves
console.log("\n# 3. Replacing claimant (own identity) -> frozen mid-window -> completes into A's history");
{
  const A3 = await anonUser();
  const B3 = await anonUser();
  probeIds.push(A3.id, B3.id);

  const seedA3 = await rpc(A3, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-SIG-3", p_idempotency_key: idem() }));
  const obsA3 = seedA3.data?.id;
  check("3.1 A3 seeds a report", seedA3.status === 200 && seedA3.data?.ok === true, JSON.stringify(seedA3.data));
  const seedB3 = await rpc(B3, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-SIG-3B", p_idempotency_key: idem() }));
  const obsB3 = seedB3.data?.id;
  check("3.2 B3 already has an identity of its own", seedB3.status === 200 && seedB3.data?.ok === true,
    JSON.stringify(seedB3.data));

  const begin = await rpc(A3, "begin_identity_transfer", {});
  const claim = await rpc(B3, "claim_reporting_identity", { p_transfer_code: begin.data?.code, p_replace_claimant: true });
  check("3.3 B3 claims with replacement -> pending, flagged as a replacing claim",
    claim.status === 200 && claim.data?.ok === true && claim.data?.state === "pending_claim" && claim.data?.replace_claimant === true,
    JSON.stringify(claim.data));

  const b3Pending = await status(B3);
  check("3.4 B3's poll: claim_pending AND claim_replaces_identity, own reports still readable",
    b3Pending.claimPending && b3Pending.claimReplacesIdentity &&
      JSON.stringify(b3Pending.reports) === JSON.stringify([obsB3]),
    b3Pending.raw);

  // The freeze: mid-window, the claimant cannot land community writes.
  const frozenWrite = await rpc(B3, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-SIG-3B", p_idempotency_key: idem() }));
  check("3.5 B3's write during the window is refused with identity_frozen",
    raised(frozenWrite, "identity_frozen"),
    JSON.stringify(frozenWrite).slice(0, 150));

  const approve = await rpc(A3, "approve_identity_transfer", {});
  check("3.6 A3 approves -> move completes",
    approve.status === 200 && approve.data?.state === "completed",
    JSON.stringify(approve.data));

  const b3After = await status(B3);
  check("3.7 B3's poll after: claim cleared, the old identity's row is gone and A3's has arrived",
    !b3After.claimPending &&
      b3After.reports.includes(obsA3) && !b3After.reports.includes(obsB3),
    `obsA3=${obsA3} obsB3=${obsB3}\n  ${JSON.stringify(b3After.reports)}`);

  const a3After = await status(A3);
  check("3.8 A3's poll after: superseded set", a3After.superseded, a3After.raw);
}

// ------------------------------------------------------------------- cleanup
console.log("\n# cleanup (probe rows and probe users)");
{
  const ids = probeIds.map((i) => `'${i}'`).join(",");
  const idIn = `(${ids})`;
  sql(`delete from community.verifications where observation_id in (select id from community.observations where room like 'XFER-SIG-%')`);
  sql(`delete from community.observations where room like 'XFER-SIG-%'`);
  sql(`delete from community.abuse_events where user_id in ${idIn}`);
  sql(`delete from community.reporter_transfer_grants where owner_user_id in ${idIn} or claimed_by in ${idIn}`);
  sql(`delete from community.superseded_identities where old_user_id in ${idIn}`);
  sql(`delete from community.reporter_profiles where user_id in ${idIn}`);
  sql(`delete from auth.users where id in ${idIn}`);
  check("cleanup: ran without error", true, "");
}

console.log(`\n# ${pass} passed, ${fail} failed`);
if (fail > 0) { console.log(JSON.stringify(failures, null, 2)); process.exit(1); }
})().catch((e) => { console.error(e); process.exit(1); });
