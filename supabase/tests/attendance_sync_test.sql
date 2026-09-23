-- pgTAP: Attendance Sync backend (migration 0020) — schema, privileges, RLS,
-- revision sequencing, insert stamping, last-write-wins and tombstones.
-- Run: supabase test db  (requires the local stack: supabase start)
--
-- Scope: the DATABASE HALF of Attendance Sync only. The Android sync client is
-- not implemented, so nothing here exercises a client, a cursor, or a push/pull
-- engine. Every assertion was derived by reading
-- supabase/migrations/0020_attendance_sync.sql, then confirming the behavior
-- experimentally against the local stack. Where the SQL leaves a behavior
-- ambiguous, the observed behavior is named explicitly in the test text rather
-- than assumed.
--
-- Isolation: attendance rows are strictly per-account (RLS: user_id =
-- auth.uid()), so every test creates fresh random auth users and asserts on its
-- OWN rows. This suite is therefore immune to whatever else the local database
-- happens to contain (unlike the community count-based suites), and because the
-- whole file runs in one transaction that is rolled back at the end, it leaves
-- no residue behind.
--
-- Write/read probes run as the `authenticated` role with auth.uid() set, which
-- is the same technique the community suites use (see rls_security_test.sql).

BEGIN;
SELECT plan(102);

-- Helpers -------------------------------------------------------------------

-- A fresh account. Random id + random email: never collides with rows another
-- run (or another suite) left behind.
create or replace function pg_temp.new_user() returns uuid
language plpgsql as $$
declare uid uuid;
begin
  insert into auth.users (id, email)
  values (gen_random_uuid(), 'att_' || gen_random_uuid() || '@test.local')
  returning id into uid;
  return uid;
end;
$$;

-- Run p_sql as a client role with auth.uid() = p_uid. Returns 'OK' or the
-- SQLSTATE of the failure. Role switching is transaction-local (true).
create or replace function pg_temp.run_as(p_role text, p_uid uuid, p_sql text)
returns text language plpgsql as $$
begin
  perform set_config('role', p_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_uid::text, ''), true);
  execute p_sql;
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return 'OK';
exception when others then
  perform set_config('role', 'postgres', true);
  perform set_config('request.jwt.claim.sub', '', true);
  return sqlstate;
end;
$$;

-- Rows p_sql returns to a role/uid. -1 means a hard permission error (stronger
-- than "0 rows": the role could not even read the table).
create or replace function pg_temp.count_as(p_role text, p_uid uuid, p_sql text)
returns bigint language plpgsql as $$
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

-- Every id this file needs, resolved once. Held in a temp table so the tests
-- below can format literals from it (never referenced *inside* role-switched
-- SQL, where the client role could not read it).
create temp table fx (k text primary key, v uuid);
insert into fx values
  ('ua',        pg_temp.new_user()),
  ('ub',        pg_temp.new_user()),
  ('uc',        pg_temp.new_user()),
  ('sem_a',     'aaaaaaaa-0000-0000-0000-000000000001'),
  ('sem_b',     'aaaaaaaa-0000-0000-0000-000000000002'),
  ('crs_a',     'bbbbbbbb-0000-0000-0000-000000000001'),
  ('crs_b',     'bbbbbbbb-0000-0000-0000-000000000002'),
  ('crs_bad',   'bbbbbbbb-0000-0000-0000-000000000003'),
  ('pat_a',     'cccccccc-0000-0000-0000-000000000001'),
  ('pat_bad',   'cccccccc-0000-0000-0000-000000000002'),
  ('ses_a',     'dddddddd-0000-0000-0000-000000000001'),
  ('ses_a2',    'dddddddd-0000-0000-0000-000000000002'),
  ('ses_bad',   'dddddddd-0000-0000-0000-000000000003');

-- ============================================================================
-- 1. Schema and object coverage
-- ============================================================================

SELECT has_schema('attendance', 'attendance schema exists');
SELECT has_sequence('attendance', 'change_seq', 'shared delta-cursor sequence exists');

SELECT has_table('attendance', 'accounts',  'accounts exists');
SELECT has_table('attendance', 'semesters', 'semesters exists');
SELECT has_table('attendance', 'courses',   'courses exists');
SELECT has_table('attendance', 'patterns',  'patterns exists');
SELECT has_table('attendance', 'sessions',  'sessions exists');

-- The four bookkeeping columns, on every table: 5 tables x 4 columns = 20.
SELECT is(
  (select count(*)::int from information_schema.columns
    where table_schema = 'attendance'
      and table_name in ('accounts','semesters','courses','patterns','sessions')
      and column_name in ('client_updated_at','revision','updated_at','deleted_at')),
  20,
  'all five tables carry client_updated_at, revision, updated_at and deleted_at'
);

-- The task brief expected cloud_id and dirty columns. Migration 0020 defines
-- NEITHER: those are Room-side concepts (CourseEntity.cloudId / .dirty). The
-- cloud identity here IS the primary key, so this assertion records the real
-- schema rather than an assumed one.
SELECT is(
  (select count(*)::int from information_schema.columns
    where table_schema = 'attendance' and column_name in ('cloud_id','dirty')),
  0,
  'no cloud_id / dirty columns: in this schema the PK is the cloud id and dirtiness is a client concept'
);

SELECT col_is_pk('attendance', 'accounts', 'user_id', 'accounts is keyed by user_id (one row per account)');
SELECT col_is_pk('attendance', 'courses', 'id', 'courses is keyed by a client-assigned uuid');

-- RLS enabled everywhere; exactly the select/insert/update triple per table.
SELECT is(
  (select count(*)::int from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'attendance' and c.relkind = 'r' and c.relrowsecurity),
  5,
  'row level security is enabled on all five tables'
);
SELECT is(
  (select count(*)::int from pg_policies where schemaname = 'attendance'),
  15,
  'exactly 15 policies exist (select/insert/update x 5 tables)'
);
SELECT is(
  (select count(*)::int from pg_policies where schemaname = 'attendance' and cmd = 'DELETE'),
  0,
  'there is deliberately no DELETE policy on any attendance table'
);

-- Delta-pull indexes: (user_id, revision) on the four child tables.
SELECT has_index('attendance', 'semesters', 'semesters_pull', 'semesters has a (user_id, revision) pull index');
SELECT has_index('attendance', 'courses',   'courses_pull',   'courses has a (user_id, revision) pull index');
SELECT has_index('attendance', 'patterns',  'patterns_pull',  'patterns has a (user_id, revision) pull index');
SELECT has_index('attendance', 'sessions',  'sessions_pull',  'sessions has a (user_id, revision) pull index');
-- accounts deliberately has none: it is one row per account, reached by its PK.
SELECT is(
  (select count(*)::int from pg_indexes
    where schemaname = 'attendance' and tablename = 'accounts' and indexdef like '%revision%'),
  0,
  'accounts has no (user_id, revision) index — its PK is the whole lookup'
);

-- Triggers: five lww + five insert-stamp.
SELECT is(
  (select count(*)::int from pg_trigger t join pg_class c on c.oid = t.tgrelid
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'attendance' and not t.tgisinternal and t.tgname like '%\_lww'),
  5,
  'five lww_touch BEFORE UPDATE triggers exist'
);
SELECT is(
  (select count(*)::int from pg_trigger t join pg_class c on c.oid = t.tgrelid
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'attendance' and not t.tgisinternal and t.tgname like '%\_insert\_stamp'),
  5,
  'five stamp_insert BEFORE INSERT triggers exist'
);
SELECT has_trigger('attendance', 'courses', 'courses_lww', 'courses_lww is wired to courses');

-- Foreign keys: exactly what the migration declares, no more.
SELECT is(
  (select count(*)::int from pg_constraint
    where connamespace = 'attendance'::regnamespace and contype = 'f'
      and pg_get_constraintdef(oid) like '%REFERENCES auth.users(id) ON DELETE CASCADE%'),
  5,
  'all five tables cascade from auth.users (account deletion removes everything)'
);
SELECT is(
  (select count(*)::int from pg_constraint
    where connamespace = 'attendance'::regnamespace and contype = 'f'
      and pg_get_constraintdef(oid) like '%REFERENCES attendance.courses(id, user_id)%'),
  2,
  'patterns and sessions each carry the same-account composite FK to courses'
);
-- These two are deliberately NOT foreign keys (dangling refs are a legitimate
-- transient local state; a cascade must never travel semester -> attendance).
SELECT is(
  (select count(*)::int from pg_constraint
    where connamespace = 'attendance'::regnamespace and contype = 'f'
      and pg_get_constraintdef(oid) like '%(semester_id)%'),
  0,
  'courses.semester_id is deliberately not a foreign key'
);
SELECT is(
  (select count(*)::int from pg_constraint
    where connamespace = 'attendance'::regnamespace and contype = 'f'
      and pg_get_constraintdef(oid) like '%(pattern_id)%'),
  0,
  'sessions.pattern_id is deliberately not a foreign key'
);

-- ============================================================================
-- 2. Privileges — what the client roles may do at all
-- ============================================================================

SELECT is(has_table_privilege('authenticated', 'attendance.courses', 'SELECT'), true,
          'authenticated may SELECT attendance rows');
SELECT is(has_table_privilege('authenticated', 'attendance.courses', 'INSERT'), true,
          'authenticated may INSERT attendance rows');
SELECT is(has_table_privilege('authenticated', 'attendance.courses', 'UPDATE'), true,
          'authenticated may UPDATE attendance rows');
SELECT is(has_table_privilege('authenticated', 'attendance.courses', 'DELETE'), false,
          'authenticated has NO DELETE privilege (delete is withheld at the grant level)');
SELECT is(has_table_privilege('anon', 'attendance.courses', 'SELECT'), false,
          'anon has no SELECT privilege on attendance at all');
SELECT is(has_schema_privilege('authenticated', 'attendance', 'USAGE'), true,
          'authenticated has USAGE on the attendance schema');
SELECT is(has_sequence_privilege('authenticated', 'attendance.change_seq', 'USAGE'), true,
          'authenticated may draw from change_seq (the triggers evaluate nextval as the caller)');
SELECT is(has_function_privilege('authenticated', 'attendance.lww_touch()', 'EXECUTE'), false,
          'lww_touch is not directly callable by clients (trigger-only)');
SELECT is(has_function_privilege('authenticated', 'attendance.stamp_insert()', 'EXECUTE'), false,
          'stamp_insert is not directly callable by clients (trigger-only)');

-- ============================================================================
-- 3. DELETE is impossible for clients — five ways, one per table
-- ============================================================================
-- This is a GRANT-level denial (42501 permission denied for table), not an RLS
-- rejection: the privilege was revoked outright and no DELETE policy exists to
-- let RLS decide. The strongest form the migration could have chosen.

SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            'delete from attendance.accounts'),
          '42501', 'authenticated cannot DELETE from accounts');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            'delete from attendance.semesters'),
          '42501', 'authenticated cannot DELETE from semesters');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            'delete from attendance.courses'),
          '42501', 'authenticated cannot DELETE from courses');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            'delete from attendance.patterns'),
          '42501', 'authenticated cannot DELETE from patterns');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            'delete from attendance.sessions'),
          '42501', 'authenticated cannot DELETE from sessions');
SELECT is(pg_temp.run_as('anon', null,
            'delete from attendance.courses'),
          '42501', 'anon cannot DELETE from courses either');
SELECT is(pg_temp.run_as('anon', null,
            'select * from attendance.courses'),
          '42501', 'anon cannot even SELECT attendance rows (no grant)');

-- ============================================================================
-- 4. INSERT authorization — the ownership boundary, per table
-- ============================================================================
-- Seeded as postgres (bypassing RLS) so there is always a parent row to hang
-- the pattern/session inserts off.

insert into attendance.accounts (user_id, term_start, term_end, client_updated_at)
  select v, '2026-07-01', '2026-12-31', now() from fx where k = 'ua';
insert into attendance.semesters (id, user_id, year, kind, start_date, end_date, client_updated_at)
  select v, (select v from fx where k='ua'), 2026, 'ODD', '2026-07-01', '2026-12-31', now()
  from fx where k = 'sem_a';
insert into attendance.courses (id, user_id, name, code, client_updated_at)
  select v, (select v from fx where k='ua'), 'A course', 'AC', now() from fx where k = 'crs_a';
insert into attendance.courses (id, user_id, name, code, client_updated_at)
  select v, (select v from fx where k='ub'), 'B course', 'BC', now() from fx where k = 'crs_b';

-- ua inserting its OWN rows: allowed (with check user_id = auth.uid()).
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.semesters (id,user_id,year,kind,start_date,end_date,client_updated_at)
                    values (%L, auth.uid(), 2027, ''EVEN'', ''2027-01-01'', ''2027-06-30'', now())',
                   (select v from fx where k = 'sem_b'))),
          'OK', 'a user may insert their own semester');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.courses (id,user_id,name,code,client_updated_at)
                    values (%L, auth.uid(), ''owned'', ''OW'', now())',
                   'eeeeeeee-0000-0000-0000-000000000001')),
          'OK', 'a user may insert their own course');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.patterns (id,user_id,course_id,day_of_week,start_hour,units,kind,effective_from,client_updated_at)
                    values (%L, auth.uid(), %L, 1, 9, 1, ''LECTURE'', ''2026-07-01'', now())',
                   (select v from fx where k = 'pat_a'), (select v from fx where k = 'crs_a'))),
          'OK', 'a user may insert a pattern under their own course');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.sessions (id,user_id,course_id,date,start_hour,units_planned,status,kind,client_updated_at)
                    values (%L, auth.uid(), %L, ''2026-08-01'', 9, 1, ''SCHEDULED'', ''LECTURE'', now())',
                   (select v from fx where k = 'ses_a'), (select v from fx where k = 'crs_a'))),
          'OK', 'a user may insert a session under their own course');

-- ua inserting rows stamped with ANOTHER user's id: RLS with-check refuses.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.courses (id,user_id,name,code,client_updated_at)
                    values (%L, %L, ''forged'', ''FG'', now())',
                   (select v from fx where k = 'crs_bad'), (select v from fx where k = 'ub'))),
          '42501', 'a user CANNOT insert a course owned by somebody else');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.semesters (id,user_id,year,kind,start_date,end_date,client_updated_at)
                    values (%L, %L, 2027, ''ODD'', ''2027-01-01'', ''2027-06-30'', now())',
                   'eeeeeeee-0000-0000-0000-000000000002', (select v from fx where k = 'ub'))),
          '42501', 'a user CANNOT insert a semester owned by somebody else');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.patterns (id,user_id,course_id,day_of_week,start_hour,units,kind,effective_from,client_updated_at)
                    values (%L, %L, %L, 1, 9, 1, ''LECTURE'', ''2026-07-01'', now())',
                   'eeeeeeee-0000-0000-0000-000000000003',
                   (select v from fx where k = 'ub'), (select v from fx where k = 'crs_b'))),
          '42501', 'a user CANNOT insert a pattern owned by somebody else');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.sessions (id,user_id,course_id,date,start_hour,units_planned,status,kind,client_updated_at)
                    values (%L, %L, %L, ''2026-08-09'', 9, 1, ''SCHEDULED'', ''LECTURE'', now())',
                   'eeeeeeee-0000-0000-0000-000000000004',
                   (select v from fx where k = 'ub'), (select v from fx where k = 'crs_b'))),
          '42501', 'a user CANNOT insert a session owned by somebody else');

-- ============================================================================
-- 5. Row isolation — own rows visible, other people's not
-- ============================================================================

SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ua'),
            'select * from attendance.accounts'),  1::bigint, 'ua sees their own accounts row');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ua'),
            'select * from attendance.semesters'), 2::bigint, 'ua sees their own two semester rows');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ua'),
            'select * from attendance.courses'),   2::bigint, 'ua sees their own two course rows');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ua'),
            'select * from attendance.patterns'),  1::bigint, 'ua sees their own pattern row');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ua'),
            'select * from attendance.sessions'),  1::bigint, 'ua sees their own session row');

SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ub'),
            'select * from attendance.accounts'),  0::bigint, 'ub sees none of ua''s accounts rows');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ub'),
            'select * from attendance.semesters'), 0::bigint, 'ub sees none of ua''s semesters');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ub'),
            'select * from attendance.courses'),   1::bigint, 'ub sees only their own course, never ua''s');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ub'),
            'select * from attendance.patterns'),  0::bigint, 'ub sees none of ua''s patterns');
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ub'),
            'select * from attendance.sessions'),  0::bigint, 'ub sees none of ua''s sessions');

SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'uc'),
            'select * from attendance.courses'),   0::bigint,
          'a brand new account sees zero courses (no ambient read)');
SELECT is(pg_temp.count_as('anon', null, 'select * from attendance.courses'), -1::bigint,
          'anon cannot read attendance at all (hard permission error)');

-- Targeted reads: the other user's specific row is invisible even by id.
SELECT is(pg_temp.count_as('authenticated', (select v from fx where k = 'ub'),
            format('select * from attendance.courses where id = %L',
                   (select v from fx where k = 'crs_a'))),
          0::bigint, 'ub cannot read ua''s course even by primary key');

-- ============================================================================
-- 6. UPDATE authorization
-- ============================================================================

SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''renamed'', client_updated_at = now() + interval ''1 hour''
                    where id = %L', (select v from fx where k = 'crs_a'))),
          'OK', 'a user may update their own course');
SELECT is((select name from attendance.courses where id = (select v from fx where k = 'crs_a')),
          'renamed', 'the own-row update actually landed');

-- Another user's row: the USING clause filters it out, so this is a silent
-- 0-row no-op, NOT an error. Recorded as observed.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''hijacked'' where id = %L',
                   (select v from fx where k = 'crs_b'))),
          'OK', 'updating another user''s row is silently filtered by USING (no error raised)');
SELECT is((select name from attendance.courses where id = (select v from fx where k = 'crs_b')),
          'B course', 'the other user''s row is unchanged by that attempt');

-- Ownership cannot be reassigned. lww_touch overwrites NEW.user_id with OLD's
-- BEFORE the WITH CHECK policy is evaluated, so the write is accepted and then
-- silently holds its original owner. (Both layers agree: with the trigger
-- disabled the WITH CHECK raises 42501 instead — the row can never change hands
-- either way.)
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set user_id = %L, client_updated_at = now() + interval ''2 hours''
                    where id = %L', (select v from fx where k = 'ub'), (select v from fx where k = 'crs_a'))),
          'OK', 'the attempt to reassign ownership is accepted syntactically');
SELECT is((select user_id from attendance.courses where id = (select v from fx where k = 'crs_a')),
          (select v from fx where k = 'ua'),
          'but the trigger pinned ownership back to the original owner');

-- ============================================================================
-- 7. Revision sequencing — one shared sequence across all five tables
-- ============================================================================

SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.courses (id,user_id,name,code,client_updated_at)
                    values (%L, auth.uid(), ''seq1'', ''S1'', now())',
                   'eeeeeeee-0000-0000-0000-00000000000a')),
          'OK', 'seed a course for the sequencing checks');

SELECT ok((select revision from attendance.courses where id = 'eeeeeeee-0000-0000-0000-00000000000a') > 0,
          'an inserted row receives a positive revision');
SELECT ok(
  (select revision from attendance.courses where id = 'eeeeeeee-0000-0000-0000-00000000000a')
    > (select revision from attendance.semesters where id = (select v from fx where k = 'sem_b')),
  'a later insert into a DIFFERENT table draws a higher revision — the sequence is shared, not per-table');

-- Global, not per-user: a different account's later insert is still higher.
SELECT ok(
  (select revision from attendance.courses where id = (select v from fx where k = 'crs_b'))
    < (select revision from attendance.sessions where id = (select v from fx where k = 'ses_a')),
  'revisions increase across accounts too — the sequence is global, not per-user');

SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''seq1b'', client_updated_at = now() + interval ''3 hours''
                    where id = %L', 'eeeeeeee-0000-0000-0000-00000000000a')),
          'OK', 'update the seeded course');
SELECT ok(
  (select revision from attendance.courses where id = 'eeeeeeee-0000-0000-0000-00000000000a')
    > (select revision from attendance.sessions where id = (select v from fx where k = 'ses_a')),
  'an accepted UPDATE advances the revision past every earlier insert');

-- A rejected (stale) update must NOT burn a revision.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''stale'', client_updated_at = (select client_updated_at - interval ''1 hour'' from attendance.courses where id = %L)
                    where id = %L',
                   'eeeeeeee-0000-0000-0000-00000000000a', 'eeeeeeee-0000-0000-0000-00000000000a')),
          'OK', 'issue a stale update');
SELECT is((select name from attendance.courses where id = 'eeeeeeee-0000-0000-0000-00000000000a'),
          'seq1b', 'the stale update changed nothing');

-- ============================================================================
-- 8. stamp_insert — server-assigned revision and updated_at on INSERT
-- ============================================================================

insert into attendance.semesters (id, user_id, year, kind, start_date, end_date,
                                  client_updated_at, revision, updated_at)
  select 'ffffffff-0000-0000-0000-000000000001', v, 2028, 'ODD', '2028-01-01', '2028-06-30',
         timestamptz '2028-03-03 12:00:00+00', 999999999, timestamptz '2000-01-01 00:00:00+00'
  from fx where k = 'ua';

SELECT isnt((select revision from attendance.semesters where id = 'ffffffff-0000-0000-0000-000000000001'),
            999999999::bigint,
            'a client-supplied revision on INSERT is overwritten by the server');
SELECT ok((select revision from attendance.semesters where id = 'ffffffff-0000-0000-0000-000000000001') > 0,
          'the overwritten revision is a real sequence value');
SELECT ok((select updated_at from attendance.semesters where id = 'ffffffff-0000-0000-0000-000000000001')
            > timestamptz '2020-01-01 00:00:00+00',
          'a client-supplied updated_at on INSERT is overwritten with server now()');
SELECT is((select client_updated_at from attendance.semesters where id = 'ffffffff-0000-0000-0000-000000000001'),
          timestamptz '2028-03-03 12:00:00+00',
          'client_updated_at is left exactly as the client sent it (that is the LWW input)');

-- ============================================================================
-- 9. Last-write-wins on client_updated_at
-- ============================================================================

-- Newer write: accepted.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''newer'', client_updated_at = now() + interval ''10 hours''
                    where id = %L', (select v from fx where k = 'crs_a'))),
          'OK', 'a strictly-newer write is accepted');
SELECT is((select name from attendance.courses where id = (select v from fx where k = 'crs_a')),
          'newer', 'the newer value is stored');

-- Older write: rejected, values untouched.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''older'', client_updated_at = (select client_updated_at - interval ''5 hours'' from attendance.courses where id = %L)
                    where id = %L', (select v from fx where k = 'crs_a'), (select v from fx where k = 'crs_a'))),
          'OK', 'a stale write is accepted syntactically');
SELECT is((select name from attendance.courses where id = (select v from fx where k = 'crs_a')),
          'newer', 'but its value is discarded — last write wins on client_updated_at');

-- EQUAL timestamp: the trigger uses <=, so an equal timestamp is a no-op too.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''equal'', client_updated_at = (select client_updated_at from attendance.courses where id = %L)
                    where id = %L', (select v from fx where k = 'crs_a'), (select v from fx where k = 'crs_a'))),
          'OK', 'an equal-timestamp write is accepted syntactically');
SELECT is((select name from attendance.courses where id = (select v from fx where k = 'crs_a')),
          'newer', 'an EQUAL client_updated_at is a no-op (the comparison is not strictly-newer)');

-- Idempotency: replaying the same logical write twice changes nothing.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''replay'', client_updated_at = now() + interval ''20 hours''
                    where id = %L', (select v from fx where k = 'crs_a'))),
          'OK', 'first application of a write is accepted');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.courses set name = ''replay'', client_updated_at = (select client_updated_at from attendance.courses where id = %L)
                    where id = %L', (select v from fx where k = 'crs_a'), (select v from fx where k = 'crs_a'))),
          'OK', 'pushing the same batch again is accepted syntactically');
SELECT is((select name from attendance.courses where id = (select v from fx where k = 'crs_a')),
          'replay', 'the replayed write is idempotent (no second application)');

-- ============================================================================
-- 10. Tombstones — deleted_at, not row removal
-- ============================================================================

insert into attendance.sessions (id, user_id, course_id, pattern_id, date, start_hour,
                                 units_planned, status, kind, client_updated_at)
  select v, (select v from fx where k='ua'), (select v from fx where k='crs_a'),
         (select v from fx where k='pat_a'), '2026-09-01', 9, 1, 'SCHEDULED', 'LECTURE', now()
  from fx where k = 'ses_a2';

SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('update attendance.sessions set deleted_at = now(), client_updated_at = now() + interval ''1 hour''
                    where id = %L', (select v from fx where k = 'ses_a2'))),
          'OK', 'a tombstone is written as an ordinary UPDATE of deleted_at');
SELECT is((select count(*)::int from attendance.sessions where id = (select v from fx where k = 'ses_a2')),
          1, 'the tombstoned row is RETAINED, not physically deleted');
SELECT ok((select deleted_at is not null from attendance.sessions where id = (select v from fx where k = 'ses_a2')),
          'deleted_at is set on the tombstoned row');
SELECT ok((select revision from attendance.sessions where id = (select v from fx where k = 'ses_a2')) > 0,
          'a tombstone carries a revision, so it is visible to a delta-pull cursor like any other change');

-- The live-uniqueness index exempts tombstoned rows: the slot frees up.
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.sessions (id,user_id,course_id,pattern_id,date,start_hour,units_planned,status,kind,client_updated_at)
                    values (%L, auth.uid(), %L, %L, ''2026-09-01'', 9, 1, ''SCHEDULED'', ''LECTURE'', now() + interval ''2 hours'')',
                   (select v from fx where k = 'ses_bad'), (select v from fx where k = 'crs_a'),
                   (select v from fx where k = 'pat_a'))),
          'OK', 'after a tombstone, the same (pattern, date) slot can be taken again');
SELECT is(pg_temp.run_as('authenticated', (select v from fx where k = 'ua'),
            format('insert into attendance.sessions (id,user_id,course_id,pattern_id,date,start_hour,units_planned,status,kind,client_updated_at)
                    values (%L, auth.uid(), %L, %L, ''2026-09-01'', 9, 1, ''SCHEDULED'', ''LECTURE'', now() + interval ''3 hours'')',
                   'dddddddd-0000-0000-0000-000000000009', (select v from fx where k = 'crs_a'),
                   (select v from fx where k = 'pat_a'))),
          '23505', 'but a SECOND live session for the same (pattern, date) is refused by the unique index');

-- ============================================================================
-- 11. Cross-table ownership — the composite foreign keys
-- ============================================================================

-- ua cannot hang a pattern off ub's course even as postgres: the composite FK
-- (course_id, user_id) -> courses(id, user_id) has no matching row.
SELECT is(
  (select count(*)::int from pg_constraint
    where connamespace = 'attendance'::regnamespace
      and conname = 'patterns_course_id_user_id_fkey'
      and pg_get_constraintdef(oid) like '%REFERENCES attendance.courses(id, user_id)%'),
  1, 'the same-account composite FK on patterns is present and correctly factored');

SELECT throws_ok(
  format('insert into attendance.patterns (id,user_id,course_id,day_of_week,start_hour,units,kind,effective_from,client_updated_at)
          values (%L, %L, %L, 1, 9, 1, ''LECTURE'', ''2026-07-01'', now())',
         (select v from fx where k = 'pat_bad'), (select v from fx where k = 'ub'),
         (select v from fx where k = 'crs_a')),
  '23503', null,
  'a pattern cannot reference another account''s course (composite FK)');

-- Dangling references that the migration deliberately permits.
SELECT lives_ok(
  format('insert into attendance.sessions (id,user_id,course_id,pattern_id,date,start_hour,units_planned,status,kind,client_updated_at)
          values (%L, %L, %L, gen_random_uuid(), ''2026-10-01'', 9, 1, ''SCHEDULED'', ''LECTURE'', now())',
         'dddddddd-0000-0000-0000-00000000000a', (select v from fx where k = 'ua'),
         (select v from fx where k = 'crs_a')),
  'a session may carry a pattern_id that no longer exists (retired patterns; deliberately not a FK)');
SELECT lives_ok(
  format('insert into attendance.courses (id,user_id,semester_id,name,code,client_updated_at)
          values (%L, %L, gen_random_uuid(), ''dangling'', ''DG'', now())',
         'eeeeeeee-0000-0000-0000-00000000000b', (select v from fx where k = 'ua')),
  'a course may carry a semester_id that does not exist (dangling refs are a legitimate transient state)');

-- ============================================================================
-- 12. Account deletion cascade (auth.users -> all five tables)
-- ============================================================================

delete from auth.users where id = (select v from fx where k = 'ua');

SELECT is(
  (select count(*)::int from attendance.accounts where user_id = (select v from fx where k = 'ua'))
  + (select count(*)::int from attendance.semesters where user_id = (select v from fx where k = 'ua'))
  + (select count(*)::int from attendance.courses where user_id = (select v from fx where k = 'ua'))
  + (select count(*)::int from attendance.patterns where user_id = (select v from fx where k = 'ua'))
  + (select count(*)::int from attendance.sessions where user_id = (select v from fx where k = 'ua')),
  0, 'deleting the auth user cascades away every attendance row the account owned');
SELECT is((select count(*)::int from attendance.courses where user_id = (select v from fx where k = 'ub')),
          1, 'the other account''s rows survive the cascade untouched');

SELECT * FROM finish();
ROLLBACK;
