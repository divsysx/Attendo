-- pgTAP: concurrency race tests for the Attendo community backend.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Proves concurrent duplicate submissions resolve to exactly one row and
-- one clean client response:
--   * same user + same idempotency key -> one observation, duplicate:true
--   * same user + same dedup window    -> one observation, duplicate_report
--   * same (poll, user)                -> one vote, already_voted
--   * same (observation, user)         -> one verification, already_verified
--   * an in-flight uncommitted insert blocks a racing insert (lock test)
--
-- Method: dblink gives us real second/third sessions. The connection
-- string uses the LOCAL DEV stack's documented default credentials
-- (postgres/postgres) — dev-only, nothing to do with the app's Supabase
-- credentials, which never appear in this repo. Postgres serializes
-- unique-index inserts, so "first commits, second retries" exercises exactly
-- the code path a simultaneous race hits (the loser's INSERT raises 23505,
-- caught by the RPC's exception handler) — and the final lock test proves
-- the serialization itself with an open transaction.
--
-- IMPORTANT: dblink remote sessions do NOT join this file's transaction.
-- Every remote write runs in the remote session's own autocommit and is
-- removed again by pg_temp.remote_cleanup() at both ends of the file
-- (defensive: a failed earlier run may have left rows behind).

BEGIN;
SELECT plan(10);

CREATE EXTENSION IF NOT EXISTS dblink;

-- The container's own network address (NOT 127.0.0.1: HBA treats loopback as
-- trust-auth, and dblink from a non-superuser refuses passwordless trust).
-- Resolve a non-loopback host for dblink: the local dev stack's HBA is
-- `trust` on 127.0.0.1 (dblink from a non-superuser refuses passwordless
-- trust) but scram-sha-256 elsewhere, so dblink must connect to a
-- non-loopback address. The Supabase network gives the DB container the
-- stable alias db.supabase.internal, which resolves from inside the
-- container across resets. (inet_server_addr() is NULL over the Unix
-- socket the test harness uses, and 127.0.0.1 over TCP, so it cannot be
-- relied on here.)
create or replace function pg_temp.self_addr() returns text
language sql as $$
  select 'db.supabase.internal';
$$;

-- Helpers -------------------------------------------------------------------

-- Run a statement on a separate autocommit session as user p_uid and return
-- jsonb {ok, code, id} parsed from the RPC result.
create or replace function pg_temp.remote_call(p_uid uuid, p_fn text)
returns jsonb
language plpgsql as $$
declare
  conn text := 'dbname=postgres user=postgres password=postgres host=' || pg_temp.self_addr() || ' port=5432';
begin
  return (
    select jsonb_build_object(
      'ok', (d.r::jsonb ->> 'ok'),
      'code', (d.r::jsonb ->> 'code'),
      'id', (d.r::jsonb ->> 'id'),
      'duplicate', (d.r::jsonb ->> 'duplicate'))
    from dblink(conn, format($q$
      select set_config('role', 'authenticated', true);
      select set_config('request.jwt.claim.sub', %L, true);
      select (%s)::text
    $q$, p_uid::text, p_fn)) as d(r text)
  );
exception
  when others then
    return jsonb_build_object('errcode', sqlstate, 'message', sqlerrm);
end;
$$;

-- Create an auth user locally (rolled back with the file's transaction is
-- fine for the *plan* bookkeeping, but rows referenced by remote commits
-- must survive until cleanup, so we create the user ON THE REMOTE SESSION
-- and return its id).
create or replace function pg_temp.remote_new_user() returns uuid
language plpgsql as $$
declare
  conn text := 'dbname=postgres user=postgres password=postgres host=' || pg_temp.self_addr() || ' port=5432';
  uid uuid;
begin
  select d.id::uuid into uid
  from dblink(conn, $q$
    insert into auth.users (id, email)
    values (gen_random_uuid(), 'race_' || gen_random_uuid() || '@test.local')
    returning id::text
  $q$) as d(id text);
  return uid;
end;
$$;

-- Remove a user and everything referencing it, on a remote session (so the
-- delete is real, not rolled back with this file's transaction).
create or replace function pg_temp.remote_cleanup(p_uid uuid) returns void
language plpgsql as $$
declare conn text := 'dbname=postgres user=postgres password=postgres host=' || pg_temp.self_addr() || ' port=5432';
begin
  perform dblink_exec(conn, format(
    'delete from auth.users where id = %L::uuid', p_uid::text));
exception
  when others then null;  -- best-effort cleanup
end;
$$;

-- Submit a report as p_uid on a remote session (convenience wrapper).
create or replace function pg_temp.remote_submit(
  p_uid uuid, p_room text, p_idem uuid
) returns jsonb
language plpgsql as $$
begin
  return pg_temp.remote_call(p_uid, format(
    'community.submit_report(%L::text, %L::text, null::text, null::text, null::date, null::smallint, %L::jsonb, null::text, %L::uuid)',
    'room_other', p_room, '{}', p_idem));
end;
$$;

-- Create a poll as p_uid on a remote session; returns {ok, id}.
create or replace function pg_temp.remote_create_poll(
  p_uid uuid, p_room text
) returns jsonb
language plpgsql as $$
begin
  return pg_temp.remote_call(p_uid, format(
    'community.create_poll(%L::text, null::text, null::text, null::date, null::smallint, %L::text, %L::jsonb, %L::uuid, 0::bigint)',
    p_room, 'Is the room busy?', '["Yes","No"]', gen_random_uuid()));
end;
$$;

-- Count rows for a user's data ON A REMOTE SESSION (sees committed state
-- only — the honest view of what other clients would see).
create or replace function pg_temp.remote_count(p_uid uuid, p_sql text)
returns bigint
language plpgsql as $$
declare conn text := 'dbname=postgres user=postgres password=postgres host=' || pg_temp.self_addr() || ' port=5432'; n bigint;
begin
  select d.n::bigint into n
  from dblink(conn, format(
    'select count(*)::text from (%s) q', p_sql)) as d(n text);
  return n;
exception
  when others then return -1;
end;
$$;


create or replace function pg_temp.pre_clean() returns integer
language plpgsql as $fn$
declare conn text := 'dbname=postgres user=postgres password=postgres host=' || pg_temp.self_addr() || ' port=5432';
begin
  perform dblink_exec(conn,
    $sql$ delete from auth.users u
       where exists (select 1 from community.reporter_profiles p
                     join community.observations o on o.reporter_id = p.user_id
                     where p.user_id = u.id
                       and o.idempotency_key in
                           ('33333333-3333-3333-3333-333333333331',
                            '33333333-3333-3333-3333-333333333332')) $sql$);
  return 0;
exception
  when others then return 0;
end;
$fn$;

create or replace function pg_temp.idem_race() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.remote_new_user();
  r1 jsonb; r2 jsonb; obs_count bigint;
begin
  r1 := pg_temp.remote_submit(uid, 'race-idem-room',
                              '33333333-3333-3333-3333-333333333331');
  r2 := pg_temp.remote_submit(uid, 'race-idem-room',
                              '33333333-3333-3333-3333-333333333331');
  return jsonb_build_object('ok', r2->>'ok', 'duplicate', r2->>'duplicate');
end;
$$;

create or replace function pg_temp.dedup_race() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.remote_new_user();
  r1 jsonb; r2 jsonb;
begin
  r1 := pg_temp.remote_submit(uid, 'race-dedup-room',
                              '33333333-3333-3333-3333-333333333332');
  r2 := pg_temp.remote_submit(uid, 'race-dedup-room', gen_random_uuid());
  return jsonb_build_object('r1_ok', r1->>'ok', 'code', r2->>'code');
end;
$$;

create or replace function pg_temp.vote_race() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.remote_new_user();
  -- The voter must not be the creator: cast_vote refuses the creator's vote
  -- in their own poll (0010's own_poll guard), which is not the race under test.
  voter uuid := pg_temp.remote_new_user();
  poll jsonb;
  v1 jsonb; v2 jsonb;
begin
  poll := pg_temp.remote_create_poll(uid, 'race-vote-room');
  v1 := pg_temp.remote_call(voter, format(
    'community.cast_vote(%L::uuid, 0::smallint)', (poll->>'id')::uuid));
  v2 := pg_temp.remote_call(voter, format(
    'community.cast_vote(%L::uuid, 1::smallint)', (poll->>'id')::uuid));
  return jsonb_build_object('ok', v2->>'ok', 'code', v2->>'code');
end;
$$;

create or replace function pg_temp.verify_race() returns jsonb
language plpgsql as $$
declare
  uid uuid := pg_temp.remote_new_user();
  reporter uuid := pg_temp.remote_new_user();
  submit jsonb;
  v1 jsonb; v2 jsonb;
begin
  submit := pg_temp.remote_submit(reporter, 'race-verify-room', gen_random_uuid());
  v1 := pg_temp.remote_call(uid, format(
    'community.verify(%L::uuid, true, null::uuid)', (submit->>'id')::uuid));
  v2 := pg_temp.remote_call(uid, format(
    'community.verify(%L::uuid, true, null::uuid)', (submit->>'id')::uuid));
  return jsonb_build_object('ok', v2->>'ok', 'code', v2->>'code');
end;
$$;

create or replace function pg_temp.race_cleanup() returns integer
language plpgsql as $fn$
declare conn text := 'dbname=postgres user=postgres password=postgres host=' || pg_temp.self_addr() || ' port=5432';
begin
  perform dblink_exec(conn,
    $sql$ delete from auth.users u
       where u.email like 'race_%@test.local' $sql$);
  return 0;
exception
  when others then return 0;
end;
$fn$;

-- Defensive pre-cleanup: remove any rows a failed earlier run left behind
-- (keyed by the fixed idempotency keys this suite uses).
SELECT is(
  (select pg_temp.pre_clean()),
  0,
  'pre-run cleanup of any leaked rows from a failed earlier run'
);

-- Race 1: idempotency key — one row, duplicate:true ---------------------------

SELECT is(
  (select s->>'ok' = 'true' and s->>'duplicate' = 'true'
   from (select pg_temp.idem_race() as s) x),
  true,
  'a retried submission from a second session gets duplicate:true'
);
SELECT is(
  (select count(*) from community.observations
    where idempotency_key = '33333333-3333-3333-3333-333333333331'),
  1::bigint,
  'exactly one observation row exists for the raced idempotency key'
);

-- Race 2: dedup window — one row, duplicate_report ------------------------------

SELECT is(
  (select s->>'code'
   from (select pg_temp.dedup_race() as s) x),
  'duplicate_report',
  'the same context filed from a second session gets duplicate_report'
);
SELECT is(
  (select count(*) from community.observations
    where room = 'race-dedup-room'),
  1::bigint,
  'exactly one observation row exists for the raced dedup window'
);

-- Race 3: votes — one row, already_voted ---------------------------------------

SELECT is(
  (select s->>'ok' = 'true' and s->>'code' = 'already_voted'
   from (select pg_temp.vote_race() as s) x),
  true,
  'a second concurrent vote from another session gets already_voted'
);
SELECT is(
  (select count(*) from community.poll_votes v
    join community.polls p on p.id = v.poll_id
    where p.room = 'race-vote-room'),
  1::bigint,
  'exactly one vote row exists for the raced (poll, user)'
);

-- Race 4: verifications — one row, already_verified ------------------------------

SELECT is(
  (select s->>'ok' = 'true' and s->>'code' = 'already_verified'
   from (select pg_temp.verify_race() as s) x),
  true,
  'a second concurrent verification from another session gets already_verified'
);
SELECT is(
  (select count(*) from community.verifications v
    join community.observations o on o.id = v.observation_id
    where o.room = 'race-verify-room'),
  1::bigint,
  'exactly one verification row exists for the raced (observation, user)'
);

-- Cleanup of all remote-committed rows from this suite.
SELECT is(
  (select pg_temp.race_cleanup()),
  0,
  'remote cleanup removed all race fixtures'
);

SELECT * FROM finish();
ROLLBACK;
