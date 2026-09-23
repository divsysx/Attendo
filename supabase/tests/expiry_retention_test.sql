-- pgTAP: expiry and retention tests for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Proves the locked decisions:
--   * read paths (RLS policies + the active view) hide expired rows even
--     before the cron sweep marks them — correctness never depends on cron
--   * sweep_expired() marks expired observations, closes due polls, and
--     expires polls past their retention-after-close
--   * retain_and_delete() hard-deletes past-retention rows and cascades
--     (verifications, poll options, votes)
--   * dormant reporter profiles are deleted; active ones survive
--   * verifying an expired observation returns a friendly code
--
-- pg_cron scheduling itself is not tested (it is infrastructure); the
-- *functions* it calls are the contract, and they are tested directly.

BEGIN;
SELECT plan(17);

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

-- Count rows visible to an authenticated stranger. (The user is created
-- BEFORE dropping to the authenticated role — auth.users inserts need the
-- privileged role; only the observation query runs as the stranger.)
create or replace function pg_temp.count_as_user(p_sql text) returns bigint
language plpgsql as $$
declare
  uid uuid := pg_temp.new_user();
  n bigint;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', uid::text, true);
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

-- Attempt to verify the un-swept expired observation as a fresh user.
create or replace function pg_temp.verify_expired_attempt() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.new_profile();
  -- Resolve the id BEFORE dropping privileges: once the role is
  -- 'authenticated', RLS hides the expired row from this very subselect
  -- (that is the read path working correctly!) and we'd verify NULL.
  obs uuid := (select id from community.observations
               where room = 'exp-unswept-room');
  result jsonb;
begin
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claim.sub', uid::text, true);
  select community.verify(obs, true) into result;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return result;
end;
$$;

-- Run retain_and_delete() (the cron-called function, invoked directly).
create or replace function pg_temp.retain() returns integer
language plpgsql as $$
begin
  perform community.retain_and_delete();
  return 0;
exception
  when others then return -1;
end;
$$;

-- Fixture: an un-swept expired observation, an active one, a due poll,
-- a past-retention expired observation + old poll, an old abuse event, and
-- a dormant profile.
create or replace function pg_temp.seed_world() returns void
language plpgsql as $$
declare
  u1 uuid; u2 uuid; u3 uuid;
  obs_expired uuid; obs_active uuid;
  poll_due uuid; poll_expired uuid;
begin
  u1 := pg_temp.new_profile();
  u2 := pg_temp.new_profile();
  u3 := pg_temp.new_profile();

  -- (a) un-swept but past expires_at: read paths must already hide it.
  -- (Backdated created_at too: the CHECK requires expires_at > created_at.)
  insert into community.observations (
    idempotency_key, reporter_id, kind, room, created_at, dedup_hour, expires_at)
  values (gen_random_uuid(), u1, 'room_other', 'exp-unswept-room',
          now() - interval '2 hours', now() - interval '2 hours',
          now() - interval '1 minute')
  returning id into obs_expired;

  -- (b) active observation for contrast.
  insert into community.observations (
    idempotency_key, reporter_id, kind, room, expires_at)
  values (gen_random_uuid(), u1, 'room_other', 'exp-active-room',
          now() + interval '3 hours')
  returning id into obs_active;

  -- a verification on the expired one (survives the 7-day window).
  insert into community.verifications (observation_id, user_id, verdict)
  values (obs_expired, u2, true);

  -- (c) a poll past closes_at but still status='open' (sweep must close it).
  insert into community.polls (
    idempotency_key, creator_id, room, question, created_at, dedup_hour, closes_at)
  values (gen_random_uuid(), u1, 'exp-due-poll-room', 'Is it free?',
          now() - interval '2 hours', now() - interval '2 hours',
          now() - interval '1 minute')
  returning id into poll_due;
  insert into community.poll_options (poll_id, option_index, label)
  values (poll_due, 0, 'Yes'), (poll_due, 1, 'No');

  -- (d) an observation the sweep marked expired 8 days ago (past retention).
  -- (now() is fixed per transaction, so created/expires get distinct offsets.)
  insert into community.observations (
    idempotency_key, reporter_id, kind, room, status,
    created_at, dedup_hour, expires_at, resolved_at)
  values (gen_random_uuid(), u3, 'room_other', 'exp-old-room', 'expired',
          now() - interval '9 days', now() - interval '9 days',
          now() - interval '9 days' + interval '1 hour', now() - interval '8 days');

  -- (e) a poll closed 8 days ago (past retention after close).
  insert into community.polls (
    idempotency_key, creator_id, room, question, status,
    created_at, dedup_hour, closes_at, closed_at)
  values (gen_random_uuid(), u3, 'exp-old-poll-room', 'Was it free?', 'closed',
          now() - interval '9 days', now() - interval '9 days',
          now() - interval '9 days' + interval '1 hour', now() - interval '8 days')
  returning id into poll_expired;
  insert into community.poll_options (poll_id, option_index, label)
  values (poll_expired, 0, 'Yes');

  -- (f) an old abuse event (30-day retention).
  insert into community.abuse_events (user_id, action, created_at)
  values (u3, 'report', now() - interval '31 days');

  -- (g) dormant profile: no activity, created 200 days ago.
  insert into community.reporter_profiles (user_id, created_at)
  values (pg_temp.new_user(), now() - interval '200 days');

  -- (h) u2 is active: their verification above counts as activity.
end;
$$;

SELECT is(
  (select count(*) from (select pg_temp.seed_world() as s) seed),
  1::bigint,
  'fixture seeded: 3 observations (unswept-expired, active, past-retention)'
);
SELECT is(
  (select count(*) from community.observations where room like 'exp-%'),
  3::bigint,
  'the seeded observations exist (3: unswept-expired, active, past-retention)'
);

-- Read paths hide expired rows WITHOUT the sweep (correctness sans cron) ------

SELECT is(
  (select pg_temp.count_as_user(
     'select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at from community.observations where room = ''exp-unswept-room''')),
  0::bigint,
  'an un-swept but past-expires_at observation is already invisible (RLS)'
);
SELECT is(
  (select pg_temp.count_as_user(
     'select * from community.active_observations where room = ''exp-unswept-room''')),
  0::bigint,
  'the view also hides it (no dependence on cron)'
);
SELECT is(
  (select pg_temp.count_as_user(
     'select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at from community.observations where room = ''exp-active-room''')),
  1::bigint,
  'the active observation remains visible (control)'
);
SELECT is(
  (select s->>'ok' = 'false' and s->>'code' = 'expired'
   from (select pg_temp.verify_expired_attempt() as s) x),
  true,
  'verifying an expired observation returns the expired code'
);

-- sweep_expired() ---------------------------------------------------------------

SELECT is(
  (select community.sweep_expired() >= 1),
  true,
  'sweep_expired() runs successfully'
);
SELECT is(
  (select status::text from community.observations
    where room = 'exp-unswept-room'),
  'expired',
  'sweep marked the past-expires_at observation as expired'
);
SELECT is(
  (select status::text from community.polls where room = 'exp-due-poll-room'),
  'closed',
  'sweep closed the poll past closes_at'
);
SELECT is(
  (select status::text from community.polls where room = 'exp-old-poll-room'),
  'expired',
  'sweep marked the long-closed poll as expired (past retention)'
);

-- retain_and_delete() ------------------------------------------------------------

SELECT is(
  (select pg_temp.retain()),
  0,
  'retain_and_delete() runs successfully'
);
SELECT is(
  (select count(*) from community.observations where room = 'exp-old-room'),
  0::bigint,
  'the past-retention expired observation was hard-deleted'
);
SELECT is(
  (select count(*) from community.verifications v
    join community.observations o on o.id = v.observation_id
    where o.room = 'exp-unswept-room'),
  1::bigint,
  'verifications on recently-expired observations survive (7-day window)'
);
SELECT is(
  (select count(*) from community.polls where room = 'exp-old-poll-room'),
  0::bigint,
  'the expired poll and its options/votes were deleted'
);
SELECT is(
  (select count(*) from community.abuse_events
    where created_at < now() - interval '30 days'),
  0::bigint,
  'abuse events older than 30 days were deleted'
);
SELECT is(
  (select count(*) from community.reporter_profiles
    where created_at < now() - interval '180 days'),
  0::bigint,
  'dormant profiles (180+ days, no activity) were deleted'
);
SELECT is(
  (select count(*) from community.observations where room = 'exp-active-room'),
  1::bigint,
  'active data survived retention untouched'
);

SELECT * FROM finish();
ROLLBACK;
