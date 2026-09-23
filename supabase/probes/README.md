# Realtime privacy probes (Part 3 / Part 15)

Live probes that verify the community Realtime contract end-to-end against a
running Supabase stack. They create their own throwaway anonymous identities,
submit/withdraw real rows via the public RPCs, and assert on what actually
arrives over the WebSocket.

## What each probe proves

| probe | proves |
|---|---|
| `rt-privacy-probe.js` | INSERT + UPDATE events for `observations`/`polls` carry only the granted public columns (never `reporter_id`, `creator_id`, `idempotency_key`); a withdrawn row's UPDATE is not delivered to other users (owner-only by RLS); votes reach no feed; subscriptions on `verifications`/`poll_votes` are refused |
| `rt-delete-probe.js` | DELETE events (retention) carry only the primary key — no identity columns, in old_record or record |
| `rt-owner-probe.js` | the owner's own subscription does receive their withdrawal UPDATE, still with the granted column list only |

The DELETE probe's "only the primary key" behavior is documented Supabase
platform behavior ("RLS is not applied to deletes"), verified against the live
stack — not an assumption.

## Running (local stack)

```sh
cd supabase/probes
npm init -y >/dev/null && npm install ws   # once
export SUPABASE_ANON_KEY=<local anon key>
node rt-privacy-probe.js                   # ~60s, expect VERDICT=CLEAN
node rt-delete-probe.js &                  # watcher; then delete rows as cron would:
docker exec supabase_db_attendo-community psql -U postgres -c \
  "delete from community.observations where id = (...)"
node rt-owner-probe.js                      # ~20s, expect VERDICT=CLEAN
```

Each run inserts rows in rooms named `RT-PROBE*` / `RT-OWNER`; clean up with
`delete from community.observations where room like 'RT-%'; delete from
community.polls where room like 'RT-%';`.

For production (Part 15): same scripts with the production URL and anon key —
edit `URL` and the auth/rest hosts at the top of each file first, and mind the
"no additional production reports" rule: these probes submit one report and one
poll each, so run them only as the explicitly-allowed controlled tests, in
probe-marked rooms (`RT-PROBE*`), and record the row ids in the verification
log.

Timing note: realtime's WAL → channel delivery is asynchronous; these probes
settle ≥ 9 s after each write. Shorter waits produce false NO-EVENTS.
