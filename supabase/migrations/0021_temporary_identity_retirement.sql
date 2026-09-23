-- 0021_temporary_identity_retirement.sql
-- Returning-sign-in cleanup: retiring the temporary anonymous identity (2026-09-15).
--
-- The lifecycle this closes (client side, Phase 2B):
--
--   anonymous A --first-time link--> account A            (link changes nothing: 0017
--                                                          precedent, uid preserved)
--   account A  --sign out-->        anonymous B           (fresh mint on next contact)
--   anonymous B --activity-->        B's reports/polls/votes
--   account A  --sign in-->          B retired, A restored
--
-- Nothing ever merges. B's server-side identity is DELETED when — and only when —
-- a client that still holds B's session asks to retire it in favour of a
-- destination identity that already exists. The client asks exactly once, in the
-- OAuth callback's import window: after the callback session for A has been parsed
-- and A's user fetched (authentication positively established), before A's session
-- is imported (while B's session is still the live one, which is what authorises
-- the deletion). A cancelled or failed OAuth never reaches this function, so B
-- survives it intact.
--
-- SECURITY DEFINER is required because no client role holds DELETE on any
-- community table (by design: every deletion in this schema happens inside a
-- definer function). The function is conservative by construction:
--
--   * caller must prove ownership of the source identity — not with a parameter
--     (there is none to forge) but with the session itself: every deletion is
--     scoped to auth.uid(), the caller's own uid. No parameter can name another
--     user's identity to delete.
--   * the caller must be ANONYMOUS ((auth.jwt() ->> 'is_anonymous') is true).
--     An account user's identity can never be retired through this function —
--     not by its owner, not by anyone else — so the destructive surface is
--     exactly the temporary identities the sign-in flow creates.
--   * the destination must be a different, established identity: p_destination_
--     user_id <> auth.uid() and must already hold a reporter_profiles row (the
--     account being signed back into, with history to return to). The parameter
--     is a PRECONDITION ONLY — it gates whether the caller's own identity may be
--     retired; it names no deletion target. A destination that has no community
--     identity yet (a brand-new GitHub account's first sign-in) declines with
--     destination_not_established and deletes nothing: there is nothing being
--     "returned to", and the client proceeds with its local cleanup only.
--   * idempotent: a caller whose identity is already gone (second callback,
--     retried sign-in, crash-and-retry) answers ok/already_retired without
--     error, deleting nothing further.
--   * no reputation inflation, no merge semantics: counts are never moved,
--     summed or re-keyed — B's rows are deleted, A's are untouched.
--
-- What is deleted (exactly the 0017 replacement hard-delete list, minus the
-- identity move that replacement performs — there is no move here, A's rows
-- already sit under A's uid):
--   reporter_transfer_grants (owner_user_id = B) — B's export grants, all states;
--     a stale .atid of B must find nothing to claim (0017, approved).
--   reporter_profiles (user_id = B)             — ONE delete; the schema's own
--     cascade graph removes every identity-owned row: observations B filed,
--     verifications B cast and verifications on B's reports, polls B created
--     with their options and ALL votes on them (others' included), B's votes on
--     anyone's polls, B's abuse_events.
-- What is inserted:
--   superseded_identities (B) — the tombstone: a straggler write under B's
--     still-valid access token (it cannot be revoked retroactively) hits
--     require_profile's identity_superseded refusal instead of silently
--     resurrecting a deleted identity.
-- What is NOT deleted:
--   activity_pulses, app_meta (global, not identity-owned);
--   A's rows in any table (A is only ever read here, as a precondition);
--   B's auth.users row — the client cannot delete auth users (that needs the
--     service-role admin API, which this app must never hold). B's auth row
--     remains, but with its community identity deleted, its session revoked by
--     the client's sign-out, and its uid tombstoned, nothing community-side
--     remains of it. See the Phase 2B report: this is a documented limitation,
--     not a silent gap.
--   any identity that never signs in: this function only ever runs on explicit
--     request from the identity's own live session. There is no cron, no
--     inactivity sweep, no batch — an anonymous user who never signs into an
--     account is untouched by this migration's existence.
-- ============================================================================

create or replace function community.retire_reporting_identity(
  p_destination_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    return jsonb_build_object('ok', false, 'code', 'not_authenticated');
  end if;

  -- Only an anonymous identity may be retired through this function. Account
  -- identities are permanent; their owners can never destroy them by calling
  -- this (and nobody else can, because everything below is auth.uid()-scoped).
  if not coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    return jsonb_build_object('ok', false, 'code', 'not_anonymous');
  end if;

  -- Idempotency first: a caller whose identity is already retired (retried
  -- sign-in, duplicate callback) is done, not an error. The tombstone is the
  -- authoritative record that the retirement ran.
  if exists (select 1 from community.superseded_identities where old_user_id = uid) then
    return jsonb_build_object('ok', true, 'code', 'already_retired');
  end if;

  -- The destination is a precondition, never a target: it must be somebody
  -- else's established identity — the account being returned to. Without one
  -- there is no "returning" sign-in at all, and nothing is deleted.
  if p_destination_user_id is null or p_destination_user_id = uid then
    return jsonb_build_object('ok', false, 'code', 'invalid_destination');
  end if;
  if not exists (
    select 1 from community.reporter_profiles where user_id = p_destination_user_id
  ) then
    return jsonb_build_object('ok', false, 'code', 'destination_not_established');
  end if;

  -- The retirement itself, in 0017's exact order: grants first (so a stale
  -- .atid of B finds nothing to claim), then the one profile delete whose
  -- cascade graph removes every identity-owned row, then the tombstone that
  -- refuses any write a still-valid access token might straggle in with.
  delete from community.reporter_transfer_grants where owner_user_id = uid;
  delete from community.reporter_profiles where user_id = uid;
  insert into community.superseded_identities (old_user_id)
  values (uid)
  on conflict (old_user_id) do nothing;

  return jsonb_build_object('ok', true, 'code', 'retired', 'server_now', now());
end;
$$;

-- ============================================================================
-- Grants: the one new client-callable RPC. Every helper it uses is already
-- internal; nothing else changes.
-- ============================================================================
revoke execute on function community.retire_reporting_identity(uuid) from public, anon;
grant execute on function community.retire_reporting_identity(uuid) to authenticated;
