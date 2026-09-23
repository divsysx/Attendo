#!/usr/bin/env node
// Reporting identity transfer: HTTP adversarial validation for the Attendo
// community backend (migrations 0015 + 0017).
//
// Runs ONLY against the local Supabase dev stack. Node >= 18, built-in fetch
// only. Publishable key from the environment (SUPABASE_PUBLISHABLE_KEY /
// SUPABASE_URL) — the same values the Android app holds; no service role
// anywhere.
//
// Every check is adversarial: we attempt the attack (replay, races, copied
// .atid, stolen code, direct table access, helper RPCs, not-fresh claims,
// superseded-owner writes, unconfirmed replacement claims) and PASS only when
// the backend denies it with the documented envelope or error.
//
// Sections K and L (0017): destination identity replacement. K drives the full
// destructive path over HTTP and then verifies — via docker exec psql as
// superuser, the admin-side view RLS cannot hide — that the claimant's
// identity genuinely no longer exists anywhere (real row deletion), including
// their historical transfer grants. L proves the alternative outcomes: the
// unconfirmed claim is refused, the frozen claimant can neither write nor
// cancel, and an aborted replacement leaves them fully intact.
//
// The one thing REST cannot do is wait 24 hours. The lost-device path ages
// the claim deadline out-of-band via `docker exec psql` — the same technique
// phase4_rest_adversarial.mjs uses for restriction, and the only honest one:
// the clock is never a client input.

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

async function table(u, method, path, body) {
  const res = await fetch(`${REST}${path}`, {
    method, headers: u === null ? H_ANON : auth(u),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => null) };
}

// Raised PL/pgSQL exceptions (errcode 28000) cross PostgREST as HTTP 403
// (PostgREST classifies 28000 as an authorization failure) with
// { code: "28000", message: "<the raised text>" }.
const raised = (r, text) =>
  (r.status === 403 || r.status === 400) && String(r.data?.code) === "28000" &&
  String(r.data?.message ?? "").includes(text);

// Out-of-band SQL on the local dev stack (phase4's restriction trick).
function sql(q) {
  return execSync(
    `docker exec supabase_db_attendo-community psql -U postgres -d postgres -tAc ${JSON.stringify(q)}`,
    { stdio: ["pipe", "pipe", "pipe"] }).toString().trim();
}

// PostgREST 16: every non-DEFAULT parameter must be present.
const SR = (o) => ({ p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_payload: {}, p_note: null, p_client_version_code: 0, ...o });

(async () => {
// ---------------------------------------------------------------- identities
const A = await anonUser();   // Device A: the identity owner
const B = await anonUser();   // Device B: the legitimate claimant
const C = await anonUser();   // a competing claimant (a copied .atid)
const D = await anonUser();   // another copy holder, kept fresh until late
console.log(`# identities: A=${A.id} B=${B.id} C=${C.id} D=${D.id}`);

// ============================================================ A. owner export
console.log("\n# A. owner exports a transfer code");
let code;
{
  const seed = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-A1", p_idempotency_key: idem() }));
  check("A1 owner seeds history with a report", seed.status === 200 && seed.data?.ok === true,
    JSON.stringify(seed.data));

  const begin = await rpc(A, "begin_identity_transfer", {});
  const b = begin.data ?? {};
  const hoursLeft = b.expires_at ? (Date.parse(b.expires_at) - Date.parse(b.server_now)) / 3.6e6 : -1;
  check("A2 begin_identity_transfer returns ok + code", begin.status === 200 && b.ok === true &&
    typeof b.code === "string" && b.code.length >= 40, JSON.stringify(b).slice(0, 200));
  check("A3 the code is base64url, no padding, ~43 chars (256-bit)",
    /^[A-Za-z0-9_-]{40,60}$/.test(b.code ?? ""), `code=${b.code}`);
  check("A4 the armed TTL is the documented 72h window",
    hoursLeft > 71 && hoursLeft <= 72.1, `hoursLeft=${hoursLeft}`);
  check("A5 begin response carries no identity internals (no owner id, no hash)",
    !JSON.stringify(b).includes(A.id) && !JSON.stringify(b).includes("hash"),
    JSON.stringify(b).slice(0, 200));
  code = b.code;
}

// ========================================================= B. code hygiene
console.log("\n# B. transfer-code hygiene");
{
  const c1 = await rpc(B, "claim_reporting_identity", { p_transfer_code: "nope" });
  check("B1 malformed code -> invalid_code", c1.data?.ok === false && c1.data?.code === "invalid_code",
    JSON.stringify(c1.data));
  const c2 = await rpc(B, "claim_reporting_identity", { p_transfer_code: code + "'" });
  check("B2 code with quote/injection chars -> invalid_code",
    c2.data?.ok === false && c2.data?.code === "invalid_code", JSON.stringify(c2.data));
  const unknown = "AAAA" + code.slice(4); // well-formed but wrong hash
  const c3 = await rpc(B, "claim_reporting_identity", { p_transfer_code: unknown });
  check("B3 unknown well-formed code -> transfer_unknown",
    c3.data?.ok === false && c3.data?.code === "transfer_unknown", JSON.stringify(c3.data));
  // no auth at all: the four client RPCs must never execute for anon
  for (const fn of ["begin_identity_transfer", "claim_reporting_identity", "approve_identity_transfer", "abort_identity_transfer"]) {
    const body = fn === "claim_reporting_identity" ? { p_transfer_code: "x".repeat(43) } : {};
    const r = await rpc(null, fn, body);
    check(`B4 anon cannot call ${fn}`, r.status === 401 || r.status === 403 || r.status === 404,
      JSON.stringify(r).slice(0, 120));
  }
}

// ===================================================== C. claim + veto paths
console.log("\n# C. claim, competing copies, and the owner's veto");
{
  const c1 = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
  const hours = c1.data?.claim_deadline ? (Date.parse(c1.data.claim_deadline) - Date.now()) / 3.6e6 : -1;
  check("C1 fresh claimant reaches pending_claim",
    c1.status === 200 && c1.data?.ok === true && c1.data?.state === "pending_claim",
    JSON.stringify(c1.data));
  check("C2 the veto window is the documented 24h", hours > 23.5 && hours <= 24.1, `hours=${hours}`);

  // a copied .atid in a third hand while the claim is live
  const c2 = await rpc(C, "claim_reporting_identity", { p_transfer_code: code });
  check("C3 a competing copy of the file cannot displace the first claimant",
    c2.data?.ok === false && c2.data?.code === "transfer_in_progress", JSON.stringify(c2.data));

  // the owner is warned, and never shown or re-leaked the code
  const mine = await rpc(A, "my_pending_reports", {});
  const m = JSON.stringify(mine.data ?? {});
  check("C4 owner's my_pending_reports flags transfer_pending",
    mine.data?.transfer_pending === true, m.slice(0, 200));
  check("C5 no RPC after begin ever echoes the transfer code",
    !m.includes(code), `code present: ${m.includes(code)}`);

  // the owner's community activity is a veto
  const write = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-C6", p_idempotency_key: idem() }));
  check("C6 owner activity during the window succeeds as a write",
    write.status === 200 && write.data?.ok === true, JSON.stringify(write.data));
  const reclaim = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
  check("C7 ...and kills the pending transfer (claimant told transfer_aborted)",
    reclaim.data?.ok === false && reclaim.data?.code === "transfer_aborted", JSON.stringify(reclaim.data));
  const mine2 = await rpc(A, "my_pending_reports", {});
  check("C8 the owner's transfer_pending flag clears after the veto",
    mine2.data?.transfer_pending === false, JSON.stringify(mine2.data?.transfer_pending));
}

// ============================================================ D. claim race
console.log("\n# D. concurrent claims: exactly one wins");
{
  const begin = await rpc(A, "begin_identity_transfer", {});
  const code2 = begin.data?.code;
  check("D1 owner can export again after the abort", begin.data?.ok === true && !!code2,
    JSON.stringify(begin.data).slice(0, 150));

  // two claimants fire simultaneously with the same copied code
  const [rB, rC] = await Promise.all([
    rpc(B, "claim_reporting_identity", { p_transfer_code: code2 }),
    rpc(C, "claim_reporting_identity", { p_transfer_code: code2 }),
  ]);
  const winners = [rB, rC].filter(r => r.data?.ok === true && r.data?.state === "pending_claim").length;
  const losers = [rB, rC].filter(r => r.data?.ok === false && r.data?.code === "transfer_in_progress").length;
  check("D2 exactly one concurrent claimant wins the slot", winners === 1 && losers === 1,
    JSON.stringify({ rB: rB.data, rC: rC.data }));

  // clean up: owner aborts whatever claim is live
  const abort = await rpc(A, "abort_identity_transfer", {});
  check("D3 owner abort_identity_transfer answers ok",
    abort.data?.ok === true && abort.data?.state === "aborted", JSON.stringify(abort.data));
}

// ====================================================== E. approve + supersede
console.log("\n# E. approve path: atomic transfer, replay dead, owner superseded");
{
  const begin = await rpc(A, "begin_identity_transfer", {});
  const code3 = begin.data?.code;
  check("E1 third export within the 3/day limit succeeds", begin.data?.ok === true && !!code3,
    JSON.stringify(begin.data).slice(0, 150));

  const claim = await rpc(B, "claim_reporting_identity", { p_transfer_code: code3 });
  check("E2 claimant reaches pending_claim", claim.data?.ok === true && claim.data?.state === "pending_claim",
    JSON.stringify(claim.data));

  const approve = await rpc(A, "approve_identity_transfer", {});
  check("E3 owner approval completes immediately",
    approve.status === 200 && approve.data?.ok === true && approve.data?.state === "completed",
    JSON.stringify(approve.data));

  // history and reputation travelled
  const mineB = await rpc(B, "my_pending_reports", {});
  const reportsB = mineB.data?.reports ?? [];
  check("E4 the claimant now sees the owner's reports as their own",
    reportsB.length >= 2 && reportsB.some(r => r.room === "xfer-A1"),
    JSON.stringify(reportsB.map(r => r.room)));
  const rep = await rpc(B, "my_reputation", {});
  check("E5 the claimant's reputation carried over (report_count >= 2)",
    (rep.data?.report_count ?? 0) >= 2, JSON.stringify(rep.data));

  // the code is dead: replay by the claimant, first use by a copy holder
  const replay = await rpc(B, "claim_reporting_identity", { p_transfer_code: code3 });
  check("E6 replaying the used code -> transfer_used",
    replay.data?.ok === false && replay.data?.code === "transfer_used", JSON.stringify(replay.data));
  const copycat = await rpc(D, "claim_reporting_identity", { p_transfer_code: code3 });
  check("E7 a copy of the .atid after completion -> transfer_used",
    copycat.data?.ok === false && copycat.data?.code === "transfer_used", JSON.stringify(copycat.data));

  // the old device is superseded: clear error, envelope everywhere
  const write = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-E8", p_idempotency_key: idem() }));
  check("E8 superseded owner's write raises identity_superseded",
    raised(write, "identity_superseded"), JSON.stringify(write).slice(0, 200));
  const mineA = await rpc(A, "my_pending_reports", {});
  check("E9 superseded owner's my_pending_reports flags superseded",
    mineA.data?.superseded === true, JSON.stringify(mineA.data?.superseded));
  const beginA = await rpc(A, "begin_identity_transfer", {});
  check("E10 superseded owner cannot export a new code",
    raised(beginA, "identity_superseded"), JSON.stringify(beginA).slice(0, 200));
  const poll = await rpc(A, "create_poll", {
    p_room: "xfer-E11", p_section: null, p_subject: null, p_class_date: null,
    p_start_hour: null, p_question: "still me?", p_options: ["Yes", "No"],
    p_client_version_code: 0, p_idempotency_key: idem(),
  });
  check("E11 superseded owner cannot create polls either",
    raised(poll, "identity_superseded"), JSON.stringify(poll).slice(0, 200));

  // the superseded uid is not a fresh claimant of someone else's code
  const E = await anonUser();
  await rpc(E, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-E12", p_idempotency_key: idem() }));
  const beginE = await rpc(E, "begin_identity_transfer", {});
  const claimA = await rpc(A, "claim_reporting_identity", { p_transfer_code: beginE.data?.code });
  check("E12 a displaced uid cannot claim a fresh code (identity_not_fresh)",
    claimA.data?.ok === false && claimA.data?.code === "identity_not_fresh", JSON.stringify(claimA.data));
  const approveA = await rpc(A, "approve_identity_transfer", {});
  check("E13 superseded owner's approve answers an envelope, never a crash",
    approveA.status === 200 && approveA.data?.ok === false && approveA.data?.code === "no_pending_transfer",
    JSON.stringify(approveA.data));
  const abortA = await rpc(A, "abort_identity_transfer", {});
  check("E14 superseded owner's abort answers an envelope",
    abortA.status === 200 && abortA.data?.ok === false && abortA.data?.code === "no_pending_transfer",
    JSON.stringify(abortA.data));
}

// ========================================================= F. not-fresh claim
console.log("\n# F. identities never merge");
{
  const F1 = await anonUser();   // will have history
  const F2 = await anonUser();   // will export
  await rpc(F1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-F1", p_idempotency_key: idem() }));
  await rpc(F2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-F2", p_idempotency_key: idem() }));
  const begin = await rpc(F2, "begin_identity_transfer", {});
  const used = await rpc(F1, "claim_reporting_identity", { p_transfer_code: begin.data?.code });
  check("F1 a claimant with their own history is refused (identity_not_fresh)",
    used.data?.ok === false && used.data?.code === "identity_not_fresh", JSON.stringify(used.data));
  // the grant survives for a genuinely fresh device
  const F3 = await anonUser();
  const fresh = await rpc(F3, "claim_reporting_identity", { p_transfer_code: begin.data?.code });
  check("F2 the grant survives for a genuinely fresh claimant",
    fresh.data?.ok === true && fresh.data?.state === "pending_claim", JSON.stringify(fresh.data));
}

// ============================================================== G. lost device
console.log("\n# G. lost-device path: window ages out, claimant completes");
{
  const G1 = await anonUser();   // the owner who disappears
  const G2 = await anonUser();   // the claimant with the .atid
  await rpc(G1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-G1", p_idempotency_key: idem() }));
  const begin = await rpc(G1, "begin_identity_transfer", {});
  const gcode = begin.data?.code;
  const claim = await rpc(G2, "claim_reporting_identity", { p_transfer_code: gcode });
  check("G1 claim reaches pending_claim while the owner is absent",
    claim.data?.ok === true && claim.data?.state === "pending_claim", JSON.stringify(claim.data));

  // 24 hours pass. The clock is never a client input; aging the deadline
  // out-of-band is the only honest simulation.
  sql(`update community.reporter_transfer_grants set claim_deadline = now() - interval '1 second' where owner_user_id = '${G1.id}' and state = 'pending_claim'`);
  const complete = await rpc(G2, "claim_reporting_identity", { p_transfer_code: gcode });
  check("G2 the claimant completes after the window",
    complete.data?.ok === true && complete.data?.state === "completed", JSON.stringify(complete.data));

  const write = await rpc(G1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-G3", p_idempotency_key: idem() }));
  check("G3 the lost device, if it returns, gets identity_superseded",
    raised(write, "identity_superseded"), JSON.stringify(write).slice(0, 200));
  const write2 = await rpc(G2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-G4", p_idempotency_key: idem() }));
  check("G4 the claimant writes normally after completing",
    write2.status === 200 && write2.data?.ok === true, JSON.stringify(write2.data));
}

// ============================================================ H. rate limit
console.log("\n# H. begin rate limit: 3/day");
{
  const H1 = await anonUser();
  await rpc(H1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-H1", p_idempotency_key: idem() }));
  let codes = [];
  for (let i = 0; i < 4; i++) {
    const r = await rpc(H1, "begin_identity_transfer", {});
    codes.push(r.data?.code ?? r.data?.code_name ?? JSON.stringify(r.data));
    if (r.data?.ok) await rpc(H1, "abort_identity_transfer", {});
  }
  const fourth = await rpc(H1, "begin_identity_transfer", {});
  check("H1 the 4th export in a day is refused (rate_limited)",
    fourth.data?.ok === false && fourth.data?.code === "rate_limited", JSON.stringify(fourth.data));
}

// =========================================================== I. lockdown
console.log("\n# I. lockdown: tables invisible, helpers uncallable");
{
  for (const t of ["reporter_transfer_grants", "superseded_identities"]) {
    const r = await table(A, "GET", `/${t}`);
    check(`I1 GET /${t} denied`, r.status === 401 || r.status === 403 || r.status === 404,
      JSON.stringify(r).slice(0, 120));
    const w = await table(A, "POST", `/${t}`, t === "reporter_transfer_grants"
      ? { owner_user_id: A.id, code_hash: "x" }
      : { old_user_id: A.id });
    check(`I2 POST /${t} denied`, w.status === 401 || w.status === 403 || w.status === 404,
      JSON.stringify(w).slice(0, 120));
    const d = await table(A, "DELETE", `/${t}`);
    const whereDenied = d.status === 400 && /WHERE/i.test(JSON.stringify(d.data ?? {}));
    check(`I3 DELETE /${t} denied`,
      d.status === 401 || d.status === 403 || d.status === 404 || whereDenied,
      JSON.stringify(d).slice(0, 120));
  }
  for (const fn of ["finalize_transfer", "cancel_pending_transfer_if_any", "retire_stale_grants", "transfer_veto_window", "transfer_armed_ttl"]) {
    const r = await rpc(A, fn, fn === "finalize_transfer" ? { p_grant_id: crypto.randomUUID() }
      : fn === "retire_stale_grants" ? { p_owner: crypto.randomUUID() } : {});
    check(`I4 helper RPC ${fn} not callable by client`,
      r.status === 404 || r.status === 403 || r.status === 401, JSON.stringify(r).slice(0, 120));
  }
  // PostgREST's own OpenAPI must not advertise the grant tables
  const spec = await fetch(`${REST}/`, { headers: auth(A) });
  const specStr = JSON.stringify(await spec.json().catch(() => null));
  check("I5 OpenAPI spec does not advertise the transfer tables",
    !specStr.includes("reporter_transfer_grants") && !specStr.includes("superseded_identities"),
    "advertised");
  // and they are not in the realtime publication
  const pubs = sql("select tablename from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'community'");
  check("I6 realtime publication does not carry the transfer tables",
    !pubs.includes("reporter_transfer_grants") && !pubs.includes("superseded_identities"), pubs);
}

// ======================================================== J. no code leakage
console.log("\n# J. transfer code never leaks outside begin's owner-only response");
{
  const J1 = await anonUser();
  const J2 = await anonUser();
  await rpc(J1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-J1", p_idempotency_key: idem() }));
  const begin = await rpc(J1, "begin_identity_transfer", {});
  const jcode = begin.data?.code;
  const surfaces = [];
  // claim responses (owner, claimant, stranger) and every read RPC
  surfaces.push(["claim by claimant", await rpc(J2, "claim_reporting_identity", { p_transfer_code: jcode })]);
  surfaces.push(["claim by owner", await rpc(J1, "claim_reporting_identity", { p_transfer_code: jcode })]);
  surfaces.push(["my_pending_reports (owner)", await rpc(J1, "my_pending_reports", {})]);
  surfaces.push(["my_pending_reports (claimant)", await rpc(J2, "my_pending_reports", {})]);
  surfaces.push(["my_reputation (owner)", await rpc(J1, "my_reputation", {})]);
  for (const [name, r] of surfaces) {
    check(`J ${name} response contains no transfer code`,
      !JSON.stringify(r.data ?? {}).includes(jcode), name);
  }
  // the stored grant is hashed, not plaintext
  const stored = sql(`select count(*) from community.reporter_transfer_grants where code_hash = '${jcode}'`);
  check("J the grant table stores only the hash (raw code absent)",
    stored === "0", `plaintext matches: ${stored}`);
}

// ================================ K. destination replacement (0017), happy path
console.log("\n# K. replacement: confirmed claim hard-deletes the claimant's identity");
{
  const K1 = await anonUser();   // Device A: the identity owner, with history
  const K2 = await anonUser();   // Device B: holds its OWN identity with history
  const K3 = await anonUser();   // a bystander: votes on B's poll, verifies B's report

  // Both sides seeded. B gets a poll (with options and the bystander's vote),
  // a report (with the bystander's verification), and its OWN armed export
  // grant — the stale .atid that must not survive the replacement.
  const seedA = await rpc(K1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-K1", p_idempotency_key: idem() }));
  check("K1 owner seeds history with a report", seedA.data?.ok === true, JSON.stringify(seedA.data));
  const seedB = await rpc(K2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-K2", p_idempotency_key: idem() }));
  check("K2 claimant seeds its OWN history (the identity to be replaced)", seedB.data?.ok === true, JSON.stringify(seedB.data));
  const bObsId = seedB.data?.id;
  const poll = await rpc(K2, "create_poll", {
    p_room: "xfer-K2", p_section: null, p_subject: null, p_class_date: null,
    p_start_hour: null, p_question: "is this poll doomed?", p_options: ["Yes", "No"],
    p_client_version_code: 0, p_idempotency_key: idem(),
  });
  const bPollId = poll.data?.id;
  check("K3 claimant's poll created", poll.data?.ok === true, JSON.stringify(poll.data));
  await rpc(K3, "cast_vote", { p_poll_id: bPollId, p_option_index: 0 });
  await rpc(K3, "verify", { p_observation_id: bObsId, p_verdict: true, p_idempotency_key: idem() });
  const beginB = await rpc(K2, "begin_identity_transfer", {});
  const bCode = beginB.data?.code;   // B's stale .atid
  check("K4 claimant exports its own identity (the stale code)", !!bCode, JSON.stringify(beginB.data).slice(0, 120));

  const beginA = await rpc(K1, "begin_identity_transfer", {});
  const aCode = beginA.data?.code;
  check("K5 owner exports a code", !!aCode, JSON.stringify(beginA.data).slice(0, 120));

  // Unconfirmed: identity_not_fresh, grant untouched.
  const nope = await rpc(K2, "claim_reporting_identity", { p_transfer_code: aCode });
  check("K6 the unconfirmed replacement claim is refused (identity_not_fresh)",
    nope.data?.ok === false && nope.data?.code === "identity_not_fresh", JSON.stringify(nope.data));

  // Confirmed: pending_claim, flagged.
  const claim = await rpc(K2, "claim_reporting_identity", { p_transfer_code: aCode, p_replace_claimant: true });
  check("K7 the confirmed replacement claim reaches pending_claim, flagged",
    claim.status === 200 && claim.data?.ok === true && claim.data?.state === "pending_claim" &&
      claim.data?.replace_claimant === true, JSON.stringify(claim.data));

  // Frozen during the window: community writes refused, and they cancel
  // nothing. Reads keep working.
  const frozenWrite = await rpc(K2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-K8", p_idempotency_key: idem() }));
  check("K8 the frozen claimant's write raises identity_frozen",
    raised(frozenWrite, "identity_frozen"), JSON.stringify(frozenWrite).slice(0, 200));
  const frozenStatus = await rpc(K2, "my_pending_reports", {});
  check("K9 the frozen claimant's status shows the pending replacement claim",
    frozenStatus.data?.claim_pending === true && frozenStatus.data?.claim_replaces_identity === true,
    JSON.stringify({ cp: frozenStatus.data?.claim_pending, cr: frozenStatus.data?.claim_replaces_identity }));

  // The owner approves: completion, with the replacement reported.
  const approve = await rpc(K1, "approve_identity_transfer", {});
  check("K10 approval completes the replacement (replaced=true)",
    approve.status === 200 && approve.data?.ok === true && approve.data?.state === "completed" &&
      approve.data?.replaced === true, JSON.stringify(approve.data));

  // The admin-side proof (superuser psql, not RLS): B's identity genuinely
  // no longer exists, row by row, table by table.
  const gone = (q) => sql(q) === "0";
  check("K11 B's own report row is gone (admin view)", gone(`select count(*) from community.observations where id = '${bObsId}'`), bObsId);
  check("K12 B's poll row is gone", gone(`select count(*) from community.polls where id = '${bPollId}'`), bPollId);
  check("K13 the options of B's poll are gone", gone(`select count(*) from community.poll_options where poll_id = '${bPollId}'`), bPollId);
  check("K14 the bystander's vote on B's poll is gone", gone(`select count(*) from community.poll_votes where poll_id = '${bPollId}'`), bPollId);
  check("K15 the bystander's verification on B's report is gone", gone(`select count(*) from community.verifications where observation_id = '${bObsId}'`), bObsId);
  // B-origin rows are gone. (B's uid legitimately owns A's re-parented data
  // now — including A's old abuse_events — so the predicates name B's
  // ORIGINAL artifacts: its report's room, its poll (and thus options,
  // votes), its own vote and verification. abuse_events rows are re-parented
  // along with the identity and cannot be attributed post-hoc; the pgTAP
  // suite pins that count precisely with seeded markers.)
  check("K16 no B-origin identity-owned rows remain",
    gone(`select count(*) from community.observations where reporter_id = '${K2.id}' and room = 'xfer-K2'`) &&
    gone(`select count(*) from community.polls where creator_id = '${K2.id}'`) &&
    gone(`select count(*) from community.poll_votes where user_id = '${K2.id}'`) &&
    gone(`select count(*) from community.verifications where user_id = '${K2.id}'`),
    `obs rooms still held: ${sql(`select string_agg(distinct room, ',') from community.observations where reporter_id = '${K2.id}'`)}`);
  check("K17 B's historical transfer grants are all gone (no stale .atid)",
    gone(`select count(*) from community.reporter_transfer_grants where owner_user_id = '${K2.id}'`), K2.id);
  // B's uid now carries A's identity: A's report, and A is the only tombstone.
  const aNowB = sql(`select count(*) from community.observations where reporter_id = '${K2.id}' and room = 'xfer-K1'`);
  check("K18 A's history is now B's", aNowB === "1", `aNowB=${aNowB}`);
  const tombs = sql(`select count(*) from community.superseded_identities where old_user_id in ('${K1.id}','${K2.id}')`);
  const tombA = sql(`select count(*) from community.superseded_identities where old_user_id = '${K1.id}'`);
  check("K19 exactly one tombstone: A's (B's uid was replaced onto, not displaced)",
    tombs === "1" && tombA === "1", `tombs=${tombs} tombA=${tombA}`);

  // B writes normally afterwards (as A's identity), and its stale code is dead.
  const bWrite = await rpc(K2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-K20", p_idempotency_key: idem() }));
  check("K20 B's uid writes normally after the replacement", bWrite.data?.ok === true, JSON.stringify(bWrite.data));
  const stale = await rpc(K2, "claim_reporting_identity", { p_transfer_code: bCode, p_replace_claimant: true });
  check("K21 B's pre-replacement .atid code finds no grant at all (transfer_unknown)",
    stale.data?.ok === false && stale.data?.code === "transfer_unknown", JSON.stringify(stale.data));
}

// ============================= L. replacement alternatives: refuse, freeze, abort
console.log("\n# L. replacement: unconfirmed refusal, freeze, and the veto that spares B");
{
  const L1 = await anonUser();   // owner
  const L2 = await anonUser();   // claimant holding its own identity
  const L3 = await anonUser();   // fresh claimant (nothing to replace)
  await rpc(L1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-L1", p_idempotency_key: idem() }));
  await rpc(L2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-L2", p_idempotency_key: idem() }));
  const begin = await rpc(L1, "begin_identity_transfer", {});
  const lcode = begin.data?.code;

  // A fresh device passing the flag has nothing to replace.
  const fresh = await rpc(L3, "claim_reporting_identity", { p_transfer_code: lcode, p_replace_claimant: true });
  check("L1 a fresh claimant passing the replacement flag gets nothing_to_replace",
    fresh.data?.ok === false && fresh.data?.code === "nothing_to_replace", JSON.stringify(fresh.data));

  // B confirms; the window opens with B frozen.
  const claim = await rpc(L2, "claim_reporting_identity", { p_transfer_code: lcode, p_replace_claimant: true });
  check("L2 the confirmed claim reaches pending_claim", claim.data?.ok === true && claim.data?.state === "pending_claim",
    JSON.stringify(claim.data));
  const frozen = await rpc(L2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-L3", p_idempotency_key: idem() }));
  check("L3 the claimant's write during the window is frozen (identity_frozen)",
    raised(frozen, "identity_frozen"), JSON.stringify(frozen).slice(0, 200));
  const grantStill = sql(`select state from community.reporter_transfer_grants where owner_user_id = '${L1.id}' and state = 'pending_claim'`);
  check("L4 the frozen write cancels nothing", grantStill === "pending_claim", grantStill);

  // The owner vetoes with activity: the claim dies, B is fully intact.
  const veto = await rpc(L1, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-L5", p_idempotency_key: idem() }));
  check("L5 the owner's veto write succeeds", veto.data?.ok === true, JSON.stringify(veto.data));
  const grantAfter = sql(`select state, abort_reason from community.reporter_transfer_grants where owner_user_id = '${L1.id}'`);
  check("L6 the owner's activity aborts the replacement claim",
    grantAfter.includes("aborted") && grantAfter.includes("owner_activity"), grantAfter);

  // B unfreezes with everything intact: writes work, its history remains,
  // and it can still export its own identity.
  const bWrite = await rpc(L2, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-L7", p_idempotency_key: idem() }));
  check("L7 the claimant writes normally after the abort", bWrite.data?.ok === true, JSON.stringify(bWrite.data));
  const bObs = sql(`select count(*) from community.observations where reporter_id = '${L2.id}'`);
  check("L8 the claimant's history is fully intact", bObs === "2", bObs);
  const bBegin = await rpc(L2, "begin_identity_transfer", {});
  check("L9 the claimant can export its own identity again", bBegin.data?.ok === true,
    JSON.stringify(bBegin.data).slice(0, 120));
  await rpc(L2, "abort_identity_transfer", {});

  // A uid mid-claim cannot open a second claim on a different code.
  const L4 = await anonUser();   // second owner
  await rpc(L4, "submit_report", SR({ p_kind: "room_other", p_room: "xfer-L10", p_idempotency_key: idem() }));
  const begin2 = await rpc(L1, "begin_identity_transfer", {});
  const l2code = begin2.data?.code;
  const claim2 = await rpc(L2, "claim_reporting_identity", { p_transfer_code: l2code, p_replace_claimant: true });
  check("L10 a history-holding claimant confirms into pending_claim", claim2.data?.ok === true, JSON.stringify(claim2.data));
  const second = await rpc(L2, "claim_reporting_identity", { p_transfer_code: (await rpc(L4, "begin_identity_transfer", {})).data?.code, p_replace_claimant: true });
  check("L11 a uid mid-claim cannot open a second one on another code (transfer_in_progress)",
    second.data?.ok === false && second.data?.code === "transfer_in_progress", JSON.stringify(second.data));
}

// ==========================================================================
console.log(`\n# Summary: ${pass} passed, ${fail} failed`);
if (failures.length) {
  console.log("# Failures:");
  for (const f of failures) console.log(`#  - ${f.name}: ${f.detail}`);
}
process.exit(fail ? 1 : 0);
})().catch(e => { console.error("HARNESS ERROR", e); process.exit(2); });
