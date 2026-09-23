#!/usr/bin/env node
/**
 * Part 3, DELETE half. Watches community.observations (and polls) as an
 * anonymous student while the shell — playing the cron/retention role —
 * deletes rows via psql. Verifies:
 *   * a DELETE of a row the watcher could see carries no identity columns
 *   * deletes of owner-only rows (withdrawn) reach no one
 *
 * Usage: node rt-delete-probe.js   (prints verdict lines on stdout)
 */
const { WebSocket } = require("ws");

const BASE = process.env.SUPABASE_URL ?? "http://127.0.0.1:54321";
const URL = BASE.replace(/^http/, "ws") + "/realtime/v1/websocket?apikey=" + process.env.SUPABASE_ANON_KEY + "&vsn=1.0.0";
const ANON = process.env.SUPABASE_ANON_KEY;
const WAIT_MS = parseInt(process.env.WAIT_MS ?? "50000", 10);

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

function join(ws, topic, table, token, ref) {
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

(async () => {
  const token = await signUp();
  const ws = new WebSocket(URL);
  const events = [];

  ws.on("open", () => {
    join(ws, "realtime:obs", "observations", token, 1);
    join(ws, "realtime:polls", "polls", token, 2);
  });
  ws.on("message", (data) => {
    const msg = JSON.parse(data.toString());
    events.push(msg);
    console.error("MSG " + msg.topic + " " + msg.event + " " + JSON.stringify(msg.payload).substring(0, 200));
  });
  // Same activation race as the other probes: wait for the backend's
  // "Subscribed to PostgreSQL" system message, not the phx_join ack.
  const subscribed = new Set();
  ws.on("message", (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.event === "system" && msg.payload?.status === "ok" && msg.payload?.message === "Subscribed to PostgreSQL") {
      subscribed.add(msg.topic);
    }
  });
  setInterval(() => {
    if (ws.readyState === 1) ws.send(JSON.stringify({ topic: "phoenix", event: "heartbeat", payload: {}, ref: "hb" }));
  }, 5000).unref();

  await new Promise((r, j) => {
    const iv = setInterval(() => {
      if (subscribed.has("realtime:obs") && subscribed.has("realtime:polls")) { clearInterval(iv); r(); }
    }, 100);
    setTimeout(() => j(new Error("subscription activation timeout")), 10000);
  });
  console.error("subscribed; writing probe rows…");

  // Self-contained: create the rows the shell will delete, so the test needs
  // no second script and the ids are known exactly.
  const s = await rpc(token, "submit_report", {
    p_kind: "room_other", p_room: "RT-DELETE", p_section: null, p_subject: null,
    p_class_date: null, p_start_hour: null, p_payload: {}, p_note: "delete probe row",
    p_idempotency_key: crypto.randomUUID(), p_client_version_code: 0,
  });
  const sub = JSON.parse(s.body);
  if (!sub.ok) throw new Error("submit: " + s.body);
  console.log("DELETE_ROW_OBSERVATION=" + sub.id);
  const p = await rpc(token, "create_poll", {
    p_room: "RT-DELETE", p_section: null, p_subject: null, p_class_date: null,
    p_start_hour: null, p_question: "delete probe?", p_options: ["yes", "no"],
    p_idempotency_key: crypto.randomUUID(),
  });
  const poll = JSON.parse(p.body);
  if (!poll.ok) throw new Error("create_poll: " + p.body);
  console.log("DELETE_ROW_POLL=" + poll.id);

  console.error("rows written; waiting " + WAIT_MS + "ms for the shell-side deletes…");
  await new Promise((r) => setTimeout(r, WAIT_MS));
  ws.close();

  const changes = events.filter((e) => e.event === "postgres_changes");
  const deletes = changes.filter((e) => (e.payload?.type ?? e.payload?.data?.type) === "DELETE");
  console.log("CHANGE_COUNT=" + changes.length);
  console.log("DELETE_COUNT=" + deletes.length);
  deletes.forEach((d, i) => {
    const rec = d.payload?.record ?? d.payload?.data?.record ?? {};
    const old = d.payload?.old_record ?? d.payload?.data?.old_record ?? {};
    const cols = Object.keys({ ...rec, ...old }).sort();
    console.log("DELETE " + i + " topic=" + d.topic + " cols: " + cols.join(","));
  });
  // Identity columns must never appear in any delivered record.
  const BANNED = {
    observations: ["reporter_id", "idempotency_key"],
    polls: ["creator_id", "idempotency_key"],
  };
  const leaked = deletes.some((d) => {
    const t = d.topic === "realtime:obs" ? "observations" : "polls";
    const rec = { ...(d.payload?.record ?? d.payload?.data?.record ?? {}), ...(d.payload?.old_record ?? d.payload?.data?.old_record ?? {}) };
    return (BANNED[t] ?? []).some((col) => col in rec);
  });
  console.log("VERDICT=" + (leaked ? "LEAK" : deletes.length ? "CLEAN" : "NO-DELETES"));
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
