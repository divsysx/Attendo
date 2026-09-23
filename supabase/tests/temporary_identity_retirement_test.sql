-- pgTAP: temporary identity retirement for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Covers migration 0021's contract end to end, as impersonated users.
-- Unlike the transfer tests, this one impersonates with FULL JWT claims
-- (request.jwt.claims), because the retirement RPC branches on
-- is_anonymous as well as sub — the flag GoTrue mints for anonymous
-- sessions, and the one an account caller must not be able to fake by
-- simply being themselves.
--
--   * the retire path: B (anonymous, seeded across every identity-owned
--     table) retires in favour of established A — grants, profile and the
--     whole cascade graph go, the tombstone lands, A is untouched, B's
--     auth.users row survives (the documented limitation), and a
--     straggler write under B's still-valid token hits
--     identity_superseded
--   * idempotency: the second call answers already_retired, deleting
--     nothing further
--   * not_anonymous: an account identity can never retire itself — not
--     with the flag false, not with the claim absent (an older token)
--   * the destination is a PRECONDITION, never a target: an unestablished
--     destination declines with nothing deleted; null and self are
--     invalid; naming another anonymous reporter as the destination still
--     deletes only the caller
--   * not_authenticated: no uid in the claims at all
--   * the §2 loop: B retired, fresh C minted, C reports, C retired — A
--     intact throughout, exactly one tombstone per retired identity, no
--     accumulating debris
--   * an independent anonymous identity that never signs in is untouched
--   * atomicity: a failure injected at the tombstone insert (after both
--     deletes) rolls the whole retirement back; the retry completes
--   * lockdown: the anon role cannot execute; an authenticated caller
--     always gets an envelope, never a permission error
--
-- Each scenario runs once into a temp table; the assertions read from
-- there. Plpgsql scenario bodies run their steps as sequential assignment
-- statements ONLY: jsonb_build_object's argument evaluation order is
-- undefined, so any two RPC calls inside one argument list can interleave
-- arbitrarily.

BEGIN;
SELECT plan(59);

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

-- Run an RPC as a user whose JWT carries full claims: sub plus, when
-- p_anon is true/false, is_anonymous. p_anon NULL impersonates an older
-- token that predates the claim; p_uid NULL impersonates a caller with no
-- sub at all (not_authenticated).
create or replace function pg_temp.as_claims(p_uid uuid, p_anon boolean, p_fn text) returns jsonb
language plpgsql as $$
declare
  claims jsonb := case
    when p_uid is null then '{}'::jsonb
    when p_anon is null then jsonb_build_object('sub', p_uid)
    else jsonb_build_object('sub', p_uid, 'is_anonymous', p_anon)
  end;
  result jsonb;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', claims::text, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_uid::text, ''), true);
  execute 'select ' || p_fn into result;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return result;
end;
$$;

-- Run an RPC as a user and capture the raised exception's message, or
-- 'no exception' when the call returned normally.
create or replace function pg_temp.rpc_exc(p_uid uuid, p_anon boolean, p_fn text) returns text
language plpgsql as $$
declare msg text;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims',
    jsonb_build_object('sub', p_uid, 'is_anonymous', p_anon)::text, true);
  perform set_config('request.jwt.claim.sub', p_uid::text, true);
  begin
    execute 'select ' || p_fn;
  exception when others then
    get stacked diagnostics msg = message_text;
  end;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return coalesce(msg, 'no exception');
end;
$$;

-- True when the call as the anon role is refused (no EXECUTE grant), as
-- opposed to running and returning an envelope.
create or replace function pg_temp.denied_as_anon(p_fn text) returns boolean
language plpgsql as $$
begin
  perform set_config('role', 'anon', true);
  perform set_config('request.jwt.claims',
    jsonb_build_object('sub', pg_temp.new_user(), 'is_anonymous', true)::text, true);
  execute 'select ' || p_fn;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  return false;
exception when others then
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claims', '', true);
  return true;
end;
$$;

-- Submit a report as p_uid; return the observation id.
create or replace function pg_temp.report_by(p_uid uuid, p_room text) returns uuid
language plpgsql as $$
declare r jsonb;
begin
  r := pg_temp.as_claims(p_uid, true, format(
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
  r := pg_temp.as_claims(p_uid, true, format(
    'community.create_poll(%L::text, null::text, null::text, null::date, null::smallint, %L::text, %L::jsonb, %L::uuid, 0::bigint)',
    p_room, 'Is the room live?', '["Yes","No"]', gen_random_uuid()));
  return (r->>'id')::uuid;
end;
$$;

-- Vote on a poll as p_uid.
create or replace function pg_temp.vote_by(p_uid uuid, p_poll_id uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_claims(p_uid, true, format(
    'community.cast_vote(%L::uuid, 0::smallint)', p_poll_id));
end;
$$;

-- Corroborate an observation as p_uid.
create or replace function pg_temp.verify_by(p_uid uuid, p_obs_id uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_claims(p_uid, true, format(
    'community.verify(%L::uuid, true::boolean, %L::uuid)', p_obs_id, gen_random_uuid()));
end;
$$;

-- B's own export grant, the stale .atid a retirement must clear.
create or replace function pg_temp.begin_transfer(p_uid uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_claims(p_uid, true, 'community.begin_identity_transfer()');
end;
$$;

-- The retirement call itself, as an anonymous caller (B's live session).
-- A NULL destination is spelled out literally: format %L of NULL is not
-- something to lean on.
create or replace function pg_temp.retire(p_uid uuid, p_dest uuid) returns jsonb
language plpgsql as $$
begin
  if p_dest is null then
    return pg_temp.as_claims(p_uid, true, 'community.retire_reporting_identity(null::uuid)');
  end if;
  return pg_temp.as_claims(p_uid, true, format(
    'community.retire_reporting_identity(%L::uuid)', p_dest));
end;
$$;

-- The same call as an account caller (is_anonymous false).
create or replace function pg_temp.retire_as_account(p_uid uuid, p_dest uuid) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_claims(p_uid, false, format(
    'community.retire_reporting_identity(%L::uuid)', p_dest));
end;
$$;

-- Scenarios (each runs once; results land in retire_results) ---------------

-- 1. The retire path: B seeded across every identity-owned table, then
-- retired in favour of established A. The admin-side zero-rows proof runs
-- as the test runner (superuser — genuine row absence, not what RLS
-- hides).
create or replace function pg_temp.retire_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  v uuid := pg_temp.new_user();
  obs_a uuid; poll_a uuid; obs_b uuid; poll_b uuid;
  r jsonb;
  b_profiles bigint; obs_b_rows bigint; poll_b_rows bigint;
  poll_b_options bigint; poll_b_votes bigint; ver_b_rows bigint;
  b_grants bigint; b_vote_on_a bigint;
  b_tomb boolean; a_profiles bigint; a_obs bigint; poll_a_rows bigint;
  b_auth_row bigint;
  straggler text;
begin
  obs_a := pg_temp.report_by(a, 'ret-room-a');
  poll_a := pg_temp.poll_by(a, 'ret-room-a');
  obs_b := pg_temp.report_by(b, 'ret-room-b');
  poll_b := pg_temp.poll_by(b, 'ret-room-b');
  perform pg_temp.vote_by(v, poll_b);    -- a bystander's vote on B's poll
  perform pg_temp.verify_by(v, obs_b);   -- a bystander's verification on B's report
  perform pg_temp.vote_by(b, poll_a);    -- B's vote on A's poll
  perform pg_temp.begin_transfer(b);     -- B's own armed export grant

  r := pg_temp.retire(b, a);

  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into obs_b_rows from community.observations where id = obs_b;
  select count(*) into poll_b_rows from community.polls where id = poll_b;
  select count(*) into poll_b_options from community.poll_options where poll_id = poll_b;
  select count(*) into poll_b_votes from community.poll_votes where poll_id = poll_b;
  select count(*) into ver_b_rows from community.verifications where observation_id = obs_b;
  select count(*) into b_grants from community.reporter_transfer_grants where owner_user_id = b;
  select count(*) into b_vote_on_a from community.poll_votes where poll_id = poll_a and user_id = b;
  b_tomb := exists (select 1 from community.superseded_identities where old_user_id = b);
  select count(*) into a_profiles from community.reporter_profiles where user_id = a;
  select count(*) into a_obs from community.observations where reporter_id = a;
  select count(*) into poll_a_rows from community.polls where id = poll_a;
  select count(*) into b_auth_row from auth.users where id = b;

  -- A straggler write under B's still-valid access token (it cannot be
  -- revoked retroactively) must hit the tombstone, not resurrect B.
  straggler := pg_temp.rpc_exc(b, true, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'ret-room-straggler', '{}', gen_random_uuid()));

  return jsonb_build_object(
    'code', r->>'code',
    'ok', r->>'ok',
    'server_now_present', r ? 'server_now',
    'b_profiles', b_profiles,
    'obs_b_rows', obs_b_rows,
    'poll_b_rows', poll_b_rows,
    'poll_b_options', poll_b_options,
    'poll_b_votes', poll_b_votes,
    'verifications_on_obs_b', ver_b_rows,
    'b_grants', b_grants,
    'b_vote_on_a_poll', b_vote_on_a,
    'b_tombstoned', b_tomb,
    'a_profiles', a_profiles,
    'a_obs', a_obs,
    'poll_a_rows', poll_a_rows,
    'b_auth_row', b_auth_row,
    'straggler_error', straggler);
end;
$$;

-- 2. Idempotency: the retried sign-in, the duplicate callback.
create or replace function pg_temp.idempotent_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  first jsonb; second jsonb;
  b_profiles bigint; b_tombstones bigint;
begin
  perform pg_temp.report_by(a, 'idem-room-a');
  perform pg_temp.report_by(b, 'idem-room-b');
  first := pg_temp.retire(b, a);
  second := pg_temp.retire(b, a);
  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into b_tombstones from community.superseded_identities where old_user_id = b;
  return jsonb_build_object(
    'first_code', first->>'code',
    'second_code', second->>'code',
    'second_ok', second->>'ok',
    'b_profiles', b_profiles,
    'b_tombstones', b_tombstones);
end;
$$;

-- 3. not_anonymous: an account identity can never retire itself.
create or replace function pg_temp.not_anonymous_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  c uuid := pg_temp.new_user();
  r jsonb;
  a_profiles bigint; a_obs bigint;
  a_tomb boolean;
begin
  perform pg_temp.report_by(a, 'acct-room-a');
  perform pg_temp.report_by(c, 'acct-room-c');
  r := pg_temp.retire_as_account(a, c);
  select count(*) into a_profiles from community.reporter_profiles where user_id = a;
  select count(*) into a_obs from community.observations where reporter_id = a;
  a_tomb := exists (select 1 from community.superseded_identities where old_user_id = a);
  return jsonb_build_object(
    'code', r->>'code',
    'ok', r->>'ok',
    'a_profiles', a_profiles,
    'a_obs', a_obs,
    'a_tombstoned', a_tomb);
end;
$$;

-- 4. destination_not_established: a destination with no community identity
-- (a brand-new GitHub account's first sign-in) declines, deleting nothing.
create or replace function pg_temp.no_destination_scenario() returns jsonb
language plpgsql as $$
declare
  b uuid := pg_temp.new_user();
  fresh uuid := pg_temp.new_user();
  obs_b uuid;
  r jsonb;
  b_profiles bigint; b_obs bigint;
  b_tomb boolean;
begin
  obs_b := pg_temp.report_by(b, 'nodest-room-b');
  r := pg_temp.retire(b, fresh);
  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into b_obs from community.observations where id = obs_b;
  b_tomb := exists (select 1 from community.superseded_identities where old_user_id = b);
  return jsonb_build_object(
    'code', r->>'code',
    'ok', r->>'ok',
    'b_profiles', b_profiles,
    'b_obs', b_obs,
    'b_tombstoned', b_tomb);
end;
$$;

-- 5. invalid_destination: null and self.
create or replace function pg_temp.invalid_destination_scenario() returns jsonb
language plpgsql as $$
declare
  b uuid := pg_temp.new_user();
  r_null jsonb; r_self jsonb;
  b_profiles bigint; b_obs bigint;
begin
  perform pg_temp.report_by(b, 'invdest-room-b');
  r_null := pg_temp.retire(b, null);
  r_self := pg_temp.retire(b, b);
  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into b_obs from community.observations where reporter_id = b;
  return jsonb_build_object(
    'null_code', r_null->>'code',
    'self_code', r_self->>'code',
    'b_profiles', b_profiles,
    'b_obs', b_obs);
end;
$$;

-- 6. The §2 loop: B retired, fresh C minted and active, C retired — A
-- intact throughout, exactly one tombstone per retired identity.
create or replace function pg_temp.loop_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  c uuid := pg_temp.new_user();
  r_b jsonb; r_c jsonb;
  a_profiles bigint; a_obs bigint;
  tombstones bigint;
  c_profiles bigint; c_obs bigint;
begin
  perform pg_temp.report_by(a, 'loop-room-a');
  perform pg_temp.report_by(b, 'loop-room-b');
  r_b := pg_temp.retire(b, a);
  -- The next Community contact after the sign-out mints a fresh C, which
  -- reports, then is itself retired by the next returning sign-in.
  perform pg_temp.report_by(c, 'loop-room-c');
  r_c := pg_temp.retire(c, a);
  select count(*) into a_profiles from community.reporter_profiles where user_id = a;
  select count(*) into a_obs from community.observations where reporter_id = a;
  select count(*) into tombstones from community.superseded_identities
    where old_user_id in (b, c);
  select count(*) into c_profiles from community.reporter_profiles where user_id = c;
  select count(*) into c_obs from community.observations where reporter_id = c;
  return jsonb_build_object(
    'b_code', r_b->>'code',
    'c_code', r_c->>'code',
    'a_profiles', a_profiles,
    'a_obs', a_obs,
    'tombstones', tombstones,
    'c_profiles', c_profiles,
    'c_obs', c_obs);
end;
$$;

-- 7. An independent anonymous identity that never signs in is untouched:
-- no cron, no inactivity sweep, no batch — only an explicit request from
-- the identity's own live session ever retires anything.
create or replace function pg_temp.independent_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  x uuid := pg_temp.new_user();
  x_obs uuid;
  x_profiles bigint; x_obs_rows bigint;
begin
  perform pg_temp.report_by(a, 'indep-room-a');
  x_obs := pg_temp.report_by(x, 'indep-room-x');
  perform pg_temp.report_by(b, 'indep-room-b');
  perform pg_temp.retire(b, a);
  select count(*) into x_profiles from community.reporter_profiles where user_id = x;
  select count(*) into x_obs_rows from community.observations where id = x_obs;
  return jsonb_build_object(
    'x_profiles', x_profiles,
    'x_obs', x_obs_rows);
end;
$$;

-- 8. The destination is a precondition, never a target: C naming B (an
-- established anonymous reporter) as the destination still deletes only
-- C's own identity.
create or replace function pg_temp.destination_is_precondition_scenario() returns jsonb
language plpgsql as $$
declare
  b uuid := pg_temp.new_user();
  c uuid := pg_temp.new_user();
  r jsonb;
  b_profiles bigint; b_obs bigint;
  c_profiles bigint;
begin
  perform pg_temp.report_by(b, 'precond-room-b');
  perform pg_temp.report_by(c, 'precond-room-c');
  r := pg_temp.retire(c, b);
  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into b_obs from community.observations where reporter_id = b;
  select count(*) into c_profiles from community.reporter_profiles where user_id = c;
  return jsonb_build_object(
    'code', r->>'code',
    'b_profiles', b_profiles,
    'b_obs', b_obs,
    'c_profiles', c_profiles);
end;
$$;

-- 9. Atomicity: a failure injected at the tombstone insert — after both
-- deletes — must roll the entire retirement back: B's profile and grants
-- survive untouched, no tombstone lands, and the retry completes.
create or replace function pg_temp.rollback_scenario() returns jsonb
language plpgsql as $$
declare
  a uuid := pg_temp.new_user();
  b uuid := pg_temp.new_user();
  fail_error text;
  b_profiles bigint; b_grants bigint;
  b_tomb boolean;
  retry jsonb;
  b_profiles_after bigint;
begin
  perform pg_temp.report_by(a, 'rb-room-a');
  perform pg_temp.report_by(b, 'rb-room-b');
  perform pg_temp.begin_transfer(b);

  -- The injected failure. Runs as the test connection (superuser); the
  -- whole test file rolls back, so the schema pollution is
  -- transaction-local. (The nested dollar-quote tag is $t$ because it
  -- cannot reuse the outer one.)
  create or replace function community.__test_boom_on_retire() returns trigger
    language plpgsql as $t$ begin raise exception 'injected_failure'; end $t$;
  create trigger __test_boom_before_retire
    before insert on community.superseded_identities
    for each row
    execute function community.__test_boom_on_retire();

  fail_error := pg_temp.rpc_exc(b, true, format(
    'community.retire_reporting_identity(%L::uuid)', a));

  drop trigger __test_boom_before_retire on community.superseded_identities;
  drop function community.__test_boom_on_retire();

  select count(*) into b_profiles from community.reporter_profiles where user_id = b;
  select count(*) into b_grants from community.reporter_transfer_grants where owner_user_id = b;
  b_tomb := exists (select 1 from community.superseded_identities where old_user_id = b);

  retry := pg_temp.retire(b, a);
  select count(*) into b_profiles_after from community.reporter_profiles where user_id = b;

  return jsonb_build_object(
    'fail_error', fail_error,
    'b_profiles', b_profiles,
    'b_grants', b_grants,
    'b_tombstoned', b_tomb,
    'retry_code', retry->>'code',
    'b_profiles_after', b_profiles_after);
end;
$$;

-- Run every scenario exactly once.
create temp table retire_results (k text primary key, v jsonb);
insert into retire_results values
  ('retire',      pg_temp.retire_scenario()),
  ('idempotent',  pg_temp.idempotent_scenario()),
  ('not_anonymous', pg_temp.not_anonymous_scenario()),
  ('no_destination', pg_temp.no_destination_scenario()),
  ('invalid_destination', pg_temp.invalid_destination_scenario()),
  ('loop',        pg_temp.loop_scenario()),
  ('independent', pg_temp.independent_scenario()),
  ('precondition', pg_temp.destination_is_precondition_scenario()),
  ('rollback',    pg_temp.rollback_scenario());

-- Assertions -------------------------------------------------------------------

-- 1. The retire path.
SELECT is(v->>'code', 'retired', 'retire: the call answers retired') FROM retire_results WHERE k='retire';
SELECT is(v->>'ok', 'true', 'retire: the envelope says ok') FROM retire_results WHERE k='retire';
SELECT is(v->>'server_now_present', 'true', 'retire: the envelope carries server_now') FROM retire_results WHERE k='retire';
SELECT is(v->>'b_profiles', '0', 'retire: B''s profile is genuinely gone') FROM retire_results WHERE k='retire';
SELECT is(v->>'obs_b_rows', '0', 'retire: B''s report is genuinely gone') FROM retire_results WHERE k='retire';
SELECT is(v->>'poll_b_rows', '0', 'retire: B''s poll is genuinely gone') FROM retire_results WHERE k='retire';
SELECT is(v->>'poll_b_options', '0', 'retire: its options went with it') FROM retire_results WHERE k='retire';
SELECT is(v->>'poll_b_votes', '0', 'retire: the bystander''s vote on B''s poll went with it too') FROM retire_results WHERE k='retire';
SELECT is(v->>'verifications_on_obs_b', '0', 'retire: the bystander''s verification on B''s report went with it too') FROM retire_results WHERE k='retire';
SELECT is(v->>'b_grants', '0', 'retire: B''s own export grants are all gone — no stale .atid survives') FROM retire_results WHERE k='retire';
SELECT is(v->>'b_vote_on_a_poll', '0', 'retire: B''s vote on A''s poll went with B') FROM retire_results WHERE k='retire';
SELECT is(v->>'b_tombstoned', 'true', 'retire: B''s uid is tombstoned') FROM retire_results WHERE k='retire';
SELECT is(v->>'a_profiles', '1', 'retire: A''s profile is untouched') FROM retire_results WHERE k='retire';
SELECT is(v->>'a_obs', '1', 'retire: A''s report is untouched') FROM retire_results WHERE k='retire';
SELECT is(v->>'poll_a_rows', '1', 'retire: A''s poll is untouched') FROM retire_results WHERE k='retire';
SELECT is(v->>'b_auth_row', '1', 'retire: B''s auth.users row survives — the documented limitation, asserted as designed') FROM retire_results WHERE k='retire';
SELECT is(v->>'straggler_error', 'identity_superseded', 'retire: a straggler write under B''s still-valid token is refused by the tombstone') FROM retire_results WHERE k='retire';

-- 2. Idempotency.
SELECT is(v->>'first_code', 'retired', 'idempotent: the first call retires') FROM retire_results WHERE k='idempotent';
SELECT is(v->>'second_code', 'already_retired', 'idempotent: the second call answers already_retired') FROM retire_results WHERE k='idempotent';
SELECT is(v->>'second_ok', 'true', 'idempotent: the second call is ok, not an error') FROM retire_results WHERE k='idempotent';
SELECT is(v->>'b_profiles', '0', 'idempotent: the second call deletes nothing further') FROM retire_results WHERE k='idempotent';
SELECT is(v->>'b_tombstones', '1', 'idempotent: exactly one tombstone, not two') FROM retire_results WHERE k='idempotent';

-- 3. not_anonymous.
SELECT is(v->>'code', 'not_anonymous', 'not anonymous: an account identity cannot retire itself') FROM retire_results WHERE k='not_anonymous';
SELECT is(v->>'ok', 'false', 'not anonymous: the refusal is an envelope, ok=false') FROM retire_results WHERE k='not_anonymous';
SELECT is(v->>'a_profiles', '1', 'not anonymous: the account''s profile is intact') FROM retire_results WHERE k='not_anonymous';
SELECT is(v->>'a_obs', '1', 'not anonymous: the account''s history is intact') FROM retire_results WHERE k='not_anonymous';
SELECT is(v->>'a_tombstoned', 'false', 'not anonymous: the account is not tombstoned') FROM retire_results WHERE k='not_anonymous';
-- An older token without the is_anonymous claim reads as an account: the
-- coalesce defaults to false, never to "assume anonymous".
SELECT is(pg_temp.as_claims(pg_temp.new_user(), null,
  'community.retire_reporting_identity(''' || gen_random_uuid()::text || '''::uuid)')->>'code',
  'not_anonymous', 'not anonymous: a token without the claim is treated as an account');

-- 4. destination_not_established.
SELECT is(v->>'code', 'destination_not_established', 'no destination: an unestablished destination declines') FROM retire_results WHERE k='no_destination';
SELECT is(v->>'ok', 'false', 'no destination: the decline is an envelope, ok=false') FROM retire_results WHERE k='no_destination';
SELECT is(v->>'b_profiles', '1', 'no destination: B''s profile is intact') FROM retire_results WHERE k='no_destination';
SELECT is(v->>'b_obs', '1', 'no destination: B''s report is intact') FROM retire_results WHERE k='no_destination';
SELECT is(v->>'b_tombstoned', 'false', 'no destination: B is not tombstoned') FROM retire_results WHERE k='no_destination';

-- 5. invalid_destination.
SELECT is(v->>'null_code', 'invalid_destination', 'invalid destination: a null destination is invalid') FROM retire_results WHERE k='invalid_destination';
SELECT is(v->>'self_code', 'invalid_destination', 'invalid destination: naming yourself is invalid') FROM retire_results WHERE k='invalid_destination';
SELECT is(v->>'b_profiles', '1', 'invalid destination: B is intact after both refusals') FROM retire_results WHERE k='invalid_destination';
SELECT is(v->>'b_obs', '1', 'invalid destination: B''s history is intact after both refusals') FROM retire_results WHERE k='invalid_destination';

-- 6. not_authenticated: no sub in the claims at all.
SELECT is(pg_temp.as_claims(null, true, 'community.retire_reporting_identity(''' || gen_random_uuid()::text || '''::uuid)')->>'code',
  'not_authenticated', 'not authenticated: a caller with no sub gets the envelope');

-- 7. The §2 loop.
SELECT is(v->>'b_code', 'retired', 'loop: the first temporary identity retires') FROM retire_results WHERE k='loop';
SELECT is(v->>'c_code', 'retired', 'loop: the second temporary identity retires') FROM retire_results WHERE k='loop';
SELECT is(v->>'a_profiles', '1', 'loop: A''s profile is intact throughout') FROM retire_results WHERE k='loop';
SELECT is(v->>'a_obs', '1', 'loop: A''s history is intact throughout') FROM retire_results WHERE k='loop';
SELECT is(v->>'tombstones', '2', 'loop: exactly one tombstone per retired identity, no debris accumulating') FROM retire_results WHERE k='loop';
SELECT is(v->>'c_profiles', '0', 'loop: the second temporary identity is fully cleared') FROM retire_results WHERE k='loop';
SELECT is(v->>'c_obs', '0', 'loop: its reports went with it') FROM retire_results WHERE k='loop';

-- 8. An independent anonymous identity is untouched.
SELECT is(v->>'x_profiles', '1', 'independent: an anonymous identity that never signs in keeps its profile') FROM retire_results WHERE k='independent';
SELECT is(v->>'x_obs', '1', 'independent: and its reports — there is no inactivity sweep') FROM retire_results WHERE k='independent';

-- 9. The destination is a precondition, never a target.
SELECT is(v->>'code', 'retired', 'precondition: C naming B as the destination still retires only C') FROM retire_results WHERE k='precondition';
SELECT is(v->>'b_profiles', '1', 'precondition: B, the named destination, loses nothing') FROM retire_results WHERE k='precondition';
SELECT is(v->>'b_obs', '1', 'precondition: B''s history is intact') FROM retire_results WHERE k='precondition';
SELECT is(v->>'c_profiles', '0', 'precondition: only C''s own identity was deleted') FROM retire_results WHERE k='precondition';

-- 10. Atomicity: a failure after the deletes rolls everything back.
SELECT is(v->>'fail_error', 'injected_failure', 'rollback: the injected failure propagates') FROM retire_results WHERE k='rollback';
SELECT is(v->>'b_profiles', '1', 'rollback: B''s profile survived the failed retirement') FROM retire_results WHERE k='rollback';
SELECT is(v->>'b_grants', '1', 'rollback: B''s own armed grant survived') FROM retire_results WHERE k='rollback';
SELECT is(v->>'b_tombstoned', 'false', 'rollback: no tombstone landed') FROM retire_results WHERE k='rollback';
SELECT is(v->>'retry_code', 'retired', 'rollback: the retry after the failure completes') FROM retire_results WHERE k='rollback';
SELECT is(v->>'b_profiles_after', '0', 'rollback: and the completed retry does delete B''s profile') FROM retire_results WHERE k='rollback';

-- 11. Lockdown: the anon role cannot execute; an authenticated caller
-- always gets an envelope, never a permission error.
SELECT is(pg_temp.denied_as_anon('community.retire_reporting_identity(''' || gen_random_uuid()::text || '''::uuid)'),
  true, 'lockdown: the anon role cannot execute the retirement');
SELECT is(pg_temp.retire(pg_temp.new_user(), pg_temp.new_user())->>'code',
  'destination_not_established', 'lockdown: an authenticated caller gets an envelope, never a permission error');

SELECT * FROM finish();
ROLLBACK;
