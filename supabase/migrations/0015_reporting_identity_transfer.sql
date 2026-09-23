-- 0015_reporting_identity_transfer.sql
-- Reporting Identity Backup/Restore: move an anonymous reporter identity —
-- profile, reputation counters, and entire history — from one device to
-- another, deliberately, atomically, and with the old holder able to refuse.
--
-- Why this exists: the identity is an anonymous-auth session whose credential
-- (the refresh token) lives in exactly one place, `attendo-community.xml` on
-- one phone, excluded from every backup. That is the reporter-per-person
-- invariant. Until now, losing the phone meant losing the identity. This
-- migration adds the one sanctioned way to move it.
--
-- Design (approved 2026-09-11, 24-hour veto window):
--
--   * The server is the authority for identity ownership. There is no device
--     identifier in the anonymous JWT (verified against this project's live
--     tokens: sub, session_id, role, amr — session_id identifies an auth
--     session, not a device, and travels with the token). "One active device"
--     is therefore enforced as "exactly one auth uid owns the identity at any
--     instant": the .atid file a student carries contains NO credential, only
--     a one-time transfer code, and the identity itself is re-parented
--     server-side from the old uid to the claiming device's fresh uid.
--
--   * Transfer is a three-step, two-party state machine:
--       ARMED          begin_identity_transfer() on the owner's device.
--                      Inert: the owner keeps reporting normally.
--       PENDING_CLAIM  claim_reporting_identity(code) by a FRESH claimant
--                      (no community history — identities never merge).
--                      Still nothing has moved.
--       COMPLETED      finalize_transfer(): one transaction that consumes the
--                      grant, copies the profile under the claimant (every
--                      counter preserved), re-parents all history, deletes
--                      the old profile, and tombstones the old uid.
--     Finalize happens only when the 24-hour veto window elapses without the
--     owner objecting, or immediately when the owner explicitly approves.
--
--   * The veto: any community WRITE by the owner during the window — report,
--     verify, vote, poll, undo, withdraw, all of which funnel through
--     require_profile() — aborts the pending claim. The owner's app learns of
--     a pending claim from my_pending_reports' transfer_pending flag and can
--     also abort explicitly (Keep my identity). The owner consuming their own
--     code (claim with their own session) kills the grant outright — the way
--     to neutralise a stolen .atid file.
--
--   * Claimant activity cancels too: a claimant who uses their fresh identity
--     while the claim is pending (a report, a vote) has chosen to stay fresh;
--     their claim aborts, and finalize re-checks freshness as the backstop.
--
--   * Race safety: every state transition is a guarded UPDATE
--     (`... where state = <expected>`), so owner-abort, claimant-abort,
--     approve and finalize can race freely — exactly one wins, and the losers
--     receive an honest status, never a partial move.
--
--   * Replay/expiry: the code is 256 random bits (base64url, 43 chars),
--     stored only as its sha256; single-use by state machine; armed grants
--     die after 72h; pending claims die if neither finalized nor aborted
--     within claim_deadline + veto window. Brute force is computationally
--     infeasible; per-claimant event rate-limiting is impossible by design
--     (a fresh claimant has no reporter_profiles row, and abuse_events
--     foreign-keys to it) — the code's entropy is the protection.
--
-- Security posture (matches abuse_events):
--   * reporter_transfer_grants and superseded_identities get RLS enabled with
--     zero policies and zero grants — no client role can read or write them.
--     Not added to the realtime publication: no transfer state is ever
--     broadcast.
--   * The code appears in exactly one place: begin_identity_transfer()'s
--     response to the owner's own authenticated session.
--   * superseded_identities gives the displaced device its clear error:
--     require_profile() raises identity_superseded before any write can
--     auto-create a fresh profile, so the old device is never silently
--     re-purposed as a new reporter without the client choosing to start
--     fresh.
-- ============================================================================

create extension if not exists pgcrypto with schema extensions;

-- ============================================================================
-- Policy constants. Functions (not literals) so tests and future migrations
-- have one place to read them; revoked from every client role.
-- ============================================================================

create or replace function community.transfer_veto_window()
returns interval
language sql
immutable
as $$
  select interval '24 hours';
$$;

create or replace function community.transfer_armed_ttl()
returns interval
language sql
immutable
as $$
  select interval '72 hours';
$$;

revoke execute on function community.transfer_veto_window() from public, anon, authenticated;
revoke execute on function community.transfer_armed_ttl() from public, anon, authenticated;

-- ============================================================================
-- Transfer grants: the state machine's storage. Only code_hash is stored;
-- the code itself exists once, in begin_identity_transfer()'s answer.
-- ============================================================================

create table community.reporter_transfer_grants (
  id                uuid primary key default gen_random_uuid(),
  owner_user_id     uuid not null references auth.users(id) on delete cascade,
  code_hash         text not null,
  state             text not null default 'armed'
                      constraint transfer_grant_state check (
                        state in ('armed', 'pending_claim', 'completed', 'aborted')),
  created_at        timestamptz not null default now(),
  claimed_by        uuid references auth.users(id) on delete set null,
  claimed_at        timestamptz,
  claim_deadline    timestamptz,
  owner_approved_at timestamptz,
  completed_at      timestamptz,
  aborted_at        timestamptz,
  abort_reason      text
);

comment on table community.reporter_transfer_grants is
  'One-time transfer codes for reporting-identity backup/restore. Server-only: RLS on, no policies, no grants.';

-- One live (armed or pending) grant per reporter — a second export must not
-- be able to compete with a transfer already in flight. Partial, so history
-- (completed/aborted) is unlimited while "live" is structurally singular.
create unique index one_live_grant_per_reporter
  on community.reporter_transfer_grants (owner_user_id)
  where state in ('armed', 'pending_claim');

create index transfer_grants_by_hash
  on community.reporter_transfer_grants (code_hash);

-- ============================================================================
-- Tombstones: uids that held an identity and lost it to a transfer. The
-- displaced device's writes raise identity_superseded (see require_profile).
-- ============================================================================

create table community.superseded_identities (
  old_user_id   uuid primary key references auth.users(id) on delete cascade,
  superseded_at timestamptz not null default now()
);

comment on table community.superseded_identities is
  'Auth uids displaced by an identity transfer. Server-only: RLS on, no policies, no grants.';

alter table community.reporter_transfer_grants enable row level security;
alter table community.superseded_identities enable row level security;
-- Deliberately no policies and no grants on either table: the abuse_events
-- posture. Only the SECURITY DEFINER functions below (running as the
-- migration owner) touch them. Nor are they added to the realtime
-- publication — transfer state is never broadcast.

-- ============================================================================
-- Rate limiting: 'transfer' joins the abuse_events action vocabulary. (Only
-- owners and completed claimants record events; a fresh claimant cannot —
-- see the header's note on the foreign key.)
-- ============================================================================

alter table community.abuse_events drop constraint abuse_action_allowed;
alter table community.abuse_events add constraint abuse_action_allowed check (
  action in ('report', 'vote', 'verify', 'poll_create', 'transfer'));

-- ============================================================================
-- Internal helpers. All revoked from every client role at the end.
-- ============================================================================

-- Retires grants the clock has killed: armed grants past their TTL, and
-- pending claims nobody finalized or aborted within the grace after their
-- veto deadline. Called lazily by begin/approve/claim so a dead grant never
-- blocks a new export.
create or replace function community.retire_stale_grants(p_owner uuid)
returns void
language sql
security definer
set search_path = community, public, extensions
as $$
  update community.reporter_transfer_grants
  set state = 'aborted', aborted_at = now(), abort_reason = 'expired'
  where owner_user_id = p_owner
    and (
      (state = 'armed' and created_at + community.transfer_armed_ttl() < now())
      or (state = 'pending_claim'
          and claim_deadline + community.transfer_veto_window() < now())
    );
$$;

-- The veto, and its claimant twin. Cancels any pending claim the calling
-- user is party to (owner by owner_user_id, claimant by claimed_by). Called
-- from require_profile(), so every community write by either party during
-- the window aborts the transfer — proof of life beats a pending claim.
create or replace function community.cancel_pending_transfer_if_any()
returns void
language sql
security definer
set search_path = community, public, extensions
as $$
  update community.reporter_transfer_grants
  set state = 'aborted', aborted_at = now(),
      abort_reason = case when owner_user_id = auth.uid() then 'owner_activity'
                          else 'claimant_activity' end
  where state = 'pending_claim'
    and (owner_user_id = auth.uid() or claimed_by = auth.uid());
$$;

-- The atomic transfer. One transaction, guarded entry: the grant must still
-- be pending and either past its veto deadline or owner-approved. The
-- re-parenting order keeps every FK satisfied at each statement:
--   1. copy the owner's profile under the claimant (all counters preserved),
--   2. re-parent the history rows to the claimant,
--   3. delete the owner's profile (nothing references it anymore),
--   4. tombstone the owner's uid.
-- There is no instant at which both uids own the history: the grant's
-- guarded UPDATE serialises competing finalizes, and the whole move commits
-- or rolls back together.
create or replace function community.finalize_transfer(p_grant_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  g         community.reporter_transfer_grants%rowtype;
  owner_id  uuid;
  claimer   uuid;
begin
  update community.reporter_transfer_grants
  set state = 'completed', completed_at = now()
  where id = p_grant_id
    and state = 'pending_claim'
    and (owner_approved_at is not null or claim_deadline <= now())
  returning * into g;
  if not found then
    return jsonb_build_object('ok', false, 'code', 'transfer_not_ready');
  end if;
  owner_id := g.owner_user_id;
  claimer  := g.claimed_by;

  -- Freshness backstop: the claimant must still hold no community history.
  -- (Activity during the window already cancelled the claim via
  -- require_profile; this catches anything that slipped past that.)
  if exists (select 1 from community.reporter_profiles where user_id = claimer) then
    update community.reporter_transfer_grants
    set state = 'aborted', aborted_at = now(), abort_reason = 'claimant_not_fresh'
    where id = p_grant_id and state = 'completed';
    return jsonb_build_object('ok', false, 'code', 'identity_not_fresh');
  end if;

  -- 1. The profile moves, counters and all: reputation travels.
  insert into community.reporter_profiles (
    user_id, created_at, trust_score, report_count,
    corroborated_count, disputed_count, restricted_until
  )
  -- The new row carries the CLAIMER's uid; the counters are the owner's.
  select claimer, created_at, trust_score, report_count,
         corroborated_count, disputed_count, restricted_until
  from community.reporter_profiles
  where user_id = owner_id;

  -- 2. Every identity-keyed row re-parents to the claimant.
  update community.observations set reporter_id = claimer where reporter_id = owner_id;
  update community.polls       set creator_id = claimer where creator_id = owner_id;
  update community.verifications set user_id   = claimer where user_id   = owner_id;
  update community.poll_votes    set user_id   = claimer where user_id   = owner_id;
  update community.abuse_events  set user_id   = claimer where user_id   = owner_id;

  -- 3. The old profile goes; nothing references it anymore.
  delete from community.reporter_profiles where user_id = owner_id;

  -- 4. The old uid is tombstoned: its writes raise identity_superseded.
  insert into community.superseded_identities (old_user_id)
  values (owner_id)
  on conflict (old_user_id) do nothing;

  -- Observability: the transfer counts as one 'transfer' event, recorded for
  -- the claimant — never auth.uid(), because on the approve path the caller
  -- is the owner, whose profile (and thus abuse_events FK target) this
  -- transaction has just deleted.
  insert into community.abuse_events (user_id, action) values (claimer, 'transfer');

  return jsonb_build_object('ok', true, 'state', 'completed', 'server_now', now());
end;
$$;

-- ============================================================================
-- require_profile — every write path's choke point (0003; 0009's
-- undo/withdraw twins call it too). Gains two duties:
--   * a tombstoned uid raises identity_superseded BEFORE the upsert below
--     could hand it a fresh profile — the displaced device's clear error,
--     never a silent re-birth as a new reporter.
--   * any write by a party to a pending transfer cancels it: the owner's
--     veto (proof of life) and the claimant's choice to stay fresh.
-- begin/approve/abort deliberately do NOT route through here (they would
-- cancel the very grant they manage); they validate against the tables
-- directly.
-- ============================================================================
create or replace function community.require_profile()
returns community.reporter_profiles
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
  profile community.reporter_profiles;
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '28000';
  end if;

  if exists (select 1 from community.superseded_identities where old_user_id = uid) then
    raise exception 'identity_superseded' using errcode = '28000';
  end if;

  insert into community.reporter_profiles (user_id)
  values (uid)
  on conflict (user_id) do update set user_id = excluded.user_id
  returning * into profile;

  perform community.cancel_pending_transfer_if_any();

  return profile;
end;
$$;

-- ============================================================================
-- begin_identity_transfer — the export side, on the owner's device.
-- Creates an ARMED grant and returns the code exactly once. The owner's
-- standing is untouched: an armed grant changes nothing until claimed.
-- ============================================================================
create or replace function community.begin_identity_transfer()
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
  profile community.reporter_profiles;
  code text;
  code_hash text;
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '28000';
  end if;

  -- A displaced uid has nothing to transfer and must start fresh instead.
  if exists (select 1 from community.superseded_identities where old_user_id = uid) then
    raise exception 'identity_superseded' using errcode = '28000';
  end if;

  -- No profile means no community identity yet: a fresh uid on the new
  -- device is equivalent, so there is nothing worth carrying over.
  select * into profile from community.reporter_profiles where user_id = uid;
  if not found then
    return jsonb_build_object('ok', false, 'code', 'nothing_to_transfer');
  end if;

  -- Retire what the clock killed before the one-live-grant check below can
  -- be blocked by a dead grant.
  perform community.retire_stale_grants(uid);

  if exists (
    select 1 from community.reporter_transfer_grants
    where owner_user_id = uid and state in ('armed', 'pending_claim')
  ) then
    return jsonb_build_object('ok', false, 'code', 'transfer_already_pending');
  end if;

  if not community.check_rate_limit('transfer', 3, interval '1 day') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited');
  end if;

  -- 256 random bits, base64url without padding: 43 characters of file-safe
  -- text. Only the hash is stored; the code exists in this response alone.
  code := replace(translate(encode(gen_random_bytes(32), 'base64'), '+/', '-_'), '=', '');
  code_hash := encode(sha256(convert_to(code, 'UTF8')), 'hex');

  insert into community.reporter_transfer_grants (owner_user_id, code_hash)
  values (uid, code_hash);

  perform community.record_abuse_event('transfer');

  return jsonb_build_object(
    'ok', true,
    'code', code,
    'expires_at', now() + community.transfer_armed_ttl(),
    'server_now', now()
  );
end;
$$;

-- ============================================================================
-- claim_reporting_identity — the restore side, on the claimant's device.
--
-- Idempotent for the claimant (re-calling reports status; after the veto
-- deadline it finalizes) and the single point where the transfer completes.
-- A different second claimant is refused: first claim wins, and the owner
-- still holds the veto for the full window.
-- ============================================================================
create or replace function community.claim_reporting_identity(
  p_transfer_code text
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
  g community.reporter_transfer_grants%rowtype;
  deadline timestamptz;
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '28000';
  end if;

  if p_transfer_code is null
     or char_length(p_transfer_code) not between 40 and 128
     or p_transfer_code ~ '[^A-Za-z0-9_-]' then
    return jsonb_build_object('ok', false, 'code', 'invalid_code');
  end if;

  select * into g
  from community.reporter_transfer_grants
  where code_hash = encode(sha256(convert_to(p_transfer_code, 'UTF8')), 'hex');
  if not found then
    return jsonb_build_object('ok', false, 'code', 'transfer_unknown');
  end if;

  if g.state = 'completed' then
    return jsonb_build_object('ok', false, 'code', 'transfer_used');
  end if;

  if g.state = 'aborted' then
    return jsonb_build_object('ok', false, 'code', 'transfer_aborted');
  end if;

  if g.state = 'armed' then
    -- An unclaimed code past its TTL is dead.
    if g.created_at + community.transfer_armed_ttl() < now() then
      update community.reporter_transfer_grants
      set state = 'aborted', aborted_at = now(), abort_reason = 'expired'
      where id = g.id and state = 'armed';
      return jsonb_build_object('ok', false, 'code', 'transfer_expired');
    end if;

    -- The owner consuming their own code kills the grant: the documented way
    -- to neutralise a stolen .atid file from the legitimate device.
    if g.owner_user_id = uid then
      update community.reporter_transfer_grants
      set state = 'aborted', aborted_at = now(), abort_reason = 'owner_self_claim'
      where id = g.id and state = 'armed';
      return jsonb_build_object('ok', false, 'code', 'transfer_self');
    end if;

    -- A displaced uid is not a fresh claimant: it must start fresh first.
    if exists (select 1 from community.superseded_identities where old_user_id = uid) then
      return jsonb_build_object('ok', false, 'code', 'identity_not_fresh');
    end if;

    -- Identities never merge: the claimant must hold no community history.
    -- (Every identity-keyed table foreign-keys to reporter_profiles, so the
    -- profile row alone is the complete test.)
    if exists (select 1 from community.reporter_profiles where user_id = uid) then
      return jsonb_build_object('ok', false, 'code', 'identity_not_fresh');
    end if;

    -- The claim: armed -> pending_claim, guarded so a racing claim (or the
    -- owner's abort) loses cleanly.
    update community.reporter_transfer_grants
    set state = 'pending_claim',
        claimed_by = uid,
        claimed_at = now(),
        claim_deadline = now() + community.transfer_veto_window()
    where id = g.id and state = 'armed'
    returning claim_deadline into deadline;
    if not found then
      -- Lost the race. Answer from the grant's actual state.
      select * into g from community.reporter_transfer_grants where id = g.id;
      if g.state = 'pending_claim' and g.claimed_by = uid then
        return jsonb_build_object('ok', true, 'state', 'pending_claim',
                                  'claim_deadline', g.claim_deadline, 'server_now', now());
      end if;
      return jsonb_build_object('ok', false, 'code', 'transfer_in_progress');
    end if;

    return jsonb_build_object('ok', true, 'state', 'pending_claim',
                              'claim_deadline', deadline, 'server_now', now());
  end if;

  -- state = 'pending_claim'
  -- The owner re-claiming their own pending grant kills it (changed their
  -- mind after handing the file over, or neutralising a leak).
  if g.owner_user_id = uid then
    update community.reporter_transfer_grants
    set state = 'aborted', aborted_at = now(), abort_reason = 'owner_self_claim'
    where id = g.id and state = 'pending_claim';
    return jsonb_build_object('ok', false, 'code', 'transfer_self');
  end if;

  if g.claimed_by is distinct from uid then
    -- First claimant wins; a competing copy of the file changes nothing.
    return jsonb_build_object('ok', false, 'code', 'transfer_in_progress');
  end if;

  -- This claimant's own claim. Stale (nobody finalized within the grace)?
  if g.claim_deadline + community.transfer_veto_window() < now() then
    update community.reporter_transfer_grants
    set state = 'aborted', aborted_at = now(), abort_reason = 'expired'
    where id = g.id and state = 'pending_claim';
    return jsonb_build_object('ok', false, 'code', 'transfer_expired');
  end if;

  -- Veto window elapsed, or the owner approved: complete the transfer.
  if g.owner_approved_at is not null or g.claim_deadline <= now() then
    return community.finalize_transfer(g.id);
  end if;

  return jsonb_build_object('ok', true, 'state', 'pending_claim',
                            'claim_deadline', g.claim_deadline, 'server_now', now());
end;
$$;

-- ============================================================================
-- approve_identity_transfer — the owner's explicit consent, from the device
-- that still holds the identity. Short-circuits the veto window: the
-- transfer completes in this very call, atomically. (The lost-device case
-- cannot use this path by definition — it waits out the window instead.)
-- ============================================================================
create or replace function community.approve_identity_transfer()
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
  g community.reporter_transfer_grants%rowtype;
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '28000';
  end if;

  perform community.retire_stale_grants(uid);

  select * into g
  from community.reporter_transfer_grants
  where owner_user_id = uid and state = 'pending_claim';
  if not found then
    return jsonb_build_object('ok', false, 'code', 'no_pending_transfer');
  end if;

  update community.reporter_transfer_grants
  set owner_approved_at = now()
  where id = g.id and state = 'pending_claim'
  returning * into g;
  if not found then
    -- A racing abort or finalize won.
    return jsonb_build_object('ok', false, 'code', 'no_pending_transfer');
  end if;

  return community.finalize_transfer(g.id);
end;
$$;

-- ============================================================================
-- abort_identity_transfer — the owner's Keep-my-identity button. Kills the
-- live grant (armed or pending); the veto made explicit.
-- ============================================================================
create or replace function community.abort_identity_transfer()
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not_authenticated' using errcode = '28000';
  end if;

  perform community.retire_stale_grants(uid);

  update community.reporter_transfer_grants
  set state = 'aborted', aborted_at = now(), abort_reason = 'owner_aborted'
  where owner_user_id = uid
    and state in ('armed', 'pending_claim');
  if not found then
    return jsonb_build_object('ok', false, 'code', 'no_pending_transfer');
  end if;

  return jsonb_build_object('ok', true, 'state', 'aborted', 'server_now', now());
end;
$$;

-- ============================================================================
-- my_pending_reports — gains the two flags the owner's app needs to warn
-- proactively: someone has a pending claim on this identity (transfer_pending)
-- and this identity was moved away (superseded). Additive: older clients
-- ignore unknown fields.
-- ============================================================================
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
    ), '[]'::jsonb),
    'transfer_pending', exists (
      select 1 from community.reporter_transfer_grants
      where owner_user_id = auth.uid() and state = 'pending_claim'),
    'superseded', exists (
      select 1 from community.superseded_identities
      where old_user_id = auth.uid())
  );
end;
$$;

-- ============================================================================
-- Grants: only the four client-facing RPCs are callable by `authenticated`;
-- every helper is revoked from every client role (including public — new
-- functions grant EXECUTE to public by default).
-- ============================================================================

revoke execute on function community.begin_identity_transfer() from public, anon;
revoke execute on function community.claim_reporting_identity(text) from public, anon;
revoke execute on function community.approve_identity_transfer() from public, anon;
revoke execute on function community.abort_identity_transfer() from public, anon;

grant execute on function community.begin_identity_transfer() to authenticated;
grant execute on function community.claim_reporting_identity(text) to authenticated;
grant execute on function community.approve_identity_transfer() to authenticated;
grant execute on function community.abort_identity_transfer() to authenticated;

revoke execute on function community.finalize_transfer(uuid) from public, anon, authenticated;
revoke execute on function community.cancel_pending_transfer_if_any() from public, anon, authenticated;
revoke execute on function community.retire_stale_grants(uuid) from public, anon, authenticated;
revoke execute on function community.transfer_veto_window() from public, anon, authenticated;
revoke execute on function community.transfer_armed_ttl() from public, anon, authenticated;

-- ============================================================================
-- app_meta: the recorded policy, for the client to render honest copy.
-- ============================================================================
insert into community.app_meta (key, value)
values (
  'transfer_policy',
  jsonb_build_object(
    'armed_ttl_hours', 72,
    'veto_window_hours', 24,
    'begin_per_day', 3
  )
)
on conflict (key) do update set value = excluded.value;
