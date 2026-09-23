-- 0013_refile_after_withdrawal.sql
-- The two-device test on 2026-09-10 found the re-file wall: a report that was
-- filed, taken back (Undo) or withdrawn, and then filed *again* was declined
-- with duplicate_report. The dedup window's unique indexes covered every row,
-- including withdrawn ones — so the row a student retracted was still sitting
-- in its dedup slot for the rest of the hour, and the honest "I changed my
-- mind, this actually is true" was refused as a repeat.
--
-- Fix: the dedup indexes become partial — a withdrawn row frees its slot the
-- moment it is withdrawn. Rejected rows keep theirs: a report the community
-- voted down is the one re-file a student should not get for free.
--
-- The rate-limit audit from the same test, in full (so the reasoning is on
-- record next to the numbers):
--
--   * report   10/hour + 60/day  KEEP. The hourly bound stops bursts, the
--     daily bound stops a slow drip that never trips the hourly one; no honest
--     student files sixty reports in a day, so it costs humans nothing.
--   * verify   60/hour           KEEP. The PK makes a second verdict on the
--     same report impossible, so the limit only binds for a client cycling
--     through dozens of different reports — the shape it exists for.
--   * vote     60/hour           KEEP. Same PK logic: one vote per poll.
--   * poll_create 5/day -> 10/day  RAISE. The one limit a human can genuinely
--     hit: polls are contextual (a room, a class hour) and short-lived, and
--     an engaged student with several contexts can pass five in a day without
--     spamming anything. Ten keeps the abuse ceiling low while clearing the
--     honest path.
--
-- Small and auditable: two indexes rebuilt as partial, one CREATE OR REPLACE
-- with a single constant changed (grants survive — CREATE OR REPLACE keeps
-- 0003's grant to authenticated).

-- ---------------------------------------------------------------------------
-- 1. The dedup window stops at withdrawal. Drop-then-create: no IF NOT EXISTS
-- for indexes, and a rerun must not error on the first pass's work.
-- ---------------------------------------------------------------------------
drop index if exists community.obs_dedup_unique;
create unique index obs_dedup_unique
  on community.observations (
    reporter_id, kind, room,
    coalesce(section, ''),
    coalesce(subject, ''),
    coalesce(class_date, '1900-01-01'::date),
    coalesce(start_hour, -1),
    coalesce(payload->>'new_room', ''),
    date_trunc('hour', dedup_hour))
  where status <> 'withdrawn';

drop index if exists community.poll_dedup_unique;
create unique index poll_dedup_unique
  on community.polls (
    creator_id, room,
    coalesce(section, ''), coalesce(subject, ''),
    coalesce(class_date, '1900-01-01'::date),
    coalesce(start_hour, -1),
    question,
    date_trunc('hour', dedup_hour))
  where status <> 'withdrawn';

-- ---------------------------------------------------------------------------
-- 2. create_poll — 0003's definition, the rate-limit constant alone changed.
-- ---------------------------------------------------------------------------
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

  -- Rate limit: 10 polls/day (0013: was 5 — the one limit an engaged student
  -- could honestly hit; see the audit in this file's header).
  if not community.check_rate_limit('poll_create', 10, interval '1 day') then
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
