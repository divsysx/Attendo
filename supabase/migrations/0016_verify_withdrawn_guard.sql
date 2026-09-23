-- 0016_verify_withdrawn_guard.sql
-- Restores a guard migration 0011 accidentally dropped.
--
-- 0011 replaced verify to add the activity pulse, but rebuilt it from 0003's
-- definition — and 0009 had since added 'withdrawn' to the terminal-status
-- guard. The withdrawn guard was lost in the rebase, so a withdrawn (or
-- freshly undone) observation was verifiable again: a stranger's verdict
-- could land on a report its owner had already retracted, moving counts and
-- trust for content nobody could see. The pgTAP suite caught it ("verify and
-- cast_vote refuse withdrawn items" went NULL) when migration 0015's tests
-- were first run.
--
-- This is 0011's definition with exactly one line restored: 'withdrawn'
-- rejoins 'rejected' and 'expired' in the terminal-status check. Production
-- has the bug (0011 was applied there), so this migration matters as much
-- remotely as locally.

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

  -- Withdrawn joins expired/rejected as terminal (0009's guard, restored).
  if obs.expires_at <= now()
     or obs.status in ('rejected', 'expired', 'withdrawn') then
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
