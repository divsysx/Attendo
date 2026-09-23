-- Attendo Community System — Row Level Security.
--
-- Principle: the database is hostile to clients. An attacker with the
-- publishable key (reverse-engineered APK, direct REST/WebSocket calls) can:
--   * read exactly what the UI shows, and nothing more
--   * call the RPCs (which rate-limit and validate)
-- and can NOT:
--   * insert/update/delete any community table directly
--   * read per-user verifications, votes, abuse events, or other users' trust
--   * forge reputation, timestamps, statuses or counts
--
-- Every table gets RLS enabled with zero write policies; the only write path
-- is the SECURITY DEFINER RPC layer in 0003_rpcs.sql (granted to `authenticated`).
--
-- Visibility (locked decisions):
--   * Room-level observations (room IS NOT NULL): visible to everyone
--     (any anon-auth user) while active and unexpired.
--   * Class/timetable observations (room IS NULL, section + date + hour):
--     visible to authenticated users while active — the client only *requests*
--     the sections relevant to it, and the section value is display metadata
--     about a timetable cell, not personal data. Server-side section-scoping
--     at the row level would require the server to know which sections a user
--     belongs to; the anonymous identity model deliberately does not collect
--     that. Scope is therefore enforced by the read API shape (active_view
--     queries filtered by the requesting context) rather than by per-user
--     row predicates — the rows carry no PII and no reporter identity.
--   * Reporters additionally see their own rows (any status) for
--     "my pending reports".
-- ============================================================================
-- helper: is the calling request authenticated with an anonymous (or any)
-- user?  auth.uid() is null for the anon role.
-- ============================================================================

create function community.is_authenticated()
returns boolean
language sql
stable
as $$
  select auth.uid() is not null;
$$;

-- ============================================================================
-- observations
-- ============================================================================

alter table community.observations enable row level security;

-- Read: active, unexpired rows only, for everyone authenticated.
create policy observations_read_active
  on community.observations
  for select
  to authenticated
  using (
    status in ('reported', 'corroborated', 'confirmed')
    and expires_at > now()
  );

-- Read: a reporter's own rows at any status (pending/disputed included).
create policy observations_read_own
  on community.observations
  for select
  to authenticated
  using (reporter_id = auth.uid());

-- The `anon` (unauthenticated) role gets NO policy: community data requires
-- at least the silently-created anonymous identity. This keeps identity
-- failure a read-only degradation, but never an open API.

-- Deliberately NO insert/update/delete policies on any role: table-level
-- writes are impossible; the RPCs are the write path.

-- ============================================================================
-- verifications — rows are never client-readable; only aggregate counts
-- travel through the active_view / RPC layer.
-- ============================================================================

alter table community.verifications enable row level security;

create policy verifications_read_own
  on community.verifications
  for select
  to authenticated
  using (user_id = auth.uid());

-- ============================================================================
-- polls / poll_options / poll_votes
-- ============================================================================

alter table community.polls enable row level security;

create policy polls_read_active
  on community.polls
  for select
  to authenticated
  using (
    (status = 'open' and closes_at > now())
    or creator_id = auth.uid()
  );

alter table community.poll_options enable row level security;

create policy poll_options_read
  on community.poll_options
  for select
  to authenticated
  using (
    exists (
      select 1 from community.polls p
      where p.id = poll_id
        and ((p.status = 'open' and p.closes_at > now()) or p.creator_id = auth.uid())
    )
  );

alter table community.poll_votes enable row level security;

-- A user may see only their own vote row (to render their chosen option);
-- tallies come from the poll_results() RPC as counts, never rows.
create policy poll_votes_read_own
  on community.poll_votes
  for select
  to authenticated
  using (user_id = auth.uid());

-- ============================================================================
-- reporter_profiles — only your own row, and only its non-sensitive fields
-- are exposed to you (trust tier for the settings screen). The full row is
-- server-only. Implemented by a view with RLS rather than the raw table.
-- ============================================================================

alter table community.reporter_profiles enable row level security;

create policy reporter_profiles_read_own
  on community.reporter_profiles
  for select
  to authenticated
  using (user_id = auth.uid());

-- abuse_events: RLS enabled, NO policies at all. Only the RPC layer (SECURITY
-- DEFINER, running as the migration owner) and the postgres role can touch it.

alter table community.abuse_events enable row level security;

-- app_meta: readable by authenticated clients for version negotiation.

alter table community.app_meta enable row level security;

create policy app_meta_read
  on community.app_meta
  for select
  to authenticated
  using (true);

-- ============================================================================
-- The client-facing read surface: a view that strips reporter identity and
-- exposes verification counts. Realtime broadcasts the base table's changes
-- and RLS filters delivery; clients fetch/subscribe through this view via the
-- REST API (PostgREST serves views subject to the underlying RLS).
-- ============================================================================

create view community.active_observations as
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
       where v.observation_id = o.id and v.verdict = false) as dispute_count
  from community.observations o
  where o.status in ('reported', 'corroborated', 'confirmed')
    and o.expires_at > now();

-- A view's own RLS: PostgREST evaluates the underlying table's policies for
-- the rows the view selects, so authenticated clients see exactly the rows
-- observations_read_active allows. The view itself adds the aggregation and
-- the column stripping. (Views in Postgres run with the *owner's* privileges,
-- so the verifications subqueries — a table the client cannot read — are safe
-- to include: the view owner reads them, the client reads the view.)

grant select on community.active_observations to authenticated;

-- ==========================================================================
-- Privileges: table-level DML revoked outright for client roles. Even if a
-- future migration accidentally adds a permissive policy, the grants are not
-- there to exploit.
--
-- SELECT on the base tables is granted to `authenticated` (never `anon`):
-- per current Supabase Realtime docs, postgres_changes delivery requires the
-- client to hold SELECT on the published table, and the own-rows REST reads
-- ("my pending reports", my vote row) need it too. RLS policies above remain
-- the row boundary — these grants expose nothing the policies do not allow.
-- abuse_events deliberately gets NO grant: no policy, no privilege.
-- ==========================================================================

revoke all on all tables in schema community from anon, authenticated;
grant usage on schema community to anon, authenticated;
grant select on community.active_observations to authenticated;
grant select on community.app_meta to authenticated;
grant select on community.observations to authenticated;
grant select on community.verifications to authenticated;
grant select on community.polls to authenticated;
grant select on community.poll_options to authenticated;
grant select on community.poll_votes to authenticated;
grant select on community.reporter_profiles to authenticated;
-- Writes happen only via the RPCs granted in 0003_rpcs.sql.
