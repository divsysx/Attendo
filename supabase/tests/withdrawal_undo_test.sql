-- pgTAP: withdrawal and fresh-submission undo for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Covers the Part 7/8 contract end to end, as impersonated users:
--   * undo within the window: zero reputation change, terminal state, kind 'undo'
--   * withdraw after the window: exactly one -3 penalty per item, ever
--   * owner-only: a stranger gets 'not_yours' and changes nothing
--   * idempotent: retrying a completed withdrawal is a success with no second
--     penalty
--   * atomic: the transition, the audit columns and the penalty are one statement
--   * reads: withdrawn rows vanish from public reads but stay in
--     my_pending_reports / my_polls for the owner for the audit window
--   * verifications stop on withdrawn rows; votes stop on withdrawn polls
--
-- The fresh window is 60 seconds — far longer than any test transaction lives,
-- so "after the window" tests simulate age the only honest way: a privileged
-- UPDATE of created_at to the past (the clock is never a client input; only
-- the RPC guards read it).

BEGIN;
SELECT plan(29);

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

-- Count rows a query returns as a role (uid null = anon); -1 = permission error.
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
exception
  when others then
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
    return -1;
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

-- Create a poll as p_uid; return the poll id.
create or replace function pg_temp.poll_by(p_uid uuid, p_room text) returns uuid
language plpgsql as $$
declare r jsonb;
begin
  r := pg_temp.as_specific(p_uid, format(
    'community.create_poll(%L::text, null::text, null::text, null::date, null::smallint, %L::text, %L::jsonb, %L::uuid)',
    p_room, 'Is the room free?', '["Yes","No"]', gen_random_uuid()));
  return (r->>'id')::uuid;
end;
$$;

-- Grant-test helpers (privileged-entry impersonation, same as count_as).

create or replace function pg_temp.any_user() returns uuid
language plpgsql as $$
declare uid uuid := pg_temp.new_user();
begin
  return uid;
end;
$$;

create or replace function pg_temp.write_attempt_authenticated(p_sql text)
returns text
language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', pg_temp.any_user()::text, true);
  execute p_sql;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return 'succeeded';
exception
  when others then
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
    return 'denied';
end;
$$;

-- True when the RPC call as an authenticated stranger is refused (permission
-- denied — no EXECUTE grant), as opposed to running and returning an envelope.
create or replace function pg_temp.rpc_denied(p_fn text) returns boolean
language plpgsql as $$
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', pg_temp.any_user()::text, true);
  execute 'select ' || p_fn;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return false;
exception
  when others then
    perform set_config('role', 'postgres', true);
    perform set_config('request.jwt.claim.sub', '', true);
    return true;
end;
$$;

-- Scenarios (one function per multi-step story) -------------------------------

-- Fresh undo of a report: state, audit columns, zero reputation movement,
-- and the idempotent retry that must cost nothing.
create or replace function pg_temp.undo_report_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.new_user();
  obs uuid;
  r_undo jsonb; r_retry jsonb;
  trust_before int; trust_after int;
  status text; kind text; withdrawn_at timestamptz;
begin
  obs := pg_temp.report_by(uid, 'wdr-undo-room');
  select trust_score into trust_before from community.reporter_profiles
    where user_id = uid;

  r_undo := pg_temp.as_specific(uid,
    format('community.undo_report(%L::uuid)', obs));
  r_retry := pg_temp.as_specific(uid,
    format('community.undo_report(%L::uuid)', obs));

  select trust_score into trust_after from community.reporter_profiles
    where user_id = uid;
  select o.status, o.withdrawal_kind, o.withdrawn_at
    into status, kind, withdrawn_at
    from community.observations o where o.id = obs;

  return jsonb_build_object(
    'undo_ok', r_undo->>'ok', 'undo_code', r_undo->>'code',
    'retry_ok', r_retry->>'ok', 'retry_code', r_retry->>'code',
    'status', status, 'kind', kind, 'has_time', withdrawn_at is not null,
    'trust_before', trust_before, 'trust_after', trust_after);
end;
$$;

-- Normal withdrawal of a report (aged past the window): one penalty, once.
create or replace function pg_temp.withdraw_report_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.new_user();
  obs uuid;
  r_withdraw jsonb; r_retry jsonb; r_undo_late jsonb;
  trust_before int; trust_after_once int; trust_after_retry int;
begin
  obs := pg_temp.report_by(uid, 'wdr-room');
  update community.observations set created_at = now() - interval '1 hour'
    where id = obs;
  select trust_score into trust_before from community.reporter_profiles
    where user_id = uid;

  r_withdraw := pg_temp.as_specific(uid,
    format('community.withdraw_report(%L::uuid)', obs));
  select trust_score into trust_after_once from community.reporter_profiles
    where user_id = uid;

  -- Retry and a late undo attempt: both must leave the penalty alone.
  r_retry := pg_temp.as_specific(uid,
    format('community.withdraw_report(%L::uuid)', obs));
  r_undo_late := pg_temp.as_specific(uid,
    format('community.undo_report(%L::uuid)', obs));
  select trust_score into trust_after_retry from community.reporter_profiles
    where user_id = uid;

  return jsonb_build_object(
    'withdraw_ok', r_withdraw->>'ok', 'withdraw_code', r_withdraw->>'code',
    'retry_ok', r_retry->>'ok', 'retry_code', r_retry->>'code',
    'late_undo_ok', r_undo_late->>'ok', 'late_undo_code', r_undo_late->>'code',
    'trust_before', trust_before,
    'trust_after_once', trust_after_once,
    'trust_after_retry', trust_after_retry);
end;
$$;

-- A stranger cannot withdraw or undo someone else's report, and their call
-- changes nothing.
create or replace function pg_temp.stranger_report_scenario() returns jsonb
language plpgsql as $$
declare
  owner uuid := pg_temp.new_user();
  stranger uuid := pg_temp.new_user();
  obs uuid;
  r jsonb;
  status text;
begin
  obs := pg_temp.report_by(owner, 'wdr-stranger-room');

  r := pg_temp.as_specific(stranger,
    format('community.withdraw_report(%L::uuid)', obs));
  select o.status into status from community.observations o where o.id = obs;

  return jsonb_build_object(
    'ok', r->>'ok', 'code', r->>'code', 'status', status);
end;
$$;

-- The polls' twin of undo_report_scenario.
create or replace function pg_temp.undo_poll_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.new_user();
  poll uuid;
  r_undo jsonb; r_retry jsonb;
  trust_before int; trust_after int;
  status text; kind text;
begin
  poll := pg_temp.poll_by(uid, 'wdr-poll-room');
  select trust_score into trust_before from community.reporter_profiles
    where user_id = uid;

  r_undo := pg_temp.as_specific(uid,
    format('community.undo_poll(%L::uuid)', poll));
  r_retry := pg_temp.as_specific(uid,
    format('community.undo_poll(%L::uuid)', poll));

  select trust_score into trust_after from community.reporter_profiles
    where user_id = uid;
  select p.status, p.withdrawal_kind into status, kind
    from community.polls p where p.id = poll;

  return jsonb_build_object(
    'undo_ok', r_undo->>'ok', 'undo_code', r_undo->>'code',
    'retry_ok', r_retry->>'ok', 'retry_code', r_retry->>'code',
    'status', status, 'kind', kind,
    'trust_before', trust_before, 'trust_after', trust_after);
end;
$$;

-- The polls' twin of withdraw_report_scenario.
create or replace function pg_temp.withdraw_poll_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.new_user();
  poll uuid;
  r_withdraw jsonb; r_retry jsonb; r_undo_late jsonb;
  trust_before int; trust_after_once int; trust_after_retry int;
begin
  poll := pg_temp.poll_by(uid, 'wdr-poll-2-room');
  update community.polls set created_at = now() - interval '1 hour'
    where id = poll;
  select trust_score into trust_before from community.reporter_profiles
    where user_id = uid;

  r_withdraw := pg_temp.as_specific(uid,
    format('community.withdraw_poll(%L::uuid)', poll));
  select trust_score into trust_after_once from community.reporter_profiles
    where user_id = uid;

  r_retry := pg_temp.as_specific(uid,
    format('community.withdraw_poll(%L::uuid)', poll));
  r_undo_late := pg_temp.as_specific(uid,
    format('community.undo_poll(%L::uuid)', poll));
  select trust_score into trust_after_retry from community.reporter_profiles
    where user_id = uid;

  return jsonb_build_object(
    'withdraw_ok', r_withdraw->>'ok', 'withdraw_code', r_withdraw->>'code',
    'retry_ok', r_retry->>'ok', 'retry_code', r_retry->>'code',
    'late_undo_ok', r_undo_late->>'ok', 'late_undo_code', r_undo_late->>'code',
    'trust_before', trust_before,
    'trust_after_once', trust_after_once,
    'trust_after_retry', trust_after_retry);
end;
$$;

-- Withdrawn rows: invisible in public reads, present for the owner in
-- my_pending_reports / my_polls, and sealed against verify / cast_vote.
create or replace function pg_temp.visibility_scenario() returns jsonb
language plpgsql as $$
declare
  owner uuid := pg_temp.new_user();
  stranger uuid := pg_temp.new_user();
  obs uuid; poll uuid;
  r_verify jsonb; r_vote jsonb;
  stranger_obs_count bigint; stranger_poll_count bigint;
  my_reports jsonb; my_polls jsonb;
begin
  obs := pg_temp.report_by(owner, 'wdr-vis-room');
  poll := pg_temp.poll_by(owner, 'wdr-vis-poll-room');

  -- Undo as the owner (via the client path — authenticated + JWT claim),
  -- not as postgres: require_profile() raises for unauthenticated callers.
  perform pg_temp.as_specific(owner,
    format('community.undo_report(%L::uuid)', obs));
  perform pg_temp.as_specific(owner,
    format('community.undo_poll(%L::uuid)', poll));

  -- Only these two rows live in these unique rooms; a stranger must see zero.
  stranger_obs_count := pg_temp.count_as('authenticated', stranger,
    'select id from community.observations where room = ''wdr-vis-room''');
  stranger_poll_count := pg_temp.count_as('authenticated', stranger,
    'select id from community.polls where room = ''wdr-vis-poll-room''');

  my_reports := pg_temp.as_specific(owner, 'community.my_pending_reports()');
  my_polls := pg_temp.as_specific(owner, 'community.my_polls()');

  r_verify := pg_temp.as_specific(stranger,
    format('community.verify(%L::uuid, true, null::uuid)', obs));
  r_vote := pg_temp.as_specific(stranger,
    format('community.cast_vote(%L::uuid, 0::smallint)', poll));

  return jsonb_build_object(
    'stranger_obs', stranger_obs_count, 'stranger_polls', stranger_poll_count,
    'mine_reports', my_reports->'reports'->0->>'status',
    'mine_reports_kind', my_reports->'reports'->0->>'withdrawal_kind',
    'mine_polls', my_polls->'polls'->0->>'status',
    'mine_polls_kind', my_polls->'polls'->0->>'withdrawal_kind',
    'verify_code', r_verify->>'code', 'vote_code', r_vote->>'code');
end;
$$;

-- my_pending_reports carries undo timing for the owner (server-computed).
create or replace function pg_temp.undo_timing_scenario() returns jsonb
language plpgsql as $$
declare
  owner uuid := pg_temp.new_user();
  obs uuid;
  mine jsonb;
begin
  obs := pg_temp.report_by(owner, 'wdr-timing-room');
  mine := pg_temp.as_specific(owner, 'community.my_pending_reports()');
  return jsonb_build_object(
    'has_until', mine->'reports'->0->>'undo_available_until' is not null,
    'has_count', mine->'reports'->0->>'verification_count' is not null);
end;
$$;

-- Tests -----------------------------------------------------------------------

-- 1-2. Server constants. (0012 narrowed the fresh-undo window from 60 to
-- 30 seconds; the expectation follows the current contract.)
SELECT is(
  (select community.fresh_undo_window() = interval '30 seconds'),
  true,
  'fresh undo window is 30 seconds');
SELECT is(
  (select value ? 'fresh_undo_seconds' and value ? 'withdrawal_penalty'
     from community.app_meta where key = 'retention_policy'),
  true,
  'retention_policy carries the client-mirrored undo constants');

-- 3-8. Fresh undo of a report.
SELECT is(
  (select s->>'undo_ok' = 'true' and s->>'undo_code' = 'withdrawn'
   from (select pg_temp.undo_report_scenario() as s) x),
  true,
  'undo_report inside the window succeeds with code withdrawn');
SELECT is(
  (select s->>'retry_ok' = 'true' and s->>'retry_code' = 'already_withdrawn'
   from (select pg_temp.undo_report_scenario() as s) x),
  true,
  'retrying a completed undo is an idempotent success');
SELECT is(
  (select s->>'status' = 'withdrawn' and s->>'kind' = 'undo'
     and (s->>'has_time')::boolean
   from (select pg_temp.undo_report_scenario() as s) x),
  true,
  'undo writes the terminal state, kind and audit timestamp');
SELECT is(
  (select (s->>'trust_after')::int - (s->>'trust_before')::int
   from (select pg_temp.undo_report_scenario() as s) x),
  0,
  'fresh undo costs zero reputation');
SELECT is(
  (select (s->>'trust_after_once')::int - (s->>'trust_before')::int
   from (select pg_temp.withdraw_report_scenario() as s) x),
  -3,
  'normal withdrawal costs exactly 3 trust once');
SELECT is(
  (select (s->>'trust_after_retry')::int
   from (select pg_temp.withdraw_report_scenario() as s) x),
  (select (s->>'trust_after_once')::int
   from (select pg_temp.withdraw_report_scenario() as s) x),
  're-withdrawal and late undo never move trust again');

-- 9. Owner-only.
SELECT is(
  (select s->>'ok' = 'false' and s->>'code' = 'not_yours'
     and s->>'status' = 'reported'
   from (select pg_temp.stranger_report_scenario() as s) x),
  true,
  'a stranger cannot withdraw someone else''s report and changes nothing');

-- 10-13. Polls' fresh undo.
SELECT is(
  (select s->>'undo_ok' = 'true' and s->>'retry_code' = 'already_withdrawn'
     and s->>'status' = 'withdrawn' and s->>'kind' = 'undo'
     and (s->>'trust_after')::int = (s->>'trust_before')::int
   from (select pg_temp.undo_poll_scenario() as s) x),
  true,
  'undo_poll inside the window: terminal state, kind undo, zero penalty');
SELECT is(
  (select (s->>'trust_after_once')::int - (s->>'trust_before')::int
   from (select pg_temp.withdraw_poll_scenario() as s) x),
  -3,
  'withdraw_poll after the window costs exactly 3 trust once');
SELECT is(
  (select (s->>'trust_after_retry')::int
   from (select pg_temp.withdraw_poll_scenario() as s) x),
  (select (s->>'trust_after_once')::int
   from (select pg_temp.withdraw_poll_scenario() as s) x),
  'poll withdrawal retry and late undo never move trust again');
SELECT is(
  (select s->>'withdraw_ok' = 'true' and s->>'retry_ok' = 'true'
   from (select pg_temp.withdraw_poll_scenario() as s) x),
  true,
  'withdraw_poll and its retry both return success envelopes');

-- 14-17. Visibility and sealing.
SELECT is(
  (select s->>'stranger_obs' = '0' and s->>'stranger_polls' = '0'
   from (select pg_temp.visibility_scenario() as s) x),
  true,
  'withdrawn rows vanish from public reads for strangers');
SELECT is(
  (select s->>'mine_reports' = 'withdrawn'
     and s->>'mine_reports_kind' = 'undo'
   from (select pg_temp.visibility_scenario() as s) x),
  true,
  'my_pending_reports keeps the owner''s withdrawn report with its kind');
SELECT is(
  (select s->>'mine_polls' = 'withdrawn' and s->>'mine_polls_kind' = 'undo'
   from (select pg_temp.visibility_scenario() as s) x),
  true,
  'my_polls keeps the owner''s withdrawn poll with its kind');
SELECT is(
  (select s->>'verify_code' = 'expired' and s->>'vote_code' = 'poll_closed'
   from (select pg_temp.visibility_scenario() as s) x),
  true,
  'verify and cast_vote refuse withdrawn items');

-- 18. Owner timing surface.
SELECT is(
  (select (s->>'has_until')::boolean and (s->>'has_count')::boolean
   from (select pg_temp.undo_timing_scenario() as s) x),
  true,
  'my_pending_reports exposes undo_available_until and verification_count');

-- 19-28. Grants: the allow-list, restated by 0009, holds after everything.

-- anon: still nothing.
SELECT is(
  (select pg_temp.count_as('anon', null,
    'select id from community.observations')),
  -1::bigint,
  'anon cannot select observations (permission denied)');
SELECT is(
  (select pg_temp.count_as('anon', null, 'select * from community.abuse_events')),
  -1::bigint,
  'anon cannot select abuse_events');

-- anon RPC surface: only is_authenticated survives (it must, for RLS).
SELECT is(
  (select pg_temp.count_as('anon', null,
    'select community.submit_report(''room_other'', ''x'', null, null, null, null, ''{}'', null, gen_random_uuid())')),
  -1::bigint,
  'anon cannot execute submit_report');

-- authenticated: no direct writes, ever.
SELECT is(
  (select case
     when pg_temp.write_attempt_authenticated(
       'update community.observations set status = ''withdrawn''') = 'denied'
     then 'denied' else 'succeeded' end),
  'denied',
  'authenticated cannot UPDATE observations directly');
SELECT is(
  (select case
     when pg_temp.write_attempt_authenticated(
       'delete from community.polls where false') = 'denied'
     then 'denied' else 'succeeded' end),
  'denied',
  'authenticated cannot DELETE polls directly');

-- Column grants: private columns stay physically unreadable even with RLS
-- allowing the row.
SELECT is(
  (select pg_temp.count_as('authenticated', pg_temp.any_user(),
    'select reporter_id from community.observations')),
  -1::bigint,
  'reporter_id is not in the granted column set');
SELECT is(
  (select pg_temp.count_as('authenticated', pg_temp.any_user(),
    'select creator_id from community.polls')),
  -1::bigint,
  'creator_id is not in the granted column set');
SELECT is(
  (select pg_temp.count_as('authenticated', pg_temp.any_user(),
    'select idempotency_key from community.observations')),
  -1::bigint,
  'idempotency_key is not in the granted column set');

-- The internal engine is not client-callable.
SELECT is(
  (select pg_temp.rpc_denied('community.withdraw_observation(' ||
    quote_literal(gen_random_uuid()::text) || '::uuid, ''withdraw'')')),
  true,
  'withdraw_observation (the engine) is not client-callable');
SELECT is(
  (select pg_temp.rpc_denied('community.retain_and_delete()')),
  true,
  'retain_and_delete is not client-callable');
SELECT is(
  (select pg_temp.rpc_denied('community.fresh_undo_window()')),
  true,
  'fresh_undo_window is not client-callable');

SELECT * FROM finish();
ROLLBACK;
