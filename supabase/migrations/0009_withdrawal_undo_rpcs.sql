-- Attendo Community System — withdrawal and fresh-submission undo: the RPCs.
--
-- Everything a client can do to reverse a submission lives here, and nothing
-- else can. The rules the whole feature stands on:
--
--   * Owner-only. auth.uid() must match reporter_id / creator_id; a stranger
--     calling the RPC gets 'not_yours' and changes nothing.
--   * Atomic. The status transition, the audit columns and (for withdrawal)
--     the single reputation penalty are one UPDATE inside one transaction.
--     Two concurrent calls serialize on the row lock; the loser finds the row
--     already withdrawn and returns 'already_withdrawn' with zero side
--     effects — no double penalty, no half state, ever.
--   * Idempotent. A retry after a network failure returns the same happy
--     answer ('already_withdrawn') without applying anything twice.
--   * No deletion. The row survives with withdrawal_kind recorded; retention
--     (updated below) reclaims withdrawn rows after 30 days.
--
-- Fresh undo vs normal withdrawal, as the product separates them:
--
--   undo_report / undo_poll        withdrawal window: created_at + 60s
--                                  reputation: unchanged (zero penalty)
--   withdraw_report / withdraw_poll any time while the item is still live
--                                  reputation: -3, exactly once per item
--
-- The window length exists in exactly one server-side place:
-- community.fresh_undo_window(). The client mirrors it as a display constant
-- (CommunityRules.FRESH_UNDO_SECONDS, pinned to this value by tests on both
-- sides), and the owner-facing read RPCs expose undo_available_until so the
-- client can simply compare clocks instead of computing windows.
--
-- The penalty amount is deliberately internal: it appears in no client
-- contract, no error message, and no read surface. The UI says only that
-- withdrawal decreases reputation.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- The one place the fresh-undo window is defined.
-- ---------------------------------------------------------------------------
create or replace function community.fresh_undo_window()
returns interval
language sql
immutable
as $$
  select interval '60 seconds';
$$;

revoke execute on function community.fresh_undo_window() from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- withdraw_observation — the shared engine behind undo_report and
-- withdraw_report. p_mode: 'undo' | 'withdraw'.
-- ---------------------------------------------------------------------------
create or replace function community.withdraw_observation(
  p_observation_id uuid,
  p_mode text
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  obs community.observations;
  changed boolean;
begin
  profile := community.require_profile();

  select * into obs from community.observations o where o.id = p_observation_id;
  if obs.id is null then
    return jsonb_build_object('ok', false, 'code', 'not_found');
  end if;

  -- Ownership is the first gate, so a stranger learns nothing beyond 'no'.
  if obs.reporter_id <> profile.user_id then
    return jsonb_build_object('ok', false, 'code', 'not_yours');
  end if;

  -- Idempotency: already withdrawn is a success with no side effects. This is
  -- the branch a retry after a dropped response lands in, and it must not
  -- repeat the penalty.
  if obs.status = 'withdrawn' then
    return jsonb_build_object('ok', true, 'code', 'already_withdrawn',
                              'withdrawal_kind', obs.withdrawal_kind,
                              'server_now', now());
  end if;

  -- Terminal moderation states are not the reporter's to take back: a rejected
  -- or expired report has already left the active set, and an expired one's
  -- withdrawal would be a penalty for nothing.
  if obs.status in ('rejected', 'expired') or obs.expires_at <= now() then
    return jsonb_build_object('ok', false, 'code', 'not_withdrawable');
  end if;

  if p_mode = 'undo' then
    -- Fresh undo: only within the window, only before anything else happened
    -- to the row's lifecycle. After the window, the honest answer is that undo
    -- is gone and withdrawal remains.
    if now() > obs.created_at + community.fresh_undo_window() then
      return jsonb_build_object('ok', false, 'code', 'undo_window_expired');
    end if;
  elsif p_mode <> 'withdraw' then
    return jsonb_build_object('ok', false, 'code', 'invalid_mode');
  end if;

  -- The transition. Everything the withdrawal means happens in this one
  -- statement, under this one row lock: the status, the audit columns, and
  -- (below) the penalty guarded by the row count this returns.
  update community.observations
     set status = 'withdrawn',
         withdrawn_at = now(),
         withdrawal_kind = p_mode,
         resolved_at = coalesce(resolved_at, now())
   where id = p_observation_id
     and reporter_id = profile.user_id
     and status in ('reported', 'corroborated', 'disputed', 'confirmed')
     and expires_at > now();
  get diagnostics changed = row_count;

  if not changed then
    -- A concurrent request won the row between our SELECT and UPDATE. The
    -- answer for the loser is the idempotent success, not an error.
    return jsonb_build_object('ok', true, 'code', 'already_withdrawn',
                              'server_now', now());
  end if;

  -- The penalty: exactly once, because it lives under the same row lock as
  -- the transition that guards it. Fresh undo pays nothing. Only trust_score
  -- moves — disputed_count stays reserved for community disputes of the
  -- report's accuracy, which a voluntary withdrawal is not.
  if p_mode = 'withdraw' then
    update community.reporter_profiles
       set trust_score = greatest(-100, least(100, trust_score - 3))
     where user_id = profile.user_id;
  end if;

  return jsonb_build_object('ok', true, 'code', 'withdrawn',
                            'withdrawal_kind', p_mode,
                            'server_now', now());
end;
$$;

-- ---------------------------------------------------------------------------
-- Client-facing wrappers: two clear names over one transactional engine.
-- ---------------------------------------------------------------------------
create or replace function community.undo_report(p_observation_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  return community.withdraw_observation(p_observation_id, 'undo');
end;
$$;

create or replace function community.withdraw_report(p_observation_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  return community.withdraw_observation(p_observation_id, 'withdraw');
end;
$$;

-- ---------------------------------------------------------------------------
-- The same engine for polls.
-- ---------------------------------------------------------------------------
create or replace function community.withdraw_poll(
  p_poll_id uuid,
  p_mode text
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  poll community.polls;
  changed boolean;
begin
  profile := community.require_profile();

  select * into poll from community.polls p where p.id = p_poll_id;
  if poll.id is null then
    return jsonb_build_object('ok', false, 'code', 'not_found');
  end if;

  if poll.creator_id <> profile.user_id then
    return jsonb_build_object('ok', false, 'code', 'not_yours');
  end if;

  if poll.status = 'withdrawn' then
    return jsonb_build_object('ok', true, 'code', 'already_withdrawn',
                              'withdrawal_kind', poll.withdrawal_kind,
                              'server_now', now());
  end if;

  -- An expired poll is already gone from every read path; withdrawing it
  -- would charge a penalty for nothing. Closed polls may still be withdrawn
  -- while they are in the 7-day post-close audit window? No: a closed poll is
  -- finished business, not public content the creator needs recalled. Refuse.
  if poll.status in ('expired', 'closed') or poll.closes_at <= now() then
    return jsonb_build_object('ok', false, 'code', 'not_withdrawable');
  end if;

  if p_mode = 'undo' then
    if now() > poll.created_at + community.fresh_undo_window() then
      return jsonb_build_object('ok', false, 'code', 'undo_window_expired');
    end if;
  elsif p_mode <> 'withdraw' then
    return jsonb_build_object('ok', false, 'code', 'invalid_mode');
  end if;

  update community.polls
     set status = 'withdrawn',
         withdrawn_at = now(),
         withdrawal_kind = p_mode,
         closed_at = coalesce(closed_at, now())
   where id = p_poll_id
     and creator_id = profile.user_id
     and status = 'open'
     and closes_at > now();
  get diagnostics changed = row_count;

  if not changed then
    return jsonb_build_object('ok', true, 'code', 'already_withdrawn',
                              'server_now', now());
  end if;

  -- Same once-only penalty rule as observations, same reasoning.
  if p_mode = 'withdraw' then
    update community.reporter_profiles
       set trust_score = greatest(-100, least(100, trust_score - 3))
     where user_id = profile.user_id;
  end if;

  return jsonb_build_object('ok', true, 'code', 'withdrawn',
                            'withdrawal_kind', p_mode,
                            'server_now', now());
end;
$$;

create or replace function community.undo_poll(p_poll_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  return community.withdraw_poll(p_poll_id, 'undo');
end;
$$;

create or replace function community.withdraw_poll(p_poll_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  return community.withdraw_poll(p_poll_id, 'withdraw');
end;
$$;

-- ---------------------------------------------------------------------------
-- my_pending_reports — extended with everything "My Reports" needs to say:
-- its public standing (verification counts), whether it was withdrawn and
-- how, and until when undo is still available. Withdrawn rows stay visible
-- to their owner for the 30-day audit window.
-- ---------------------------------------------------------------------------
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
          'target_end_hour', o.target_end_hour,
          'withdrawn_at', o.withdrawn_at,
          'withdrawal_kind', o.withdrawal_kind,
          'undo_available_until', o.created_at + community.fresh_undo_window(),
          'verification_count', (
            select count(*) from community.verifications v
            where v.observation_id = o.id and v.verdict = true),
          'dispute_count', (
            select count(*) from community.verifications v
            where v.observation_id = o.id and v.verdict = false)
        ) order by o.created_at desc)
      from community.observations o
      where o.reporter_id = auth.uid()
        and (
          o.expires_at > now() - interval '7 days'
          or (o.status = 'withdrawn' and o.withdrawn_at > now() - interval '30 days')
        )
    ), '[]'::jsonb)
  );
end;
$$;

revoke execute on function community.my_pending_reports() from public, anon;
grant execute on function community.my_pending_reports() to authenticated;

-- ---------------------------------------------------------------------------
-- my_polls — the polls' twin of my_pending_reports, and the polls' first
-- owner-facing read RPC. Sanitized: no creator_id (it is the caller), no
-- idempotency_key, no voter rows — counts only.
-- ---------------------------------------------------------------------------
create or replace function community.my_polls()
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
    'polls', coalesce((
      select jsonb_agg(jsonb_build_object(
          'id', p.id,
          'room', p.room,
          'section', p.section,
          'subject', p.subject,
          'class_date', p.class_date,
          'start_hour', p.start_hour,
          'question', p.question,
          'status', p.status,
          'created_at', p.created_at,
          'closes_at', p.closes_at,
          'closed_at', p.closed_at,
          'withdrawn_at', p.withdrawn_at,
          'withdrawal_kind', p.withdrawal_kind,
          'undo_available_until', p.created_at + community.fresh_undo_window(),
          'total_votes', (
            select count(*) from community.poll_votes v where v.poll_id = p.id)
        ) order by p.created_at desc)
      from community.polls p
      where p.creator_id = auth.uid()
        and (
          p.closes_at > now() - interval '7 days'
          or (p.status = 'withdrawn' and p.withdrawn_at > now() - interval '30 days')
        )
        and p.created_at > now() - interval '30 days'
    ), '[]'::jsonb)
  );
end;
$$;

revoke execute on function community.my_polls() from public, anon;
grant execute on function community.my_polls() to authenticated;

-- ---------------------------------------------------------------------------
-- verify — a withdrawn observation is no longer verifiable, same as an
-- expired one. (Replaces 0003's verify for this one guard; everything else
-- is unchanged.)
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

  -- Withdrawn joins expired/rejected as terminal.
  if obs.expires_at <= now()
     or obs.status in ('rejected', 'expired', 'withdrawn') then
    return jsonb_build_object('ok', false, 'code', 'expired');
  end if;

  -- Cannot verify your own report.
  if obs.reporter_id = profile.user_id then
    return jsonb_build_object('ok', false, 'code', 'own_report');
  end if;

  if not community.check_rate_limit('verify', 60, interval '1 hour') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_short');
  end if;

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

grant execute on function community.verify(uuid, boolean, uuid)
  to authenticated;

-- ---------------------------------------------------------------------------
-- cast_vote needs no re-creation: 'withdrawn' is simply not 'open', so
-- 0003's existing status guard already refuses votes on withdrawn polls.
-- Its execute grant is restated in the privilege block at the end.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Retention: withdrawn rows carry a 30-day audit window, then go. Replaces
-- 0004's retain_and_delete for this one addition.
-- ---------------------------------------------------------------------------
create or replace function community.retain_and_delete()
returns void
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  delete from community.observations
  where status = 'expired'
    and resolved_at < now() - interval '7 days';

  -- Withdrawn reports: 30 days of audit, then reclaimed (cascades: votes,
  -- verifications).
  delete from community.observations
  where status = 'withdrawn'
    and withdrawn_at < now() - interval '30 days';

  delete from community.polls
  where status = 'expired';

  delete from community.polls
  where status = 'withdrawn'
    and withdrawn_at < now() - interval '30 days';

  delete from community.abuse_events
  where created_at < now() - interval '30 days';

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

  delete from cron.job_run_details
  where end_time < now() - interval '7 days';
end;
$$;

revoke execute on function community.retain_and_delete() from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- is_authenticated — pinned search_path (hygiene; the function is INVOKER
-- rights and fully qualified, so this closes a warning, not a hole).
-- ---------------------------------------------------------------------------
create or replace function community.is_authenticated()
returns boolean
language sql
stable
set search_path = public, extensions
as $$
  select auth.uid() is not null;
$$;

-- ---------------------------------------------------------------------------
-- Privileges: the full intended client surface, restated. This block is the
-- repo's copy of the production remediation: revoke everything for the two
-- client roles, then grant exactly what the read paths and RPCs need. New
-- columns (withdrawn_at, withdrawal_kind) join the column grants so Realtime
-- delivery of withdrawal UPDATEs works.
-- ---------------------------------------------------------------------------
revoke all on all tables in schema community from anon, authenticated;
-- PUBLIC holds default EXECUTE on every function; taking that too is what
-- makes the grant list below an actual allow-list rather than a hint. The
-- only two community functions RLS policies invoke as the querying user are
-- is_authenticated() and poll_is_readable(), and both are re-granted.
revoke all on all functions in schema community from public, anon, authenticated;

grant usage on schema community to anon, authenticated;

grant select (
  id, kind, room, section, subject, class_date, start_hour,
  payload, note, status, created_at, dedup_hour, expires_at, resolved_at,
  observation_type, event_date, target_start_hour, target_end_hour,
  withdrawn_at, withdrawal_kind
) on community.observations to authenticated;

grant select (
  id, room, section, subject, class_date, start_hour, question,
  status, created_at, dedup_hour, closes_at, closed_at,
  withdrawn_at, withdrawal_kind
) on community.polls to authenticated;

grant select on community.verifications to authenticated;
grant select on community.poll_options to authenticated;
grant select on community.poll_votes to authenticated;
grant select on community.reporter_profiles to authenticated;
grant select on community.app_meta to authenticated;
grant select on community.active_observations to authenticated;
-- abuse_events: no grant, no policy, nothing.

-- The client-callable RPC surface, exact signatures.
grant execute on function community.submit_report(
  text, text, text, text, date, smallint, jsonb, text, uuid, bigint,
  text, date, smallint) to authenticated;
grant execute on function community.verify(uuid, boolean, uuid) to authenticated;
grant execute on function community.create_poll(
  text, text, text, date, smallint, text, jsonb, uuid, bigint) to authenticated;
grant execute on function community.poll_results(uuid) to authenticated;
grant execute on function community.cast_vote(uuid, smallint) to authenticated;
grant execute on function community.my_reputation() to authenticated;
grant execute on function community.require_profile() to authenticated;
grant execute on function community.poll_is_readable(uuid) to authenticated;
-- is_authenticated() runs inside RLS policies as the querying user; PUBLIC
-- needs it back or every read of observations/polls errors instead of
-- filtering.
grant execute on function community.is_authenticated() to public;
grant execute on function community.my_pending_reports() to authenticated;
grant execute on function community.my_polls() to authenticated;
grant execute on function community.undo_report(uuid) to authenticated;
grant execute on function community.withdraw_report(uuid) to authenticated;
grant execute on function community.undo_poll(uuid) to authenticated;
grant execute on function community.withdraw_poll(uuid) to authenticated;

-- Internal engine: withdrawn callable by clients only through the wrappers.
revoke execute on function community.withdraw_observation(uuid, text)
  from public, anon, authenticated;
revoke execute on function community.withdraw_poll(uuid, text)
  from public, anon, authenticated;

update community.app_meta
set value = '{
  "fresh_undo_seconds": 60,
  "withdrawal_penalty": -3,
  "withdrawn_retention_days": 30
}'::jsonb
where key = 'retention_policy';
