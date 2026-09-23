-- Attendo Community System — column-level security for Realtime delivery.
--
-- Found by Phase 4 adversarial validation:
--   Realtime postgres_changes broadcasts the FULL base-table row. RLS filters
--   ROWS but not COLUMNS — the `select:` subscription parameter is
--   client-requested (an attacker simply omits it), so it cannot be the
--   security boundary. Every subscriber to community.observations received
--   reporter_id + idempotency_key; every subscriber to community.polls
--   received creator_id + idempotency_key.
--
-- Fix: replace the table-level SELECT grants with column-level grants for
-- `authenticated` on the two published tables. Postgres column privileges are
-- enforced server-side for every reader — Realtime included — so the payload
-- physically cannot contain a column the role cannot SELECT.
--
-- What is deliberately still readable:
--   observations.note — the plan's privacy model treats notes as anonymous
--   text ("notes travel as text with no identity"); the active_observations
--   view already exposes it. Only the *link* to the reporter is protected.
--
-- What the column revoke changes for honest clients:
--   * REST `select=*` on observations/polls now fails (column privilege
--     semantics: all-or-explicit-columns). The client uses the view, explicit
--     column selects, or the new my_pending_reports() RPC.
--   * Filtering observations by `reporter_id=eq.<self>` no longer works
--     (the WHERE clause needs column privilege). The plan's "my pending
--     reports" surface therefore moves into my_pending_reports(), a
--     SECURITY DEFINER RPC that filters by auth.uid() internally and returns
--     sanitized rows (no reporter_id, no idempotency_key).
--
-- anon remains with zero grants (unchanged). abuse_events remains with no
-- grant at all (unchanged). RLS policies are unchanged — row-level scope
-- was and remains correct; this migration closes the column-level gap.
--
-- safe on an existing database: idempotent re-grants.

-- ---------------------------------------------------------------------------
-- observations: everything except reporter_id / idempotency_key
-- (note stays readable — see header)
-- ---------------------------------------------------------------------------
revoke select on community.observations from authenticated;
grant select (
  id, kind, room, section, subject, class_date, start_hour,
  payload, note, status, created_at, dedup_hour, expires_at, resolved_at
) on community.observations to authenticated;

-- ---------------------------------------------------------------------------
-- polls: everything except creator_id / idempotency_key
-- ---------------------------------------------------------------------------
revoke select on community.polls from authenticated;
grant select (
  id, room, section, subject, class_date, start_hour, question,
  status, created_at, dedup_hour, closes_at, closed_at
) on community.polls to authenticated;

-- ---------------------------------------------------------------------------
-- poll eligibility predicate (SECURITY DEFINER): the poll_options RLS policy
-- references community.polls in its USING subquery, and policy expressions
-- run with the *caller's* privileges — which can no longer read the revoked
-- creator_id column. A definer-qualifier helper keeps the policy working
-- while the column itself stays unreadable.
-- ---------------------------------------------------------------------------
create or replace function community.poll_is_readable(p_poll_id uuid)
returns boolean
language sql
security definer
set search_path = community, public, extensions
stable
as $$
  select exists (
    select 1 from community.polls p
    where p.id = p_poll_id
      and ((p.status = 'open' and p.closes_at > now()) or p.creator_id = auth.uid())
  );
$$;

revoke execute on function community.poll_is_readable(uuid) from public, anon;
grant execute on function community.poll_is_readable(uuid) to authenticated;

drop policy poll_options_read on community.poll_options;
create policy poll_options_read
  on community.poll_options
  for select
  to authenticated
  using (community.poll_is_readable(poll_id));

-- ---------------------------------------------------------------------------
-- my_pending_reports() — the reporter's own rows at any status, sanitized.
-- Restores the "my pending reports" surface that reporter_id-based client
-- filtering can no longer provide (and never needed to).
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
          'resolved_at', o.resolved_at
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
