#!/usr/bin/env node
/** Part 3, owner half: the reporter's own subscription must receive their
 *  withdrawal UPDATE (post-image is owner-only via read_own RLS), with the
 *  granted column list only. */
const { WebSocket } = require("ws");
const BASE = process.env.SUPABASE_URL ?? "http://127.0.0.1:54321";
const URL = BASE.replace(/^http/, "ws") + "/realtime/v1/websocket?apikey=" + process.env.SUPABASE_ANON_KEY + "&vsn=1.0.0";
const ANON = process.env.SUPABASE_ANON_KEY;

async function signUp() {
  const res = await fetch(BASE + "/auth/v1/signup", {
    method: "POST",
    headers: { apikey: ANON, Authorization: `Bearer ${ANON}`, "Content-Type": "application/json" },
    body: JSON.stringify({ data: {} }),
  });
  const b = await res.json();
  if (!b.access_token) throw new Error("token: " + JSON.stringify(b));
  return b.access_token;
}
async function rpc(token, name, params) {
  const res = await fetch(BASE + "/rest/v1/rpc/" + name, {
    method: "POST",
    headers: { apikey: ANON, Authorization: `Bearer ${token}`, "Content-Type": "application/json",
      Accept: "application/json", "Accept-Profile": "community", "Content-Profile": "community" },
    body: JSON.stringify(params),
  });
  return { status: res.status, body: await res.text() };
}

(async () => {
  const token = await signUp();
  const ws = new WebSocket(URL);
  const events = [];
  let activated = false;
  ws.on("open", () => ws.send(JSON.stringify({
    topic: "realtime:obs", event: "phx_join",
    payload: { config: { postgres_changes: [{ event: "*", schema: "community", table: "observations" }] }, access_token: token },
    ref: "1" })));
  ws.on("message", (d) => {
    const m = JSON.parse(d.toString());
    events.push(m);
    if (m.event === "system" && m.payload?.status === "ok" && m.payload?.message === "Subscribed to PostgreSQL") activated = true;
  });
  setInterval(() => { if (ws.readyState === 1) ws.send(JSON.stringify({ topic: "phoenix", event: "heartbeat", payload: {}, ref: "hb" })); }, 5000).unref();
  await new Promise((r, j) => { const iv = setInterval(() => { if (activated) { clearInterval(iv); r(); } }, 50); setTimeout(() => j(new Error("subscription activation timeout")), 8000); });

  const s = await rpc(token, "submit_report", {
    p_kind: "room_other", p_room: "RT-OWNER", p_section: null, p_subject: null,
    p_class_date: null, p_start_hour: null, p_payload: {}, p_note: "owner view probe",
    p_idempotency_key: crypto.randomUUID(), p_client_version_code: 0 });
  const sub = JSON.parse(s.body);
  if (!sub.ok) throw new Error("submit: " + s.body);
  await new Promise((r) => setTimeout(r, 9000));
  const w = await rpc(token, "withdraw_report", { p_observation_id: sub.id });
  console.error("withdraw:", w.body);
  await new Promise((r) => setTimeout(r, 9000));
  ws.close();

  const changes = events.filter((e) => e.event === "postgres_changes");
  const types = changes.map((e) => e.payload?.type ?? e.payload?.data?.type ?? "?");
  console.log("OWNER_EVENT_TYPES=" + types.join(","));
  changes.forEach((e, i) => {
    const rec = e.payload?.record ?? e.payload?.data?.record ?? {};
    console.log("EVENT " + i + " cols: " + Object.keys(rec).sort().join(","));
    if ("reporter_id" in rec || "idempotency_key" in rec) console.log("LEAK at " + i);
  });
  const leak = changes.some((e) => { const rec = e.payload?.record ?? e.payload?.data?.record ?? {}; return "reporter_id" in rec || "idempotency_key" in rec; });
  const gotUpdate = types.filter((t) => t === "UPDATE").length === 1;
  console.log("VERDICT=" + (leak ? "LEAK" : gotUpdate ? "CLEAN" : "INCOMPLETE"));
})().catch((e) => { console.error(e); process.exit(1); });
