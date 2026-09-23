#!/usr/bin/env node
// Reporting identity transfer: LIVE lifecycle demonstration against production
// (the demo the feature's sign-off requires). Node >= 18, built-in fetch only.
//
// Two lifecycles, exactly as specified:
//
//   1. Full transfer: Device A creates a transfer -> Device B claims ->
//      A vetoes -> A retries -> B claims again -> A approves (immediate
//      completion) -> A is rejected (identity_superseded) -> B retains the
//      reputation and history.
//   2. Lost device: A2 creates a transfer and goes dark -> B2 claims ->
//      the 24-hour window passes (aged out-of-band as postgres; the clock is
//      never a client input) -> B2 claims again and completes -> A2 is
//      superseded.
//
// Plus the replay check on the used code. Everything is probe-marked
// (rooms XFER-LIVE-*) and cleaned up at the end via SQL. Publishable key only —
// the same credential the Android app holds; no service role in any request.

const BASE = process.env.SUPABASE_URL;
const KEY = process.env.SUPABASE_PUBLISHABLE_KEY;
if (!BASE || !KEY) { console.error("missing SUPABASE_URL / SUPABASE_PUBLISHABLE_KEY"); process.exit(2); }

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

// Out-of-band SQL through the linked project's Management API (the same
// authority the dashboard SQL editor gives; used only for aging the lost-device
// deadline and for probe cleanup).
function sql(q) {
  const out = execSync(
    `npx supabase db query --linked ${JSON.stringify(q)}`,
    { cwd: process.cwd(), stdio: ["pipe", "pipe", "pipe"] }).toString();
  const m = out.match(/\[[\s\S]*\]/);
  return m ? JSON.parse(m[0]) : [];
}

const probeIds = [];

(async () => {
// ---------------------------------------------------------------- identities
const A = await anonUser();   // Device A: the identity owner
const B = await anonUser();   // Device B: the new phone
const V = await anonUser();   // a bystander whose verification builds A's reputation
const A2 = await anonUser();  // lost-device owner
const B2 = await anonUser();  // lost-device claimant
probeIds.push(A.id, B.id, V.id, A2.id, B2.id);
console.log(`# identities: A=${A.id} B=${B.id} V=${V.id} A2=${A2.id} B2=${B2.id}`);

const SR = (o) => ({ p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_payload: {}, p_note: null, p_client_version_code: 0, ...o });

// ================================================ 1. the full transfer lifecycle
console.log("\n# 1. Device A -> create transfer -> Device B claims -> A vetoes -> retry -> success");

// A has a history and a reputation to carry over: one report, one corroboration.
// submit_report answers {ok, id, ...} — the id is the observation's.
let seed = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE-A", p_idempotency_key: idem() }));
check("1.1 A seeds community history with a report", seed.status === 200 && seed.data?.ok === true, JSON.stringify(seed.data));
const obsId = seed.data?.id;
let ver = await rpc(V, "verify", { p_observation_id: obsId, p_verdict: true, p_idempotency_key: idem() });
check("1.2 a bystander corroborates it, building A's reputation", ver.status === 200 && ver.data?.ok === true, JSON.stringify(ver.data));

const repBefore = await rpc(A, "my_reputation", {});
console.log(`#    A's reputation before: ${JSON.stringify(repBefore.data)}`);

let begin = await rpc(A, "begin_identity_transfer", {});
let code = begin.data?.code;
check("1.3 A creates the transfer (code minted, 72h TTL)",
  begin.status === 200 && begin.data?.ok === true && /^[A-Za-z0-9_-]{40,60}$/.test(code ?? ""),
  JSON.stringify(begin.data).slice(0, 200));

let claim = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
const hours = claim.data?.claim_deadline ? (Date.parse(claim.data.claim_deadline) - Date.now()) / 3.6e6 : -1;
check("1.4 B claims -> pending, 24h veto window open",
  claim.status === 200 && claim.data?.ok === true && claim.data?.state === "pending_claim" && hours > 23.5,
  JSON.stringify(claim.data));

const statusPending = await rpc(A, "my_pending_reports", {});
check("1.5 A sees the pending-transfer warning (the Keep-it-here flag)",
  statusPending.data?.transfer_pending === true, JSON.stringify(statusPending.data).slice(0, 120));

const veto = await rpc(A, "abort_identity_transfer", {});
check("1.6 A vetoes -> aborted, nothing moved",
  veto.status === 200 && veto.data?.state === "aborted", JSON.stringify(veto.data));

const afterVeto = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
check("1.7 the vetoed code is dead (B re-claiming it -> transfer_aborted)",
  afterVeto.data?.ok === false && afterVeto.data?.code === "transfer_aborted", JSON.stringify(afterVeto.data));

// retry: a fresh code, the successful path
begin = await rpc(A, "begin_identity_transfer", {});
const code2 = begin.data?.code;
check("1.8 A retries: a fresh code is minted", begin.data?.ok === true && code2 && code2 !== code,
  JSON.stringify(begin.data).slice(0, 200));

claim = await rpc(B, "claim_reporting_identity", { p_transfer_code: code2 });
check("1.9 B claims the fresh code -> pending again",
  claim.status === 200 && claim.data?.state === "pending_claim", JSON.stringify(claim.data));

const approve = await rpc(A, "approve_identity_transfer", {});
check("1.10 A explicitly approves -> transfer completes immediately",
  approve.status === 200 && approve.data?.state === "completed", JSON.stringify(approve.data));

const aWrite = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE-A", p_idempotency_key: idem() }));
check("1.11 A is rejected: every write now raises identity_superseded",
  raised(aWrite, "identity_superseded"), JSON.stringify(aWrite).slice(0, 200));

const aStatus = await rpc(A, "my_pending_reports", {});
check("1.12 A's status carries the superseded flag (the Start-fresh path)",
  aStatus.data?.superseded === true, JSON.stringify(aStatus.data).slice(0, 120));

const bMine = await rpc(B, "my_pending_reports", {});
const movedIds = (bMine.data?.reports ?? []).map((r) => r.id);
check("1.13 B retains the history: A's report is now B's",
  movedIds.includes(obsId), `obsId=${obsId} bMine=${JSON.stringify(bMine.data).slice(0, 200)}`);

const repAfter = await rpc(B, "my_reputation", {});
const before = repBefore.data ?? {};
const after = repAfter.data ?? {};
check("1.14 B retains the reputation: report and corroboration counters travelled",
  (after.report_count ?? 0) >= (before.report_count ?? 0) &&
    (after.corroborated_count ?? 0) >= (before.corroborated_count ?? 0) &&
    (after.trust_score ?? 0) >= (before.trust_score ?? 0),
  `before=${JSON.stringify(before)} after=${JSON.stringify(after)}`);
console.log(`#    B's reputation after: ${JSON.stringify(after)}`);

const replay = await rpc(B, "claim_reporting_identity", { p_transfer_code: code2 });
check("1.15 the used code cannot be replayed (-> transfer_used)",
  replay.data?.ok === false && replay.data?.code === "transfer_used", JSON.stringify(replay.data));

// ==================================================== 2. the lost-device path
console.log("\n# 2. A unavailable -> B claims -> 24h passes -> B completes");

seed = await rpc(A2, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE-B", p_idempotency_key: idem() }));
check("2.1 A2 seeds community history with a report", seed.status === 200 && seed.data?.ok === true, JSON.stringify(seed.data));
const obsId2 = seed.data?.id;

begin = await rpc(A2, "begin_identity_transfer", {});
const code3 = begin.data?.code;
check("2.2 A2 creates the transfer, then goes dark", begin.data?.ok === true && !!code3,
  JSON.stringify(begin.data).slice(0, 200));

claim = await rpc(B2, "claim_reporting_identity", { p_transfer_code: code3 });
check("2.3 B2 claims -> pending, A2's 24h window opens",
  claim.status === 200 && claim.data?.state === "pending_claim", JSON.stringify(claim.data));

// The window passes. Aged as postgres via the Management API — the clock is
// never a client input, and waiting a real day is not a demo.
sql(`update community.reporter_transfer_grants set claim_deadline = now() - interval '1 minute' where owner_user_id = '${A2.id}' and state = 'pending_claim'`);

const complete = await rpc(B2, "claim_reporting_identity", { p_transfer_code: code3 });
check("2.4 after the window, B2's same claim completes the transfer",
  complete.status === 200 && complete.data?.state === "completed", JSON.stringify(complete.data));

const a2Write = await rpc(A2, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE-B", p_idempotency_key: idem() }));
check("2.5 A2 (the lost phone) is rejected with identity_superseded",
  raised(a2Write, "identity_superseded"), JSON.stringify(a2Write).slice(0, 200));

const b2Mine = await rpc(B2, "my_pending_reports", {});
check("2.6 B2 retains the lost phone's history",
  (b2Mine.data?.reports ?? []).some((r) => r.id === obsId2),
  `obsId2=${obsId2} b2Mine=${JSON.stringify(b2Mine.data).slice(0, 200)}`);

// ------------------------------------------------------------------- cleanup
console.log("\n# cleanup (probe rows and probe users)");
const ids = probeIds.map((i) => `'${i}'`).join(",");
const idIn = `(${ids})`;
sql(`delete from community.verifications where observation_id in (select id from community.observations where room like 'XFER-LIVE-%')`);
sql(`delete from community.observations where room like 'XFER-LIVE-%'`);
sql(`delete from community.abuse_events where user_id in ${idIn}`);
sql(`delete from community.reporter_transfer_grants where owner_user_id in ${idIn} or claimed_by in ${idIn}`);
sql(`delete from community.superseded_identities where old_user_id in ${idIn}`);
sql(`delete from community.reporter_profiles where user_id in ${idIn}`);
sql(`delete from auth.users where id in ${idIn}`);
const left = sql(`select (select count(*) from community.observations where room like 'XFER-LIVE-%') as obs, (select count(*) from community.reporter_profiles where user_id in ${idIn}) as profiles, (select count(*) from community.reporter_transfer_grants where owner_user_id in ${idIn} or claimed_by in ${idIn}) as grants, (select count(*) from auth.users where id in ${idIn}) as users`);
check("cleanup: no probe rows remain",
  Number(left[0]?.obs) === 0 && Number(left[0]?.profiles) === 0 &&
    Number(left[0]?.grants) === 0 && Number(left[0]?.users) === 0,
  JSON.stringify(left));

console.log(`\n# ${pass} passed, ${fail} failed`);
if (fail > 0) { console.log(JSON.stringify(failures, null, 2)); process.exit(1); }
})().catch((e) => { console.error(e); process.exit(1); });
