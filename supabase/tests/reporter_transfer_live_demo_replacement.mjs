#!/usr/bin/env node
// Reporting identity REPLACEMENT: LIVE scenario-3 demonstration against
// production (0017's sign-off demo). Node >= 18, built-in fetch only.
//
// The destructive path, exactly as specified:
//
//   A has an identity with history/reputation -> B already holds a DIFFERENT
//   identity with its own history (report, poll vote, and an armed export
//   code of its own) -> B "imports" A's .atid: the unconfirmed claim is
//   refused (identity_not_fresh) -> the destructive confirmation -> the
//   flagged claim is accepted (pending, replace_claimant) -> B's community
//   write during the window is frozen but does NOT cancel the move -> A
//   approves -> completed(replaced) -> server-side zero-row proof that B's
//   old identity is hard-deleted (including B's historical grants) -> A's
//   history/reputation now B's -> A superseded -> B keeps using the identity
//   -> B's old export code is dead (transfer_unknown).
//
// Everything is probe-marked (rooms/polls XFER-LIVE3-*) and cleaned up at the
// end via SQL. Publishable key only — the same credential the Android app
// holds; no service role in any request. The zero-row proof runs through the
// Management API (admin), never an RLS-visible client query.

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
// authority the dashboard SQL editor gives; used only for the zero-row proof
// and probe cleanup). SUPABASE_SQL=local re-routes the same queries to the
// local stack's psql so the demo can be rehearsed before it touches
// production — the SQL is identical either way.
const SQL_LOCAL = process.env.SUPABASE_SQL === "local";
function sql(q) {
  // One line: the shell keeps JSON.stringify's escaped newlines literal, and
  // both targets are happiest with a single-line statement.
  const oneLine = q.replace(/\s+/g, " ").trim();
  const out = SQL_LOCAL
    ? execSync(
        `docker exec supabase_db_attendo-community psql -U postgres -X -A -c ${JSON.stringify(oneLine)}`,
        { cwd: process.cwd(), stdio: ["pipe", "pipe", "pipe"] }).toString()
    : execSync(
        `npx supabase db query --linked ${JSON.stringify(oneLine)}`,
        { cwd: process.cwd(), stdio: ["pipe", "pipe", "pipe"] }).toString();
  // Both targets answer pipe-separated records with a header row (-A keeps
  // psql from ASCII-arting the table); normalise to [{...}].
  if (SQL_LOCAL) {
    const lines = out.trim().split("\n").filter((l) => l && !/^\(\d+ rows?\)$/.test(l));
    if (lines.length < 2) return [];
    const cols = lines[0].split("|");
    return lines.slice(1).map((line) =>
      Object.fromEntries(cols.map((c, i) => {
        const v = (line.split("|")[i] ?? "").trim();
        // psql prints booleans as t/f; the linked CLI answers real JSON — make
        // the two targets agree so checks can compare against true/false.
        return [c, v === "t" ? true : v === "f" ? false : v];
      })));
  }
  const m = out.match(/\[[\s\S]*\]/);
  return m ? JSON.parse(m[0]) : [];
}

const n = (rows, i, k) => Number(rows[i]?.[k] ?? -1);

const probeIds = [];

(async () => {
// ---------------------------------------------------------------- identities
const A = await anonUser();   // the identity owner: history + reputation
const B = await anonUser();   // the replacing phone: holds its OWN history
const V = await anonUser();   // bystander: verifies A's report, owns a poll B votes on
probeIds.push(A.id, B.id, V.id);
console.log(`# identities: A=${A.id} B=${B.id} V=${V.id}`);

const SR = (o) => ({ p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_payload: {}, p_note: null, p_client_version_code: 0, ...o });

// =========================================== 1. A: history + reputation
console.log("\n# 1. A's identity: one report, corroborated, plus a poll");

let seed = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE3-A", p_idempotency_key: idem() }));
check("3.1.1 A seeds community history with a report", seed.status === 200 && seed.data?.ok === true, JSON.stringify(seed.data));
const obsId = seed.data?.id;

let ver = await rpc(V, "verify", { p_observation_id: obsId, p_verdict: true, p_idempotency_key: idem() });
check("3.1.2 a bystander corroborates it, building A's reputation", ver.status === 200 && ver.data?.ok === true, JSON.stringify(ver.data));

let aPoll = await rpc(A, "create_poll", { p_room: "XFER-LIVE3-A", p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_question: "XFER-LIVE3-A: is the demo visible?", p_options: ["yes", "no"], p_idempotency_key: idem(), p_client_version_code: 0 });
check("3.1.3 A owns a poll (the re-parenting covers polls too)", aPoll.status === 200 && aPoll.data?.ok === true, JSON.stringify(aPoll.data));
const aPollId = aPoll.data?.id;

const repBefore = await rpc(A, "my_reputation", {});
console.log(`#    A's reputation before: ${JSON.stringify(repBefore.data)}`);

// =========================================== 2. B: a DIFFERENT identity with history
console.log("\n# 2. B's identity: its own report, its own vote, its own armed export code");

let bSeed = await rpc(B, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE3-B", p_idempotency_key: idem() }));
check("3.2.1 B already has community history of its own", bSeed.status === 200 && bSeed.data?.ok === true, JSON.stringify(bSeed.data));

let bPoll = await rpc(B, "create_poll", { p_room: "XFER-LIVE3-B", p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_question: "XFER-LIVE3-B: B's own poll", p_options: ["a", "b"], p_idempotency_key: idem(), p_client_version_code: 0 });
check("3.2.2 B owns a poll of its own (must be hard-deleted with B)", bPoll.status === 200 && bPoll.data?.ok === true, JSON.stringify(bPoll.data));

let vPoll = await rpc(V, "create_poll", { p_room: "XFER-LIVE3-V", p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_question: "XFER-LIVE3-V: bystander poll", p_options: ["a", "b"], p_idempotency_key: idem(), p_client_version_code: 0 });
check("3.2.3 a bystander poll for B to vote on", vPoll.status === 200 && vPoll.data?.ok === true, JSON.stringify(vPoll.data));
const vPollId = vPoll.data?.id;
let bVote = await rpc(B, "cast_vote", { p_poll_id: vPollId, p_option_index: 0 });
check("3.2.4 B votes on it (a vote that must be hard-deleted with B)", bVote.status === 200 && bVote.data?.ok === true, JSON.stringify(bVote.data));

let bExport = await rpc(B, "begin_identity_transfer", {});
check("3.2.5 B holds an armed export code of its own (a historical grant that must be hard-deleted)",
  bExport.status === 200 && bExport.data?.ok === true, JSON.stringify(bExport.data).slice(0, 200));
const bCode = bExport.data?.code;

// =========================================== 3. B imports A's .atid
console.log("\n# 3. the import: refused unconfirmed, accepted once the deletion is confirmed");

let begin = await rpc(A, "begin_identity_transfer", {});
const code = begin.data?.code;
check("3.3.1 A exports an identity file (code minted)", begin.status === 200 && begin.data?.ok === true,
  JSON.stringify(begin.data).slice(0, 200));

// What the app does on "import": the unconfirmed claim. B is not fresh, so the
// server refuses — and this refusal is what triggers the destructive dialog.
let refused = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
check("3.3.2 the unconfirmed claim is refused: identity_not_fresh",
  refused.data?.ok === false && refused.data?.code === "identity_not_fresh", JSON.stringify(refused.data));

// The destructive confirmation: the ONLY call that carries the flag.
let claim = await rpc(B, "claim_reporting_identity", { p_transfer_code: code, p_replace_claimant: true });
const hours = claim.data?.claim_deadline ? (Date.parse(claim.data.claim_deadline) - Date.now()) / 3.6e6 : -1;
check("3.3.3 the confirmed replacement claim is accepted: pending, flagged, 24h window",
  claim.status === 200 && claim.data?.ok === true && claim.data?.state === "pending_claim" &&
    claim.data?.replace_claimant === true && hours > 23.5,
  JSON.stringify(claim.data));

// =========================================== 4. the window: frozen, not cancellable
console.log("\n# 4. during the window: B's writes are frozen, the move is not cancelled");

let frozen = await rpc(B, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE3-B", p_idempotency_key: idem() }));
check("3.4.1 B's community write is frozen (identity_frozen)", raised(frozen, "identity_frozen"), JSON.stringify(frozen).slice(0, 200));

let grantState = sql(`select state, replace_claimant from community.reporter_transfer_grants where owner_user_id = '${A.id}' and claimed_by = '${B.id}'`);
check("3.4.2 the frozen write did NOT cancel the move (still pending_claim, still flagged)",
  grantState[0]?.state === "pending_claim" && grantState[0]?.replace_claimant === true,
  JSON.stringify(grantState));

let bRead = await rpc(B, "my_pending_reports", {});
check("3.4.3 B's reads still work (frozen is a write pause, not an outage)",
  bRead.status === 200 && bRead.data?.claim_pending === true && bRead.data?.claim_replaces_identity === true,
  JSON.stringify(bRead.data).slice(0, 160));

// =========================================== 5. completion via the owner's approval
console.log("\n# 5. A approves -> the replacement completes");

let approve = await rpc(A, "approve_identity_transfer", {});
check("3.5.1 A's approval completes the replacement (completed, replaced)",
  approve.status === 200 && approve.data?.ok === true && approve.data?.state === "completed" &&
    approve.data?.replaced === true,
  JSON.stringify(approve.data));

// =========================================== 6. the zero-row proof (admin SQL)
console.log("\n# 6. server-side proof: B's old identity is hard-deleted, A's is B's");

// Every predicate names B's ORIGINAL artifacts — A's re-parented rows now
// legitimately live under B's uid, so "anything with B's uid" would be the
// wrong question. abuse_events are re-parented with the identity and cannot be
// attributed post-hoc; the pgTAP replacement scenario pins that count.
const z = sql(`select
  (select count(*) from community.reporter_profiles where user_id = '${B.id}') as b_profiles,
  (select count(*) from community.observations where reporter_id = '${B.id}' and room = 'XFER-LIVE3-B') as b_old_obs,
  (select count(*) from community.observations where reporter_id = '${B.id}' and room = 'XFER-LIVE3-A') as a_obs_now_bs,
  (select count(*) from community.polls where creator_id = '${B.id}' and id::text = '${aPollId}') as a_poll_now_bs,
  (select count(*) from community.polls where creator_id = '${B.id}' and question = 'XFER-LIVE3-B: B''s own poll') as b_poll_rows,
  (select count(*) from community.poll_votes v join community.polls p on p.id = v.poll_id where v.user_id = '${B.id}' and p.question = 'XFER-LIVE3-V: bystander poll') as b_votes,
  (select count(*) from community.reporter_transfer_grants where owner_user_id = '${B.id}') as b_grants,
  (select count(*) from community.superseded_identities where old_user_id = '${A.id}') as a_tombstoned,
  (select count(*) from community.superseded_identities where old_user_id = '${B.id}') as b_tombstoned,
  (select count(*) from community.reporter_transfer_grants where owner_user_id = '${A.id}' and claimed_by = '${B.id}' and state = 'completed') as the_one_grant`);
console.log(`#    zero-row proof: ${JSON.stringify(z[0])}`);
check("3.6.1 exactly one profile under B's uid — A's, re-parented (B's own is deleted)", n(z, 0, "b_profiles") === 1, JSON.stringify(z[0]));
check("3.6.2 B's original report is gone (hard delete, not anonymised)", n(z, 0, "b_old_obs") === 0, JSON.stringify(z[0]));
check("3.6.3 A's report now belongs to B", n(z, 0, "a_obs_now_bs") === 1, JSON.stringify(z[0]));
check("3.6.4 A's poll now belongs to B", n(z, 0, "a_poll_now_bs") === 1, JSON.stringify(z[0]));
check("3.6.5 B's own poll is gone", n(z, 0, "b_poll_rows") === 0, JSON.stringify(z[0]));
check("3.6.6 B's vote on the bystander poll is gone", n(z, 0, "b_votes") === 0, JSON.stringify(z[0]));
check("3.6.7 B's historical transfer grants are gone — including the armed export code", n(z, 0, "b_grants") === 0, JSON.stringify(z[0]));
check("3.6.8 A's uid is tombstoned exactly once", n(z, 0, "a_tombstoned") === 1, JSON.stringify(z[0]));
check("3.6.9 B's uid is NOT tombstoned (it was replaced onto, not displaced)", n(z, 0, "b_tombstoned") === 0, JSON.stringify(z[0]));
check("3.6.10 exactly one completed grant remains — the A→B move itself", n(z, 0, "the_one_grant") === 1, JSON.stringify(z[0]));
check("3.6.11 no partial state: every count above is its exact expected value",
  [n(z, 0, "b_profiles"), n(z, 0, "b_old_obs"), n(z, 0, "a_obs_now_bs"), n(z, 0, "a_poll_now_bs"),
   n(z, 0, "b_poll_rows"), n(z, 0, "b_votes"), n(z, 0, "b_grants"), n(z, 0, "a_tombstoned"),
   n(z, 0, "b_tombstoned"), n(z, 0, "the_one_grant")]
    .every((v, i) => v === [1, 0, 1, 1, 0, 0, 0, 1, 0, 1][i]),
  JSON.stringify(z[0]));

// =========================================== 7. A: superseded
console.log("\n# 7. A's original device is superseded");

let aWrite = await rpc(A, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE3-A", p_idempotency_key: idem() }));
check("3.7.1 every A write raises identity_superseded", raised(aWrite, "identity_superseded"), JSON.stringify(aWrite).slice(0, 200));

let aStatus = await rpc(A, "my_pending_reports", {});
check("3.7.2 A's status carries the superseded flag (the Start-fresh path)",
  aStatus.data?.superseded === true, JSON.stringify(aStatus.data).slice(0, 120));

// =========================================== 8. B: carries on as A
console.log("\n# 8. B continues using A's identity");

let bMine = await rpc(B, "my_pending_reports", {});
check("3.8.1 B sees A's history: the moved report is B's",
  (bMine.data?.reports ?? []).some((r) => r.id === obsId),
  `obsId=${obsId} bMine=${JSON.stringify(bMine.data).slice(0, 200)}`);

const repAfter = await rpc(B, "my_reputation", {});
const before = repBefore.data ?? {};
const after = repAfter.data ?? {};
check("3.8.2 B carries A's reputation (reports and corroborations travelled)",
  (after.report_count ?? 0) >= (before.report_count ?? 0) &&
    (after.corroborated_count ?? 0) >= (before.corroborated_count ?? 0) &&
    (after.trust_score ?? 0) >= (before.trust_score ?? 0),
  `before=${JSON.stringify(before)} after=${JSON.stringify(after)}`);
console.log(`#    B's reputation after: ${JSON.stringify(after)}`);

let bWrite = await rpc(B, "submit_report", SR({ p_kind: "room_other", p_room: "XFER-LIVE3-AFTER", p_idempotency_key: idem() }));
check("3.8.3 B writes as the (former A) identity — the freeze lifted on completion",
  bWrite.status === 200 && bWrite.data?.ok === true, JSON.stringify(bWrite).slice(0, 200));

// =========================================== 9. the old B identity is dead
console.log("\n# 9. the old B identity cannot be recovered or reused");

let staleB = await rpc(B, "claim_reporting_identity", { p_transfer_code: bCode });
check("3.9.1 B's own armed export code is dead — its grant was hard-deleted (transfer_unknown)",
  staleB.data?.ok === false && staleB.data?.code === "transfer_unknown", JSON.stringify(staleB.data));

let replay = await rpc(B, "claim_reporting_identity", { p_transfer_code: code });
check("3.9.2 the used A→B code cannot be replayed (transfer_used)",
  replay.data?.ok === false && replay.data?.code === "transfer_used", JSON.stringify(replay.data));

// =========================================== 10. cleanup
console.log("\n# cleanup (probe rows and probe users)");
const ids = probeIds.map((i) => `'${i}'`).join(",");
const idIn = `(${ids})`;
sql(`delete from community.poll_votes where user_id in ${idIn}`);
sql(`delete from community.verifications where observation_id in (select id from community.observations where room like 'XFER-LIVE3-%')`);
sql(`delete from community.poll_options where poll_id in (select id from community.polls where room like 'XFER-LIVE3-%' or question like 'XFER-LIVE3-%')`);
sql(`delete from community.polls where room like 'XFER-LIVE3-%' or question like 'XFER-LIVE3-%'`);
sql(`delete from community.observations where room like 'XFER-LIVE3-%'`);
sql(`delete from community.abuse_events where user_id in ${idIn}`);
sql(`delete from community.reporter_transfer_grants where owner_user_id in ${idIn} or claimed_by in ${idIn}`);
sql(`delete from community.superseded_identities where old_user_id in ${idIn}`);
sql(`delete from community.reporter_profiles where user_id in ${idIn}`);
sql(`delete from auth.users where id in ${idIn}`);
const left = sql(`select (select count(*) from community.observations where room like 'XFER-LIVE3-%') as obs, (select count(*) from community.polls where room like 'XFER-LIVE3-%' or question like 'XFER-LIVE3-%') as polls, (select count(*) from community.reporter_profiles where user_id in ${idIn}) as profiles, (select count(*) from community.reporter_transfer_grants where owner_user_id in ${idIn} or claimed_by in ${idIn}) as grants, (select count(*) from community.superseded_identities where old_user_id in ${idIn}) as tombstones, (select count(*) from auth.users where id in ${idIn}) as users`);
check("cleanup: no probe rows remain",
  Object.values(left[0] ?? {}).every((v) => Number(v) === 0),
  JSON.stringify(left));

console.log(`\n# ${pass} passed, ${fail} failed`);
if (fail > 0) { console.log(JSON.stringify(failures, null, 2)); process.exit(1); }
})().catch((e) => { console.error(e); process.exit(1); });
