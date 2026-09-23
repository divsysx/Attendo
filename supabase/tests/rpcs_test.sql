-- pgTAP: RPC behavior tests for the Attendo community write path.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Tests run submit_report / verify / create_poll / cast_vote / poll_results
-- as impersonated authenticated users (set local role authenticated +
-- request.jwt.claim.sub), proving validation, rate limits, idempotency,
-- dedup, status transitions, reputation deltas and restrictions.
--
-- Note on structure: every pg_temp helper must be *declared* before its first
-- call site, and pgTAP tests run in file order, so helpers sit at the top and
-- scenario functions that need several statements wrap them in one function.

BEGIN;
SELECT plan(45);

-- Helpers -------------------------------------------------------------------

-- Create an auth user and return its id.
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

-- Run an RPC body as a freshly-created impersonated authenticated user.
-- `as_user` must be entered privileged (the test file's default postgres
-- role) and only drops to `authenticated` for the RPC call itself; role and
-- claim GUCs are transaction-scoped (local=true), so they would otherwise
-- leak into later helper calls (new_user needs auth.users INSERT, which
-- `authenticated` lacks) — the function restores the privileged role on
-- exit for that reason.
create or replace function pg_temp.as_user(p_fn text) returns jsonb
language plpgsql as $$
declare uid uuid := pg_temp.new_user(); result jsonb;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', uid::text, true);
  execute 'select ' || p_fn into result;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return result;
end;
$$;

-- submit_report as a fresh user. Explicit casts in the generated SQL: dynamic
-- EXECUTE resolves `unknown` literals differently from static SQL, so typed
-- placeholders are needed to match the function signature.
create or replace function pg_temp.submit_as(
  p_kind text, p_room text, p_class_date date, p_start_hour int,
  p_payload jsonb, p_note text, p_idem uuid
) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_user(format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, %L::date, %s::smallint, %L::jsonb, %s::text, %L::uuid)',
    p_kind, p_room, p_class_date, coalesce(p_start_hour::text, 'null'),
    coalesce(p_payload::text, '{}'),
    case when p_note is null then 'null' else format('%L', p_note) end,
    p_idem));
end;
$$;

-- Same, but keeping a session per user so multiple calls act as one identity.
-- Returns the created uid; the role stays privileged between calls —
-- as_specific() flips it only for the duration of each RPC call.
create or replace function pg_temp.begin_user() returns uuid
language plpgsql as $$
declare uid uuid := pg_temp.new_user();
begin
  return uid;
end;
$$;

-- Run the next RPC as a specific user (must be privileged on entry; restores
-- the privileged role on exit, same rationale as as_user).
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

-- A canonical valid room report (fresh user).
create or replace function pg_temp.fresh_report(p_room text default '203') returns jsonb
language plpgsql as $$
begin
  return pg_temp.submit_as('room_other', p_room, null, null, '{}'::jsonb,
                           null, gen_random_uuid());
end;
$$;

-- The observation created by the most recent fresh_report_tracked() call, so a
-- later test can identify the identity that report belonged to.
create temp table if not exists pg_temp.report_trace (
  id uuid primary key, reporter_id uuid not null);

-- fresh_report, additionally recording the created observation. Used where an
-- assertion must be scoped to the identity THIS run just created, because a
-- global count would also see rows left behind by earlier local runs.
create or replace function pg_temp.fresh_report_tracked(p_room text default '203')
returns jsonb
language plpgsql as $$
declare r jsonb;
begin
  r := pg_temp.fresh_report(p_room);
  if r ->> 'id' is not null then
    insert into pg_temp.report_trace (id, reporter_id)
    select o.id, o.reporter_id from community.observations o
     where o.id = (r ->> 'id')::uuid
    on conflict (id) do nothing;
  end if;
  return r;
end;
$$;

-- Idempotency scenario: ONE user submits, then retries the exact same
-- idempotency key. Returns {r1_ok, r1_id, r2_ok, r2_id, r2_dup}.
create or replace function pg_temp.idempotent_retry_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_user();
  r1 jsonb; r2 jsonb;
begin
  r1 := pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'idem-room', '{}', '11111111-1111-1111-1111-111111111111'));
  r2 := pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'idem-room', '{}', '11111111-1111-1111-1111-111111111111'));
  return jsonb_build_object(
    'r1_ok', r1->>'ok', 'r1_id', r1->>'id',
    'r2_ok', r2->>'ok', 'r2_id', r2->>'id', 'r2_dup', r2->>'duplicate');
end;
$$;

-- Dedup scenario: ONE user files the same room report twice with different
-- idempotency keys. Returns {r1_ok, r2_code}.
create or replace function pg_temp.dedup_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_user();
  r1 jsonb; r2 jsonb;
begin
  r1 := pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'dedup-room', '{}', gen_random_uuid()));
  r2 := pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'dedup-room', '{}', gen_random_uuid()));
  return jsonb_build_object('r1_ok', r1->>'ok', 'r2_code', r2->>'code');
end;
$$;

-- Rate limit scenario: one user files reports for 11 distinct rooms; the 11th
-- must come back rate_limited_short. Returns the 11th result's code.
create or replace function pg_temp.rate_limit_scenario() returns text
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_user();
  result jsonb;
  i integer;
begin
  for i in 1..10 loop
    result := pg_temp.as_specific(uid, format(
      'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
      'room_other', 'rate-room-' || i, '{}', gen_random_uuid()));
    if result->>'ok' <> 'true' then
      return 'unexpected_' || coalesce(result->>'code', 'null');
    end if;
  end loop;
  result := pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'rate-room-11', '{}', gen_random_uuid()));
  return result->>'code';
end;
$$;

-- Status transition scenario: reporter R files a report; verifiers V1..V3
-- verify true. Expect: after V2 -> corroborated, after V3 (trusted? no) stays
-- corroborated. Returns text: status_after_2|status_after_3.
create or replace function pg_temp.transition_scenario() returns text
language plpgsql as $$
declare
  r uuid := pg_temp.begin_user();
  submit jsonb;
  obs_id uuid;
  v1 uuid; v2 uuid; v3 uuid;
  s2 text; s3 text;
begin
  submit := pg_temp.as_specific(r, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'trans-room', '{}', gen_random_uuid()));
  obs_id := (submit->>'id')::uuid;

  v1 := pg_temp.begin_user();
  perform pg_temp.as_specific(v1, format('community.verify(%L::uuid, true, null::uuid)', obs_id));
  v2 := pg_temp.begin_user();
  perform pg_temp.as_specific(v2, format('community.verify(%L::uuid, true, null::uuid)', obs_id));
  select status::text into s2 from community.observations where id = obs_id;

  v3 := pg_temp.begin_user();
  perform pg_temp.as_specific(v3, format('community.verify(%L::uuid, true, null::uuid)', obs_id));
  select status::text into s3 from community.observations where id = obs_id;

  return s2 || '|' || s3;
end;
$$;


create or replace function pg_temp.verify_own_report_scenario() returns jsonb
language plpgsql as $$
declare
  r uuid := pg_temp.begin_user();
  submit jsonb;
begin
  submit := pg_temp.as_specific(r, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'own-room', '{}', gen_random_uuid()));
  return pg_temp.as_specific(r, format(
    'community.verify(%L::uuid, true, null::uuid)', (submit->>'id')::uuid));
end;
$$;
create or replace function pg_temp.verify_twice_scenario() returns jsonb
language plpgsql as $$
declare
  r uuid := pg_temp.begin_user();
  v uuid := pg_temp.begin_user();
  submit jsonb;
begin
  submit := pg_temp.as_specific(r, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'verify-twice-room', '{}', gen_random_uuid()));
  perform pg_temp.as_specific(v, format('community.verify(%L::uuid, true, null::uuid)', (submit->>'id')::uuid));
  return pg_temp.as_specific(v, format('community.verify(%L::uuid, true, null::uuid)', (submit->>'id')::uuid));
end;
$$;
create or replace function pg_temp.create_poll_as(
  p_room text, p_question text, p_options jsonb
) returns jsonb
language plpgsql as $$
begin
  return pg_temp.as_user(format(
    'community.create_poll(%L::text, null::text, null::text, null::date, null::smallint, %L::text, %L::jsonb, %L::uuid, 0::bigint)',
    p_room, p_question, p_options, gen_random_uuid()));
end;
$$;
create or replace function pg_temp.vote_scenario() returns jsonb
language plpgsql as $$
declare
  submit jsonb := pg_temp.create_poll_as('vote-room', 'Is 203 busy?', '["Yes","No"]');
  poll_id uuid := (submit->>'id')::uuid;
begin
  return pg_temp.as_user(format('community.cast_vote(%L::uuid, 0::smallint)', poll_id));
end;
$$;
create or replace function pg_temp.vote_invalid_option_scenario() returns jsonb
language plpgsql as $$
declare
  submit jsonb := pg_temp.create_poll_as('vote-bad-opt-room', 'Is 203 busy?', '["Yes","No"]');
  poll_id uuid := (submit->>'id')::uuid;
begin
  return pg_temp.as_user(format('community.cast_vote(%L::uuid, 9::smallint)', poll_id));
end;
$$;
create or replace function pg_temp.vote_twice_scenario() returns jsonb
language plpgsql as $$
declare
  submit jsonb := pg_temp.create_poll_as('vote-twice-room', 'Is 203 busy?', '["Yes","No"]');
  poll_id uuid := (submit->>'id')::uuid;
  u uuid := pg_temp.begin_user();
  r jsonb;
begin
  perform pg_temp.as_specific(u, format('community.cast_vote(%L::uuid, 0::smallint)', poll_id));
  return pg_temp.as_specific(u, format('community.cast_vote(%L::uuid, 1::smallint)', poll_id));
end;
$$;
create or replace function pg_temp.vote_closed_poll_scenario() returns jsonb
language plpgsql as $$
declare
  submit jsonb := pg_temp.create_poll_as('closed-poll-room', 'Is 203 busy?', '["Yes","No"]');
  poll_id uuid := (submit->>'id')::uuid;
begin
  -- Simulate the sweep having closed it: status flips (the constraint keeps
  -- closes_at > created_at, so we cannot backdate closes_at; the RPC refuses
  -- on status alone, which is the real-world post-sweep state).
  update community.polls set status = 'closed', closed_at = now()
    where id = poll_id;
  return pg_temp.as_user(format('community.cast_vote(%L::uuid, 0::smallint)', poll_id));
end;
$$;
create or replace function pg_temp.poll_results_scenario() returns jsonb
language plpgsql as $$
declare
  submit jsonb := pg_temp.create_poll_as('results-room', 'Is 203 busy?', '["Yes","No"]');
  poll_id uuid := (submit->>'id')::uuid;
  u1 uuid := pg_temp.begin_user();
  u2 uuid := pg_temp.begin_user();
begin
  perform pg_temp.as_specific(u1, format('community.cast_vote(%L::uuid, 0::smallint)', poll_id));
  perform pg_temp.as_specific(u2, format('community.cast_vote(%L::uuid, 0::smallint)', poll_id));
  return pg_temp.as_user(format('community.poll_results(%L::uuid)', poll_id));
end;
$$;
create or replace function pg_temp.reputation_scenario() returns jsonb
language plpgsql as $$
declare
  r uuid := pg_temp.begin_user();
  submit jsonb;
  obs_id uuid;
  v1 uuid; v2 uuid;
  trust_before integer; trust_after integer;
begin
  submit := pg_temp.as_specific(r, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'reputation-room', '{}', gen_random_uuid()));
  obs_id := (submit->>'id')::uuid;
  select trust_score into trust_before from community.reporter_profiles where user_id = r;
  v1 := pg_temp.begin_user();
  perform pg_temp.as_specific(v1, format('community.verify(%L::uuid, true, null::uuid)', obs_id));
  v2 := pg_temp.begin_user();
  perform pg_temp.as_specific(v2, format('community.verify(%L::uuid, true, null::uuid)', obs_id));
  select trust_score into trust_after from community.reporter_profiles where user_id = r;
  return jsonb_build_object('trust_delta', trust_after - trust_before);
end;
$$;
create or replace function pg_temp.restriction_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_user();
begin
  -- Establish the profile with one valid report first (restrictions only
  -- exist on profiles), then restrict server-side and attempt a second.
  perform pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'pre-restriction-room', '{}', gen_random_uuid()));
  update community.reporter_profiles
    set restricted_until = now() + interval '1 day'
    where user_id = uid;
  return pg_temp.as_specific(uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', 'restricted-room', '{}', gen_random_uuid()));
end;
$$;
create or replace function pg_temp.my_reputation_scenario() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.begin_user();
begin
  return pg_temp.as_specific(uid, 'community.my_reputation()');
end;
$$;


-- submit_report: happy path + identity ---------------------------------------

SELECT is(
  (pg_temp.fresh_report_tracked('203') -> 'ok'),
  'true'::jsonb,
  'submit_report succeeds for a valid room observation'
);
-- Scoped to the reporter this run just created: a global count would also see
-- profiles left behind by earlier local runs.
SELECT is(
  (select count(*) from community.reporter_profiles p
    where p.user_id = (select reporter_id from pg_temp.report_trace limit 1)),
  1::bigint,
  'submit_report creates the anonymous reporter profile on first use'
);

-- Invalid kind ---------------------------------------------------------------
SELECT is(
  (pg_temp.submit_as('not_a_kind', '203', null::date, null::int, '{}'::jsonb, null,
                     gen_random_uuid()) -> 'code'),
  '"invalid_kind"'::jsonb,
  'unknown report kind is rejected with invalid_kind'
);

-- Payload validation ---------------------------------------------------------
SELECT is(
  (pg_temp.submit_as('class_room_changed', null, current_date::date, 10::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'class_room_changed without new_room is rejected'
);
SELECT is(
  (pg_temp.submit_as('class_room_changed', null, current_date::date, 10::int,
                     '{"new_room": "211"}'::jsonb, null, gen_random_uuid()) -> 'ok'),
  'true'::jsonb,
  'class_room_changed with new_room is accepted'
);
SELECT is(
  (pg_temp.submit_as('class_time_changed', null, current_date::date, 10::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'class_time_changed without new_start_hour is rejected'
);
SELECT is(
  (pg_temp.submit_as('class_time_changed', null, current_date::date, 10::int,
                     '{"new_start_hour": 12}'::jsonb, null, gen_random_uuid()) -> 'ok'),
  'true'::jsonb,
  'class_time_changed with a valid new_start_hour is accepted'
);
SELECT is(
  (pg_temp.submit_as('class_time_changed', null, current_date::date, 10::int,
                     '{"new_start_hour": 20}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'class_time_changed with out-of-range new_start_hour is rejected'
);
SELECT is(
  (pg_temp.submit_as('class_time_changed', null, current_date::date, 10::int,
                     '{"new_start_hour": "abc"}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'class_time_changed with a non-numeric new_start_hour is rejected (friendly code)'
);
SELECT is(
  (pg_temp.submit_as('room_other', '203', null::date, null::int,
                     '{"mystery_field": 1}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'payload with an unknown field is rejected'
);
SELECT is(
  (pg_temp.submit_as('room_occupied_despite_free', null, null::date, null::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'room kind without room context is rejected'
);
SELECT is(
  (pg_temp.submit_as('class_cancelled', null, null::date, null::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_payload"'::jsonb,
  'class kind without date+hour context is rejected'
);

-- Out-of-range start hour and date -------------------------------------------
SELECT is(
  (pg_temp.submit_as('class_cancelled', null, current_date::date, 8::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"invalid_start_hour"'::jsonb,
  'start_hour below the teaching grid is rejected with invalid_start_hour'
);
SELECT is(
  (pg_temp.submit_as('class_cancelled', null, (current_date + 10)::date, null::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"date_out_of_range"'::jsonb,
  'class_date more than 1 day ahead is rejected'
);

-- Note/room length -----------------------------------------------------------
SELECT is(
  (pg_temp.submit_as('room_other', '203', null::date, null::int, '{}'::jsonb,
                     repeat('x', 281), gen_random_uuid()) -> 'code'),
  '"note_too_long"'::jsonb,
  'note over 280 chars is rejected with note_too_long'
);
SELECT is(
  (pg_temp.submit_as('room_other', repeat('r', 61), null::date, null::int,
                     '{}'::jsonb, null, gen_random_uuid()) -> 'code'),
  '"room_too_long"'::jsonb,
  'room over 60 chars is rejected with room_too_long'
);

-- Idempotency (same user retrying an offline submission) --------------------
SELECT is(
  (select s->>'r1_ok' = 'true'
          and s->>'r2_ok' = 'true'
          and s->>'r1_id' = s->>'r2_id'
          and s->>'r2_dup' = 'true'
   from (select pg_temp.idempotent_retry_scenario() as s) x),
  true,
  'retrying the same idempotency key returns the original id and duplicate=true'
);
SELECT is(
  (select count(*) from community.observations
    where idempotency_key = '11111111-1111-1111-1111-111111111111'),
  1::bigint,
  'an idempotent retry created exactly one row'
);

-- Dedup window (via the RPC, friendly code) ----------------------------------
SELECT is(
  (select s->>'r1_ok' = 'true' and s->>'r2_code' = 'duplicate_report'
   from (select pg_temp.dedup_scenario() as s) x),
  true,
  'the same user cannot submit the same room report twice in the window'
);

-- Rate limits ----------------------------------------------------------------
SELECT is(
  (select pg_temp.rate_limit_scenario()),
  'rate_limited_short',
  'the 11th report within an hour is rejected with rate_limited_short'
);

-- Status transitions ---------------------------------------------------------
SELECT is(
  (select pg_temp.transition_scenario()),
  'corroborated|corroborated',
  'two positive verifications move reported -> corroborated; a third untrusted stays there'
);

-- verify: error paths --------------------------------------------------------

-- not_found
SELECT is(
  (select pg_temp.as_specific(pg_temp.begin_user(),
    format('community.verify(%L::uuid, true, null::uuid)', gen_random_uuid())) ->> 'code'),
  'not_found',
  'verify on a nonexistent observation returns not_found'
);

-- own_report: the reporter cannot verify their own report.
SELECT is(
  (select (s->>'code') from (select pg_temp.verify_own_report_scenario() as s) x),
  'own_report',
  'the reporter cannot verify their own report'
);

-- already_verified: second verify returns ok with already_verified code and
-- does not add a second row.
SELECT is(
  (select s->>'ok' = 'true' and s->>'code' = 'already_verified'
   from (select pg_temp.verify_twice_scenario() as s) x),
  true,
  'verifying twice returns already_verified (ok) instead of an error'
);
SELECT is(
  (select count(*) from community.verifications v
    where v.observation_id = (select id from community.observations
                               where room = 'verify-twice-room')),
  1::bigint,
  'a double verification still leaves exactly one verification row'
);

-- Polls ----------------------------------------------------------------------

-- create_poll happy path + options land.
SELECT is(
  (select s->>'ok' = 'true'
   from (select pg_temp.create_poll_as('poll-room-1', 'Is 203 busy?', '["Yes","No"]') as s) x),
  true,
  'create_poll succeeds with a room context and 2 options'
);
SELECT is(
  (select count(*) from community.poll_options po
    join community.polls p on p.id = po.poll_id
    where p.room = 'poll-room-1'),
  2::bigint,
  'create_poll inserted exactly its 2 options'
);

-- create_poll validation errors.
SELECT is(
  (select pg_temp.create_poll_as(null, 'Is 203 busy?', '["Yes","No"]') ->> 'code'),
  'context_required',
  'create_poll without room or class context is rejected'
);
SELECT is(
  (select pg_temp.create_poll_as('poll-room-2', 'ab', '["Yes","No"]') ->> 'code'),
  'invalid_question',
  'create_poll with a too-short question is rejected'
);
SELECT is(
  (select pg_temp.create_poll_as('poll-room-3', 'Is 203 busy?', '["Yes"]') ->> 'code'),
  'invalid_options',
  'create_poll with only 1 option is rejected'
);
SELECT is(
  (select pg_temp.create_poll_as('poll-room-4', 'Is 203 busy?',
          '["Yes","No","Maybe","Dunno","Extra"]') ->> 'code'),
  'invalid_options',
  'create_poll with 5 options is rejected'
);
SELECT is(
  (select pg_temp.create_poll_as('poll-room-5', 'Is 203 busy?', '["Yes","Yes"]') ->> 'code'),
  'invalid_option_label',
  'create_poll with duplicate option labels is rejected (ambiguous tallies)'
);

-- cast_vote: happy path, invalid option, closed poll, duplicate.
SELECT is(
  (select s->>'ok' = 'true'
   from (select pg_temp.vote_scenario() as s) x),
  true,
  'cast_vote on an open poll with a valid option succeeds'
);

SELECT is(
  (select s->>'code' from (select pg_temp.vote_invalid_option_scenario() as s) x),
  'invalid_option',
  'cast_vote with an option index that does not exist is rejected'
);

SELECT is(
  (select s->>'code' = 'already_voted' and s->>'ok' = 'true'
   from (select pg_temp.vote_twice_scenario() as s) x),
  true,
  'a second vote by the same user returns already_voted (ok), never a second row'
);
SELECT is(
  (select count(*) from community.poll_votes v
    join community.polls p on p.id = v.poll_id
    where p.room = 'vote-twice-room'),
  1::bigint,
  'the duplicate vote left exactly one vote row'
);

-- cast_vote on a closed poll is refused (server clock decides openness).
SELECT is(
  (select s->>'code' from (select pg_temp.vote_closed_poll_scenario() as s) x),
  'poll_closed',
  'cast_vote on a poll past closes_at is rejected with poll_closed'
);

-- own_poll: the creator cannot vote in their own poll (Part 5/9 parity — the
-- poll twin of verify's own_report guard).
create or replace function pg_temp.vote_own_poll_scenario() returns jsonb
language plpgsql as $$
declare
  r uuid := pg_temp.begin_user();
  submit jsonb;
begin
  -- The poll must be r's own, so it is created as r (not via create_poll_as,
  -- which uses whatever identity as_user picks).
  submit := pg_temp.as_specific(r, format(
    'community.create_poll(%L::text, null::text, null::text, null::date, null::smallint, %L::text, %L::jsonb, %L::uuid, 0::bigint)',
    'own-poll-room', 'Is 203 busy?', '["Yes","No"]', gen_random_uuid()));
  return pg_temp.as_specific(r, format(
    'community.cast_vote(%L::uuid, 0::smallint)', (submit->>'id')::uuid));
end;
$$;
SELECT is(
  (select s->>'code' from (select pg_temp.vote_own_poll_scenario() as s) x),
  'own_poll',
  'the creator cannot vote in their own poll'
);
SELECT is(
  (select count(*) from community.poll_votes v
    join community.polls p on p.id = v.poll_id
    where p.room = 'own-poll-room'),
  0::bigint,
  'the refused own-poll vote left no vote row'
);

-- poll_results: counts only, no voter identities.
SELECT is(
  (select s->>'ok' = 'true' and (s->'options'->0->>'count') = '2'
   from (select pg_temp.poll_results_scenario() as s) x),
  true,
  'poll_results aggregates votes per option'
);
SELECT is(
  (select s ? 'votes' or s ? 'voters' or s ? 'user_ids'
   from (select pg_temp.poll_results_scenario() as s) x),
  false,
  'poll_results exposes no voter rows or identities'
);

-- Reputation + restriction ---------------------------------------------------

-- A corroborated report earns the reporter +2 trust.
SELECT is(
  (select s->>'trust_delta' = '2'
   from (select pg_temp.reputation_scenario() as s) x),
  true,
  'corroboration earns the reporter +2 trust'
);

-- Restricted users cannot report.
SELECT is(
  (select s->>'code' from (select pg_temp.restriction_scenario() as s) x),
  'restricted',
  'a restricted reporter cannot submit reports'
);

-- my_reputation: own row visible, defaults for profile-less callers.
SELECT is(
  (select s->>'ok' = 'true' and s->>'trust_score' is not null
   from (select pg_temp.my_reputation_scenario() as s) x),
  true,
  'my_reputation returns the caller''s own trust state'
);
SELECT is(
  (select s->>'ok' = 'true' and (s->>'trust_score') = '0'
   from (select pg_temp.as_user('community.my_reputation()') as s) x),
  true,
  'my_reputation for a user with no profile yet returns defaults, not null'
);

SELECT * FROM finish();
ROLLBACK;
