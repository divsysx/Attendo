-- 0012_live_pulse.sql
-- Live disappearance: a content-free pulse table every subscriber can see.
--
-- The two-device test on 2026-09-09 found the hole 0011 could not close:
-- reports appeared live, but withdrawals did not. Realtime delivers an
-- UPDATE only to subscribers who can SELECT the *new* row — and the read
-- policies (0002) make a withdrawn observation, a withdrawn or closed poll,
-- and a rejected report invisible to everyone but their owner. So the very
-- events that make content disappear were the events nobody else received:
-- phone B's snapshot only updated on the next screen entry.
--
-- Fix: community.activity_pulses — one row per public table, no content
-- (a table name and a timestamp, nothing else), bumped by a trigger on
-- every INSERT or UPDATE of community.observations and community.polls.
-- The row is always visible to authenticated, so every change reaches
-- every subscriber: appearances, tallies, status transitions, and — the
-- point — disappearances, which now announce themselves as a pulse instead
-- of as silence. No ids, no payloads, no identities travel: the pulse says
-- "this table changed, refetch", which is all the app ever did with the
-- direct events anyway.
--
-- DELETE is deliberately not pulsed: the only deletions are retention's,
-- which remove rows already invisible to every subscriber.
--
-- The fresh-undo window narrows from 60 to 30 seconds (both sides, in the
-- one server place and its client mirror), matching what the cards offer:
-- Undo for the first half-minute, Withdraw after.

-- ---------------------------------------------------------------------------
-- The pulse table. Two rows, ever: one per pulsed table.
-- ---------------------------------------------------------------------------
create table if not exists community.activity_pulses (
  table_name text primary key,
  at timestamptz not null default now()
);

-- The client surface: read-only, content-free. Writes happen only inside
-- the security-definer trigger, so there is deliberately no write path for
-- any client role. (Drop-then-create: CREATE POLICY has no IF NOT EXISTS,
-- and this file must be safe to re-run after a partial pass.)
alter table community.activity_pulses enable row level security;

drop policy if exists activity_pulses_read on community.activity_pulses;
create policy activity_pulses_read
  on community.activity_pulses
  for select
  to authenticated
  using (true);

-- 0009's revoke-all was one-time, and Supabase's default privileges would
-- hand the new table to anon/authenticated wholesale — so revoke first,
-- then grant exactly the two columns realtime delivery needs.
revoke all on community.activity_pulses from anon, authenticated;
grant select (table_name, at) on community.activity_pulses to authenticated;

-- ---------------------------------------------------------------------------
-- The pulse trigger — one function, two tables. clock_timestamp() rather
-- than now() so two changes inside one transaction still produce a visible
-- UPDATE each (now() is frozen per transaction; a pulse whose new value
-- equals the old could be optimised away and never reach the WAL).
-- ---------------------------------------------------------------------------
create or replace function community.pulse_row()
returns trigger
language plpgsql
security definer
set search_path = community, public, extensions
as $$
begin
  insert into community.activity_pulses (table_name, at)
  values (tg_table_name, clock_timestamp())
  on conflict (table_name) do update set at = clock_timestamp();
  return null;
end;
$$;

revoke execute on function community.pulse_row() from public, anon, authenticated;

-- Drop-then-create for the same reason as the policy: CREATE TRIGGER has no
-- IF NOT EXISTS, and a second pass must not error on the first pass's work.
drop trigger if exists observations_pulse on community.observations;
create trigger observations_pulse
  after insert or update on community.observations
  for each row execute function community.pulse_row();

drop trigger if exists polls_pulse on community.polls;
create trigger polls_pulse
  after insert or update on community.polls
  for each row execute function community.pulse_row();

-- ---------------------------------------------------------------------------
-- The publication. Idempotent: a rerun must not error on the already-added
-- table.
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'community'
      and tablename = 'activity_pulses'
  ) then
    alter publication supabase_realtime add table community.activity_pulses;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- The fresh-undo window: 30 seconds, in the one place it exists server-side.
-- ---------------------------------------------------------------------------
create or replace function community.fresh_undo_window()
returns interval
language sql
immutable
as $$
  select interval '30 seconds';
$$;

revoke execute on function community.fresh_undo_window() from public, anon, authenticated;

-- The recorded policy value follows (0009 seeded 60).
update community.app_meta
set value = jsonb_set(value, '{fresh_undo_seconds}', '30'::jsonb)
where key = 'retention_policy';
