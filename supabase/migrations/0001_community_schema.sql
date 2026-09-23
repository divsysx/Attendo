-- Attendo Community System — initial schema.
--
-- The official bundled timetable remains the source of truth. Everything in the
-- `community` schema is an observation/evidence overlay: no table here is ever
-- read back into the app's timetable, attendance, session or room-booking data.
--
-- Scope decisions (locked):
--   * Room-level observations are visible to all relevant Attendo users.
--   * Class/timetable observations are scoped to the relevant course/section.
--   * Contextual polls follow the same room/class scope.
--
-- Security posture (enforced in 0002_rls.sql):
--   * RLS is enabled on every table. Clients (anon / authenticated roles) have
--     NO insert/update/delete table policies at all — every write goes through
--     SECURITY DEFINER RPCs in 0003_rpcs.sql that validate, rate-limit and
--     timestamp server-side.
--   * No service-role or secret key is ever used by the Android client.
--
-- All timestamps are timestamptz with server-side defaults; clients can never
-- set created_at / expires_at / closes_at / status / reporter identity / trust.

-- ============================================================================
-- Schema + enums
-- ============================================================================

create schema if not exists community;

create type community.report_kind as enum (
  -- Room observations (visible to everyone).
  'room_occupied_despite_free',   -- timetable says free, someone sees a class
  'room_free_despite_busy',       -- timetable says busy, room is actually empty
  'class_moved_here',             -- a class relocated into this room
  'extra_class_in_room',          -- unscheduled class happening in this room
  'room_other',
  -- Class/timetable observations (scoped to the relevant section).
  'class_moved',
  'extra_class',
  'class_cancelled',
  'class_room_changed',
  'class_time_changed',
  'class_missing_from_timetable',
  'class_other'
);

create type community.observation_status as enum (
  'reported', 'corroborated', 'confirmed', 'disputed', 'rejected', 'expired'
);

create type community.poll_status as enum ('open', 'closed', 'expired');

-- ============================================================================
-- Reporter identity / trust — server-controlled, never client-writable.
-- ============================================================================

create table community.reporter_profiles (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  created_at         timestamptz not null default now(),
  -- Deterministic, auditable trust score maintained only by the RPC layer.
  -- -100..100. Never rendered to other users; no leaderboard exists.
  trust_score        integer not null default 0,
  report_count       integer not null default 0,
  corroborated_count integer not null default 0,
  disputed_count     integer not null default 0,
  -- Abuse throttle: set by the RPC layer when trust collapses or a burst of
  -- disputed reports lands. Write RPCs refuse work while this is in the future.
  restricted_until   timestamptz,
  constraint reporter_trust_bounds check (trust_score between -100 and 100),
  constraint reporter_counts_nonnegative check (
    report_count >= 0 and corroborated_count >= 0 and disputed_count >= 0)
);

comment on table community.reporter_profiles is
  'Server-controlled reputation for anonymous community reporters. No client write path exists.';

-- ============================================================================
-- Observations — the atom of the community system.
-- ============================================================================

create table community.observations (
  id              uuid primary key default gen_random_uuid(),
  -- Client-generated, stable across offline retries: the INSERT dedup key.
  idempotency_key uuid not null,
  reporter_id     uuid not null
                    references community.reporter_profiles(user_id) on delete cascade,
  kind            community.report_kind not null,
  status          community.observation_status not null default 'reported',

  -- Context. At least a room OR (class_date + start_hour) is required; the
  -- scope of visibility is derived from exactly this context:
  --   room set            -> room-level, visible to everyone
  --   section + date/hour -> class-level, visible to that section's users
  room            text,
  section         text,
  subject         text,
  class_date      date,
  start_hour      smallint,
  -- class start hour on the 24h teaching grid 9..17 (attendo TimeGrid)
  constraint obs_start_hour_range check (start_hour is null or start_hour between 9 and 17),
  -- Payload is validated per-kind inside submit_report(); the CHECK here only
  -- bounds shape (object, sane size) so malformed rows cannot exist at all.
  payload         jsonb not null default '{}'::jsonb,
  constraint obs_payload_is_object check (jsonb_typeof(payload) = 'object'),
  constraint obs_payload_size check (octet_length(payload::text) <= 2048),
  -- Optional, never required. Bounded.
  note            text,
  constraint obs_note_length check (note is null or char_length(note) <= 280),
  -- All timestamps server-set.
  created_at      timestamptz not null default now(),
  -- Dedup hour: server-computed at insert time (see submit_report). Exists so
  -- the unique dedup index can compare hours without a function in the index
  -- expression — date_part('hour', timestamptz) is STABLE, not IMMUTABLE,
  -- because the hour depends on the session timezone; storing the server's
  -- reading at insert time makes the window deterministic.
  dedup_hour      timestamp not null default now(),
  expires_at      timestamptz not null,
  resolved_at     timestamptz,

  -- Context requirements.
  constraint obs_context_required check (
    room is not null or (class_date is not null and start_hour is not null)),
  constraint obs_room_not_blank check (room is null or room <> ''),
  constraint obs_section_not_blank check (section is null or section <> ''),
  constraint obs_subject_not_blank check (subject is null or subject <> ''),
  -- Expiry must be after creation (server computes both; defensive).
  constraint obs_expires_after_created check (expires_at > created_at),

  -- Idempotency: an offline-retried submission lands once.
  constraint obs_idempotency_unique unique (idempotency_key)
);

-- NULLS DISTINCT is Postgres's default: bare class_date/start_hour would make
-- every room-only report (NULL slot) "distinct" and defeat the dedup window.
-- Wrapping the nullable columns in COALESCE makes NULLs compare equal; the
-- sentinels ('1900-01-01', -1) are outside every legal value (hours are 9..17).
create unique index obs_dedup_unique
  on community.observations (
    reporter_id, kind, room,
    coalesce(section, ''),
    coalesce(subject, ''),
    coalesce(class_date, '1900-01-01'::date),
    coalesce(start_hour, -1),
    coalesce(payload->>'new_room', ''),
    date_trunc('hour', dedup_hour));

create index obs_active_by_room
  on community.observations (room, expires_at)
  where status in ('reported', 'corroborated', 'confirmed');

create index obs_active_by_date
  on community.observations (class_date, start_hour)
  where status in ('reported', 'corroborated', 'confirmed');

create index obs_reporter on community.observations (reporter_id);

create index obs_expiry on community.observations (expires_at);

comment on table community.observations is
  'Community observations: evidence about rooms/classes, never authoritative timetable data.';

-- ============================================================================
-- Verifications — one per user per observation.
-- ============================================================================

create table community.verifications (
  observation_id  uuid not null
                    references community.observations(id) on delete cascade,
  user_id         uuid not null
                    references community.reporter_profiles(user_id) on delete cascade,
  -- true = "this is accurate".
  verdict         boolean not null,
  created_at      timestamptz not null default now(),
  primary key (observation_id, user_id)
);

create index verifications_by_user on community.verifications (user_id);

comment on table community.verifications is
  'One yes/no verification per user per observation. PK makes duplicates structurally impossible.';

-- ============================================================================
-- Polls — contextual, short-lived, bound to a room or class context.
-- ============================================================================

create table community.polls (
  id              uuid primary key default gen_random_uuid(),
  idempotency_key uuid not null,
  creator_id      uuid not null
                    references community.reporter_profiles(user_id) on delete cascade,
  -- Context mirrors observations; scope of visibility follows the same rule.
  room            text,
  section         text,
  subject         text,
  class_date      date,
  start_hour      smallint,
  constraint poll_start_hour_range check (start_hour is null or start_hour between 9 and 17),
  question        text not null,
  constraint poll_question_length check (char_length(question) between 3 and 160),
  status          community.poll_status not null default 'open',
  created_at      timestamptz not null default now(),
  -- Server-computed dedup hour, same rationale as observations.dedup_hour.
  dedup_hour      timestamp not null default now(),
  -- Server-set, capped at 24h by the creation RPC.
  closes_at       timestamptz not null,
  closed_at       timestamptz,
  constraint poll_closes_after_created check (closes_at > created_at),
  constraint poll_context_required check (
    room is not null or (class_date is not null and start_hour is not null)),
  constraint poll_room_not_blank check (room is null or room <> ''),
  constraint poll_idempotency_unique unique (idempotency_key)
);

-- Same dedup window shape as observations. Nullable columns are COALESCE-
-- wrapped so NULLs compare equal (see the obs_dedup_unique note above); an
-- inline constraint cannot hold these expressions, hence a separate index.
create unique index poll_dedup_unique
  on community.polls (
    creator_id, room,
    coalesce(section, ''), coalesce(subject, ''),
    coalesce(class_date, '1900-01-01'::date),
    coalesce(start_hour, -1),
    question,
    date_trunc('hour', dedup_hour));

create index polls_open_by_room
  on community.polls (room, closes_at)
  where status = 'open';

create index polls_open_by_date
  on community.polls (class_date, start_hour)
  where status = 'open';

create index polls_creator on community.polls (creator_id);

-- Poll options: fixed at creation, 2..4 options.
create table community.poll_options (
  poll_id       uuid not null references community.polls(id) on delete cascade,
  option_index  smallint not null,
  label         text not null,
  constraint poll_option_index_range check (option_index between 0 and 3),
  constraint poll_option_label_length check (char_length(label) between 1 and 40),
  primary key (poll_id, option_index)
);

-- Poll votes: one per user per poll — the PK is the entire anti-brigading story.
create table community.poll_votes (
  poll_id       uuid not null references community.polls(id) on delete cascade,
  user_id       uuid not null
                  references community.reporter_profiles(user_id) on delete cascade,
  option_index  smallint not null,
  created_at    timestamptz not null default now(),
  primary key (poll_id, user_id)
);

create index poll_votes_tally on community.poll_votes (poll_id, option_index);

-- ============================================================================
-- Abuse state — sliding-window counters. No client SELECT policy exists.
-- ============================================================================

create table community.abuse_events (
  user_id     uuid not null
                references community.reporter_profiles(user_id) on delete cascade,
  action      text not null,
  constraint abuse_action_allowed check (action in
    ('report', 'vote', 'verify', 'poll_create')),
  created_at  timestamptz not null default now()
);

create index abuse_events_window
  on community.abuse_events (user_id, action, created_at);

-- ============================================================================
-- app_meta — additive-only backend version markers for old-client handling.
-- ============================================================================

create table community.app_meta (
  key    text primary key,
  value  jsonb not null
);

insert into community.app_meta (key, value) values
  ('community_schema_version', '1'::jsonb),
  ('min_supported_client_version_code', '1'::jsonb);
