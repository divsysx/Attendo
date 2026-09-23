-- Attendo Attendance Sync — Phase 1 cloud schema.
--
-- Private per-account cloud copy of a student's attendance data, so Android and
-- (later) Web clients can synchronise. Everything the portable Backup Format v2
-- carries is represented here, with sync bookkeeping added:
--
--   attendance.accounts   ~ BackupPreferences + AcademicCalendar (one row/account)
--   attendance.semesters  ~ SemesterEntity
--   attendance.courses    ~ CourseEntity
--   attendance.patterns   ~ PatternEntity
--   attendance.sessions   ~ SessionEntity
--
-- Scope decisions (mirroring the requirements):
--   * Ownership is auth.users.id — the Supabase Auth user. Nothing here
--     references community.reporter_profiles or any anonymous community
--     identity; the two systems share at most a user_id value, never a row.
--   * Accountless installs stay local-only: they never touch these tables.
--   * Deletion is soft (deleted_at tombstones). There is deliberately NO
--     client DELETE path — clients tombstone; hard deletes happen only when
--     the auth.users row is deleted (account deletion cascades everything).
--   * Supabase is the authoritative live state; clients are replicas that
--     reconcile offline and push when online.
--
-- Sync model (delta-first, idempotent, LWW):
--   * Every row carries `revision bigint` drawn from the shared sequence
--     attendance.change_seq. Clients pull with a single per-account cursor:
--     "revision > cursor" on each table, ordered by revision. One sequence
--     across all five tables keeps the cursor exact.
--   * Every row carries `client_updated_at timestamptz` — the writing
--     device's clock at the moment of the local edit. The BEFORE UPDATE
--     trigger attendance.lww_touch() implements last-write-wins: a row whose
--     incoming client_updated_at is not strictly newer than the stored one is
--     left untouched (replays and stale-device writes both become no-ops, so
--     pushing the same batch twice is safe).
--   * `updated_at` and `revision` are server-controlled; clients never send
--     them (values in an incoming payload are ignored/overwritten).
--
-- Idempotency of this migration: every statement is if-not-exists /
-- create-or-replace / drop-if-exists-then-create, per project convention.
-- Additive only: nothing in schema `community` or `public` is read or written.

-- ============================================================================
-- Schema + shared delta-cursor sequence
-- ============================================================================

create schema if not exists attendance;

create sequence if not exists attendance.change_seq;

-- ============================================================================
-- accounts — settings that ARE attendance state (calendar + BackupPreferences)
-- ============================================================================

create table if not exists attendance.accounts (
  user_id           uuid primary key references auth.users(id) on delete cascade,
  -- AcademicCalendar
  term_start        date not null,
  term_end          date not null,
  holidays          date[] not null default '{}',
  working_saturdays date[] not null default '{}',
  -- BackupPreferences
  -- The threshold the dashboard's OVERALL figure is judged against. This is an
  -- account-level academic setting (AppSettings.overallTarget), distinct from
  -- any course's own target.
  overall_target_bp integer not null default 7500,
  -- The default target applied to NEWLY created courses (AppSettings.
  -- courseTarget). It is NOT a copy of any existing course's target: actual
  -- course targets live in attendance.courses.target_bp, and the bulk
  -- "apply to all courses" operation writes each course row explicitly —
  -- there is deliberately no bulk cloud operation for it.
  default_target_bp integer not null default 7500,
  section           text,
  batch             text,
  display_name      text,
  attendance_basis  text not null default 'UNIVERSITY',
  joined_on         date,
  -- sync bookkeeping (server/trigger-controlled; see header)
  client_updated_at timestamptz not null,
  revision          bigint not null default nextval('attendance.change_seq'),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  constraint acc_term_order check (term_end >= term_start),
  constraint acc_target_bounds check (
    overall_target_bp between 0 and 10000 and default_target_bp between 0 and 10000),
  constraint acc_basis check (attendance_basis in ('UNIVERSITY', 'PERSONAL')),
  constraint acc_personal_needs_date check (
    attendance_basis = 'UNIVERSITY' or joined_on is not null),
  constraint acc_section_not_blank check (section is null or section <> ''),
  constraint acc_batch_not_blank check (batch is null or batch <> ''),
  constraint acc_display_name_length check (
    display_name is null or char_length(display_name) <= 60)
);

comment on table attendance.accounts is
  'Per-account attendance settings (calendar, targets, section, attendance basis). One row per Supabase Auth user.';

-- ============================================================================
-- semesters
-- ============================================================================

create table if not exists attendance.semesters (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  year             integer not null,
  kind             text not null,
  start_date       date not null,
  end_date         date not null,
  archived         boolean not null default false,
  client_updated_at timestamptz not null,
  revision         bigint not null default nextval('attendance.change_seq'),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz,
  constraint sem_kind check (kind in ('ODD', 'EVEN')),
  constraint sem_year_sane check (year between 2000 and 2100),
  constraint sem_dates_order check (end_date >= start_date)
);

comment on table attendance.semesters is
  'Semester per account. Cloud ids are UUIDs assigned by the writing client; the local (year, kind) uniqueness is NOT enforced here — LWW reconciles instead.';

-- ============================================================================
-- courses
-- ============================================================================

create table if not exists attendance.courses (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  -- Mirrors CourseEntity.semesterId: deliberately NOT a foreign key. A
  -- dangling reference is a legitimate transient state locally, and no
  -- cascade may ever travel from a semester to a term of attendance.
  semester_id      uuid,
  name             text not null,
  code             text not null,
  target_bp        integer not null default 7500,
  color_argb       integer not null default 0,
  archived         boolean not null default false,
  client_updated_at timestamptz not null,
  revision         bigint not null default nextval('attendance.change_seq'),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz,
  constraint course_name_not_blank check (name <> ''),
  constraint course_code_not_blank check (code <> ''),
  constraint course_name_length check (char_length(name) <= 120),
  constraint course_code_length check (char_length(code) <= 40),
  constraint course_target_bounds check (target_bp between 0 and 10000)
);

comment on table attendance.courses is
  'Per-account course. semester_id intentionally has no FK (see CourseEntity.semesterId for the reasoning this mirrors). target_bp is the course''s OWN target; the account-level default for new courses lives in attendance.accounts.default_target_bp, and a bulk "apply to all" is just N ordinary row updates.';

-- Composite-key support for the same-account foreign keys in patterns and
-- sessions. (id, user_id) uniqueness is implied by the PK but must exist
-- explicitly as an index before it can be an FK target.
create unique index if not exists courses_id_user
  on attendance.courses (id, user_id);

-- ============================================================================
-- patterns — recurring weekly slots
-- ============================================================================

create table if not exists attendance.patterns (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  course_id        uuid not null,
  day_of_week      smallint not null,
  start_hour       smallint not null,
  units            smallint not null,
  kind             text not null,
  room             text,
  effective_from   date not null,
  effective_to     date,
  client_updated_at timestamptz not null,
  revision         bigint not null default nextval('attendance.change_seq'),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz,
  -- The composite FK guarantees a pattern can only ever reference a course
  -- owned by the same account — RLS keeps rows private, this keeps references
  -- private too.
  foreign key (course_id, user_id) references attendance.courses (id, user_id),
  constraint pat_day_of_week check (day_of_week between 1 and 7),
  constraint pat_start_hour check (start_hour between 9 and 17),
  constraint pat_units check (units between 1 and 9),
  constraint pat_kind check (kind in ('LECTURE', 'PRACTICAL', 'TUTORIAL')),
  constraint pat_room_not_blank check (room is null or room <> ''),
  constraint pat_effective_order check (
    effective_to is null or effective_to >= effective_from),
  constraint pat_fits_grid check (start_hour + units <= 18)
);

comment on table attendance.patterns is
  'Recurring weekly slot. Cloud delete is a tombstone; the local Room cascade-to-course has no cloud analogue (the client pushes tombstones for the orphaned sessions itself).';

-- ============================================================================
-- sessions — dated class occurrences
-- ============================================================================

create table if not exists attendance.sessions (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  course_id        uuid not null,
  -- Plain uuid, deliberately NOT a foreign key: a session legitimately
  -- outlives its pattern (retired patterns), mirroring SessionEntity.patternId.
  pattern_id       uuid,
  date             date not null,
  start_hour       smallint not null,
  units_planned    smallint not null,
  units_mask_bits  integer not null default 0,
  status           text not null,
  cancellation_reason text,
  kind             text not null,
  room             text,
  note             text,
  approved_at      timestamptz,
  last_edited_at   timestamptz,
  moved_to_session_id   uuid,
  moved_from_session_id uuid,
  client_updated_at timestamptz not null,
  revision         bigint not null default nextval('attendance.change_seq'),
  updated_at       timestamptz not null default now(),
  deleted_at       timestamptz,
  foreign key (course_id, user_id) references attendance.courses (id, user_id),
  constraint sess_start_hour check (start_hour between 9 and 17),
  constraint sess_units_planned check (units_planned between 1 and 9),
  constraint sess_units_mask check (units_mask_bits >= 0),
  constraint sess_status check (status in ('SCHEDULED', 'HELD', 'CANCELLED')),
  constraint sess_cancellation_reason check (
    cancellation_reason is null
    or cancellation_reason in ('FACULTY_CANCELLED', 'HOLIDAY', 'RESCHEDULED', 'OTHER')),
  constraint sess_kind check (kind in ('LECTURE', 'PRACTICAL', 'TUTORIAL')),
  constraint sess_room_not_blank check (room is null or room <> ''),
  constraint sess_note_length check (note is null or char_length(note) <= 500)
);

comment on table attendance.sessions is
  'Dated class occurrence. Clients MUST use a deterministic id (e.g. UUIDv5 of pattern_id + date) for pattern-generated sessions so two devices generate the same row; the unique index below is the backstop.';

-- The cloud half of the generator idempotency rule: one live row per
-- (pattern, date). Ad-hoc rows (pattern_id null) and tombstoned rows are
-- exempt, exactly like the local unique index. COALESCE makes NULL pattern
-- refs not collide (bare NULLs are distinct anyway; the wrapper documents
-- intent and keeps the index valid if that default ever changes).
create unique index if not exists sessions_pattern_date_live
  on attendance.sessions (
    user_id,
    coalesce(pattern_id, '00000000-0000-0000-0000-000000000000'::uuid),
    date)
  where deleted_at is null;

comment on index attendance.sessions_pattern_date_live is
  'One live session per (account, pattern, date) — the cloud mirror of the Room generator dedup key.';

-- ============================================================================
-- Last-write-wins + revision stamping — shared trigger functions
-- ============================================================================

create or replace function attendance.lww_touch()
returns trigger
language plpgsql
as $$
begin
  -- Last-write-wins on the writing device's clock. NOT strictly-newer
  -- (<=) means: replay of an already-applied write, or a write from a
  -- device that is behind — either way the stored row wins and the UPDATE
  -- becomes a no-op. This is what makes re-pushing a batch idempotent.
  if NEW.client_updated_at <= OLD.client_updated_at then
    return OLD;
  end if;

  -- Ownership can never change hands.
  NEW.user_id := OLD.user_id;

  -- Server-controlled bookkeeping.
  NEW.revision := nextval('attendance.change_seq');
  NEW.updated_at := now();
  return NEW;
end;
$$;

drop trigger if exists accounts_lww on attendance.accounts;
create trigger accounts_lww before update on attendance.accounts
  for each row execute function attendance.lww_touch();

drop trigger if exists semesters_lww on attendance.semesters;
create trigger semesters_lww before update on attendance.semesters
  for each row execute function attendance.lww_touch();

drop trigger if exists courses_lww on attendance.courses;
create trigger courses_lww before update on attendance.courses
  for each row execute function attendance.lww_touch();

drop trigger if exists patterns_lww on attendance.patterns;
create trigger patterns_lww before update on attendance.patterns
  for each row execute function attendance.lww_touch();

drop trigger if exists sessions_lww on attendance.sessions;
create trigger sessions_lww before update on attendance.sessions
  for each row execute function attendance.lww_touch();

-- Insert stamping: revision and updated_at are ALWAYS server-assigned on
-- INSERT, even if a non-compliant client sends its own values (a forged
-- revision would be invisible to other devices' pull cursors forever).
-- client_updated_at stays client-supplied — the table's NOT NULL enforces
-- its presence and nothing here rewrites it.
create or replace function attendance.stamp_insert()
returns trigger
language plpgsql
as $$
begin
  NEW.revision := nextval('attendance.change_seq');
  NEW.updated_at := now();
  return NEW;
end;
$$;

drop trigger if exists accounts_insert_stamp on attendance.accounts;
create trigger accounts_insert_stamp before insert on attendance.accounts
  for each row execute function attendance.stamp_insert();

drop trigger if exists semesters_insert_stamp on attendance.semesters;
create trigger semesters_insert_stamp before insert on attendance.semesters
  for each row execute function attendance.stamp_insert();

drop trigger if exists courses_insert_stamp on attendance.courses;
create trigger courses_insert_stamp before insert on attendance.courses
  for each row execute function attendance.stamp_insert();

drop trigger if exists patterns_insert_stamp on attendance.patterns;
create trigger patterns_insert_stamp before insert on attendance.patterns
  for each row execute function attendance.stamp_insert();

drop trigger if exists sessions_insert_stamp on attendance.sessions;
create trigger sessions_insert_stamp before insert on attendance.sessions
  for each row execute function attendance.stamp_insert();

-- ============================================================================
-- Row Level Security — private per account, no cross-account access, no DELETE
-- ============================================================================

alter table attendance.accounts  enable row level security;
alter table attendance.semesters enable row level security;
alter table attendance.courses   enable row level security;
alter table attendance.patterns  enable row level security;
alter table attendance.sessions  enable row level security;

-- accounts
drop policy if exists accounts_select on attendance.accounts;
create policy accounts_select on attendance.accounts
  for select to authenticated using (user_id = auth.uid());

drop policy if exists accounts_insert on attendance.accounts;
create policy accounts_insert on attendance.accounts
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists accounts_update on attendance.accounts;
create policy accounts_update on attendance.accounts
  for update to authenticated using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- semesters
drop policy if exists semesters_select on attendance.semesters;
create policy semesters_select on attendance.semesters
  for select to authenticated using (user_id = auth.uid());

drop policy if exists semesters_insert on attendance.semesters;
create policy semesters_insert on attendance.semesters
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists semesters_update on attendance.semesters;
create policy semesters_update on attendance.semesters
  for update to authenticated using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- courses
drop policy if exists courses_select on attendance.courses;
create policy courses_select on attendance.courses
  for select to authenticated using (user_id = auth.uid());

drop policy if exists courses_insert on attendance.courses;
create policy courses_insert on attendance.courses
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists courses_update on attendance.courses;
create policy courses_update on attendance.courses
  for update to authenticated using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- patterns
drop policy if exists patterns_select on attendance.patterns;
create policy patterns_select on attendance.patterns
  for select to authenticated using (user_id = auth.uid());

drop policy if exists patterns_insert on attendance.patterns;
create policy patterns_insert on attendance.patterns
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists patterns_update on attendance.patterns;
create policy patterns_update on attendance.patterns
  for update to authenticated using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- sessions
drop policy if exists sessions_select on attendance.sessions;
create policy sessions_select on attendance.sessions
  for select to authenticated using (user_id = auth.uid());

drop policy if exists sessions_insert on attendance.sessions;
create policy sessions_insert on attendance.sessions
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists sessions_update on attendance.sessions;
create policy sessions_update on attendance.sessions
  for update to authenticated using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- Deliberately NO delete policy on any table, for any role: deletion is a
-- tombstone (an UPDATE of deleted_at). Hard deletes happen only through the
-- auth.users on-delete cascade when the account itself is deleted.

-- ============================================================================
-- Delta-pull indexes: per-account rows ordered by cursor position
-- ============================================================================

create index if not exists semesters_pull on attendance.semesters (user_id, revision);
create index if not exists courses_pull   on attendance.courses   (user_id, revision);
create index if not exists patterns_pull  on attendance.patterns  (user_id, revision);
create index if not exists sessions_pull  on attendance.sessions  (user_id, revision);

-- ============================================================================
-- Privileges — authenticated can read and upsert its own rows (RLS decides
-- "own"); anon gets nothing; DELETE is granted to no client role.
--
-- NOTE FOR THE OPERATOR: this schema must also be added to the project's
-- exposed schemas (Supabase dashboard → Settings → API → "Exposed schemas",
-- add `attendance`) before PostgREST will serve it. Applying this migration
-- without that step is harmless — the tables exist, they are simply not
-- REST-reachable until the setting is updated.
-- ============================================================================

revoke all on all tables in schema attendance from anon, authenticated;
revoke all on all functions in schema attendance from public, anon, authenticated;

grant usage on schema attendance to authenticated;
grant select, insert, update
  on attendance.accounts, attendance.semesters, attendance.courses,
     attendance.patterns, attendance.sessions
  to authenticated;

-- Column defaults and the lww_touch trigger evaluate nextval() with the
-- calling role's privileges, so the inserting role needs sequence usage.
-- (Burning sequence numbers is harmless; values carry no information.)
grant usage on sequence attendance.change_seq to authenticated;

-- lww_touch is a trigger function, invocable only as a trigger; the revoke
-- above already stripped direct EXECUTE from every client role, per house
-- convention (firing a trigger does not re-check the modifier's privileges).
