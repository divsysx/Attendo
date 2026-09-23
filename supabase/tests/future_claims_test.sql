-- pgTAP: future claims — reports about a later slot today.
--
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- A future claim is an expectation, not an observation: "Room 203 will be
-- occupied 2–3 PM today". These tests pin the whole contract added by
-- migration 0007_future_claims.sql:
--   * the shape: a claim carries observation_type='future', an explicit
--     event_date (server today, never another date) and a target interval,
--     and a non-claim report may not carry any of it;
--   * the reclassification: pre-migration rows (room = present, class-bound
--     = historical) kept their honest type;
--   * the validation: class kinds refused, wrong dates refused, out-of-grid
--     hours refused, a slot that already started refused — once 2 PM arrives
--     the claim is no longer about the future;
--   * the expiry: a claim dies at its target slot's end + 30 minutes, so the
--     active view stops showing it without any sweep;
--   * the dedup: the target hour is part of a claim's identity — two claims
--     about different slots of the same room are two claims;
--   * the compatibility: the old client's parameter set (no type, no target)
--     still files a present report, exactly as before.
--
-- Time-of-day: the happy path needs a slot later than now(), so the target is
-- picked adaptively (now's hour + 1, clamped onto the 9..17 grid). When the
-- suite runs after the last slot has started (17:00 UTC on), the same claim
-- is asserted to be refused invalid_target instead — every run exercises the
-- server's decision, whichever side of the slot boundary it falls on.

BEGIN;
-- Scenario blocks fold their checks into ONE composite ok() each: pgTAP's
-- test functions return text, so they cannot be AND-chained mid-statement —
-- the scenario functions carry the individual answers as jsonb flags instead.
SELECT plan(20);

-- Helpers (same impersonation machinery as rpcs_test.sql) --------------------

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

-- submit_report as a fresh user, with the claim parameters.
create or replace function pg_temp.claim_as(
  p_kind text, p_room text, p_obs_type text,
  p_event_date date, p_target_start int, p_idem uuid
) returns jsonb
language plpgsql as $$
declare uid uuid := pg_temp.new_user();
begin
  return pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid, 0::bigint, %L::text, %s::date, %s::smallint)',
    p_kind, p_room, '{}', p_idem, p_obs_type,
    case when p_event_date is null then 'null'
         else format('%L', p_event_date) end,
    coalesce(p_target_start::text, 'null')));
end;
$$;

-- The same, keeping one session: returns the uid for follow-up calls.
create or replace function pg_temp.begin_claim_user() returns uuid
language plpgsql as $$
declare uid uuid;
begin
  insert into auth.users (id, email)
  values (gen_random_uuid(), 't_' || gen_random_uuid() || '@test.local')
  returning id into uid;
  return uid;
end;
$$;

create or replace function pg_temp.claim_as_specific(
  p_uid uuid, p_kind text, p_room text, p_obs_type text,
  p_event_date date, p_target_start int, p_idem uuid
) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_specific(p_uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid, 0::bigint, %L::text, %s::date, %s::smallint)',
    p_kind, p_room, '{}', p_idem, p_obs_type,
    case when p_event_date is null then 'null'
         else format('%L', p_event_date) end,
    coalesce(p_target_start::text, 'null')));
end;
$$;

-- A slot strictly later than now, on the 9..17 grid — or null when the
-- teaching day has no such slot left (evening runs).
create or replace function pg_temp.later_slot() returns int
language plpgsql as $$
declare
  h int := extract(hour from now())::int;
  target int := least(greatest(h + 1, 9), 17);
begin
  if now() < make_timestamptz(extract(year from current_date)::int,
                              extract(month from current_date)::int,
                              extract(day from current_date)::int,
                              target, 0, 0, 'UTC') then
    return target;
  end if;
  return null;
end;
$$;

-- Scenario: the canonical claim (occupied despite free, later today), asserted
-- against whichever side of the slot boundary now() falls on.
create or replace function pg_temp.canonical_claim() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_claim_user();
  target int := pg_temp.later_slot();
  r jsonb;
  obs_type text;
  ed date;
  ts smallint;
  te smallint;
  exp timestamptz;
  want timestamptz;
begin
  r := pg_temp.claim_as_specific(uid, 'room_occupied_despite_free', '203', 'future',
                                 current_date, coalesce(target, 17),
                                 gen_random_uuid());
  if target is not null then
    select observation_type::text, event_date, target_start_hour, target_end_hour, expires_at
      into obs_type, ed, ts, te, exp
      from community.observations where room = '203' and reporter_id = uid;
    want := make_timestamptz(extract(year from current_date)::int,
                             extract(month from current_date)::int,
                             extract(day from current_date)::int,
                             target + 1, 0, 0, 'UTC') + interval '30 minutes';
    return jsonb_build_object(
      'case', 'accepted',
      'ok', r->>'ok',
      'type', obs_type, 'event_date', ed, 'target_start', ts, 'target_end', te,
      'expiry_matches', exp = want);
  else
    return jsonb_build_object('case', 'refused', 'ok', r->>'ok', 'code', r->>'code');
  end if;
end;
$$;

-- Tests ----------------------------------------------------------------------

-- 1. The canonical claim lands with its explicit interval, or is refused when
--    no later slot exists today — both are the server keeping its word.
--    (pgTAP's test functions return text, so a scenario's several checks are
--    folded into one composite assertion here and below.)
SELECT CASE result->>'case'
  WHEN 'accepted' THEN
    ok(
      (result->>'ok')::boolean
      and result->>'type' = 'future'
      and result->>'event_date' = current_date::text
      and result->>'target_start' = pg_temp.later_slot()::text
      and result->>'target_end' = (pg_temp.later_slot() + 1)::text
      and (result->>'expiry_matches')::boolean,
      'a claim about a later slot today is accepted, typed future, carries its explicit event date and target interval, and expires at the slot end + 30 minutes')
  ELSE
    ok((result->>'ok')::boolean = false AND result->>'code' = 'invalid_target',
       'after the last slot has started, a claim is refused: it is not about the future anymore')
  END
FROM (SELECT pg_temp.canonical_claim() as result) s;

-- 2. Old-client compatibility: the pre-0007 parameter set still files a
--    present report (defaults), unchanged behavior.
SELECT is(
  pg_temp.as_specific(
    (SELECT pg_temp.begin_claim_user()),
    format('community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
           'room_other', 'old-client-room', '{}', gen_random_uuid())
  )->>'ok',
  'true',
  'a report without the new parameters is accepted (old client)');

SELECT is(
  (SELECT observation_type::text FROM community.observations WHERE room = 'old-client-room'),
  'present',
  'a report filed without the type parameter lands as a present observation');

-- A class-bound report is historical by construction — the slot it points at
-- via class_date/start_hour IS the historical slot — so the server derives it
-- and no client ever states a type except when filing a claim.
SELECT is(
  pg_temp.as_specific(
    (SELECT pg_temp.begin_claim_user()),
    format('community.submit_report(%L::text, null::text, null::text, null::text, %L::date, %s::smallint, %L::jsonb, null::text, %L::uuid)',
           'class_cancelled', current_date, 10, '{}', gen_random_uuid())
  )->>'ok',
  'true',
  'an old client''s class report is still accepted');

SELECT is(
  (SELECT observation_type::text FROM community.observations
    WHERE kind = 'class_cancelled' ORDER BY created_at DESC LIMIT 1),
  'historical',
  'a class-bound report lands as a historical observation without the client saying so');

-- 3. A present report may not smuggle claim fields.
SELECT is(
  pg_temp.claim_as('room_other', 'smuggle-room', 'present', current_date, 14,
                   gen_random_uuid())->>'code',
  'invalid_payload',
  'a present report carrying a target slot is refused');

-- 4. An unknown observation type is refused, not defaulted.
SELECT is(
  pg_temp.claim_as('room_other', 'bad-type-room', 'speculative', null, null,
                   gen_random_uuid())->>'code',
  'invalid_payload',
  'an unknown observation type is refused');

-- 5. Claims are about rooms: a class kind cannot be a claim.
SELECT is(
  pg_temp.claim_as('class_cancelled', '203', 'future', current_date, 14,
                   gen_random_uuid())->>'code',
  'invalid_payload',
  'a class-kind claim is refused — claims are about rooms');

-- 6. Tomorrow is out of scope; yesterday is impossible.
SELECT is(
  pg_temp.claim_as('room_occupied_despite_free', '203', 'future',
                   current_date + 1, 14, gen_random_uuid())->>'code',
  'date_out_of_range',
  'a claim about tomorrow is refused (later slot today is the whole scope)');

SELECT is(
  pg_temp.claim_as('room_occupied_despite_free', '203', 'future',
                   current_date - 1, 14, gen_random_uuid())->>'code',
  'date_out_of_range',
  'a claim about yesterday is refused');

-- 7. A claim without its event date is not a claim.
SELECT is(
  pg_temp.claim_as('room_occupied_despite_free', '203', 'future', null, 14,
                   gen_random_uuid())->>'code',
  'date_out_of_range',
  'a claim without an explicit event date is refused');

-- 8. The target hour must be on the teaching grid.
SELECT is(
  pg_temp.claim_as('room_occupied_despite_free', '203', 'future', current_date, 8,
                   gen_random_uuid())->>'code',
  'invalid_start_hour',
  'a claim about 8 AM is refused — the grid starts at 9');

SELECT is(
  pg_temp.claim_as('room_occupied_despite_free', '203', 'future', current_date, 18,
                   gen_random_uuid())->>'code',
  'invalid_start_hour',
  'a claim about 6 PM is refused — the grid ends at 17');

-- 9. Dedup: the target hour is part of the claim's identity. Same reporter,
--    same kind/room/target within the hour -> duplicate; a different target
--    about the same room is a different claim.
CREATE OR REPLACE FUNCTION pg_temp.claim_dedup_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_claim_user();
  target int := pg_temp.later_slot();
  other int;
  r1 jsonb; r_same jsonb; r_other jsonb;
begin
  if target is null then
    return jsonb_build_object('case', 'evening');
  end if;
  -- Another later slot, when one exists; otherwise the dedup assertion runs
  -- against the same target only (the duplicate half still holds).
  other := case when target + 1 <= 17 then target + 1 end;
  r1 := pg_temp.claim_as_specific(uid, 'room_occupied_despite_free', 'dedup-room', 'future',
                                  current_date, target, gen_random_uuid());
  r_same := pg_temp.claim_as_specific(uid, 'room_occupied_despite_free', 'dedup-room', 'future',
                                      current_date, target, gen_random_uuid());
  if other is not null then
    r_other := pg_temp.claim_as_specific(uid, 'room_occupied_despite_free', 'dedup-room', 'future',
                                         current_date, other, gen_random_uuid());
  end if;
  return jsonb_build_object(
    'case', 'day',
    'r1_ok', r1->>'ok',
    'same_code', r_same->>'code',
    'other_ok', coalesce(r_other->>'ok', 'skipped'));
end;
$$;

SELECT CASE result->>'case'
  WHEN 'day' THEN
    ok(
      (result->>'r1_ok')::boolean
      and result->>'same_code' = 'duplicate_report'
      and (result->>'other_ok' = 'skipped' or (result->>'other_ok')::boolean),
      'the same claim twice within the hour is one claim; a different target slot of the same room is its own claim')
  ELSE
    ok(true, 'evening run: the dedup window needs a later slot (covered by test 1)')
  END
FROM (SELECT pg_temp.claim_dedup_scenario() as result) s;

-- 10. Expiry: a claim whose target slot has passed is invisible in the active
--     view even before any sweep — the read path is the boundary.
CREATE OR REPLACE FUNCTION pg_temp.expired_claim_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid;
  obs_id uuid;
begin
  -- Insert an already-expired claim directly (as postgres): the RPC would
  -- refuse it, which is exactly what test 1's evening half covers. What this
  -- tests is the read boundary, which belongs to the view, not the RPC.
  insert into auth.users (id, email)
  values (gen_random_uuid(), 't_' || gen_random_uuid() || '@test.local')
  returning id into uid;
  insert into community.reporter_profiles (user_id) values (uid);
  insert into community.observations (
    idempotency_key, reporter_id, kind, room, created_at, expires_at,
    observation_type, event_date, target_start_hour, target_end_hour
  ) values (
    gen_random_uuid(), uid, 'room_occupied_despite_free', 'expired-claim-room',
    now() - interval '6 minutes', now() - interval '5 minutes',
    'future', current_date - 1, 9, 10
  ) returning id into obs_id;
  return jsonb_build_object(
    'visible', exists(select 1 from community.active_observations where id = obs_id));
end;
$$;

SELECT ok(
  NOT (pg_temp.expired_claim_scenario()->>'visible')::boolean,
  'a claim past its target slot''s end is not in the active view');

-- 11. The view exposes the claim fields, so clients never infer a claim from
--     a date (the model''s rule: futurity is stated, never implied).
SELECT ok(
  exists (
    select 1
    from information_schema.columns
    where table_schema = 'community'
      and table_name = 'active_observations'
      and column_name in ('observation_type', 'event_date',
                          'target_start_hour', 'target_end_hour')
  ) and (
    select count(*) from information_schema.columns
    where table_schema = 'community'
      and table_name = 'active_observations'
      and column_name in ('observation_type', 'event_date',
                          'target_start_hour', 'target_end_hour')
  ) = 4,
  'the active view carries all four claim fields');

-- 12. Column grants: the claim columns are readable by authenticated (the
--     0006 column-security shape extends to them).
SELECT ok(
  has_column_privilege('authenticated', 'community.observations',
                       'observation_type', 'SELECT')
  and has_column_privilege('authenticated', 'community.observations',
                           'event_date', 'SELECT')
  and has_column_privilege('authenticated', 'community.observations',
                           'target_start_hour', 'SELECT')
  and has_column_privilege('authenticated', 'community.observations',
                           'target_end_hour', 'SELECT'),
  'authenticated may select the claim columns');

-- 13. Column security holds: reporter_id is still NOT granted.
SELECT ok(
  NOT has_column_privilege('authenticated', 'community.observations',
                           'reporter_id', 'SELECT'),
  'reporter_id stays unreadable by clients');

-- 14. The shape constraint makes a claimless future row impossible.
SELECT throws_ok(
  $$ insert into community.observations (
       idempotency_key, reporter_id, kind, room, expires_at, observation_type
     ) values (
       gen_random_uuid(), (select user_id from community.reporter_profiles limit 1),
       'room_other', 'shape-room', now() + interval '1 hour', 'future'
     ) $$,
  NULL,
  'a future-typed row without its target interval cannot exist');

-- 15. Schema marker kept at/above the 0007 floor (0008 bumps it to 3).
SELECT is(
  (select value #>> '{}' from community.app_meta where key = 'community_schema_version')::int >= 2,
  true,
  'the community schema version marker is at least 2');

SELECT * FROM finish();
ROLLBACK;
