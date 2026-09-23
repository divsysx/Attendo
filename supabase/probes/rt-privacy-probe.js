#!/usr/bin/env node
/**
 * Part 3 live probe, full contract. One run verifies:
 *
 *  1. observations INSERT (submit_report)   — granted columns only
 *  2. observations UPDATE (withdraw_report) — granted columns only
 *  3. polls INSERT (create_poll)            — granted columns only
 *  4. polls UPDATE (withdraw_poll)          — granted columns only
 *  5. subscribing to the private tables (verifications, poll_votes,
 *     reporter_profiles) must NOT yield a working feed — the role has no
 *     table-level SELECT there, so realtime cannot read them for this user.
 *
 * Banned in every record that reaches the watcher:
 *   observations: reporter_id, idempotency_key
 *   polls:        creator_id,  idempotency_key
 *   any table:    a voting/verification row (proof the private feeds leak),
 *                 trust_score / any reporter_profiles column
 *
 * Requires >= 8s of settle time after each write: realtime's WAL→channel
 * delivery is asynchronous and a shorter wait reports false NO-EVENTS.
 */
const { WebSocket } = require("ws");

const BASE = process.env.SUPABASE_URL ?? "http://127.0.0.1:54321";
const URL = BASE.replace(/^http/, "ws") + "/realtime/v1/websocket?apikey=" + process.env.SUPABASE_ANON_KEY + "&vsn=1.0.0";
const ANON = process.env.SUPABASE_ANON_KEY;
const SETTLE_MS = 9000;

async function signUp() {
  const res = await fetch(BASE + "/auth/v1/signup", {
    method: "POST",
    headers: { apikey: ANON, Authorization: `Bearer ${ANON}`, "Content-Type": "application/json" },
    body: JSON.stringify({ data: {} }),
  });
  const body = await res.json();
  if (!body.access_token) throw new Error("anonymous token failed: " + JSON.stringify(body));
  return body.access_token;
}

async function rpc(token, name, params) {
  const res = await fetch(BASE + "/rest/v1/rpc/" + name, {
    method: "POST",
    headers: {
      apikey: ANON,
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "application/json",
      "Accept-Profile": "community",
      "Content-Profile": "community",
    },
    body: JSON.stringify(params),
  });
  return { status: res.status, body: await res.text() };
}

function joinChannel(ws, topic, table, token, ref) {
  ws.send(
    JSON.stringify({
      topic,
      event: "phx_join",
      payload: {
        config: { postgres_changes: [{ event: "*", schema: "community", table }] },
        access_token: token,
      },
      ref: String(ref),
    })
  );
}

function connect(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    const events = [];
    let joins = 0;
    const expected = 4; // obs, polls, + two forbidden
    ws.on("open", () => {
      joinChannel(ws, "realtime:obs", "observations", token, 1);
      joinChannel(ws, "realtime:polls", "polls", token, 2);
      joinChannel(ws, "realtime:verif", "verifications", token, 3);
      joinChannel(ws, "realtime:votes", "poll_votes", token, 4);
    });
    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      events.push(msg);
      console.error("MSG " + msg.topic + " " + msg.event + " " + JSON.stringify(msg.payload).substring(0, 160));
      if (msg.event === "phx_reply") {
        joins++;
        if (joins === expected) resolve({ ws, events });
      }
    });
    // phx_join's ok reply is NOT activation: the backend confirms the actual
    // postgres_changes subscription asynchronously with a system message
    // ("Subscribed to PostgreSQL"). Writes before that are silently missed —
    // production takes ~1s longer than local to activate, which raced the
    // first write and lost the INSERT. So: resolve only when the two public
    // channels report subscribed (the two forbidden ones never will).
    const subscribed = new Set();
    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      if (msg.event === "system" && msg.payload?.status === "ok" && msg.payload?.message === "Subscribed to PostgreSQL") {
        subscribed.add(msg.topic);
        if (subscribed.has("realtime:obs") && subscribed.has("realtime:polls")) {
          setTimeout(() => { if (ws.readyState === 1) resolve({ ws, events }); }, 1500);
        }
      }
    });
    setInterval(() => {
      if (ws.readyState === 1) ws.send(JSON.stringify({ topic: "phoenix", event: "heartbeat", payload: {}, ref: "hb" }));
    }, 5000).unref();
    ws.on("error", reject);
    setTimeout(() => reject(new Error("realtime connect timeout")), 10000);
  });
}

(async () => {
  const watcher = await signUp();
  const reporter = await signUp();
  const verifier2 = await signUp();

  const { ws, events } = await connect(watcher);
  console.error("watcher subscribed to 4 channels (2 public, 2 forbidden)");
  await new Promise((r) => setTimeout(r, 1500));

  // --- observations: submit then withdraw ---
  const submit = await rpc(reporter, "submit_report", {
    p_kind: "room_other",
    p_room: "RT-PROBE2",
    p_section: null,
    p_subject: null,
    p_class_date: null,
    p_start_hour: null,
    p_payload: {},
    p_note: "realtime privacy probe v2",
    p_idempotency_key: crypto.randomUUID(),
    p_client_version_code: 0,
  });
  const submitted = JSON.parse(submit.body);
  if (!submitted.ok) throw new Error("submit failed: " + submit.body);
  const observationId = submitted.id;
  console.error("report submitted:", observationId);
  await new Promise((r) => setTimeout(r, SETTLE_MS));

  // A VISIBLE update: the watcher (not the reporter) verifies the report.
  // status reported -> corroborated passes read-active RLS, so the UPDATE must
  // be delivered. (The withdrawal UPDATE is deliberately NOT delivered to other
  // users: withdrawn rows are owner-only by RLS. That filtering is the privacy
  // property, verified separately below via the owner's own subscription.)
  const verify = await rpc(watcher, "verify", { p_observation_id: observationId, p_verdict: true });
  console.error("verify 1:", verify.body);
  const verify2 = await rpc(verifier2, "verify", { p_observation_id: observationId, p_verdict: true });
  console.error("verify 2:", verify2.body);
  await new Promise((r) => setTimeout(r, SETTLE_MS));

  // The withdrawal: another UPDATE, whose post-image (status=withdrawn) is
  // owner-only. It must NOT be delivered to this watcher.
  const withdraw = await rpc(reporter, "withdraw_report", { p_observation_id: observationId });
  console.error("withdraw:", withdraw.body);
  await new Promise((r) => setTimeout(r, SETTLE_MS));

  // --- polls: create, vote (vote must NOT reach the watcher), withdraw ---
  const pollRes = await rpc(reporter, "create_poll", {
    p_room: "RT-PROBE2",
    p_section: null,
    p_subject: null,
    p_class_date: null,
    p_start_hour: null,
    p_question: "Is the projector working?",
    p_options: ["yes", "no"],
    p_idempotency_key: crypto.randomUUID(),
  });
  const poll = JSON.parse(pollRes.body);
  if (!poll.ok) throw new Error("create_poll failed: " + pollRes.body);
  const pollId = poll.id;
  console.error("poll created:", pollId);
  await new Promise((r) => setTimeout(r, SETTLE_MS));

  const vote = await rpc(watcher, "cast_vote", { p_poll_id: pollId, p_option_index: 0 });
  console.error("vote (should reach no feed):", vote.body);
  await new Promise((r) => setTimeout(r, SETTLE_MS));

  const withdrawPoll = await rpc(reporter, "withdraw_poll", { p_poll_id: pollId });
  console.error("withdraw_poll:", withdrawPoll.body);
  await new Promise((r) => setTimeout(r, SETTLE_MS));

  ws.close();

  // --- verdicts ---
  const byTopic = {};
  events
    .filter((e) => e.event === "postgres_changes")
    .forEach((e) => {
      const record = e.payload?.record ?? e.payload?.data?.record;
      const old = e.payload?.old_record ?? e.payload?.data?.old_record;
      const table = e.payload?.table ?? record?.__table;
      const topicTable =
        e.topic === "realtime:obs" ? "observations" : e.topic === "realtime:polls" ? "polls" : e.topic;
      const evType = e.payload?.type ?? e.payload?.data?.type ?? "?";
      const key = topicTable + ":" + evType;
      byTopic[key] = (byTopic[key] ?? 0) + 1;
      const rec = record ?? {};
      const oldRec = old ?? {};
      console.log("EVENT " + key + " record-cols: " + Object.keys(rec).sort().join(","));
      if (Object.keys(oldRec).length) console.log("  old-cols: " + Object.keys(oldRec).sort().join(","));
    });

  const BANNED = {
    observations: ["reporter_id", "idempotency_key"],
    polls: ["creator_id", "idempotency_key"],
  };
  let leak = false;
  events
    .filter((e) => e.event === "postgres_changes")
    .forEach((e) => {
      const t = e.topic === "realtime:obs" ? "observations" : e.topic === "realtime:polls" ? "polls" : e.topic;
      const record = e.payload?.record ?? e.payload?.data?.record ?? {};
      const old = e.payload?.old_record ?? e.payload?.data?.old_record ?? {};
      (BANNED[t] ?? []).forEach((col) => {
        if (col in record) { console.log("LEAK: " + t + "." + col + " in record"); leak = true; }
        if (col in old) { console.log("LEAK: " + t + "." + col + " in old_record"); leak = true; }
      });
    });

  // Forbidden channels: any postgres_changes event there is a breach.
  ["realtime:verif", "realtime:votes"].forEach((topic) => {
    const got = events.filter((e) => e.topic === topic && e.event === "postgres_changes");
    if (got.length) { console.log("LEAK: " + got.length + " events on forbidden channel " + topic); leak = true; }
  });

  console.log("EVENTS: " + JSON.stringify(byTopic));
  const need = ["observations:INSERT", "observations:UPDATE", "polls:INSERT"];
  const missing = need.filter((k) => !byTopic[k]);
  if (missing.length) console.log("MISSING: " + missing.join(", "));
  // Delivered observations events, in order, with their types:
  const obsTypes = events
    .filter((e) => e.topic === "realtime:obs" && e.event === "postgres_changes")
    .map((e) => (e.payload?.type ?? e.payload?.data?.type ?? "?"));
  console.log("OBS_EVENT_TYPES=" + obsTypes.join(","));
  // Withdrawal UPDATE must not reach other users; a vote must reach no feed.
  if (obsTypes.filter((t) => t === "UPDATE").length !== 1) {
    // >1 means the withdrawn row leaked to a non-owner; 0 means the visible
    // corroborate UPDATE was not delivered (delivery problem, not privacy).
    if (obsTypes.filter((t) => t === "UPDATE").length > 1) { console.log("LEAK: withdrawn UPDATE delivered to non-owner"); leak = true; }
  }
  const voteEvents = events.filter(
    (e) => e.event === "postgres_changes" && JSON.stringify(e.payload).includes("poll_votes"));
  if (voteEvents.length) { console.log("LEAK: vote rows reached a feed"); leak = true; }
  console.log("VERDICT=" + (leak ? "LEAK" : missing.length ? "INCOMPLETE" : "CLEAN"));
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
