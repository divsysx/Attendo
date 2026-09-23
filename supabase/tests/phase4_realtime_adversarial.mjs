#!/usr/bin/env node
// Phase 4c: Realtime adversarial validation for the Attendo community backend.
//
// Speaks the Supabase Realtime Phoenix protocol (v2.0.0 text frames) over the
// browser-style global WebSocket, using ONLY the local dev stack's
// publishable key and anonymous-auth user tokens. No service keys.
//
// Validates:
//   R1  only intended tables are published (checked out-of-band in SQL, and
//       here: subscriptions to unpublished tables must yield no events)
//   R2  RLS is respected by delivery: two identities receive only rows they
//       may SELECT (stranger-visible active rows only; no hidden-status rows)
//   R3  no reporter/user identity leaks in Realtime payloads
//   R4  INSERT/UPDATE/DELETE events flow where applicable (and DELETE events
//       only carry old_record keys)
//   R5  disconnect/reconnect: missed events are recovered by snapshot
//       re-fetch (the locked reconciliation model), not by Realtime replay
//   R6  a row that becomes expired/ineligible is not exposed on the read
//       path afterward (verified via REST after the sweep)
//
// Writes happen through the RPCs as a real client would; the one privileged
// step (forcing expiry via SQL in the container) is executed by the wrapper
// script, not here.

const BASE = process.env.SUPABASE_URL || "http://127.0.0.1:54321";
const KEY = process.env.SUPABASE_PUBLISHABLE_KEY;
if (!KEY) { console.error("missing SUPABASE_PUBLISHABLE_KEY"); process.exit(2); }
const WS = BASE.replace(/^http/, "ws") + "/realtime/v1/websocket";
const REST = `${BASE}/rest/v1`;
const PROFILES = { "Accept-Profile": "community", "Content-Profile": "community" };

let pass = 0, fail = 0; const failures = [];
function check(name, ok, detail) {
  if (ok) { pass++; console.log(`ok - ${name}`); }
  else { fail++; failures.push({ name, detail }); console.log(`not ok - ${name}\n  ${String(detail).slice(0, 300)}`); }
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const idem = () => crypto.randomUUID();
// PostgREST 16 requires every non-DEFAULT RPC param present in the body.
const SR = (o) => ({ p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_payload: {}, p_note: null, p_client_version_code: 0, ...o });
const CP = (o) => ({ p_section: null, p_subject: null, p_class_date: null, p_start_hour: null, p_client_version_code: 0, ...o });
// privileged server-side actions (a client can NEVER do these) — used to
// simulate cron/abuse-system behavior from the wrapper:
const { execSync } = await import("node:child_process");
const sql = (q) => execSync(
  // collapse newlines: JSON.stringify would turn them into literal "\n" for bash
  `docker exec supabase_db_attendo-community psql -U postgres -d postgres -t -c ${JSON.stringify(q.replace(/\n\s*/g, " "))}`,
  { stdio: ["ignore", "pipe", "ignore"] }).toString();

// --- minimal REST helpers ---------------------------------------------------
async function rest(u, method, path, body) {
  const headers = { apikey: KEY, "Content-Type": "application/json", ...PROFILES };
  if (u?.token) { headers.Authorization = `Bearer ${u.token}`; }
  const res = await fetch(`${REST}${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => null) };
}
async function rpc(u, fn, body) {
  const res = await fetch(`${REST}/rpc/${fn}`, {
    method: "POST",
    headers: { apikey: KEY, "Content-Type": "application/json", Authorization: `Bearer ${u.token}`, ...PROFILES },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => null) };
}
async function signup() {
  const res = await fetch(`${BASE}/auth/v1/signup`, {
    method: "POST", headers: { apikey: KEY, "Content-Type": "application/json" }, body: "{}",
  });
  const d = await res.json();
  return { token: d.access_token, id: d.user.id };
}

// --- Phoenix protocol client -------------------------------------------------
// A channel on a socket: joins with postgres_changes config, collects events.
class RtChannel {
  constructor(user, name, changes) {
    this.user = user; this.name = name; this.changes = changes;
    this.events = [];        // {table, type, record, old_record}
    this.joined = false; this.joinError = null; this.closed = false;
    this.pending = [];       // unresolved checks waiting for events
    this.ref = 0;
  }
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(`${WS}?apikey=${KEY}&vsn=1.0.0`);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (e) => { if (!this.openedYet) reject(e); };
      this.ws.onclose = () => { this.closed = true; };
      this.ws.onmessage = (m) => this._onMessage(JSON.parse(m.data));
    });
  }
  _send(joinRef, ref, event, payload) {
    // v1.0.0 serializer: object frames (arrays are rejected by this build)
    this.ws.send(JSON.stringify({ topic: this.name, event, payload, ref }));
  }
  join() {
    return new Promise((resolve) => {
      this.joinRef = String(++this.ref);
      const msgRef = String(++this.ref);
      this._joinResolver = resolve;
      this._send(this.joinRef, msgRef, "phx_join", {
        config: { broadcast: { ack: false, self: false }, presence: { enabled: false }, postgres_changes: this.changes, private: false },
        access_token: this.user.token,
      });
    });
  }
  _onMessage(frame) {
    const { ref, topic, event, payload } = frame;
    if (event === "phx_reply" && topic === this.name && this._joinResolver) {
      const r = this._joinResolver; this._joinResolver = null;
      if (payload?.status === "ok") { this.joined = true; this.subs = payload.response?.postgres_changes; }
      else { this.joinError = payload?.response?.reason || payload?.response || "error"; }
      r();
    } else if (event === "postgres_changes" && topic === this.name) {
      for (const d of payload?.data ? [payload] : [payload]) {
        const data = d.data ?? d;
        this.events.push({
          table: data.table, type: data.type,
          record: data.record ?? null, old_record: data.old_record ?? null,
          errors: data.errors ?? null,
        });
      }
    }
  }
  close() { try { this.ws?.close(); } catch {} }
  // wait for at least n events matching predicate (or timeout)
  async waitFor(pred, n = 1, timeoutMs = 8000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      const hits = this.events.filter(pred);
      if (hits.length >= n) return hits;
      await sleep(150);
    }
    return this.events.filter(pred);
  }
}

// ==============================================================================
(async () => {
const alice = await signup();
const bob = await signup();
const carol = await signup();   // a third identity for private-row contrast
check("R0 three realtime identities created", !!alice.token && !!bob.token && !!carol.token, "");

// ------------------------------------------------------------------ R1 + R2
// alice's report in RT-A (active, stranger-visible).
const seed1 = await rpc(alice, "submit_report", SR({
  p_kind: "room_occupied_despite_free", p_room: "RT-A",
  p_idempotency_key: idem(),
}));
const aliceObsId = seed1.data?.id;
check("R0b seed report accepted", seed1.data?.ok === true, JSON.stringify(seed1.data));

// alice + bob subscribe to community.observations with no filter (worst case:
// unfiltered channel — the broadest any client could request).
const chA = new RtChannel(alice, "realtime:rt-alice", [
  { event: "*", schema: "community", table: "observations" },
  { event: "*", schema: "community", table: "polls" },
]);
const chB = new RtChannel(bob, "realtime:rt-bob", [
  { event: "*", schema: "community", table: "observations" },
]);
await chA.connect(); await chA.join();
await chB.connect(); await chB.join();
check("R1 alice's channel subscribed", chA.joined, JSON.stringify(chA.joinError));
check("R1 bob's channel subscribed", chB.joined, JSON.stringify(chB.joinError));

// Warm-up gate: right after `supabase db reset` the Realtime engine's WAL
// listener can take a while to attach even though joins succeed. Probe with
// out-of-band INSERTs until one is delivered, so a cold engine can never be
// mistaken for a denial (or vice versa). alice's profile row already exists
// from seed1, so the FK is satisfied.
let warm = false;
for (let i = 0; i < 4 && !warm; i++) {
  // distinct room per attempt: the dedup unique constraint would otherwise
  // reject a retry in the same (reporter, kind, room, hour) bucket
  const room = `RT-WARMUP-${i}`;
  sql(`insert into community.observations (idempotency_key, reporter_id, kind, room, expires_at) values (gen_random_uuid(), '${alice.id}', 'room_other', '${room}', now() + interval '2 hours')`);
  const got = await chA.waitFor(e => e.record?.room === room && e.type === "INSERT", 1, 15000);
  if (got.length) warm = true;
}
check("R1c warm-up: realtime engine is delivering events", warm,
  "engine did not deliver any warm-up INSERT within 60s");
sql(`delete from community.observations where room like 'RT-WARMUP-%'`);
// drain the warm-up DELETE event(s) so later waits only see test rows
await chA.waitFor(e => e.type === "DELETE" && e.old_record?.room?.startsWith("RT-WARMUP"), 1, 15000);

// both receive the already-committed INSERT? Realtime does NOT replay history —
// events only flow for post-subscribe commits. Submit a NEW observation now.
const seed2 = await rpc(alice, "submit_report", SR({
  p_kind: "room_free_despite_busy", p_room: "RT-B",
  p_idempotency_key: idem(),
}));
check("R2 new report for delivery accepted", seed2.data?.ok === true, JSON.stringify(seed2.data));

let evA = await chA.waitFor(e => e.table === "observations" && e.type === "INSERT" && e.record?.room === "RT-B", 1, 30000);
let evB = await chB.waitFor(e => e.table === "observations" && e.type === "INSERT" && e.record?.room === "RT-B", 1, 30000);
check("R2 alice received the INSERT event", evA.length >= 1, JSON.stringify(chA.events));
check("R2 bob (stranger, eligible row) also received it", evB.length >= 1, JSON.stringify(chB.events));

// R3: identity leak scan on the event payloads
const leakFields = ["reporter_id"];
const aPayloadStr = JSON.stringify(chA.events) + JSON.stringify(chB.events);
const idLeak = [alice.id, bob.id, carol.id].some(id => aPayloadStr.includes(id)) ||
               [...evA, ...chB.events].some(e => leakFields.some(f => f in (e.record || {})));
check("R3 realtime payload carries no reporter identity (no reporter_id field, no known user uuid)",
  !idLeak, `payload=${aPayloadStr.slice(0, 300)}`);

// --------------------------------------------------------------------- R4
// UPDATE: bob verifies alice's RT-A observation → status change → UPDATE event.
// two independent verifiers: bob + carol. The status transition
// (reported -> corroborated) happens on the SECOND verification — one alone
// does not touch the observations row, so no UPDATE event would be expected.
const ver = await rpc(bob, "verify", { p_observation_id: aliceObsId, p_verdict: true, p_idempotency_key: idem() });
const ver2 = await rpc(carol, "verify", { p_observation_id: aliceObsId, p_verdict: true, p_idempotency_key: idem() });
check("R4 verification submitted for update event", ver.data?.ok === true || ver.data?.code === "already_verified",
  JSON.stringify(ver.data));
check("R4 second verification triggers the status transition", ver2.data?.ok === true || ver2.data?.code === "already_verified",
  JSON.stringify(ver2.data));
evA = await chA.waitFor(e => e.type === "UPDATE" && e.table === "observations", 1, 30000);
check("R4 UPDATE event delivered on status transition", evA.length >= 1, JSON.stringify(chA.events.map(e => e.type)));

// DELETE: out-of-band — the wrapper deletes a row in SQL; the event must
// arrive with old_record only (no full record).
console.log("# (wrapper will DELETE a row out-of-band; waiting for event)");
// We use a dedicated row for this: submit as alice, then wrapper deletes it.
const seed3 = await rpc(alice, "submit_report", SR({
  p_kind: "room_other", p_room: "RT-DELETE",
  p_idempotency_key: idem(),
}));
check("R4 row for delete test accepted", seed3.data?.ok === true, JSON.stringify(seed3.data));
// server-side DELETE (retention sweep analog) — a client can never do this:
await sleep(500);
sql(`delete from community.observations where id = '${seed3.data.id}'`);
let evD = await chA.waitFor(e => e.type === "DELETE" && e.table === "observations", 1, 60000);
check("R4b DELETE event delivered", evD.length >= 1, JSON.stringify(chA.events.map(e => e.type)));
if (evD.length) {
  check("R4c DELETE event has old_record only (record is null)",
    evD[0].record === null && evD[0].old_record && "id" in evD[0].old_record,
    JSON.stringify(evD[0]).slice(0, 200));
}

// --------------------------------------------------------------------- R1
// Subscription to an UNPUBLISHED table must subscribe but never deliver.
const chBad = new RtChannel(carol, "realtime:rt-carol", [
  { event: "*", schema: "community", table: "poll_votes" },   // deliberately NOT published
]);
await chBad.connect(); await chBad.join();
const pollForVote = await rpc(alice, "create_poll", CP({
  p_room: "RT-A", p_question: "Is RT-A busy?", p_options: ["Yes", "No"],
  p_idempotency_key: idem(),
}));
const voteSeed = await rpc(bob, "cast_vote", {
  p_poll_id: pollForVote.data?.id, p_option_index: 0,
});
check("R1 seed vote cast (for unpublish test)", voteSeed.data?.ok === true, JSON.stringify(voteSeed.data));
await sleep(2000);
check("R1b unpublished table (poll_votes) delivers no events", chBad.events.length === 0,
  JSON.stringify(chBad.events));

// RLS denial by delivery: a row a subscriber must NOT see is never delivered.
// carol creates a report, then the wrapper flips its status to 'disputed'
// (invisible to strangers). chB (bob, stranger) must not see the UPDATE.
const hidden = await rpc(carol, "submit_report", SR({
  p_kind: "room_other", p_room: "RT-HIDDEN",
  p_idempotency_key: idem(),
}));
check("R2b hidden-row seed accepted", hidden.data?.ok === true, JSON.stringify(hidden.data));
// server flips the row to disputed (abuse-system analog) — stranger must not
// receive the UPDATE and must not read the row afterward:
sql(`update community.observations set status = 'disputed', resolved_at = now() where id = '${hidden.data.id}'`);
await sleep(3000);
const bobSawHidden = chB.events.some(e => e.record?.room === "RT-HIDDEN" && e.type === "UPDATE");
check("R2c stranger's channel never receives the invisible row's UPDATE",
  !bobSawHidden, JSON.stringify(chB.events.map(e => ({ t: e.type, room: e.record?.room }))));
// and bob's REST read of the hidden row is also empty:
const bobRead = await rest(bob, "GET", `/observations?select=id&room=eq.RT-HIDDEN`);
check("R2d REST read path also hides it from the stranger",
  bobRead.status === 200 && (bobRead.data || []).length === 0, JSON.stringify(bobRead.data));
// alice (owner) can still see it:
const carolRead = await rest(carol, "GET", `/observations?select=id,status&room=eq.RT-HIDDEN`);
check("R2e owner still sees their own row via REST",
  carolRead.status === 200 && carolRead.data?.length === 1 && carolRead.data[0].status === "disputed",
  JSON.stringify(carolRead.data));

// --------------------------------------------------------------------- R5
// disconnect/reconnect + missed-event recovery by snapshot refetch.
chB.close();
await sleep(500);
// while bob is disconnected, alice files a new report bob will miss.
const missed = await rpc(alice, "submit_report", SR({
  p_kind: "room_other", p_room: "RT-MISSED",
  p_idempotency_key: idem(),
}));
check("R5 event filed while bob offline", missed.data?.ok === true, JSON.stringify(missed.data));
await sleep(1500);
const chB2 = new RtChannel(bob, "realtime:rt-bob2", [
  { event: "*", schema: "community", table: "observations" },
]);
await chB2.connect(); await chB2.join();
check("R5b bob reconnected and subscribed", chB2.joined, JSON.stringify(chB2.joinError));
// missed-event recovery: the reconciliation path is a REST snapshot re-fetch.
const snap = await rest(bob, "GET", "/active_observations?select=room");
const snapRooms = (snap.data || []).map(r => r.room);
check("R5c snapshot re-fetch recovers the missed row (reconciliation model)",
  snap.status === 200 && snapRooms.includes("RT-MISSED"), JSON.stringify(snap.data));
// and realtime does NOT replay the old event after reconnect:
await sleep(1500);
const replayed = chB2.events.some(e => e.record?.room === "RT-MISSED" && e.type === "INSERT");
check("R5d no replay of the missed event after reconnect (snapshot is the recovery path)",
  !replayed, JSON.stringify(chB2.events.map(e => ({ t: e.type, room: e.record?.room }))));

// --------------------------------------------------------------------- R6
// expiry: wrapper backdates expires_at on RT-A's observation + runs sweep.
console.log("# expiring RT-A out-of-band (cron sweep analog), then checking read path");
sql(`update community.observations set created_at = now() - interval '2 hours', dedup_hour = now() - interval '2 hours', expires_at = now() - interval '1 minute' where room = 'RT-A'`);
sql(`select community.sweep_expired()`);
await sleep(1500);
const expiredView = await rest(bob, "GET", "/active_observations?select=room");
const stillVisible = (expiredView.data || []).some(r => r.room === "RT-A");
check("R6 expired observation no longer on the read path", !stillVisible,
  JSON.stringify(expiredView.data));
const expiredTable = await rest(bob, "GET", "/observations?select=id,room&room=eq.RT-A");
check("R6b expired observation hidden from stranger's table read too",
  expiredTable.status === 200 && (expiredTable.data || []).length === 0, JSON.stringify(expiredTable.data));

chA.close(); chB.close(); chB2.close(); chBad.close();

console.log(`\n# Realtime summary: ${pass} passed, ${fail} failed`);
if (failures.length) {
  console.log("# Failures:");
  for (const f of failures) console.log(`#  - ${f.name}: ${String(f.detail).slice(0, 250)}`);
}
process.exit(fail ? 1 : 0);
})().catch(e => { console.error("HARNESS ERROR", e); process.exit(2); });
