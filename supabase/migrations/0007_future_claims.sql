-- Attendo Community System — future claims: an expectation about a later slot today.
--
-- Until now every observation has been a report about a moment already happening or
-- already known: room reports mean "I see this now", class reports point at a slot
-- via class_date (yesterday, today or tomorrow). A student who *knows* the 2 PM slot
-- in Room 203 will be occupied — the professor said so, the booking board says so —
-- had no honest way to say it: reporting "occupied" now would be a lie about the
-- present, and a class report needs a class.
--
-- This migration adds the third thing a report can be, without disturbing the first
-- two:
--
--   observation_type = 'present'    what the reporter sees at the moment of filing
--                                    (every room report today; the default, so the
--                                    column is invisible to old clients and old rows)
--   observation_type = 'historical' about a selected recent slot, via class_date
--                                    (existing class reports are reclassified)
--   observation_type = 'future'     an expectation about a LATER SLOT TODAY, with an
--                                    explicit target interval — event_date +
--                                    target_start_hour/target_end_hour — never a
--                                    class_date repurposed to imply futurity
--
-- Future claims are deliberately narrow:
--   * room context only (a claim is "this room will be occupied/free/used at X"),
--   * event_date must be the server's today — tomorrow is out of scope by product
--     decision, and yesterday is impossible,
--   * the target slot must not have started: once 2 PM arrives, "will be occupied
--     at 2" stops being a claim and the student should file a present report instead,
--   * expiry is the target slot's end + 30 minutes: a claim's entire value is the
--     window before and during the slot it names, and it is worthless after.
--
-- A claim is community-reported expectation, never timetable data: nothing in this
-- migration feeds any read path the app's availability math uses, and the client
-- renders claims under an unmistakable "expected later" label (see the view below).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- The type itself
-- ----------------------------------------------------------------------------
create type community.observation_type as enum ('present', 'historical', 'future');

alter table community.observations
  add column observation_type community.observation_type
    not null default 'present';

-- The explicit target interval a future claim carries. Non-future rows must leave
-- all three null — the constraint makes "a future claim without a target" and "a
-- target on a present observation" structurally impossible, rather than merely
-- rejected by the RPC.
alter table community.observations
  add column event_date        date,
  add column target_start_hour smallint,
  add column target_end_hour   smallint;

alter table community.observations
  add constraint obs_target_start_range
    check (target_start_hour is null or target_start_hour between 9 and 17),
  add constraint obs_target_end_range
    check (target_end_hour is null or target_end_hour between 10 and 18),
  add constraint obs_target_interval
    check (target_start_hour is null or target_end_hour = target_start_hour + 1),
  add constraint obs_type_shape check (
    (observation_type = 'future'
       and event_date is not null
       and target_start_hour is not null
       and target_end_hour is not null)
    or
    (observation_type <> 'future'
       and event_date is null
       and target_start_hour is null
       and target_end_hour is null));

-- Existing rows get their honest type: room reports were present observations,
-- class-bound reports were about a slot on a date (historical). No row keeps the
-- default by accident.
update community.observations
  set observation_type = 'historical'
  where class_date is not null;

-- ----------------------------------------------------------------------------
-- Dedup: a claim's target slot is part of its identity
--
-- "Room 203 will be occupied at 11" and "…at 14" are two different claims; without
-- the target hour in the dedup index the second would be refused as a duplicate of
-- the first within the same hour. Same reporter + kind + room + target + hour of
-- filing remains one claim, exactly as before.
-- ----------------------------------------------------------------------------
drop index community.obs_dedup_unique;

create unique index obs_dedup_unique
  on community.observations (
    reporter_id, kind, room,
    coalesce(section, ''),
    coalesce(subject, ''),
    coalesce(class_date, '1900-01-01'::date),
    coalesce(start_hour, -1),
    coalesce(payload->>'new_room', ''),
    coalesce(target_start_hour, -1),
    date_trunc('hour', dedup_hour));

-- ----------------------------------------------------------------------------
-- Claim expiry: the target slot's end + 30 minutes (mirrored in :core
-- CommunityRules.claimExpiresAt; unit-tested on both sides)
-- ----------------------------------------------------------------------------
create or replace function community.claim_expiry(
  p_event_date date,
  p_target_end_hour smallint
)
returns timestamptz
language sql
security definer
set search_path = community, public, extensions
immutable
as $$
  select make_timestamptz(extract(year from p_event_date)::int,
                          extract(month from p_event_date)::int,
                          extract(day from p_event_date)::int,
                          p_target_end_hour, 0, 0, 'UTC')
         + interval '30 minutes';
$$;

revoke execute on function community.claim_expiry(date, smallint) from public, anon, authenticated;

-- ----------------------------------------------------------------------------
-- submit_report: the three new parameters carry defaults, because the PostgREST
-- contract requires a client to send every non-default parameter — an old client
-- sending the old parameter set keeps working unchanged against this migration,
-- and a new client only sends the claim parameters when it is filing a claim.
--
-- The old signature is dropped first: `create or replace` with a different
-- parameter list would OVERLOAD rather than replace, leaving two callable
-- submit_reports and making every 9-argument call ambiguous. Dropping also
-- drops 0003's EXECUTE grant, so it is re-issued below for the new signature.
-- ----------------------------------------------------------------------------
drop function community.submit_report(
  text, text, text, text, date, smallint, jsonb, text, uuid, bigint);

create or replace function community.submit_report(
  p_kind text,
  p_room text,
  p_section text,
  p_subject text,
  p_class_date date,
  p_start_hour smallint,
  p_payload jsonb,
  p_note text,
  p_idempotency_key uuid,
  p_client_version_code bigint default 0,
  -- Null default, not 'present': the derivation below reads "the client said
  -- nothing" as "let the report's own context say what it is", and a default
  -- of 'present' would make that unreadable.
  p_observation_type text default null,
  p_event_date date default null,
  p_target_start_hour smallint default null
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  kind community.report_kind;
  obs_type community.observation_type;
  room text;
  section text;
  subject text;
  class_date date;
  payload jsonb;
  note text;
  validation_error text;
  observation_id uuid;
  expires_at timestamptz;
  event_date date;
  target_start_hour smallint;
  target_end_hour smallint;
  server_today date := current_date;
begin
  profile := community.require_profile();

  -- Parse and normalise the kind.
  begin
    kind := p_kind::community.report_kind;
  exception when invalid_text_representation then
    return jsonb_build_object('ok', false, 'code', 'invalid_kind');
  end;

  -- Parse and normalise the observation type; absent means what the report's
  -- own context says it is. A class-bound report points at a slot via
  -- class_date/start_hour, which is exactly what a historical observation is,
  -- so the type is derived — no client ever states a type except when filing
  -- a claim, and an old client's reports keep landing correctly.
  begin
    obs_type := coalesce(p_observation_type, 'present')::community.observation_type;
  exception when invalid_text_representation then
    return jsonb_build_object('ok', false, 'code', 'invalid_payload',
                              'message', 'unknown observation type');
  end;
  if p_observation_type is null and p_class_date is not null then
    obs_type := 'historical';
  end if;

  -- Normalise free text: trim; blank -> null.
  room := nullif(btrim(p_room), '');
  section := nullif(btrim(p_section), '');
  subject := nullif(btrim(p_subject), '');
  note := nullif(btrim(p_note), '');
  payload := coalesce(p_payload, '{}'::jsonb);
  if jsonb_typeof(payload) <> 'object' then
    return jsonb_build_object('ok', false, 'code', 'invalid_payload');
  end if;

  if note is not null and char_length(note) > 280 then
    return jsonb_build_object('ok', false, 'code', 'note_too_long');
  end if;

  if room is not null and char_length(room) > 60 then
    return jsonb_build_object('ok', false, 'code', 'room_too_long');
  end if;

  -- Clamp client-supplied date to server today +/- 1 day (device clock skew
  -- and modest timezone offsets are tolerated; anything further is refused).
  if p_class_date is not null and p_class_date not between
      server_today - 1 and server_today + 1 then
    return jsonb_build_object('ok', false, 'code', 'date_out_of_range');
  end if;
  class_date := p_class_date;

  -- start_hour: validate before the CHECK constraint trips, so clients get a
  -- friendly code rather than a 500 from an integrity violation.
  if p_start_hour is not null and p_start_hour not between 9 and 17 then
    return jsonb_build_object('ok', false, 'code', 'invalid_start_hour');
  end if;

  validation_error := community.validate_payload(
    kind, payload, room, class_date, p_start_hour);
  if validation_error is not null then
    return jsonb_build_object('ok', false, 'code', 'invalid_payload',
                              'message', validation_error);
  end if;

  -- ---------------------------------------------------------------- future claims
  -- A claim is an expectation about a later slot today: room context, an explicit
  -- event date that must be the server's today, and a target hour whose slot has
  -- not started yet. Anything else is not a claim and is refused with a code the
  -- client can say something useful about.
  event_date := p_event_date;
  target_start_hour := p_target_start_hour;
  target_end_hour := target_start_hour + 1;

  if obs_type = 'future' then
    if kind not in ('room_occupied_despite_free', 'room_free_despite_busy',
                    'class_moved_here', 'extra_class_in_room', 'room_other') then
      return jsonb_build_object('ok', false, 'code', 'invalid_payload',
                                'message', 'future claims are about rooms');
    end if;
    if room is null then
      return jsonb_build_object('ok', false, 'code', 'invalid_payload',
                                'message', 'room context is required for claims');
    end if;
    -- A claim is about a later slot TODAY: tomorrow is out of the product's scope,
    -- and any other date is impossible.
    if event_date is null or event_date <> server_today then
      return jsonb_build_object('ok', false, 'code', 'date_out_of_range');
    end if;
    if target_start_hour is null or target_start_hour not between 9 and 17 then
      return jsonb_build_object('ok', false, 'code', 'invalid_start_hour');
    end if;
    -- The slot must still be ahead: once it starts, "will be occupied at 2" stops
    -- being a claim about the future and the student should report what they see.
    if now() >= make_timestamptz(extract(year from event_date)::int,
                                 extract(month from event_date)::int,
                                 extract(day from event_date)::int,
                                 target_start_hour, 0, 0, 'UTC') then
      return jsonb_build_object('ok', false, 'code', 'invalid_target',
                                'message', 'that slot has already started — report what you see instead');
    end if;
    expires_at := community.claim_expiry(event_date, target_end_hour);
  else
    -- Non-claim reports must not carry claim fields — the column shape check
    -- would trip on the insert anyway; a friendly code beats a 500.
    if event_date is not null or target_start_hour is not null then
      return jsonb_build_object('ok', false, 'code', 'invalid_payload',
                                'message', 'only future claims carry a target slot');
    end if;
    expires_at := community.expiry_for(kind, class_date, p_start_hour);
  end if;

  -- Restriction: abuse throttle.
  if profile.restricted_until is not null and profile.restricted_until > now() then
    return jsonb_build_object('ok', false, 'code', 'restricted');
  end if;

  -- Idempotency: a retried submission returns the original outcome.
  select id into observation_id
  from community.observations
  where idempotency_key = p_idempotency_key and reporter_id = profile.user_id;
  if observation_id is not null then
    return jsonb_build_object('ok', true, 'id', observation_id, 'duplicate', true);
  end if;

  -- Rate limits: 10 reports/hour, 60/day.
  if not community.check_rate_limit('report', 10, interval '1 hour') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_short');
  end if;
  if not community.check_rate_limit('report', 60, interval '1 day') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_day');
  end if;

  -- Duplicate window (unique constraint is the backstop; a friendly error
  -- beats an integrity violation for the client).
  begin
    insert into community.observations (
      idempotency_key, reporter_id, kind, room, section, subject,
      class_date, start_hour, payload, note, expires_at,
      observation_type, event_date, target_start_hour, target_end_hour
    ) values (
      p_idempotency_key, profile.user_id, kind, room, section, subject,
      class_date, p_start_hour, payload, note, expires_at,
      obs_type, event_date, target_start_hour,
      case when obs_type = 'future' then target_end_hour end
    )
    returning id into observation_id;
  exception
    when unique_violation then
      -- Either the idempotency key or the dedup window tripped.
      select id into observation_id
      from community.observations
      where idempotency_key = p_idempotency_key and reporter_id = profile.user_id;
      if observation_id is not null then
        return jsonb_build_object('ok', true, 'id', observation_id, 'duplicate', true);
      end if;
      return jsonb_build_object('ok', false, 'code', 'duplicate_report');
  end;

  -- Counters + abuse event. Failure path: the insert already committed inside
  -- this function's single transaction — profile counters update atomically
  -- with it, and the transaction is all-or-nothing, so no partial state.
  update community.reporter_profiles
  set report_count = report_count + 1
  where user_id = profile.user_id;

  perform community.record_abuse_event('report');

  return jsonb_build_object('ok', true, 'id', observation_id,
                            'expires_at', expires_at,
                            'server_now', now());
end;
$$;

-- The grant 0003 gave the old signature died with the drop; the new one needs
-- its own, and the client-facing surface is otherwise unchanged.
grant execute on function community.submit_report(
  text, text, text, text, date, smallint, jsonb, text, uuid, bigint,
  text, date, smallint)
  to authenticated;

-- ----------------------------------------------------------------------------
-- Read surfaces: the view and the pending-reports RPC carry the claim fields so
-- clients can label claims without inferring anything from dates.
-- ----------------------------------------------------------------------------
-- create or replace view can only APPEND columns, so the claim fields come
-- after the counts; the client decodes by name, never by position.
create or replace view community.active_observations as
  select
    o.id,
    o.kind,
    o.status,
    o.room,
    o.section,
    o.subject,
    o.class_date,
    o.start_hour,
    o.payload,
    o.note,
    o.created_at,
    o.expires_at,
    (select count(*) from community.verifications v
       where v.observation_id = o.id and v.verdict = true) as verification_count,
    (select count(*) from community.verifications v
       where v.observation_id = o.id and v.verdict = false) as dispute_count,
    o.observation_type,
    o.event_date,
    o.target_start_hour,
    o.target_end_hour
  from community.observations o
  where o.status in ('reported', 'corroborated', 'confirmed')
    and o.expires_at > now();

grant select on community.active_observations to authenticated;

create or replace function community.my_pending_reports()
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  if auth.uid() is null then
    return jsonb_build_object('ok', false, 'code', 'not_authenticated');
  end if;

  return jsonb_build_object(
    'ok', true,
    'server_now', now(),
    'reports', coalesce((
      select jsonb_agg(jsonb_build_object(
          'id', o.id,
          'kind', o.kind,
          'status', o.status,
          'room', o.room,
          'section', o.section,
          'subject', o.subject,
          'class_date', o.class_date,
          'start_hour', o.start_hour,
          'note', o.note,
          'created_at', o.created_at,
          'expires_at', o.expires_at,
          'resolved_at', o.resolved_at,
          'observation_type', o.observation_type,
          'event_date', o.event_date,
          'target_start_hour', o.target_start_hour,
          'target_end_hour', o.target_end_hour
        ) order by o.created_at desc)
      from community.observations o
      where o.reporter_id = auth.uid()
        and o.expires_at > now() - interval '7 days'
    ), '[]'::jsonb)
  );
end;
$$;

revoke execute on function community.my_pending_reports() from public, anon;
grant execute on function community.my_pending_reports() to authenticated;

-- ----------------------------------------------------------------------------
-- Column grants: 0006 gave `authenticated` an explicit column list on
-- observations; new columns need their own grant or every Realtime delivery and
-- explicit-column select of them fails (all-or-explicit is the column-privilege
-- rule — an unlisted column is unreadable).
-- ----------------------------------------------------------------------------
grant select (observation_type, event_date, target_start_hour, target_end_hour)
  on community.observations to authenticated;

-- Schema marker: additive-only versioning for old-client handling.
update community.app_meta
  set value = '2'::jsonb
  where key = 'community_schema_version';
