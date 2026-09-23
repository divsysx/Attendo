-- pgTAP: reporting identity transfer for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Covers migration 0015's contract end to end, as impersonated users:
--   * the approve path: claim -> owner approves -> immediate atomic
--     completion, reputation and history travel, old uid tombstoned
--   * the lost-device path: claim -> veto window ages out (simulated the
--     only honest way, a privileged UPDATE of claim_deadline; the clock is
--     never a client input) -> claimant's next call completes
--   * the veto: owner activity aborts, explicit abort aborts, owner
--     self-claim neutralises (armed and pending both)
--   * claimant activity aborts their own claim (identities never merge
--     mid-flight)
--   * races: a competing claimant cannot displace the first; a used code is
--     dead; an expired code is dead; begin rate-limits at 3/day
--   * freshness: a claimant with history, and a displaced uid, both get
--     identity_not_fresh; a tombstoned uid cannot export
--   * the clear error: a tombstoned uid's writes raise identity_superseded
--   * lockdown: neither new table is client-readable or writable, no
--     internal helper is client-callable, and the four client RPCs answer
--     envelopes (never permission-denied) to authenticated callers
--
-- 0017 — destination identity replacement (hard delete):
--   * the unconfirmed replacement claim is still identity_not_fresh; the
--     confirmed one (p_replace_claimant) reaches pending_claim flagged
--   * the claimant is FROZEN during the window: writes raise identity_frozen
--     and cannot cancel the move
--   * the owner's activity veto works on replacement claims too; after any
--     abort the claimant is fully intact and unfrozen
--   * completion HARD-DELETES the claimant's identity: as the test runner
--     (superuser — the admin-side view, not what RLS shows), zero rows
--     remain in every identity-owned table, including the claimant's own
--     historical transfer grants; the owner is the only tombstone
--   * a finalize failure at the last statement (trigger-injected) rolls the
--     whole transaction back: the claimant's data and the pending grant
--     survive untouched, and the retry completes
--   * a uid mid-claim cannot open a second one; a fresh claimant passing the
--     replacement flag gets nothing_to_replace; a displaced uid gets
--     identity_not_fresh even with the flag
--
-- Each scenario runs once into a temp table; the assertions read from there.
-- Plpgsql scenario bodies run their steps as sequential assignment statements
-- ONLY: jsonb_build_object's argument evaluation order is undefined, so any
-- two RPC calls inside one argument list can interleave arbitrarily.

BEGIN;
SELECT plan(117);

-- Helpers -------------------------------------------------------------------

create or replace function pg_temp.new_user() returns uuid
language plpgsql as $$
declare uid uuid;
begin
  insert into auth.users (id, email)
  values (gen_random_uuid(), 't_' || gen_random_uuid() || '@test.local')
  returning id into uid;
  return uid;
end;
$$;

-- Run an RPC as a specific user (privileged on entry; restores on exit).
create or replace function pg_temp.as_specific(p_uid uuid, p_fn text) returns jsonb
language plpgsql as $$
declare result jsonb;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', p_uid::text, true);
  execute 'select ' || p_fn into result;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return result;
end;
$$;

-- Run an RPC as a user and capture the raised exception's message, or
-- 'no exception' when the call returned normally.
create or replace function pg_temp.rpc_exception(p_uid uuid, p_fn text) returns text
language plpgsql as $$
declare msg text;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', p_uid::text, true);
  begin
    execute 'select ' || p_fn;
  exception when others then
    get stacked diagnostics msg = message_text;
  end;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return coalesce(msg, 'no exception');
end;
$$;

-- True when the RPC call as an authenticated stranger is refused (no EXECUTE
-- grant), as opposed to running and returning an envelope.
create or replace function pg_temp.rpc_denied(p_fn text) returns boolean
language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', pg_temp.new_user()::text, true);
  execute 'select ' || p_fn;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return false;
exception when others then
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return true;
end;
$$;

-- Count rows a query returns as a role; -1 = permission error.
create or replace function pg_temp.count_as(p_role text, p_uid uuid, p_sql text)
returns bigint
language plpgsql as $$
declare n bigint;
begin
  perform set_config('role', p_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_uid::text, ''), true);
  execute 'select count(*) from (' || p_sql || ') q' into n;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return n;
exception when others then
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return -1;
end;
$$;

-- 'succeeded' or 'denied' for an arbitrary SQL write attempt as p_uid.
create or replace function pg_temp.write_attempt(p_uid uuid, p_sql text) returns text
language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', p_uid::text, true);
  execute p_sql;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return 'succeeded';
exception when others then
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return 'denied';
end;
$$;

-- Submit a report as p_uid; return the observation id.
create or replace function pg_temp.report_by(p_uid uuid, p_room text) returns uuid
language plpgsql as $$
declare r jsonb;
begin
  r := pg_temp.as_specific(p_uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', p_room, '{}', gen_random_uuid()));
  return (r->>'id')::uuid;
end;
$$;

create or replace function pg_temp.begin_transfer(p_uid uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_specific(p_uid, 'community.begin_identity_transfer()');
end;
$$;

create or replace function pg_temp.claim(p_uid uuid, p_code text) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_specific(p_uid, format('community.claim_reporting_identity(%L::text)', p_code));
end;
$$;

-- The confirmed-replacement variant: the flag a client sets only after the
-- destructive confirmation dialog.
create or replace function pg_temp.claim_replace(p_uid uuid, p_code text) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_specific(p_uid, format(
    'community.claim_reporting_identity(%L::text, true::boolean)', p_code));
end;
$$;

-- A well-formed unknown code: base64url, no padding, 43 chars.
create or replace function pg_temp.unknown_code() returns text
language sql as $$
  select replace(translate(encode(gen_random_bytes(32), 'base64'), '+/', '-_'), '=', '');
$$;

-- Create a poll as p_uid; return the poll id.
create or replace function pg_temp.poll_by(p_uid uuid, p_room text) returns uuid
language plpgsql as $$
declare r jsonb;
begin
  r := pg_temp.as_specific(p_uid, format(
    'community.create_poll(%L::text, null::text, null::text, null::date, null::smallint, %L::text, %L::jsonb, %L::uuid, 0::bigint)',
    p_room, 'Is the room live?', '["Yes","No"]', gen_random_uuid()));
  return (r->>'id')::uuid;
end;
$$;

-- Vote on a poll as p_uid.
create or replace function pg_temp.vote_by(p_uid uuid, p_poll_id uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_specific(p_uid, format(
    'community.cast_vote(%L::uuid, 0::smallint)', p_poll_id));
end;
$$;

-- Corroborate an observation as p_uid.
create or replace function pg_temp.verify_by(p_uid uuid, p_obs_id uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_specific(p_uid, format(
    'community.verify(%L::uuid, true::boolean, %L::uuid)', p_obs_id, gen_random_uuid()));
end;
$$;

-- Scenarios (each runs once; results land in xfer_results) --------------------
-- Every step is a sequential assignment: RPC calls never sit inside one
-- expression, because jsonb_build_object's argument order is undefined.

-- 1. The approve path, with reputation and history on board.
create or replace function pg_temp.approve_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text;
  r_claim jsonb;
  a_pending boolean; b_pending boolean;
  approve jsonb;
  b_obs bigint; a_obs bigint;
  b_report_count bigint; b_trust bigint; a_profiles bigint;
  a_sup boolean; b_sup boolean;
  b_reports jsonb;
begin
  perform pg_temp.report_by(a, 'xfer-room-1');
  perform pg_temp.report_by(a, 'xfer-room-2');
  update community.reporter_profiles set trust_score = 7 where user_id = a;

  code := (pg_temp.begin_transfer(a))->>'code';
  r_claim := pg_temp.claim(b, code);
  a_pending := (pg_temp.as_specific(a, 'community.my_pending_reports()') ->> 'transfer_pending')::boolean;
  b_pending := (pg_temp.as_specific(b, 'community.my_pending_reports()') ->> 'transfer_pending')::boolean;
  approve := pg_temp.as_specific(a, 'community.approve_identity_transfer()');

  select count(*) into b_obs from community.observations where reporter_id = b;
  select count(*) into a_obs from community.observations where reporter_id = a;
  select report_count into b_report_count from community.reporter_profiles where user_id = b;
  select trust_score into b_trust from community.reporter_profiles where user_id = b;
  select count(*) into a_profiles from community.reporter_profiles where user_id = a;
  a_sup := (pg_temp.as_specific(a, 'community.my_pending_reports()') ->> 'superseded')::boolean;
  b_sup := (pg_temp.as_specific(b, 'community.my_pending_reports()') ->> 'superseded')::boolean;
  b_reports := coalesce(pg_temp.as_specific(b, 'community.my_pending_reports()') -> 'reports', '[]'::jsonb);

  return jsonb_build_object(
    'code_present', code is not null,
    'claim_state', r_claim->>'state',
    'deadline_future', (r_claim->>'claim_deadline')::timestamptz > now() + interval '23 hours',
    'a_pending_flag', a_pending,
    'b_pending_flag', b_pending,
    'approve_state', approve->>'state',
    'b_obs_count', b_obs,
    'a_obs_count', a_obs,
    'b_report_count', b_report_count,
    'b_trust', b_trust,
    'a_profile_count', a_profiles,
    'a_superseded_flag', a_sup,
    'b_superseded_flag', b_sup,
    'b_my_reports_count', jsonb_array_length(b_reports));
end;
$$;

-- 2. The lost-device path: the window ages out, the claimant completes, the
-- owner is left with the clear error and nothing else.
create or replace function pg_temp.lost_device_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text;
  r_claim jsonb; r_complete jsonb; r_replay jsonb;
  a_error text;
  b_write uuid;
begin
  perform pg_temp.report_by(a, 'lost-room-1');
  code := (pg_temp.begin_transfer(a))->>'code';

  r_claim := pg_temp.claim(b, code);
  -- The window passes with the owner absent. The clock is never a client
  -- input; a privileged UPDATE is the only honest simulation.
  update community.reporter_transfer_grants
    set claim_deadline = now() - interval '1 second'
    where owner_user_id = a and state = 'pending_claim';

  r_complete := pg_temp.claim(b, code);
  r_replay := pg_temp.claim(b, code);

  a_error := pg_temp.rpc_exception(a, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'lost-room-a', '{}', gen_random_uuid()));
  b_write := pg_temp.report_by(b, 'lost-room-b');

  return jsonb_build_object(
    'claim_state', r_claim->>'state',
    'complete_state', r_complete->>'state',
    'replay_code', r_replay->>'code',
    'a_error', a_error,
    'b_can_write', b_write is not null);
end;
$$;

-- 3. The veto: the owner's community activity aborts the pending claim.
create or replace function pg_temp.veto_activity_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text;
  write_result jsonb;
  grant_state text;
  reclaim jsonb;
  a_obs bigint;
begin
  perform pg_temp.report_by(a, 'veto-room-1');
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim(b, code);

  -- The owner reports during the window: the write succeeds AND the
  -- pending transfer aborts.
  write_result := pg_temp.as_specific(a, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'veto-room-2', '{}', gen_random_uuid()));

  select state into grant_state from community.reporter_transfer_grants where owner_user_id = a;
  reclaim := pg_temp.claim(b, code);
  select count(*) into a_obs from community.observations where reporter_id = a;

  return jsonb_build_object(
    'write_ok', write_result->>'ok',
    'grant_state', grant_state,
    'reclaim_code', reclaim->>'code',
    'a_kept_history', a_obs = 2);
end;
$$;

-- 4. The explicit veto: abort_identity_transfer.
create or replace function pg_temp.veto_abort_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text;
  abort jsonb;
  reclaim jsonb;
begin
  perform pg_temp.report_by(a, 'abort-room-1');
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim(b, code);
  abort := pg_temp.as_specific(a, 'community.abort_identity_transfer()');
  reclaim := pg_temp.claim(b, code);
  return jsonb_build_object(
    'abort_ok', abort->>'ok',
    'reclaim_code', reclaim->>'code');
end;
$$;

-- 5. Owner self-claim neutralises the code, armed and pending both.
create or replace function pg_temp.self_claim_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text; code2 text;
  self_armed jsonb;
  b_claim2 jsonb;
  self_pending jsonb;
  b_after jsonb;
  begin_again jsonb;
begin
  perform pg_temp.report_by(a, 'self-room-1');

  -- Armed: the owner consumes their own fresh code.
  code := (pg_temp.begin_transfer(a))->>'code';
  self_armed := pg_temp.claim(a, code);

  -- Pending: the file was handed over, the owner changes their mind.
  code2 := (pg_temp.begin_transfer(a))->>'code';
  b_claim2 := pg_temp.claim(b, code2);
  self_pending := pg_temp.claim(a, code2);
  b_after := pg_temp.claim(b, code2);
  begin_again := pg_temp.begin_transfer(a);

  return jsonb_build_object(
    'self_armed_code', self_armed->>'code',
    'claim2_state', b_claim2->>'state',
    'self_pending_code', self_pending->>'code',
    'b_after_pending_code', b_after->>'code',
    'a_can_begin_again', begin_again->>'ok');
end;
$$;

-- 6. A competing claimant cannot displace the first.
create or replace function pg_temp.competing_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  c uuid := pg_temp.new_user();
  code text;
  second jsonb;
  first_holds boolean;
begin
  perform pg_temp.report_by(a, 'compete-room-1');
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim(b, code);
  second := pg_temp.claim(c, code);
  select claimed_by = b into first_holds from community.reporter_transfer_grants
    where owner_user_id = a and state = 'pending_claim';
  return jsonb_build_object(
    'second_code', second->>'code',
    'first_claimant_holds', first_holds);
end;
$$;

-- 7. A claimant with history never merges: identity_not_fresh, grant intact.
create or replace function pg_temp.not_fresh_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  fresh uuid := pg_temp.new_user();
  code text;
  used jsonb;
  grant_state text;
  fresh_claim jsonb;
begin
  perform pg_temp.report_by(a, 'fresh-room-a');
  perform pg_temp.report_by(b, 'fresh-room-b');   -- b has history now
  code := (pg_temp.begin_transfer(a))->>'code';
  used := pg_temp.claim(b, code);
  select state into grant_state from community.reporter_transfer_grants
    where owner_user_id = a;
  fresh_claim := pg_temp.claim(fresh, code);

  return jsonb_build_object(
    'used_code', used->>'code',
    'grant_state_after_used', grant_state,
    'fresh_state', fresh_claim->>'state');
end;
$$;

-- 8. Claimant activity cancels their own claim.
create or replace function pg_temp.claimant_activity_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text;
  grant_state text; grant_reason text;
  reclaim jsonb;
  a_obs bigint;
begin
  perform pg_temp.report_by(a, 'cact-room-a');
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim(b, code);

  -- The claimant uses their fresh identity while the claim is pending.
  perform pg_temp.report_by(b, 'cact-room-b');

  select state into grant_state from community.reporter_transfer_grants where owner_user_id = a;
  select abort_reason into grant_reason from community.reporter_transfer_grants where owner_user_id = a;
  reclaim := pg_temp.claim(b, code);
  select count(*) into a_obs from community.observations where reporter_id = a;

  return jsonb_build_object(
    'grant_state', grant_state,
    'grant_reason', grant_reason,
    'reclaim_code', reclaim->>'code',
    'a_unaffected', a_obs = 1);
end;
$$;

-- 9. Expired armed grant: dead code, owner can export again.
create or replace function pg_temp.expiry_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text;
  expired jsonb;
  begin_again jsonb;
begin
  perform pg_temp.report_by(a, 'exp-room-1');
  code := (pg_temp.begin_transfer(a))->>'code';
  -- 73 hours later (privileged: the clock is never a client input).
  update community.reporter_transfer_grants
    set created_at = now() - interval '73 hours'
    where owner_user_id = a and state = 'armed';

  expired := pg_temp.claim(b, code);
  begin_again := pg_temp.begin_transfer(a);
  return jsonb_build_object(
    'expired_code', expired->>'code',
    'begin_again_ok', begin_again->>'ok');
end;
$$;

-- 10. begin rate limit: 3/day, then refused.
create or replace function pg_temp.rate_limit_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  fourth jsonb;
begin
  perform pg_temp.report_by(a, 'rate-room-1');
  perform pg_temp.begin_transfer(a);
  perform pg_temp.as_specific(a, 'community.abort_identity_transfer()');
  perform pg_temp.begin_transfer(a);
  perform pg_temp.as_specific(a, 'community.abort_identity_transfer()');
  perform pg_temp.begin_transfer(a);
  perform pg_temp.as_specific(a, 'community.abort_identity_transfer()');
  fourth := pg_temp.begin_transfer(a);
  return jsonb_build_object('fourth_code', fourth->>'code');
end;
$$;

-- 11. The tombstoned uid: no new export, no claiming into the displaced
-- identity, and the owner-facing RPCs answer envelopes rather than raising.
create or replace function pg_temp.tombstone_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  c uuid := pg_temp.new_user();
  code text; code2 text;
  a_begin_error text;
  a_claim jsonb;
  a_claim_replace jsonb;
  a_approve jsonb;
  a_abort jsonb;
begin
  perform pg_temp.report_by(a, 'tomb-room-1');
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim(b, code);
  update community.reporter_transfer_grants
    set claim_deadline = now() - interval '1 second'
    where owner_user_id = a and state = 'pending_claim';
  perform pg_temp.claim(b, code);   -- completes; a is tombstoned

  -- A fresh grant from a third reporter, which the displaced a attempts.
  perform pg_temp.report_by(c, 'tomb-room-c');
  code2 := (pg_temp.begin_transfer(c))->>'code';

  a_begin_error := pg_temp.rpc_exception(a, 'community.begin_identity_transfer()');
  a_claim := pg_temp.claim(a, code2);
  a_claim_replace := pg_temp.claim_replace(a, code2);
  a_approve := pg_temp.as_specific(a, 'community.approve_identity_transfer()');
  a_abort := pg_temp.as_specific(a, 'community.abort_identity_transfer()');

  return jsonb_build_object(
    'a_begin_error', a_begin_error,
    'a_claim_code', a_claim->>'code',
    'a_claim_replace_code', a_claim_replace->>'code',
    'a_approve_code', a_approve->>'code',
    'a_abort_code', a_abort->>'code');
end;
$$;

-- 12. Destination replacement, the happy path: B seeded across EVERY
-- identity-owned table (report, poll + a bystander's vote and verification,
-- own armed export grant), confirmed replacement claim, the freeze, owner
-- approval, then the admin-side (superuser, not RLS) zero-rows proof.
create or replace function pg_temp.replacement_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  v uuid := pg_temp.new_user();
  code text; b_code text;
  obs_a uuid; obs_b uuid; poll_b uuid;
  unconfirmed jsonb; confirmed jsonb;
  unconfirmed_state text;
  b_frozen text;
  frozen_grant_state text;
  b_status jsonb;
  b_sees_reports bigint;
  approve jsonb;
  b_profiles bigint; b_trust bigint;
  b_profile_is_as boolean;
  obs_b_rows bigint; obs_a_now_bs bigint; a_obs_rows bigint;
  b_polls bigint; poll_b_rows bigint; poll_b_options bigint; poll_b_votes bigint;
  ver_b_rows bigint;
  b_grants bigint;
  a_tomb boolean; b_tomb boolean;
  b_write uuid;
  b_reports jsonb;
  stale_code jsonb;
begin
  perform pg_temp.report_by(a, 'rep-room-a');
  update community.reporter_profiles set trust_score = 7 where user_id = a;
  obs_a := (select id from community.observations where reporter_id = a limit 1);
  obs_b := pg_temp.report_by(b, 'rep-room-b');
  poll_b := pg_temp.poll_by(b, 'rep-room-b');
  perform pg_temp.vote_by(v, poll_b);
  perform pg_temp.verify_by(v, obs_b);
  b_code := (pg_temp.begin_transfer(b))->>'code';  -- B's own stale .atid
  code := (pg_temp.begin_transfer(a))->>'code';

  -- Unconfirmed: the claim is still refused (requirement: identities never
  -- merge; the destructive dialog is the only key).
  unconfirmed := pg_temp.claim(b, code);
  select state into unconfirmed_state from community.reporter_transfer_grants
    where owner_user_id = a and state in ('armed', 'pending_claim');
  confirmed := pg_temp.claim_replace(b, code);

  -- The freeze: B's write is refused and does NOT cancel the move.
  b_frozen := pg_temp.rpc_exception(b, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'rep-room-frozen', '{}', gen_random_uuid()));
  select state into frozen_grant_state from community.reporter_transfer_grants
    where owner_user_id = a and state = 'pending_claim';

  b_status := pg_temp.as_specific(b, 'community.my_pending_reports()');
  select count(*) into b_sees_reports from community.observations where reporter_id = b;

  approve := pg_temp.as_specific(a, 'community.approve_identity_transfer()');

  -- The admin-side view: direct superuser counts — genuine row absence, not
  -- what RLS hides.
  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select trust_score into b_trust from community.reporter_profiles where user_id = b;
  -- The exact invariant: exactly ONE profile remains under B's uid, and it
  -- is A's (trust 7 — B's own row, seeded with default trust, was deleted).
  b_profile_is_as := b_profiles = 1 and b_trust = 7;
  select count(*) into obs_b_rows from community.observations where id = obs_b;
  select count(*) into obs_a_now_bs from community.observations where id = obs_a and reporter_id = b;
  select count(*) into a_obs_rows from community.observations where reporter_id = a;
  select count(*) into b_polls from community.polls where creator_id = b;
  select count(*) into poll_b_rows from community.polls where id = poll_b;
  select count(*) into poll_b_options from community.poll_options where poll_id = poll_b;
  select count(*) into poll_b_votes from community.poll_votes where poll_id = poll_b;
  select count(*) into ver_b_rows from community.verifications where observation_id = obs_b;
  select count(*) into b_grants from community.reporter_transfer_grants where owner_user_id = b;
  a_tomb := exists (select 1 from community.superseded_identities where old_user_id = a);
  b_tomb := exists (select 1 from community.superseded_identities where old_user_id = b);

  b_write := pg_temp.report_by(b, 'rep-room-after');
  b_reports := coalesce(pg_temp.as_specific(b, 'community.my_pending_reports()') -> 'reports', '[]'::jsonb);
  stale_code := pg_temp.claim(b, b_code);

  return jsonb_build_object(
    'unconfirmed_code', unconfirmed->>'code',
    'unconfirmed_state', unconfirmed_state,
    'confirmed_state', confirmed->>'state',
    'confirmed_replace', confirmed->>'replace_claimant',
    'b_frozen_error', b_frozen,
    'grant_after_frozen_write', frozen_grant_state,
    'b_claim_pending', b_status->>'claim_pending',
    'b_claim_replaces', b_status->>'claim_replaces_identity',
    'b_reads_work', b_sees_reports = 1,
    'approve_state', approve->>'state',
    'approve_replaced', approve->>'replaced',
    'b_profiles', b_profiles,
    'b_profile_is_as', b_profile_is_as,
    'b_trust', b_trust,
    'obs_b_rows', obs_b_rows,
    'obs_a_now_bs', obs_a_now_bs,
    'a_obs_rows', a_obs_rows,
    'b_polls', b_polls,
    'poll_b_rows', poll_b_rows,
    'poll_b_options', poll_b_options,
    'poll_b_votes', poll_b_votes,
    'verifications_on_obs_b', ver_b_rows,
    'b_grants', b_grants,
    'a_tombstoned', a_tomb,
    'b_tombstoned', b_tomb,
    'b_can_write', b_write is not null,
    'b_sees_moved_report', (
      select count(*) from jsonb_array_elements(b_reports) e
      where (e->>'id')::uuid = obs_a) = 1,
    'stale_b_code', stale_code->>'code');
end;
$$;

-- 13. Replacement vetoed: B frozen during the window, the owner's activity
-- aborts the claim, and B comes back fully intact and unfrozen (the
-- server-side half of "transfer cancelled -> outbox remains").
create or replace function pg_temp.replacement_veto_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  code text; b_code text;
  b_frozen text;
  state_after_b_write text;
  owner_write jsonb;
  grant_state text; grant_reason text;
  b_unfrozen uuid;
  b_obs bigint;
  b_own_grant_state text;
  b_status jsonb;
begin
  perform pg_temp.report_by(a, 'rveto-room-a');
  perform pg_temp.report_by(b, 'rveto-room-b');
  b_code := (pg_temp.begin_transfer(b))->>'code';  -- B's own live export grant
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim_replace(b, code);

  -- B's community action during the window: refused, and it cancels nothing.
  b_frozen := pg_temp.rpc_exception(b, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'rveto-room-frozen', '{}', gen_random_uuid()));
  select state into state_after_b_write from community.reporter_transfer_grants
    where owner_user_id = a and state = 'pending_claim';

  -- The owner's proof-of-life veto aborts a replacement claim like any other.
  owner_write := pg_temp.as_specific(a, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'rveto-room-a2', '{}', gen_random_uuid()));
  select state, abort_reason into grant_state, grant_reason
  from community.reporter_transfer_grants where owner_user_id = a;

  -- B is unfrozen, intact, and their own unrelated export grant survived.
  b_unfrozen := pg_temp.report_by(b, 'rveto-room-b2');
  select count(*) into b_obs from community.observations where reporter_id = b;
  select state into b_own_grant_state from community.reporter_transfer_grants
    where owner_user_id = b;
  b_status := pg_temp.as_specific(b, 'community.my_pending_reports()');

  return jsonb_build_object(
    'b_frozen_error', b_frozen,
    'grant_after_b_write', state_after_b_write,
    'owner_write_ok', owner_write->>'ok',
    'grant_state', grant_state,
    'grant_reason', grant_reason,
    'b_unfrozen_write', b_unfrozen is not null,
    'b_obs_intact', b_obs = 2,
    'b_own_grant_still_armed', b_own_grant_state = 'armed',
    'b_claim_pending_after', b_status->>'claim_pending');
end;
$$;

-- 14. Atomicity: a failure injected at the LAST statement of
-- finalize_transfer (the closing abuse_event insert — after B's identity is
-- already deleted) must roll the entire transaction back: B's server data
-- and the pending grant survive untouched, and the retry completes.
create or replace function pg_temp.replacement_rollback_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  v uuid := pg_temp.new_user();
  code text;
  obs_b uuid; poll_b uuid;
  fail_error text;
  grant_state text;
  b_profiles bigint; b_obs bigint; b_polls bigint; b_votes bigint;
  b_vers bigint; b_grants bigint;
  a_profiles bigint; a_obs bigint;
  retry jsonb;
  obs_b_after bigint;
begin
  perform pg_temp.report_by(a, 'rb-room-a');
  obs_b := pg_temp.report_by(b, 'rb-room-b');
  poll_b := pg_temp.poll_by(b, 'rb-room-b');
  perform pg_temp.vote_by(v, poll_b);
  perform pg_temp.verify_by(v, obs_b);
  code := (pg_temp.begin_transfer(a))->>'code';
  perform pg_temp.claim_replace(b, code);
  perform pg_temp.begin_transfer(b);  -- B's own armed grant must survive too

  -- The injected failure. Runs as the test connection (superuser); the whole
  -- test file rolls back, so the schema pollution is transaction-local.
  -- (The nested dollar-quote tag is $t$ because it cannot reuse the outer one.)
  create or replace function community.__test_boom_on_transfer() returns trigger
  language plpgsql as $t$ begin raise exception 'injected_failure'; end $t$;
  create trigger __test_boom_before_transfer
    before insert on community.abuse_events
    for each row when (new.action = 'transfer')
    execute function community.__test_boom_on_transfer();

  -- approve as A, expecting the exception to propagate (the honest failure
  -- mode a crash between statements would produce).
  begin
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claim.sub', a::text, true);
    begin
      execute 'select community.approve_identity_transfer()';
      fail_error := 'no exception';
    exception when others then
      get stacked diagnostics fail_error = message_text;
    end;
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
  end;

  drop trigger __test_boom_before_transfer on community.abuse_events;
  drop function community.__test_boom_on_transfer();

  -- B's data survived the failed finalization completely (superuser counts).
  select state into grant_state from community.reporter_transfer_grants
    where owner_user_id = a and state in ('armed', 'pending_claim');
  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into b_obs from community.observations where id = obs_b;
  select count(*) into b_polls from community.polls where id = poll_b;
  select count(*) into b_votes from community.poll_votes where poll_id = poll_b;
  select count(*) into b_vers from community.verifications where observation_id = obs_b;
  select count(*) into b_grants from community.reporter_transfer_grants where owner_user_id = b;
  select count(*) into a_profiles from community.reporter_profiles where user_id = a;
  select count(*) into a_obs from community.observations where reporter_id = a;

  -- With the failure gone, the retry completes and the deletion happens.
  retry := pg_temp.as_specific(a, 'community.approve_identity_transfer()');
  select count(*) into obs_b_after from community.observations where id = obs_b;

  return jsonb_build_object(
    'fail_error', fail_error,
    'grant_state', grant_state,
    'b_profiles', b_profiles,
    'b_obs', b_obs,
    'b_polls', b_polls,
    'b_votes', b_votes,
    'b_verifications', b_vers,
    'b_grants', b_grants,
    'a_profiles', a_profiles,
    'a_obs', a_obs,
    'retry_state', retry->>'state',
    'obs_b_after_retry', obs_b_after);
end;
$$;

-- 15. Replacement edges: a fresh device passing the flag has nothing to
-- replace; a uid mid-claim cannot open a second one on a different code.
create or replace function pg_temp.replacement_edges_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  c uuid := pg_temp.new_user();
  fresh uuid := pg_temp.new_user();
  code text; code2 text;
  fresh_replace jsonb;
  grant_armed boolean;
  b_claim jsonb;
  second_code jsonb;
begin
  perform pg_temp.report_by(a, 'edge-room-a');
  perform pg_temp.report_by(b, 'edge-room-b');
  perform pg_temp.report_by(c, 'edge-room-c');
  code := (pg_temp.begin_transfer(a))->>'code';
  code2 := (pg_temp.begin_transfer(c))->>'code';

  fresh_replace := pg_temp.claim_replace(fresh, code);
  select state = 'armed' into grant_armed from community.reporter_transfer_grants
    where owner_user_id = a;

  b_claim := pg_temp.claim_replace(b, code);          -- B holds history
  second_code := pg_temp.claim_replace(b, code2);     -- a second .atid mid-claim

  return jsonb_build_object(
    'fresh_replace_code', fresh_replace->>'code',
    'grant_armed_after', grant_armed,
    'b_claim_state', b_claim->>'state',
    'second_code', second_code->>'code');
end;
$$;

-- Run every scenario exactly once.
create temp table xfer_results (k text primary key, v jsonb);
insert into xfer_results values
  ('approve',             pg_temp.approve_scenario()),
  ('lost_device',         pg_temp.lost_device_scenario()),
  ('veto_activity',       pg_temp.veto_activity_scenario()),
  ('veto_abort',          pg_temp.veto_abort_scenario()),
  ('self_claim',          pg_temp.self_claim_scenario()),
  ('competing',           pg_temp.competing_scenario()),
  ('not_fresh',           pg_temp.not_fresh_scenario()),
  ('claimant_activity',   pg_temp.claimant_activity_scenario()),
  ('expiry',              pg_temp.expiry_scenario()),
  ('rate_limit',          pg_temp.rate_limit_scenario()),
  ('tombstone',           pg_temp.tombstone_scenario()),
  ('replacement',         pg_temp.replacement_scenario()),
  ('replacement_veto',    pg_temp.replacement_veto_scenario()),
  ('replacement_rollback', pg_temp.replacement_rollback_scenario()),
  ('replacement_edges',   pg_temp.replacement_edges_scenario());

-- Assertions -------------------------------------------------------------------

-- 1. Approve path.
SELECT is(v->>'code_present', 'true', 'approve: the code is returned once to the owner') FROM xfer_results WHERE k='approve';
SELECT is(v->>'claim_state', 'pending_claim', 'approve: a fresh claimant reaches pending_claim') FROM xfer_results WHERE k='approve';
SELECT is(v->>'deadline_future', 'true', 'approve: the veto window is ~24h') FROM xfer_results WHERE k='approve';
SELECT is(v->>'a_pending_flag', 'true', 'approve: the owner sees transfer_pending during the window') FROM xfer_results WHERE k='approve';
SELECT is(v->>'b_pending_flag', 'false', 'approve: the claimant does not') FROM xfer_results WHERE k='approve';
SELECT is(v->>'approve_state', 'completed', 'approve: owner approval completes immediately') FROM xfer_results WHERE k='approve';
SELECT is(v->>'b_obs_count', '2', 'approve: the history re-parents to the claimant') FROM xfer_results WHERE k='approve';
SELECT is(v->>'a_obs_count', '0', 'approve: the owner keeps none of it') FROM xfer_results WHERE k='approve';
SELECT is(v->>'b_report_count', '2', 'approve: report_count travels') FROM xfer_results WHERE k='approve';
SELECT is(v->>'b_trust', '7', 'approve: trust travels') FROM xfer_results WHERE k='approve';
SELECT is(v->>'a_profile_count', '0', 'approve: the owner has no profile left') FROM xfer_results WHERE k='approve';
SELECT is(v->>'a_superseded_flag', 'true', 'approve: my_pending_reports tells the owner they are superseded') FROM xfer_results WHERE k='approve';
SELECT is(v->>'b_superseded_flag', 'false', 'approve: the claimant is not superseded') FROM xfer_results WHERE k='approve';
SELECT is(v->>'b_my_reports_count', '2', 'approve: the claimant sees the moved reports as their own') FROM xfer_results WHERE k='approve';

-- 2. Lost-device path.
SELECT is(v->>'claim_state', 'pending_claim', 'lost device: claim reaches pending') FROM xfer_results WHERE k='lost_device';
SELECT is(v->>'complete_state', 'completed', 'lost device: the claimant completes after the window') FROM xfer_results WHERE k='lost_device';
SELECT is(v->>'replay_code', 'transfer_used', 'lost device: the code is single-use') FROM xfer_results WHERE k='lost_device';
SELECT is(v->>'a_error', 'identity_superseded', 'lost device: the displaced owner gets the clear error') FROM xfer_results WHERE k='lost_device';
SELECT is(v->>'b_can_write', 'true', 'lost device: the claimant writes normally after completing') FROM xfer_results WHERE k='lost_device';

-- 3. Veto by activity.
SELECT is(v->>'write_ok', 'true', 'veto by activity: the owner write itself succeeds') FROM xfer_results WHERE k='veto_activity';
SELECT is(v->>'grant_state', 'aborted', 'veto by activity: the pending transfer aborts') FROM xfer_results WHERE k='veto_activity';
SELECT is(v->>'reclaim_code', 'transfer_aborted', 'veto by activity: the claimant learns the transfer is dead') FROM xfer_results WHERE k='veto_activity';
SELECT is(v->>'a_kept_history', 'true', 'veto by activity: the owner keeps everything') FROM xfer_results WHERE k='veto_activity';

-- 4. Veto by abort.
SELECT is(v->>'abort_ok', 'true', 'veto by abort: the owner aborts') FROM xfer_results WHERE k='veto_abort';
SELECT is(v->>'reclaim_code', 'transfer_aborted', 'veto by abort: the claimant cannot complete') FROM xfer_results WHERE k='veto_abort';

-- 5. Owner self-claim.
SELECT is(v->>'self_armed_code', 'transfer_self', 'self-claim: the owner consuming an armed code kills the grant') FROM xfer_results WHERE k='self_claim';
SELECT is(v->>'claim2_state', 'pending_claim', 'self-claim: a fresh code reaches pending first') FROM xfer_results WHERE k='self_claim';
SELECT is(v->>'self_pending_code', 'transfer_self', 'self-claim: the owner consuming a pending claim kills it') FROM xfer_results WHERE k='self_claim';
SELECT is(v->>'b_after_pending_code', 'transfer_aborted', 'self-claim: the claimant cannot complete afterwards') FROM xfer_results WHERE k='self_claim';
SELECT is(v->>'a_can_begin_again', 'true', 'self-claim: the owner can export a fresh code afterwards') FROM xfer_results WHERE k='self_claim';

-- 6. Competing claimant.
SELECT is(v->>'second_code', 'transfer_in_progress', 'competing claimant: refused while a claim is live') FROM xfer_results WHERE k='competing';
SELECT is(v->>'first_claimant_holds', 'true', 'competing claimant: the first claimant keeps the slot') FROM xfer_results WHERE k='competing';

-- 7. Not-fresh claimant.
SELECT is(v->>'used_code', 'identity_not_fresh', 'not fresh: a claimant with history is refused') FROM xfer_results WHERE k='not_fresh';
SELECT is(v->>'grant_state_after_used', 'armed', 'not fresh: the grant survives for a genuinely fresh device') FROM xfer_results WHERE k='not_fresh';
SELECT is(v->>'fresh_state', 'pending_claim', 'not fresh: a fresh device can still claim') FROM xfer_results WHERE k='not_fresh';

-- 8. Claimant activity.
SELECT is(v->>'grant_state', 'aborted', 'claimant activity: their own claim aborts') FROM xfer_results WHERE k='claimant_activity';
SELECT is(v->>'grant_reason', 'claimant_activity', 'claimant activity: the abort reason names it') FROM xfer_results WHERE k='claimant_activity';
SELECT is(v->>'a_unaffected', 'true', 'claimant activity: the owner is unaffected') FROM xfer_results WHERE k='claimant_activity';

-- 9. Codes: malformed and unknown.
SELECT is(pg_temp.claim(pg_temp.new_user(), 'nope')->>'code', 'invalid_code', 'codes: a malformed code is rejected before any lookup');
SELECT is(pg_temp.claim(pg_temp.new_user(), repeat('x', 43) || '=')->>'code', 'invalid_code', 'codes: a code with illegal characters is invalid');
SELECT is(pg_temp.claim(pg_temp.new_user(), pg_temp.unknown_code())->>'code', 'transfer_unknown', 'codes: an unknown well-formed code finds no grant');

-- 10. Expiry.
SELECT is(v->>'expired_code', 'transfer_expired', 'expiry: an armed code dies after its TTL') FROM xfer_results WHERE k='expiry';
SELECT is(v->>'begin_again_ok', 'true', 'expiry: the owner can export again after expiry') FROM xfer_results WHERE k='expiry';

-- 11. Rate limit.
SELECT is(v->>'fourth_code', 'rate_limited', 'rate limit: begin refuses past 3/day') FROM xfer_results WHERE k='rate_limit';

-- 12. begin with no community history.
SELECT is(pg_temp.begin_transfer(pg_temp.new_user())->>'code', 'nothing_to_transfer', 'begin: a uid with no profile has nothing to transfer');

-- 13. The tombstoned uid.
SELECT is(v->>'a_begin_error', 'identity_superseded', 'tombstone: a displaced uid cannot export a new code') FROM xfer_results WHERE k='tombstone';
SELECT is(v->>'a_claim_code', 'identity_not_fresh', 'tombstone: a displaced uid cannot claim a fresh code either') FROM xfer_results WHERE k='tombstone';
SELECT is(v->>'a_claim_replace_code', 'identity_not_fresh', 'tombstone: not even with the replacement flag — a displaced uid must start fresh') FROM xfer_results WHERE k='tombstone';
SELECT is(v->>'a_approve_code', 'no_pending_transfer', 'tombstone: approve answers an envelope, never a crash') FROM xfer_results WHERE k='tombstone';
SELECT is(v->>'a_abort_code', 'no_pending_transfer', 'tombstone: abort answers an envelope, never a crash') FROM xfer_results WHERE k='tombstone';

-- 14. Lockdown: the two new tables are client-invisible.
SELECT is(pg_temp.count_as('authenticated', pg_temp.new_user(),
  'select * from community.reporter_transfer_grants'), -1::bigint,
  'lockdown: reporter_transfer_grants is not client-readable');
SELECT is(pg_temp.count_as('authenticated', pg_temp.new_user(),
  'select * from community.superseded_identities'), -1::bigint,
  'lockdown: superseded_identities is not client-readable');
SELECT is(pg_temp.write_attempt(pg_temp.new_user(),
  'insert into community.reporter_transfer_grants (owner_user_id, code_hash) values (''' ||
  gen_random_uuid()::text || ''', ''x'')'), 'denied',
  'lockdown: reporter_transfer_grants is not client-writable');
SELECT is(pg_temp.write_attempt(pg_temp.new_user(),
  'insert into community.superseded_identities (old_user_id) values (''' ||
  gen_random_uuid()::text || ''')'), 'denied',
  'lockdown: superseded_identities is not client-writable');

-- 15. Lockdown: the internal helpers are not client-callable.
SELECT is(pg_temp.rpc_denied('community.finalize_transfer(''' || gen_random_uuid()::text || '''::uuid)'),
  true, 'lockdown: finalize_transfer is not client-callable');
SELECT is(pg_temp.rpc_denied('community.cancel_pending_transfer_if_any()'),
  true, 'lockdown: cancel_pending_transfer_if_any is not client-callable');
SELECT is(pg_temp.rpc_denied('community.retire_stale_grants(''' || gen_random_uuid()::text || '''::uuid)'),
  true, 'lockdown: retire_stale_grants is not client-callable');
SELECT is(pg_temp.rpc_denied('community.transfer_veto_window()'),
  true, 'lockdown: transfer_veto_window is not client-callable');
SELECT is(pg_temp.rpc_denied('community.transfer_armed_ttl()'),
  true, 'lockdown: transfer_armed_ttl is not client-callable');

-- 16. The four client RPCs answer envelopes to authenticated callers.
SELECT is(pg_temp.begin_transfer(pg_temp.new_user())->>'code', 'nothing_to_transfer',
  'client RPCs: begin_identity_transfer is executable');
SELECT is(pg_temp.claim(pg_temp.new_user(), pg_temp.unknown_code())->>'code', 'transfer_unknown',
  'client RPCs: claim_reporting_identity is executable');
SELECT is(pg_temp.as_specific(pg_temp.new_user(), 'community.approve_identity_transfer()')->>'code',
  'no_pending_transfer', 'client RPCs: approve_identity_transfer is executable');
SELECT is(pg_temp.as_specific(pg_temp.new_user(), 'community.abort_identity_transfer()')->>'code',
  'no_pending_transfer', 'client RPCs: abort_identity_transfer is executable');

-- 17. The recorded policy.
SELECT is((select value->>'veto_window_hours' from community.app_meta where key = 'transfer_policy'),
  '24', 'app_meta: the veto window is recorded as 24 hours');
SELECT is((select value->>'supports_replacement' from community.app_meta where key = 'transfer_policy'),
  'true', 'app_meta: replacement support is advertised to the client');

-- 18. Destination replacement: the claim.
SELECT is(v->>'unconfirmed_code', 'identity_not_fresh', 'replacement: the unconfirmed claim is still refused') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'unconfirmed_state', 'armed', 'replacement: the refused claim leaves the grant armed') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'confirmed_state', 'pending_claim', 'replacement: the confirmed claim reaches pending_claim') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'confirmed_replace', 'true', 'replacement: the pending claim is flagged as a replacement') FROM xfer_results WHERE k='replacement';

-- 19. The freeze during the window.
SELECT is(v->>'b_frozen_error', 'identity_frozen', 'replacement: the claimant''s write during the window raises identity_frozen') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'grant_after_frozen_write', 'pending_claim', 'replacement: the refused write cancels nothing') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_claim_pending', 'true', 'replacement: the claimant''s status shows claim_pending') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_claim_replaces', 'true', 'replacement: the status flags it as a replacement (the freeze notice)') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_reads_work', 'true', 'replacement: reads keep working while frozen') FROM xfer_results WHERE k='replacement';

-- 20. Completion: the admin-side hard-delete proof (superuser counts, not
-- what RLS shows — genuine row absence).
SELECT is(v->>'approve_state', 'completed', 'replacement: approval completes the replacement') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'approve_replaced', 'true', 'replacement: the completion reports the claimant identity was replaced') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_profile_is_as', 'true', 'replacement: exactly one profile remains under B''s uid, and it is A''s — B''s own row was deleted') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_trust', '7', 'replacement: the profile now under B''s uid carries A''s trust score') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'obs_b_rows', '0', 'replacement: B''s report row is genuinely gone') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'obs_a_now_bs', '1', 'replacement: A''s report now belongs to B''s uid') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'a_obs_rows', '0', 'replacement: A''s uid keeps none of it') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_polls', '0', 'replacement: no B-owned poll remains') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'poll_b_rows', '0', 'replacement: B''s poll row is genuinely gone') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'poll_b_options', '0', 'replacement: its options went with it') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'poll_b_votes', '0', 'replacement: the bystander''s vote on B''s poll went with it too') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'verifications_on_obs_b', '0', 'replacement: the bystander''s verification on B''s report went with it too') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_grants', '0', 'replacement: B''s own export grants are all gone — no stale .atid survives') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'a_tombstoned', 'true', 'replacement: A''s uid is tombstoned (superseded on the origin device)') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_tombstoned', 'false', 'replacement: B''s uid is NOT tombstoned — it was replaced onto') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_can_write', 'true', 'replacement: B''s uid writes normally afterwards (as A''s identity)') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'b_sees_moved_report', 'true', 'replacement: B sees A''s history as their own') FROM xfer_results WHERE k='replacement';
SELECT is(v->>'stale_b_code', 'transfer_unknown', 'replacement: B''s pre-replacement .atid code finds no grant at all') FROM xfer_results WHERE k='replacement';

-- 21. Replacement vetoed: B frozen, then fully intact.
SELECT is(v->>'b_frozen_error', 'identity_frozen', 'replacement veto: B''s action during the window is frozen, not cancelling') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'grant_after_b_write', 'pending_claim', 'replacement veto: B''s refused action leaves the claim pending') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'owner_write_ok', 'true', 'replacement veto: the owner''s veto write itself succeeds') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'grant_state', 'aborted', 'replacement veto: the owner''s activity aborts a replacement claim too') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'grant_reason', 'owner_activity', 'replacement veto: the abort reason names the owner''s activity') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'b_unfrozen_write', 'true', 'replacement veto: B writes normally once the claim is dead') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'b_obs_intact', 'true', 'replacement veto: B''s history is fully intact') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'b_own_grant_still_armed', 'true', 'replacement veto: B''s own unrelated export grant survived the abort') FROM xfer_results WHERE k='replacement_veto';
SELECT is(v->>'b_claim_pending_after', 'false', 'replacement veto: the status no longer shows a pending claim') FROM xfer_results WHERE k='replacement_veto';

-- 22. Atomicity: a failure after the deletion rolls everything back.
SELECT is(v->>'fail_error', 'injected_failure', 'rollback: the injected failure propagates') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'grant_state', 'pending_claim', 'rollback: the grant is still pending after the failed finalization') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'b_profiles', '1', 'rollback: B''s profile survived the failed finalization') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'b_obs', '1', 'rollback: B''s report survived') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'b_polls', '1', 'rollback: B''s poll survived') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'b_votes', '1', 'rollback: the vote on B''s poll survived') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'b_verifications', '1', 'rollback: the verification on B''s report survived') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'b_grants', '1', 'rollback: B''s own armed grant survived') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'a_profiles', '1', 'rollback: A''s profile was not consumed') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'a_obs', '1', 'rollback: A''s history was not moved') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'retry_state', 'completed', 'rollback: the retry after the failure completes') FROM xfer_results WHERE k='replacement_rollback';
SELECT is(v->>'obs_b_after_retry', '0', 'rollback: and the completed retry does delete B''s report') FROM xfer_results WHERE k='replacement_rollback';

-- 23. Replacement edges.
SELECT is(v->>'fresh_replace_code', 'nothing_to_replace', 'edges: a fresh device passing the replacement flag has nothing to replace') FROM xfer_results WHERE k='replacement_edges';
SELECT is(v->>'grant_armed_after', 'true', 'edges: the grant survives the nothing-to-replace refusal') FROM xfer_results WHERE k='replacement_edges';
SELECT is(v->>'b_claim_state', 'pending_claim', 'edges: a history-holding claimant confirms into pending_claim') FROM xfer_results WHERE k='replacement_edges';
SELECT is(v->>'second_code', 'transfer_in_progress', 'edges: a uid mid-claim cannot open a second one on another code') FROM xfer_results WHERE k='replacement_edges';

SELECT * FROM finish();
ROLLBACK;
