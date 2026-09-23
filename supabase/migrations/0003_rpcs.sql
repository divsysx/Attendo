-- Attendo Community System — the write path: SECURITY DEFINER RPCs.
--
-- Every client write (report / verify / poll / vote) goes through these
-- functions. They run as the migration owner with a pinned search_path, so
-- RLS on community tables is bypassed *for them only*, and each function
-- enforces: input validation, server timestamps, rate limits, dedup /
-- idempotency, restriction checks, and deterministic reputation updates —
-- all inside one transaction.
--
-- Design rules:
--   * `set search_path = community, public, extensions` — pinned, no
--     unqualified object resolution outside these schemas.
--   * auth.uid() identifies the caller; null (anon role) is rejected.
--   * All timestamps come from now(); client clock is never trusted.
--   * Client-supplied dates are clamped to server today ± 1 day.
--   * Counters are never client-supplied; status transitions are computed
--     here, monotonically.
--   * Return values are jsonb: { ok, ... } or { ok: false, code, message }.
--     PostgREST surfaces these as a 200 with a JSON body (v1 contract; a
--     future migration may raise exceptions for HTTP codes — additive change).
-- ============================================================================

create or replace function community.require_profile()
returns community.reporter_profiles
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
  profile community.reporter_profiles;
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '28000';
  end if;

  insert into community.reporter_profiles (user_id)
  values (uid)
  on conflict (user_id) do update set user_id = excluded.user_id
  returning * into profile;
  return profile;
end;
$$;

revoke execute on function community.require_profile() from public, anon;
-- (authenticated gets execute in the grant block at the end; internal use only
-- is fine, but granting it costs nothing and eases testing.)

-- ============================================================================
-- Rate limiting: sliding window over abuse_events.
-- ============================================================================

create or replace function community.check_rate_limit(
  p_action text,
  p_max_count integer,
  p_window interval
)
returns boolean
language sql
security definer
set search_path = community, public, extensions
stable
as $$
  select (
    select count(*) from community.abuse_events e
    where e.user_id = auth.uid()
      and e.action = p_action
      and e.created_at > now() - p_window
  ) < p_max_count;
$$;

create or replace function community.record_abuse_event(p_action text)
returns void
language sql
security definer
set search_path = community, public, extensions
as $$
  insert into community.abuse_events (user_id, action) values (auth.uid(), p_action);
$$;

revoke execute on function community.check_rate_limit(text, integer, interval) from public, anon;
revoke execute on function community.record_abuse_event(text) from public, anon;

-- ============================================================================
-- Expiry windows per kind (mirrored in :core CommunityRules.kt; unit-tested
-- on both sides). Server-time only.
-- ============================================================================

create or replace function community.expiry_for(
  p_kind community.report_kind,
  p_class_date date,
  p_start_hour smallint
)
returns timestamptz
language plpgsql
security definer
set search_path = community, public, extensions
immutable
as $$
declare
  base timestamptz;
begin
  -- Class-bound observations expire at end of the class date + 2h.
  if p_class_date is not null then
    base := make_timestamptz(extract(year from p_class_date)::int,
                             extract(month from p_class_date)::int,
                             extract(day from p_class_date)::int,
                             0, 0, 0, 'UTC');
    -- Teaching day ends 18:00 UTC-local grid; +2h grace after the day.
    return base + interval '20 hours';
  end if;
  -- Room-state observations with no date: 3h lifetime.
  return now() + interval '3 hours';
end;
$$;

revoke execute on function community.expiry_for(community.report_kind, date, smallint) from public, anon, authenticated;

-- ============================================================================
-- Payload validation per kind. Returns an error message or null when valid.
-- ============================================================================

create or replace function community.validate_payload(
  p_kind community.report_kind,
  p_payload jsonb,
  p_room text,
  p_class_date date,
  p_start_hour smallint
)
returns text
language plpgsql
security definer
set search_path = community, public, extensions
immutable
as $$
declare
  new_room text;
  new_hour smallint;
begin
  -- No unexpected top-level keys beyond the known vocabulary.
  if exists (
    select 1
    from jsonb_object_keys(p_payload) k
    where k not in ('new_room', 'new_start_hour', 'new_date', 'subject', 'section')
  ) then
    return 'payload contains unknown fields';
  end if;

  new_room := nullif(p_payload->>'new_room', '');
  -- start_hour: validate as text first so a non-numeric value (e.g. "abc")
  -- returns a friendly code instead of an unhandled cast exception.
  if p_payload ? 'new_start_hour' then
    if p_payload->>'new_start_hour' !~ '^[0-9]{1,2}$' then
      return 'new_start_hour must be an integer between 9 and 17';
    end if;
    new_hour := (p_payload->>'new_start_hour')::smallint;
    if new_hour not between 9 and 17 then
      return 'new_start_hour must be between 9 and 17';
    end if;
  end if;

  case p_kind
    when 'class_room_changed', 'class_moved', 'class_moved_here', 'room_other' then
      -- class_room_changed requires a destination room.
      if p_kind = 'class_room_changed' and (new_room is null or char_length(new_room) > 60) then
        return 'new_room is required for class_room_changed and must be at most 60 characters';
      end if;
      if new_room is not null and char_length(new_room) > 60 then
        return 'new_room must be at most 60 characters';
      end if;
    when 'class_time_changed' then
      if not p_payload ? 'new_start_hour' then
        return 'new_start_hour is required for class_time_changed';
      end if;
    when 'class_moved' then
      null; -- new_date/new_start_hour/new_room optional combinations
    else
      null;
  end case;

  -- Room kinds need a room context; class kinds need date+hour context.
  if p_kind in ('room_occupied_despite_free', 'room_free_despite_busy',
                'class_moved_here', 'extra_class_in_room', 'room_other')
     and p_room is null then
    return 'room context is required for room observations';
  end if;

  if p_kind in ('class_moved', 'extra_class', 'class_cancelled', 'class_room_changed',
                'class_time_changed', 'class_missing_from_timetable', 'class_other')
     and (p_class_date is null or p_start_hour is null) then
    return 'class date and start hour are required for class observations';
  end if;

  return null;
end;
$$;

revoke execute on function community.validate_payload(community.report_kind, jsonb, text, date, smallint) from public, anon, authenticated;

-- ============================================================================
-- submit_report — the frictionless report entry point.
-- ============================================================================

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
  p_client_version_code bigint default 0
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  kind community.report_kind;
  room text;
  section text;
  subject text;
  class_date date;
  payload jsonb;
  note text;
  validation_error text;
  observation_id uuid;
  expires_at timestamptz;
  server_today date := current_date;
begin
  profile := community.require_profile();

  -- Parse and normalise the kind.
  begin
    kind := p_kind::community.report_kind;
  exception when invalid_text_representation then
    return jsonb_build_object('ok', false, 'code', 'invalid_kind');
  end;

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
    expires_at := community.expiry_for(kind, class_date, p_start_hour);
    insert into community.observations (
      idempotency_key, reporter_id, kind, room, section, subject,
      class_date, start_hour, payload, note, expires_at
    ) values (
      p_idempotency_key, profile.user_id, kind, room, section, subject,
      class_date, p_start_hour, payload, note, expires_at
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

-- ============================================================================
-- Status transition + reputation: applied after a verification lands.
-- Deterministic, auditable: thresholds live here as constants, mirrored in
-- :core CommunityRules.kt and pgTAP-tested.
-- ============================================================================

create or replace function community.recompute_observation_status(
  p_observation_id uuid
)
returns void
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  obs community.observations;
  positives integer;
  negatives integer;
  distinct_positive_reporters integer;
  trusted_positives integer;  -- verifier trust >= 25
  new_status community.observation_status;
begin
  select * into obs from community.observations o where o.id = p_observation_id;
  if obs.id is null then
    return;
  end if;

  select
    count(*) filter (where v.verdict = true),
    count(*) filter (where v.verdict = false),
    count(distinct v.user_id) filter (where v.verdict = true)
  into positives, negatives, distinct_positive_reporters
  from community.verifications v
  where v.observation_id = p_observation_id;

  select count(*)
  into trusted_positives
  from community.verifications v
  join community.reporter_profiles p on p.user_id = v.user_id
  where v.observation_id = p_observation_id
    and v.verdict = true
    and p.trust_score >= 25;

  -- Monotonic: once confirmed, only disputed/rejected can move it; once
  -- rejected/expired, terminal.
  if obs.status in ('rejected', 'expired') then
    return;
  end if;

  new_status := obs.status;
  if negatives > positives + 5 then
    new_status := 'rejected';
  elsif negatives > positives + 2 then
    new_status := 'disputed';
  elsif obs.status = 'reported' and distinct_positive_reporters >= 2 then
    new_status := 'corroborated';
  elsif obs.status = 'corroborated' and trusted_positives >= 3 then
    new_status := 'confirmed';
  end if;

  if new_status = obs.status then
    return;
  end if;

  update community.observations
  set status = new_status, resolved_at = case
    when new_status in ('rejected', 'expired') then now()
    else resolved_at
  end
  where id = p_observation_id;

  -- Deterministic reputation deltas, once per transition:
  --   corroborated +2, confirmed +3, disputed -2, rejected -5
  -- (to the reporter; clamped to bounds by the check constraint's implicit
  --  failure — we clamp explicitly to keep the transaction safe.)
  if new_status in ('corroborated', 'confirmed', 'disputed', 'rejected') then
    update community.reporter_profiles
    set trust_score = greatest(-100, least(100,
        trust_score + case new_status
          when 'corroborated' then 2
          when 'confirmed' then 3
          when 'disputed' then -2
          when 'rejected' then -5
        end)),
        corroborated_count = corroborated_count +
          case when new_status in ('corroborated', 'confirmed') then 1 else 0 end,
        disputed_count = disputed_count +
          case when new_status in ('disputed', 'rejected') then 1 else 0 end
    where user_id = obs.reporter_id;

    -- Automatic restriction: trust collapsed or a burst of disputes.
    update community.reporter_profiles
    set restricted_until = now() + interval '7 days'
    where user_id = obs.reporter_id
      and (trust_score <= -50 or disputed_count >= 5);
  end if;
end;
$$;

revoke execute on function community.recompute_observation_status(uuid) from public, anon, authenticated;

-- ============================================================================
-- verify — "Is this accurate? Yes / No".
-- ============================================================================

create or replace function community.verify(
  p_observation_id uuid,
  p_verdict boolean,
  p_idempotency_key uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  obs community.observations;
begin
  profile := community.require_profile();

  select * into obs from community.observations o where o.id = p_observation_id;
  if obs.id is null then
    return jsonb_build_object('ok', false, 'code', 'not_found');
  end if;

  -- Expired or terminal observations cannot be verified.
  if obs.expires_at <= now() or obs.status in ('rejected', 'expired') then
    return jsonb_build_object('ok', false, 'code', 'expired');
  end if;

  -- Cannot verify your own report.
  if obs.reporter_id = profile.user_id then
    return jsonb_build_object('ok', false, 'code', 'own_report');
  end if;

  -- Rate limit: 60 verifications/hour.
  if not community.check_rate_limit('verify', 60, interval '1 hour') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_short');
  end if;

  -- Duplicate: PK on (observation_id, user_id). Concurrent duplicates resolve
  -- to exactly one row; the loser gets a friendly 'already_verified'.
  begin
    insert into community.verifications (observation_id, user_id, verdict)
    values (p_observation_id, profile.user_id, p_verdict);
  exception
    when unique_violation then
      return jsonb_build_object('ok', true, 'code', 'already_verified');
  end;

  perform community.record_abuse_event('verify');
  perform community.recompute_observation_status(p_observation_id);

  return jsonb_build_object('ok', true, 'server_now', now());
end;
$$;

-- ============================================================================
-- create_poll — contextual polls only.
-- ============================================================================

create or replace function community.create_poll(
  p_room text,
  p_section text,
  p_subject text,
  p_class_date date,
  p_start_hour smallint,
  p_question text,
  p_options jsonb,          -- array of 2..4 strings, each 1..40 chars
  p_idempotency_key uuid,
  p_client_version_code bigint default 0
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  room text;
  section text;
  subject text;
  question text;
  options text[];
  option_count integer;
  i integer;
  poll_id uuid;
  server_today date := current_date;
  closes_at timestamptz;
begin
  profile := community.require_profile();

  if profile.restricted_until is not null and profile.restricted_until > now() then
    return jsonb_build_object('ok', false, 'code', 'restricted');
  end if;

  room := nullif(btrim(p_room), '');
  section := nullif(btrim(p_section), '');
  subject := nullif(btrim(p_subject), '');
  question := nullif(btrim(p_question), '');

  if question is null or char_length(question) not between 3 and 160 then
    return jsonb_build_object('ok', false, 'code', 'invalid_question');
  end if;

  if room is null and (p_class_date is null or p_start_hour is null) then
    return jsonb_build_object('ok', false, 'code', 'context_required');
  end if;

  if p_start_hour is not null and p_start_hour not between 9 and 17 then
    return jsonb_build_object('ok', false, 'code', 'invalid_start_hour');
  end if;

  if p_class_date is not null and p_class_date not between
      server_today - 1 and server_today + 1 then
    return jsonb_build_object('ok', false, 'code', 'date_out_of_range');
  end if;

  if p_room is not null and char_length(room) > 60 then
    return jsonb_build_object('ok', false, 'code', 'room_too_long');
  end if;

  -- Options: must be a JSON array of 2..4 strings.
  if jsonb_typeof(p_options) <> 'array' then
    return jsonb_build_object('ok', false, 'code', 'invalid_options');
  end if;
  option_count := jsonb_array_length(p_options);
  if option_count not between 2 and 4 then
    return jsonb_build_object('ok', false, 'code', 'invalid_options');
  end if;

  options := array[]::text[];
  for i in 0..option_count - 1 loop
    declare
      label text := nullif(btrim(p_options->>i), '');
    begin
      if label is null or char_length(label) not between 1 and 40 then
        return jsonb_build_object('ok', false, 'code', 'invalid_option_label');
      end if;
      if label = any(options) then
        return jsonb_build_object('ok', false, 'code', 'invalid_option_label');
      end if;
      options := options || label;
    end;
  end loop;

  -- Idempotency.
  select id into poll_id from community.polls
  where idempotency_key = p_idempotency_key and creator_id = profile.user_id;
  if poll_id is not null then
    return jsonb_build_object('ok', true, 'id', poll_id, 'duplicate', true);
  end if;

  -- Rate limit: 5 polls/day.
  if not community.check_rate_limit('poll_create', 5, interval '1 day') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_day');
  end if;

  -- closes_at: slot end + 30 min by default, hard cap 24h.
  if p_class_date is not null then
    closes_at := make_timestamptz(extract(year from p_class_date)::int,
                                  extract(month from p_class_date)::int,
                                  extract(day from p_class_date)::int,
                                  0, 0, 0, 'UTC') + interval '18 hours 30 minutes';
  else
    closes_at := now() + interval '3 hours';
  end if;
  if closes_at > now() + interval '24 hours' then
    closes_at := now() + interval '24 hours';
  end if;
  if closes_at <= now() then
    closes_at := now() + interval '30 minutes';
  end if;

  begin
    insert into community.polls (
      idempotency_key, creator_id, room, section, subject,
      class_date, start_hour, question, closes_at
    ) values (
      p_idempotency_key, profile.user_id, room, section, subject,
      p_class_date, p_start_hour, question, closes_at
    )
    returning id into poll_id;

    for i in 1..option_count loop
      insert into community.poll_options (poll_id, option_index, label)
      values (poll_id, i - 1, options[i]);
    end loop;
  exception
    when unique_violation then
      select id into poll_id from community.polls
      where idempotency_key = p_idempotency_key and creator_id = profile.user_id;
      if poll_id is not null then
        return jsonb_build_object('ok', true, 'id', poll_id, 'duplicate', true);
      end if;
      return jsonb_build_object('ok', false, 'code', 'duplicate_poll');
  end;

  perform community.record_abuse_event('poll_create');

  return jsonb_build_object('ok', true, 'id', poll_id, 'closes_at', closes_at,
                            'server_now', now());
end;
$$;

-- ============================================================================
-- cast_vote — one vote per user per poll, structurally.
-- ============================================================================

create or replace function community.cast_vote(
  p_poll_id uuid,
  p_option_index smallint
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  poll community.polls;
begin
  profile := community.require_profile();

  select * into poll from community.polls p where p.id = p_poll_id;
  if poll.id is null then
    return jsonb_build_object('ok', false, 'code', 'not_found');
  end if;

  -- Server clock decides openness, never the client's.
  if poll.status <> 'open' or poll.closes_at <= now() then
    perform community.close_poll_if_due(p_poll_id);
    return jsonb_build_object('ok', false, 'code', 'poll_closed');
  end if;

  if p_option_index is null or not exists (
    select 1 from community.poll_options
    where poll_id = p_poll_id and option_index = p_option_index) then
    return jsonb_build_object('ok', false, 'code', 'invalid_option');
  end if;

  if not community.check_rate_limit('vote', 60, interval '1 hour') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_short');
  end if;

  begin
    insert into community.poll_votes (poll_id, user_id, option_index)
    values (p_poll_id, profile.user_id, p_option_index);
  exception
    when unique_violation then
      return jsonb_build_object('ok', true, 'code', 'already_voted');
  end;

  perform community.record_abuse_event('vote');

  return jsonb_build_object('ok', true, 'server_now', now());
end;
$$;

-- ============================================================================
-- poll_results — counts only; never voter rows or identities.
-- ============================================================================

create or replace function community.poll_results(p_poll_id uuid)
returns jsonb
language sql
security definer
set search_path = community, public, extensions
stable
as $$
  select jsonb_build_object(
    'ok', true,
    'poll_id', p_poll_id,
    'status', p.status,
    'closes_at', p.closes_at,
    'options', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'option_index', o.option_index,
        'label', o.label,
        'count', (
          select count(*) from community.poll_votes v
          where v.poll_id = p_poll_id and v.option_index = o.option_index
        )
      ) order by o.option_index), '[]'::jsonb)
      from community.poll_options o
      where o.poll_id = p_poll_id
    ),
    'total_votes', (
      select count(*) from community.poll_votes v where v.poll_id = p_poll_id
    )
  )
  from community.polls p
  where p.id = p_poll_id;
$$;

-- ============================================================================
-- close_poll_if_due — used by cast_vote and the cron sweep.
-- ============================================================================

create or replace function community.close_poll_if_due(p_poll_id uuid)
returns void
language sql
security definer
set search_path = community, public, extensions
as $$
  update community.polls
  set status = 'closed', closed_at = now()
  where id = p_poll_id and status = 'open' and closes_at <= now();
$$;

-- ============================================================================
-- my_reputation / my_pending_reports — the reporter's own state.
-- ============================================================================

create or replace function community.my_reputation()
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  result jsonb;
begin
  if auth.uid() is null then
    return jsonb_build_object('ok', false, 'code', 'not_authenticated');
  end if;
  select jsonb_build_object(
      'ok', true,
      'trust_score', p.trust_score,
      'report_count', p.report_count,
      'restricted_until', p.restricted_until,
      'server_now', now()
    )
    into result
    from community.reporter_profiles p
    where p.user_id = auth.uid();
  -- No row yet (no community interaction ever): degrade to defaults, not null.
  return coalesce(result, jsonb_build_object(
    'ok', true,
    'trust_score', 0,
    'report_count', 0,
    'restricted_until', null,
    'server_now', now()
  ));
end;
$$;

-- ============================================================================
-- Grants: the client-facing surface. Only these functions are callable by
-- the authenticated role; everything else is revoked above.
-- ============================================================================

grant execute on function community.submit_report(
  text, text, text, text, date, smallint, jsonb, text, uuid, bigint)
  to authenticated;

grant execute on function community.verify(uuid, boolean, uuid)
  to authenticated;

grant execute on function community.create_poll(
  text, text, text, date, smallint, text, jsonb, uuid, bigint)
  to authenticated;

grant execute on function community.cast_vote(uuid, smallint)
  to authenticated;

grant execute on function community.poll_results(uuid)
  to authenticated;

grant execute on function community.my_reputation()
  to authenticated;

grant execute on function community.require_profile()
  to authenticated;
