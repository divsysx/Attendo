-- Attendo Community System — withdrawal and fresh-submission undo: schema.
--
-- A student who files something by mistake needs a short, penalty-free way back
-- (fresh undo); a student who wants their content gone later pays a small
-- reputation cost (normal withdrawal). Both are the *same* terminal state with
-- a different audit trail — which is why this migration is nearly all columns.
--
-- Lifecycle (reports):      reported -> withdrawn (kind 'undo' | 'withdraw')
-- Lifecycle (polls):        open     -> withdrawn (kind 'undo' | 'withdraw')
--
-- Design rules:
--   * Withdrawal is a state transition, never a DELETE. The row stays for
--     audit and idempotency; every read path (RLS policies, the active view,
--     the polls policies) already filters on the positive status lists, so a
--     withdrawn row disappears from public results with zero read-path changes.
--   * The kind of exit ('undo' vs 'withdraw') is recorded on the row, because
--     it is the audit fact the reputation rule depends on: one withdrawal, one
--     penalty — an undo, none.
--   * The reputation penalty itself is applied only inside the withdraw RPC's
--     atomic status transition (0009), so "one withdrawn report -> one
--     decrease" is a row-lock guarantee, not a client promise.
--
-- This file is schema-only: `ALTER TYPE ... ADD VALUE` cannot run inside the
-- transaction Supabase wraps each migration in *and* have the value used later
-- in the same transaction, so the functions that compare against 'withdrawn'
-- live in 0009.

-- The terminal state both features share.
alter type community.observation_status add value if not exists 'withdrawn';
alter type community.poll_status add value if not exists 'withdrawn';

-- The audit columns. Nullable + no default: every pre-existing row was filed
-- before withdrawal existed, and 'not withdrawn yet' is exactly null here.
-- `ADD CONSTRAINT` has no IF NOT EXISTS, so the guards are DO blocks — which
-- also keep this migration safe to re-run against a half-applied database.
alter table community.observations
  add column if not exists withdrawn_at    timestamptz,
  add column if not exists withdrawal_kind text;

do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'obs_withdrawal_kind') then
    alter table community.observations
      add constraint obs_withdrawal_kind check (
        withdrawal_kind is null or withdrawal_kind in ('undo', 'withdraw'));
  end if;
end $$;

alter table community.polls
  add column if not exists withdrawn_at    timestamptz,
  add column if not exists withdrawal_kind text;

do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'poll_withdrawal_kind') then
    alter table community.polls
      add constraint poll_withdrawal_kind check (
        withdrawal_kind is null or withdrawal_kind in ('undo', 'withdraw'));
  end if;
end $$;

-- Withdrawn rows must never re-enter public reads. The existing RLS read
-- policies and partial indexes enumerate the *active* statuses, so 'withdrawn'
-- is excluded from all of them by construction — nothing here needs to change.
-- The indexes below keep "is this withdrawn" cheap for the owner-facing RPCs.
create index if not exists obs_withdrawn on community.observations (withdrawn_at)
  where withdrawn_at is not null;
create index if not exists polls_withdrawn on community.polls (withdrawn_at)
  where withdrawn_at is not null;

-- Schema marker: additive-only versioning for old-client handling.
update community.app_meta
  set value = '3'::jsonb
  where key = 'community_schema_version';
