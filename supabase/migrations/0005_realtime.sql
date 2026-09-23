-- Attendo Community System — Realtime publication.
--
-- Realtime is an enhancement, never a dependency (locked decision #4):
--   * The Android client's correctness path is REST snapshot + reconciliation.
--   * Realtime subscriptions are targeted (filtered by room / class_date),
--     lifecycle-scoped (only while a relevant screen is visible), and torn
--     down in the background.
--   * If Realtime is unavailable or the free-tier connection limit is hit,
--     the client falls back to bounded polling; nothing breaks.
--
-- RLS filters delivery: Realtime only sends rows the authenticated caller
-- could SELECT, per current Supabase docs — so publication here exposes
-- exactly the read surface defined in 0002_rls.sql, nothing more.
--
-- Only the base tables clients need change events for are published. The
-- view (active_observations) is not publishable directly; clients subscribe
-- to observations and apply their own display filtering, or use the snapshot
-- endpoint. poll_votes are NOT published (per-vote rows are private); poll
-- result changes are picked up via poll_results polling / the polls row
-- itself, keeping per-vote broadcasts off the wire.

begin;
  drop publication if exists supabase_realtime;
  create publication supabase_realtime;
commit;

alter publication supabase_realtime add table community.observations;
alter publication supabase_realtime add table community.polls;
