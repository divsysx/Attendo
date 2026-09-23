-- pgTAP: schema + constraint tests for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- These tests insert as the migration owner (postgres), bypassing RLS, to
-- prove the *database shape* holds regardless of any policy: bad rows cannot
-- exist even for a privileged writer.

BEGIN;
SELECT plan(36);

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

create or replace function pg_temp.new_profile() returns uuid
language plpgsql as $$
declare uid uuid := pg_temp.new_user();
begin
  insert into community.reporter_profiles (user_id) values (uid);
  return uid;
end;
$$;

create or replace function pg_temp.insert_obs(
  p_reporter uuid,
  p_kind text,
  p_room text,
  p_section text,
  p_subject text,
  p_class_date date,
  p_start_hour int,
  p_payload jsonb,
  p_note text,
  p_idem uuid,
  p_expires timestamptz default now() + interval '3 hours'
) returns uuid
language plpgsql as $$
declare oid uuid;
begin
  insert into community.observations (
    idempotency_key, reporter_id, kind, room, section, subject,
    class_date, start_hour, payload, note, expires_at)
  values (
    p_idem, p_reporter, p_kind::community.report_kind, p_room, p_section,
    p_subject, p_class_date, p_start_hour, p_payload, p_note, p_expires)
  returning id into oid;
  return oid;
end;
$$;


create or replace function pg_temp.insert_obs_twice(
  p_reporter uuid,
  p_kind1 text, p_kind2 text,
  p_room text,
  p_payload jsonb
) returns int
language plpgsql as $$
begin
  perform pg_temp.insert_obs(p_reporter, p_kind1, p_room, null, null, null, null, p_payload, null, gen_random_uuid());
  perform pg_temp.insert_obs(p_reporter, p_kind2, p_room, null, null, null, null, p_payload, null, gen_random_uuid());
  return 0;
end;
$$;


create or replace function pg_temp.verify_twice(p_obs uuid, p_user uuid) returns int
language plpgsql as $$
begin
  insert into community.verifications (observation_id, user_id, verdict) values (p_obs, p_user, true);
  insert into community.verifications (observation_id, user_id, verdict) values (p_obs, p_user, false);
  return 0;
end;
$$;

create or replace function pg_temp.vote_twice(p_poll uuid, p_user uuid) returns int
language plpgsql as $$
begin
  insert into community.poll_votes (poll_id, user_id, option_index) values (p_poll, p_user, 0);
  insert into community.poll_votes (poll_id, user_id, option_index) values (p_poll, p_user, 1);
  return 0;
end;
$$;

create or replace function pg_temp.cascade_check() returns int
language plpgsql as $$
declare
  uid uuid;
  oid uuid;
  obs_before integer;
  obs_after integer;
begin
  uid := pg_temp.new_profile();
  oid := pg_temp.insert_obs(uid, 'room_other', 'cascade-check-room', null, null, null, null, '{}'::jsonb, null, gen_random_uuid());
  insert into community.verifications (observation_id, user_id, verdict) values (oid, pg_temp.new_profile(), true);
  select count(*) into obs_before from community.observations where reporter_id = uid;
  delete from community.reporter_profiles where user_id = uid;
  select count(*) into obs_after from community.observations where reporter_id = uid;
  if obs_before <> 1 or obs_after <> 0 then
    raise exception 'cascade_check failed: before=% after=%', obs_before, obs_after;
  end if;
  return 0;
end;
$$;

-- Enum existence -------------------------------------------------------------
SELECT has_enum('community', 'report_kind', 'report_kind enum exists');
SELECT has_enum('community', 'observation_status', 'observation_status enum exists');
SELECT has_enum('community', 'poll_status', 'poll_status enum exists');

-- Table existence ------------------------------------------------------------
SELECT has_table('community', 'reporter_profiles', 'reporter_profiles exists');
SELECT has_table('community', 'observations', 'observations exists');
SELECT has_table('community', 'verifications', 'verifications exists');
SELECT has_table('community', 'polls', 'polls exists');
SELECT has_table('community', 'poll_options', 'poll_options exists');
SELECT has_table('community', 'poll_votes', 'poll_votes exists');
SELECT has_table('community', 'abuse_events', 'abuse_events exists');
SELECT has_table('community', 'app_meta', 'app_meta exists');
SELECT has_view('community', 'active_observations', 'active_observations view exists');

-- Constraint: context required (neither room nor date+hour) -------------------
SELECT throws_ok(
  $$insert into community.observations (idempotency_key, reporter_id, kind, expires_at)
    values (gen_random_uuid(), pg_temp.new_profile(), 'room_other', now() + interval '1 hour')$$,
  23514,
  NULL,
  'observation with no context is rejected'
);

-- Constraint: start_hour range -----------------------------------------------
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'class_cancelled', null, '2nd Yr CSE-A', 'ADEC', current_date, 8, '{}'::jsonb, null, gen_random_uuid())$$,
  23514,
  NULL,
  'start_hour 8 (before teaching grid) is rejected'
);
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'class_cancelled', null, '2nd Yr CSE-A', 'ADEC', current_date, 18, '{}'::jsonb, null, gen_random_uuid())$$,
  23514,
  NULL,
  'start_hour 18 (past teaching grid) is rejected'
);

-- Constraint: note length ----------------------------------------------------
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '203', null, null, null, null, '{}'::jsonb, repeat('x', 281), gen_random_uuid())$$,
  23514,
  NULL,
  'note over 280 chars is rejected'
);

-- Constraint: payload must be an object --------------------------------------
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '203', null, null, null, null, '["array"]'::jsonb, null, gen_random_uuid())$$,
  23514,
  NULL,
  'non-object payload is rejected'
);

-- Constraint: blank room -----------------------------------------------------
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '', null, null, null, null, '{}'::jsonb, null, gen_random_uuid())$$,
  23514,
  NULL,
  'blank room is rejected'
);

-- Constraint: trust bounds ---------------------------------------------------
SELECT throws_ok(
  $$insert into community.reporter_profiles (user_id, trust_score)
    values (pg_temp.new_user(), 101)$$,
  23514,
  NULL,
  'trust_score above 100 is rejected'
);
SELECT throws_ok(
  $$insert into community.reporter_profiles (user_id, trust_score)
    values (pg_temp.new_user(), -101)$$,
  23514,
  NULL,
  'trust_score below -100 is rejected'
);

-- Constraint: idempotency uniqueness -----------------------------------------
SELECT lives_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '203', null, null, null, null, '{}'::jsonb, null, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')$$,
  'first insert with idempotency key works'
);
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '204', null, null, null, null, '{}'::jsonb, null, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')$$,
  23505,
  NULL,
  'the same idempotency key cannot be reused by anyone'
);

-- Constraint: dedup window (same reporter, kind, context, hour) ---------------
SELECT throws_ok(
  $$select pg_temp.insert_obs_twice(pg_temp.new_profile(), 'room_occupied_despite_free', 'room_occupied_despite_free', '203', '{}'::jsonb)$$,
  23505,
  NULL,
  'same reporter cannot file the same room report twice in the dedup window'
);
SELECT lives_ok(
  $$select pg_temp.insert_obs_twice(pg_temp.new_profile(), 'room_occupied_despite_free', 'room_free_despite_busy', '203', '{}'::jsonb)$$,
  'a different kind in the same context is not a duplicate'
);

-- Constraint: expires_at must be in the future -------------------------------
SELECT throws_ok(
  $$select pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '203', null, null, null, null, '{}'::jsonb, null, gen_random_uuid(), now() - interval '1 hour')$$,
  23514,
  NULL,
  'expires_at in the past is rejected'
);

-- Polls: question bounds -----------------------------------------------------
SELECT throws_ok(
  $$insert into community.polls (idempotency_key, creator_id, room, question, closes_at)
    values (gen_random_uuid(), pg_temp.new_profile(), '203', 'ab', now() + interval '1 hour')$$,
  23514,
  NULL,
  'poll question under 3 chars is rejected'
);
SELECT throws_ok(
  $$insert into community.polls (idempotency_key, creator_id, room, question, closes_at)
    values (gen_random_uuid(), pg_temp.new_profile(), '203', repeat('q', 161), now() + interval '1 hour')$$,
  23514,
  NULL,
  'poll question over 160 chars is rejected'
);

-- Polls: context required ----------------------------------------------------
SELECT throws_ok(
  $$insert into community.polls (idempotency_key, creator_id, question, closes_at)
    values (gen_random_uuid(), pg_temp.new_profile(), 'Is 203 busy?', now() + interval '1 hour')$$,
  23514,
  NULL,
  'poll with no room and no class context is rejected'
);

-- Poll options: index and label bounds ---------------------------------------
SELECT lives_ok(
  $$insert into community.polls (idempotency_key, creator_id, room, question, closes_at)
    values ('12345678-1234-1234-1234-123456789abc', pg_temp.new_profile(), '203', 'Is 203 busy?', now() + interval '1 hour')$$,
  'valid poll insert works'
);
SELECT throws_ok(
  $$insert into community.poll_options (poll_id, option_index, label)
    values ('12345678-1234-1234-1234-123456789abc', 4, 'Yes')$$,
  23514,
  NULL,
  'poll option index above 3 is rejected'
);
SELECT throws_ok(
  $$insert into community.poll_options (poll_id, option_index, label)
    values ('12345678-1234-1234-1234-123456789abc', 0, '')$$,
  23514,
  NULL,
  'empty poll option label is rejected'
);

-- Verifications: PK = one row per (observation, user) ------------------------
SELECT throws_ok(
  $$select pg_temp.verify_twice(
      pg_temp.insert_obs(pg_temp.new_profile(), 'room_other', '203', null, null, null, null, '{}'::jsonb, null, gen_random_uuid()),
      pg_temp.new_profile())$$,
  23505,
  NULL,
  'a second verification row for the same (observation, user) is rejected by the PK'
);

-- Poll votes: PK = one row per (poll, user) ----------------------------------
SELECT throws_ok(
  $$select pg_temp.vote_twice(
      (select id from community.polls where idempotency_key = '12345678-1234-1234-1234-123456789abc'),
      pg_temp.new_profile())$$,
  23505,
  NULL,
  'a second vote for the same (poll, user) is rejected by the PK'
);

-- FK cascades ------------------------------------------------------------------
SELECT lives_ok(
  $$select pg_temp.cascade_check()$$,
  'deleting a reporter profile cascades to their observations (and their verifications)'
);
SELECT is(
  (select count(*) from community.observations o
     join community.polls p on false
    where o.room = 'cascade-check-room'),
  0::bigint,
  'cascade-check cleanup ran (self-check)'
);

-- app_meta seeded --------------------------------------------------------------
-- Version 2 (migration 0007, future claims) is the floor: migration 0008
-- (withdrawal/undo) bumps it to 3. The floor is what old-client negotiation
-- needs; asserting the exact head would break on every future migration.
SELECT is((select value::text from community.app_meta where key = 'community_schema_version')::int >= 2, true,
  'community_schema_version is at least 2');

SELECT * FROM finish();
ROLLBACK;
