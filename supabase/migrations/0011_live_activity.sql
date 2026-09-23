-- 0011_live_activity.sql
-- Live tallies: make every community action visible to Realtime.
--
-- The Realtime publication (0005) carries community.observations and
-- community.polls. Reports, withdrawals and status transitions already touch
-- those rows, so they reach subscribers. Two actions did not:
--
--   * cast_vote  — writes only poll_votes, which is private (deliberately not
--                  in the publication, no grants: a vote row must never
--                  travel). A vote therefore fired no event, and every other
--                  student's tally was frozen until their next screen entry.
--   * verify     — writes only verifications (private), and
--                  recompute_observation_status updates the observation only
--                  on a status *transition*. The agree/disagree counts a card
--                  shows moved invisibly.
--
-- Fix: an activity_at pulse column on both public tables, bumped on the
-- success path of cast_vote and verify. The vote row and the verifier's
-- identity still never travel — only the fact that the poll/observation just
-- changed, which is what subscribers need to know to refetch. Idempotent
-- replays (already_voted / already_verified) deliberately do not bump: a
-- replay is not activity.
--
-- Small and auditable: two columns, two CREATE OR REPLACEs (which keep their
-- existing grants — 0009/0010's grant statements still apply), two column
-- grants. RLS policies are unchanged; the column leaks nothing (it is now(),
-- server-set, per row).

-- ---------------------------------------------------------------------------
-- The pulse columns.
-- ---------------------------------------------------------------------------
alter table community.polls
  add column if not exists activity_at timestamptz not null default now();

alter table community.observations
  add column if not exists activity_at timestamptz not null default now();

-- ---------------------------------------------------------------------------
-- cast_vote — 0010's definition plus the activity pulse on a successful vote.
-- ---------------------------------------------------------------------------
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

  -- The own-poll rule (Part 9 / Part 5 parity): the creator cannot vote in
  -- their own poll. Without this the creator seeds the first vote and every
  -- displayed count starts biased — the poll equivalent of verifying your
  -- own report.
  if poll.creator_id = profile.user_id then
    return jsonb_build_object('ok', false, 'code', 'own_poll');
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

  -- The pulse (0011): the vote row is private and stays so; the poll it
  -- belongs to is what subscribers can see, so the tally change travels as
  -- a polls UPDATE and every open screen refetches.
  update community.polls
     set activity_at = now()
   where id = p_poll_id;

  return jsonb_build_object('ok', true, 'server_now', now());
end;
$$;

-- ---------------------------------------------------------------------------
-- verify — 0003's definition plus the activity pulse on a successful verdict.
-- ---------------------------------------------------------------------------
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

  -- The pulse (0011): the verification row is private and stays so; the agree/
  -- disagree counts move on the observation, so the change travels as an
  -- observations UPDATE — whether or not the status itself transitioned.
  update community.observations
     set activity_at = now()
   where id = p_observation_id;

  return jsonb_build_object('ok', true, 'server_now', now());
end;
$$;

-- ---------------------------------------------------------------------------
-- Column grants — 0006's pattern: the new columns join the granted list so
-- Realtime delivery (and any honest read) can see them. Nothing else changes:
-- creator_id / reporter_id / idempotency_key stay revoked.
-- ---------------------------------------------------------------------------
grant select (activity_at) on community.polls to authenticated;
grant select (activity_at) on community.observations to authenticated;
