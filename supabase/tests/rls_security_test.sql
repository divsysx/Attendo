-- pgTAP: RLS / security adversarial matrix for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Proves the database is hostile to clients: an attacker holding only the
-- publishable key (anon role, or an authenticated anonymous identity) can
-- read exactly what the UI shows and nothing more, and can never write any
-- community table directly.
--
-- Matrix (from the plan §18 security rows):
--   * anon role cannot SELECT any community data
--   * authenticated users see active rows and own rows — never other users'
--     verifications, votes, trust or abuse events
--   * no role can INSERT/UPDATE/DELETE any community table directly
--   * internal helpers are not callable by client roles
--   * disputed/expired observations are invisible to strangers

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

-- A fixture table (temp, this transaction) so every test can reference the
-- seeded ids without re-seeding.
create temp table if not exists pg_temp.fixture (
  key text primary key, value uuid not null);

-- Seed one "world" as postgres (simulating the RPC layer's writes):
-- reporter A with an active / disputed / expired observation, verifier B with
-- a verification on the active one, an open poll + options + B's vote, and
-- an abuse event.
create or replace function pg_temp.seed_world() returns void
language plpgsql as $$
declare
  a uuid; b uuid;
  obs_active uuid; obs_disputed uuid; obs_expired uuid;
  poll_id uuid;
begin
  a := pg_temp.new_user();
  b := pg_temp.new_user();
  insert into community.reporter_profiles (user_id) values (a), (b);
  insert into pg_temp.fixture values ('a', a), ('b', b);

  insert into community.observations (
    idempotency_key, reporter_id, kind, room, expires_at)
  values (gen_random_uuid(), a, 'room_other', 'sec-active-room', now() + interval '3 hours')
  returning id into obs_active;

  insert into community.observations (
    idempotency_key, reporter_id, kind, room, status, expires_at, resolved_at)
  values (gen_random_uuid(), a, 'room_other', 'sec-disputed-room', 'disputed',
          now() + interval '3 hours', now())
  returning id into obs_disputed;

  insert into community.observations (
    idempotency_key, reporter_id, kind, room, status, expires_at, resolved_at)
  values (gen_random_uuid(), a, 'room_other', 'sec-expired-room', 'expired',
          now() + interval '3 hours', now())
  returning id into obs_expired;

  insert into pg_temp.fixture values ('obs_active', obs_active),
                                     ('obs_disputed', obs_disputed),
                                     ('obs_expired', obs_expired);

  insert into community.verifications (observation_id, user_id, verdict)
  values (obs_active, b, true);

  insert into community.polls (
    idempotency_key, creator_id, room, question, closes_at)
  values (gen_random_uuid(), a, 'sec-poll-room', 'Is the room free?',
          now() + interval '2 hours')
  returning id into poll_id;
  insert into pg_temp.fixture values ('poll_id', poll_id);

  insert into community.poll_options (poll_id, option_index, label)
  values (poll_id, 0, 'Yes'), (poll_id, 1, 'No');

  insert into community.poll_votes (poll_id, user_id, option_index)
  values (poll_id, b, 0);

  insert into community.abuse_events (user_id, action) values (a, 'report');
end;
$$;

create or replace function pg_temp.fx(p_key text) returns uuid
language sql as $$
  select value from pg_temp.fixture where key = p_key;
$$;

-- Count rows a query returns as a given role (uid null = anon).
-- Returns -1 on permission error: a hard denial, stronger than 0 rows.
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

-- A fresh authenticated stranger's view count of a query.
create or replace function pg_temp.count_as_user(p_sql text) returns bigint
language plpgsql as $$
begin
  return pg_temp.count_as('authenticated', pg_temp.new_user(), p_sql);
end;
$$;

-- The anon role's view count of a query.
create or replace function pg_temp.count_as_anon(p_sql text) returns bigint
language plpgsql as $$
begin
  return pg_temp.count_as('anon', null, p_sql);
end;
$$;

-- Attempt a write (any statement) as a role; 'denied' or 'succeeded'.
create or replace function pg_temp.write_attempt(p_role text, p_uid uuid, p_sql text)
returns text
language plpgsql as $$
begin
  perform set_config('role', p_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_uid::text, ''), true);
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

-- Any seeded auth user id (for write attempts that need a uid).
create or replace function pg_temp.any_uid() returns uuid
language sql as $$
  select value from pg_temp.fixture where key = 'b';
$$;

-- Seed the world once; tests read it repeatedly.
SELECT is(
  (select count(*) from (select pg_temp.seed_world() as s) seed),
  1::bigint,
  'world fixture seeded (2 users, 3 observations, 1 poll)'
);

-- anon role: nothing is visible ----------------------------------------------

SELECT is(
  (select pg_temp.count_as_anon('select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at from community.observations')),
  -1::bigint,
  'anon cannot query observations (no grant)'
);
SELECT is(
  (select pg_temp.count_as_anon('select id, room, section, subject, class_date, start_hour, question, status, created_at, dedup_hour, closes_at, closed_at from community.polls')),
  -1::bigint,
  'anon cannot query polls (no grant)'
);
SELECT is(
  (select pg_temp.count_as_anon('select * from community.reporter_profiles')),
  -1::bigint,
  'anon cannot query reporter_profiles (no grant)'
);
SELECT is(
  (select pg_temp.count_as_anon('select * from community.abuse_events')),
  -1::bigint,
  'anon cannot query abuse_events at all (no grant, no policy)'
);
SELECT is(
  (select pg_temp.count_as_anon('select * from community.active_observations')),
  -1::bigint,
  'anon cannot query the view (no grant)'
);
SELECT is(
  (select pg_temp.count_as_anon('select * from community.poll_votes')),
  -1::bigint,
  'anon cannot query poll_votes (no grant)'
);
SELECT is(
  (select pg_temp.count_as_anon('select * from community.verifications')),
  -1::bigint,
  'anon cannot query verifications (no grant)'
);

-- authenticated strangers: active rows only, never identities ------------------

SELECT is(
  (select pg_temp.count_as_user(format(
     'select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at
        from community.observations where id in (%L, %L, %L)',
     pg_temp.fx('obs_active'), pg_temp.fx('obs_disputed'), pg_temp.fx('obs_expired')))),
  1::bigint,
  'a stranger sees only the 1 active observation (disputed/expired hidden)'
);
SELECT is(
  (select pg_temp.count_as_user(
     'select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at from community.observations where room = ''sec-disputed-room''')),
  0::bigint,
  'disputed observations are invisible to strangers'
);
SELECT is(
  (select pg_temp.count_as_user(
     'select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at from community.observations where room = ''sec-expired-room''')),
  0::bigint,
  'expired observations are invisible to strangers'
);
SELECT is(
  (select pg_temp.count_as_user(format(
     'select * from community.active_observations where id in (%L, %L, %L)',
     pg_temp.fx('obs_active'), pg_temp.fx('obs_disputed'), pg_temp.fx('obs_expired')))),
  1::bigint,
  'the view exposes exactly the active row'
);
SELECT is(
  (select column_name::text from information_schema.columns
    where table_schema = 'community' and table_name = 'active_observations'
      and column_name = 'reporter_id'),
  null::text,
  'the view has no reporter_id column at all'
);
SELECT is(
  (select pg_temp.count_as_user('select * from community.verifications')),
  0::bigint,
  'a stranger sees zero verification rows'
);
SELECT is(
  (select pg_temp.count_as(
      'authenticated', pg_temp.fx('b'),
      'select * from community.verifications')),
  1::bigint,
  'the verifier sees only their own verification row'
);
SELECT is(
  (select pg_temp.count_as_user('select * from community.reporter_profiles')),
  0::bigint,
  'a stranger sees zero reporter profiles (trust is private)'
);
SELECT is(
  (select pg_temp.count_as_user('select * from community.poll_votes')),
  0::bigint,
  'a stranger sees zero vote rows'
);
SELECT is(
  (select pg_temp.count_as('authenticated', pg_temp.fx('b'),
     'select * from community.poll_votes')),
  1::bigint,
  'the voter sees only their own vote row'
);
SELECT is(
  (select pg_temp.count_as_user(format(
     'select id, room, section, subject, class_date, start_hour, question, status, created_at, dedup_hour, closes_at, closed_at
        from community.polls where status = ''open'' and id = %L',
     pg_temp.fx('poll_id')))),
  1::bigint,
  'an open poll is visible to any authenticated user'
);
SELECT is(
  (select pg_temp.count_as_user(format(
     'select po.poll_id, po.option_index, po.label from community.poll_options po
        join community.polls p on p.id = po.poll_id where p.id = %L',
     pg_temp.fx('poll_id')))),
  2::bigint,
  'options of a visible open poll are visible (and only those)'
);
SELECT is(
  (select pg_temp.count_as('authenticated', pg_temp.fx('a'), format(
     'select id, kind, room, section, subject, class_date, start_hour, payload, note, status, created_at, dedup_hour, expires_at, resolved_at
        from community.observations where id in (%L, %L, %L)',
     pg_temp.fx('obs_active'), pg_temp.fx('obs_disputed'), pg_temp.fx('obs_expired')))),
  3::bigint,
  'the reporter sees their own rows at any status (my pending reports)'
);

-- Column-level security (0006): identity columns are unreadable by clients --
-- (Realtime broadcasts the full row; only column privileges can strip
-- reporter_id / idempotency_key / creator_id from every path at once.)

SELECT is(
  (select pg_temp.count_as_user('select reporter_id from community.observations')),
  -1::bigint,
  'authenticated cannot SELECT the reporter_id column (Realtime leak closed)'
);
SELECT is(
  (select pg_temp.count_as_user('select idempotency_key from community.observations')),
  -1::bigint,
  'authenticated cannot SELECT the idempotency_key column (observations)'
);
SELECT is(
  (select pg_temp.count_as_user(
     'select creator_id from community.polls')),
  -1::bigint,
  'authenticated cannot SELECT the creator_id column (polls)'
);
SELECT is(
  (select pg_temp.count_as_user(format(
     'select id, room from community.polls where id = %L',
     pg_temp.fx('poll_id')))),
  1::bigint,
  'the readable poll columns still work for eligible rows'
);
-- even the owner cannot read their own reporter_id column:
SELECT is(
  (select pg_temp.count_as('authenticated', pg_temp.fx('a'),
     'select reporter_id from community.observations')),
  -1::bigint,
  'even the reporter cannot read the reporter_id column (identity column, not row data)'
);

-- Writes: every direct write attempt is denied, for both roles -----------------

SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'insert into community.observations (
        idempotency_key, reporter_id, kind, room, expires_at)
      values (gen_random_uuid(), auth.uid(), ''room_other'', ''203'', now() + interval ''1 hour'')')),
  'denied',
  'authenticated INSERT into observations is denied'
);
SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'update community.observations set status = ''confirmed''')),
  'denied',
  'authenticated UPDATE on observations is denied (no status forgery)'
);
SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'update community.reporter_profiles set trust_score = 100 where user_id = auth.uid()')),
  'denied',
  'authenticated UPDATE on reporter_profiles is denied (no trust forgery)'
);
SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'insert into community.abuse_events (user_id, action)
      values (auth.uid(), ''report'')')),
  'denied',
  'authenticated INSERT into abuse_events is denied (no rate-limit tampering)'
);
SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'update community.polls set closes_at = now() + interval ''30 days''')),
  'denied',
  'authenticated UPDATE on polls is denied (no lifetime extension)'
);
SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'insert into community.verifications (observation_id, user_id, verdict)
      select id, auth.uid(), true from community.observations limit 1')),
  'denied',
  'authenticated INSERT into verifications is denied (RPC only)'
);
SELECT is(
  (select pg_temp.write_attempt('anon', null,
     'insert into community.poll_votes (poll_id, user_id, option_index)
      select id, gen_random_uuid(), 0 from community.polls limit 1')),
  'denied',
  'anon INSERT into poll_votes is denied'
);
SELECT is(
  (select pg_temp.write_attempt('anon', null,
     'delete from community.observations')),
  'denied',
  'anon DELETE on observations is denied'
);

-- Internal helpers are not callable by client roles ------------------------------

SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'select community.record_abuse_event(''report'')')),
  'denied',
  'authenticated cannot call the internal record_abuse_event helper'
);
SELECT is(
  (select pg_temp.write_attempt('authenticated', pg_temp.any_uid(),
     'select community.recompute_observation_status(gen_random_uuid())')),
  'denied',
  'authenticated cannot call the internal recompute_observation_status helper'
);

SELECT * FROM finish();
ROLLBACK;
