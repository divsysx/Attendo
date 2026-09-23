-- Attendo Community System — expiry sweeps and retention.
--
-- Correctness never depends on this job: every read path (RLS policies and
-- the active_observations view) already filters expires_at > now(), so an
-- un-swept row is invisible, not wrong. pg_cron here is hygiene: status
-- hygiene, poll closure, and storage reclamation.
--
-- Retention policy (documented, tested in supabase/tests):
--   * expired observations: hard-deleted after 7 days
--   * abuse_events: deleted after 30 days
--   * reporter_profiles inactive for 180 days: deleted
--   * cron.job_run_details: trimmed (not auto-cleaned by pg_cron itself)

create extension if not exists pg_cron;

create or replace function community.sweep_expired()
returns integer
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  changed integer := 0;
begin
  -- Mark expired observations (visible read paths already exclude these).
  update community.observations
  set status = 'expired', resolved_at = now()
  where expires_at <= now()
    and status in ('reported', 'corroborated', 'disputed', 'confirmed');
  changed := changed + 1;  -- coarse: job success only, not a row count

  -- Close due polls.
  update community.polls
  set status = 'closed', closed_at = now()
  where status = 'open' and closes_at <= now();

  -- Mark polls expired once fully past retention of their close.
  update community.polls
  set status = 'expired'
  where status = 'closed' and closed_at < now() - interval '7 days';

  return changed;
end;
$$;

create or replace function community.retain_and_delete()
returns void
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  -- Hard-delete observations that expired more than 7 days ago. Cascades:
  -- verifications.
  delete from community.observations
  where status = 'expired'
    and resolved_at < now() - interval '7 days';

  -- Expired polls and their options/votes (cascade).
  delete from community.polls
  where status = 'expired';

  -- Abuse events: 30-day window is enough for every rate limit (max 1 day)
  -- and for abuse investigation.
  delete from community.abuse_events
  where created_at < now() - interval '30 days';

  -- Dormant reporter profiles: no activity in 180 days (no reports, votes,
  -- verifications, polls).
  delete from community.reporter_profiles p
  where p.created_at < now() - interval '180 days'
    and not exists (select 1 from community.observations o
                    where o.reporter_id = p.user_id)
    and not exists (select 1 from community.polls pl
                    where pl.creator_id = p.user_id)
    and not exists (select 1 from community.poll_votes v
                    where v.user_id = p.user_id)
    and not exists (select 1 from community.verifications v
                    where v.user_id = p.user_id);

  -- pg_cron's own run history (documented as never auto-cleaned).
  delete from cron.job_run_details
  where end_time < now() - interval '7 days';
end;
$$;

revoke execute on function community.sweep_expired() from public, anon, authenticated;
revoke execute on function community.retain_and_delete() from public, anon, authenticated;

-- Schedule: sweep every 10 minutes, retention daily at a low-traffic hour.
-- (Off-round minute chosen deliberately; the college timezone is IST but
-- low-traffic at ~03:37 UTC (09:07 IST) is acceptable for a hygiene job.)

select cron.schedule(
  'community-sweep-expired',
  '*/10 * * * *',
  $$ select community.sweep_expired(); $$
);

select cron.schedule(
  'community-retain-and-delete',
  '37 3 * * *',
  $$ select community.retain_and_delete(); $$
);

insert into community.app_meta (key, value) values
  ('retention_policy', '{
    "expired_observations_delete_after_days": 7,
    "abuse_events_delete_after_days": 30,
    "dormant_profiles_delete_after_days": 180,
    "sweep_cron": "*/10 * * * *",
    "retention_cron": "37 3 * * *"
  }'::jsonb)
on conflict (key) do update set value = excluded.value;
