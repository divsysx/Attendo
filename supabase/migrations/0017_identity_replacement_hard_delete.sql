-- 0017_identity_replacement_hard_delete.sql
-- Destination identity replacement: HARD DELETE (approved 2026-09-11).
--
-- Restoring Reporting Identity A onto a device that currently holds Reporting
-- Identity B, after the claimant's explicit destructive confirmation, deletes
-- B's ENTIRE server-side identity — real row deletion, not RLS hiding — and
-- moves A in, in the same transaction:
--
--   * claim_reporting_identity gains p_replace_claimant (default false). The
--     old refusal stands: a claimant holding a profile gets identity_not_fresh
--     UNLESS they passed the flag, which the client sets only after the
--     destructive confirmation dialog. A fresh claimant passing the flag gets
--     nothing_to_replace (replacement without an identity to replace).
--   * During the 24-hour pending window NOTHING is deleted — the window is
--     B's bail-out (owner veto, expiry, both leave B fully intact). Instead
--     B is FROZEN: require_profile raises identity_frozen for a pending
--     replacement claimant, so community writes can neither land (they would
--     be deleted at completion anyway) nor — with cancel_pending_transfer_if_any
--     taught to skip replacement grants on the claimant side — accidentally
--     cancel the move. Reads (status, reports, reputation) keep working.
--   * finalize_transfer performs the replacement atomically, after the guarded
--     pending_claim -> completed UPDATE (the serialization point), in the same
--     transaction as the identity move:
--       2a. every grant the claimant ever owned is deleted (a stale .atid of
--           B must find nothing to claim — approved: B's historical grant
--           audit trail is intentionally removed), then
--       2b. ONE delete of B's reporter_profiles row; the schema's own cascade
--           graph removes every identity-owned row (see below), then
--       3-7. the unchanged move: A's profile under B's uid, history re-parented,
--           A's profile deleted, A's uid tombstoned, 'transfer' event recorded.
--     Any failure anywhere rolls back the deletion together with the move:
--     B's server data and the pending-transfer semantics survive intact.
--
-- The complete hard-delete list for B (every table with an identity-owned
-- column; each of 2-8 is an ON DELETE CASCADE off the 2b profile delete):
--   reporter_profiles (direct)            — reputation/profile state
--   observations  (reporter_id)           — every report B filed
--   verifications (user_id)               — every verdict B cast
--   verifications (observation_id via 2)  — others' verdicts on B's reports
--   polls         (creator_id)            — every poll B asked
--   poll_options  (poll_id via polls)     — their options
--   poll_votes    (poll_id via polls)     — ALL votes on B's polls, others' included
--   poll_votes    (user_id)               — B's votes on anyone's polls
--   abuse_events  (user_id)               — B's rate-limit/transfer history
--   reporter_transfer_grants (direct, 2a) — B's export grants, all states
-- NOT deleted: activity_pulses and app_meta (global, not identity-owned);
-- B's auth.users row (the destination device keeps its session — it now holds
-- A's identity); no tombstone for B (its uid was replaced ONTO, not displaced).
--
-- Concurrency additions:
--   * one_pending_claim_per_claimant: a uid can hold at most one live
--     pending_claim as claimant — first claim wins in both directions (a
--     second .atid cannot be claimed by a device mid-claim either).
--   * claim_reporting_identity answers transfer_in_progress for a uid that
--     already holds a pending claim, and converts the index's unique
--     violation (same uid racing two codes) into the same honest envelope.
--   * retire_stale_grants now retires by owner OR claimant, and
--     my_pending_reports calls it: a replacement claimant whose window lapsed
--     is unfrozen by the next status poll without needing the owner to act.
-- ============================================================================

-- ============================================================================
-- Storage: the flag rides the grant, so the completed row is itself the audit
-- record that this transfer replaced a claimant identity (an abuse_event
-- cannot record it — B's profile, the FK target, is gone by then).
-- ============================================================================
alter table community.reporter_transfer_grants
  add column replace_claimant boolean not null default false;

-- One live pending claim per claimant (partial, so history is unlimited).
create unique index one_pending_claim_per_claimant
  on community.reporter_transfer_grants (claimed_by)
  where state = 'pending_claim' and claimed_by is not null;

-- ============================================================================
-- retire_stale_grants: now retires grants the caller is party to as OWNER or
-- as CLAIMANT. The claimant side matters for the freeze: without it, a
-- replacement claimant whose window lapsed (owner absent, claim never
-- finalized) would stay frozen forever with no action of their own to clear it.
-- (The parameter name stays p_owner for CREATE OR REPLACE compatibility;
-- it means "either party".)
-- ============================================================================
create or replace function community.retire_stale_grants(p_owner uuid)
returns void
language sql
security definer
set search_path = community, public, extensions
as $$
  update community.reporter_transfer_grants
  set state = 'aborted', aborted_at = now(), abort_reason = 'expired'
  where (owner_user_id = p_owner or claimed_by = p_owner)
    and (
      (state = 'armed' and created_at + community.transfer_armed_ttl() < now())
      or (state = 'pending_claim'
          and claim_deadline + community.transfer_veto_window() < now())
    );
$$;

-- ============================================================================
-- cancel_pending_transfer_if_any: the owner's veto is unchanged (proof of
-- life beats a pending claim, replacement or not). The claimant side — the
-- "chose to stay fresh" twin — must NOT fire for replacement claims: a
-- replacement claimant is frozen rather than cancelling, and this guard keeps
-- that true even for a write path that somehow bypassed the freeze.
-- ============================================================================
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
    and (
      owner_user_id = auth.uid()
      or (claimed_by = auth.uid() and not replace_claimant)
    );
$$;

-- ============================================================================
-- finalize_transfer: the atomic transfer, now with the replacement step.
-- Statement order keeps every FK satisfied: the claimant's identity (if being
-- replaced) dies BEFORE the owner's profile is inserted under the claimant's
-- uid, and the move itself is unchanged. There is still no instant at which
-- both uids own the history: the grant's guarded UPDATE serialises competing
-- finalizes, and the deletion + move commit or roll back together.
-- ============================================================================
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

  -- Defense in depth (unreachable by construction: the only way an owner's
  -- profile disappears is a replacement, and that same replacement deletes
  -- the owner's grants, this one included). If a future schema change ever
  -- lets the owner's identity vanish while a claim is live, abort honestly
  -- rather than "complete" an empty transfer.
  if not exists (select 1 from community.reporter_profiles where user_id = owner_id) then
    update community.reporter_transfer_grants
    set state = 'aborted', aborted_at = now(), abort_reason = 'owner_gone'
    where id = p_grant_id and state = 'completed';
    return jsonb_build_object('ok', false, 'code', 'transfer_unknown');
  end if;

  if g.replace_claimant then
    -- 2a. Every grant the claimant ever owned dies with the identity: no
    --     stale .atid of B survives the replacement (approved — B's
    --     historical grant audit trail is intentionally removed).
    delete from community.reporter_transfer_grants where owner_user_id = claimer;
    -- 2b. ONE delete; the cascade graph removes every identity-owned row
    --     (the header's hard-delete list). Actual row deletion — storage is
    --     freed, nothing is hidden behind RLS.
    delete from community.reporter_profiles where user_id = claimer;
  elsif exists (select 1 from community.reporter_profiles where user_id = claimer) then
    -- Freshness backstop for NON-replacement claims: the claimant must still
    -- hold no community history. (Replacement claims hold one by design.)
    update community.reporter_transfer_grants
    set state = 'aborted', aborted_at = now(), abort_reason = 'claimant_not_fresh'
    where id = p_grant_id and state = 'completed';
    return jsonb_build_object('ok', false, 'code', 'identity_not_fresh');
  end if;

  -- 3. The profile moves, counters and all: reputation travels.
  insert into community.reporter_profiles (
    user_id, created_at, trust_score, report_count,
    corroborated_count, disputed_count, restricted_until
  )
  -- The new row carries the CLAIMER's uid; the counters are the owner's.
  select claimer, created_at, trust_score, report_count,
         corroborated_count, disputed_count, restricted_until
  from community.reporter_profiles
  where user_id = owner_id;

  -- 4. Every identity-keyed row re-parents to the claimant.
  update community.observations set reporter_id = claimer where reporter_id = owner_id;
  update community.polls       set creator_id = claimer where creator_id = owner_id;
  update community.verifications set user_id   = claimer where user_id   = owner_id;
  update community.poll_votes    set user_id   = claimer where user_id   = owner_id;
  update community.abuse_events  set user_id   = claimer where user_id   = owner_id;

  -- 5. The old profile goes; nothing references it anymore.
  delete from community.reporter_profiles where user_id = owner_id;

  -- 6. The old uid is tombstoned: its writes raise identity_superseded.
  --    (The claimant's uid is NOT tombstoned — it was replaced onto, not
  --    displaced; it now legitimately holds the moved identity.)
  insert into community.superseded_identities (old_user_id)
  values (owner_id)
  on conflict (old_user_id) do nothing;

  -- 7. Observability: the transfer counts as one 'transfer' event, recorded
  -- for the claimant (whose fresh profile row this transaction just created).
  insert into community.abuse_events (user_id, action) values (claimer, 'transfer');

  return jsonb_build_object('ok', true, 'state', 'completed',
                            'replaced', g.replace_claimant, 'server_now', now());
end;
$$;

-- ============================================================================
-- require_profile — gains the freeze: a claimant mid-replacement has their
-- identity scheduled for deletion, so their community writes are refused
-- (identity_frozen) rather than either landing rows that completion would
-- hard-delete anyway or cancelling the move. Reads never pass through here.
-- Reads (my_pending_reports, my_reputation, my_polls) keep working so the
-- frozen device can still see its data and its transfer status.
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

  if exists (
    select 1 from community.reporter_transfer_grants
    where claimed_by = uid and state = 'pending_claim' and replace_claimant
  ) then
    raise exception 'identity_frozen' using errcode = '28000';
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
-- claim_reporting_identity — new signature (the old single-argument function
-- is dropped and replaced; CREATE OR REPLACE cannot change a signature).
-- p_replace_claimant defaults to false, so existing request bodies behave
-- exactly as before. The replacement flag is recorded on the grant at first
-- claim and governs finalize; a re-claim cannot alter it.
-- ============================================================================
drop function community.claim_reporting_identity(text);

create or replace function community.claim_reporting_identity(
  p_transfer_code text,
  p_replace_claimant boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  uid uuid := auth.uid();
  replace boolean := coalesce(p_replace_claimant, false);
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

    -- A displaced uid is not a fresh claimant: it must start fresh first
    -- (even with p_replace_claimant — a tombstoned uid has nothing left to
    -- replace; its profile already moved away).
    if exists (select 1 from community.superseded_identities where old_user_id = uid) then
      return jsonb_build_object('ok', false, 'code', 'identity_not_fresh');
    end if;

    -- First claim wins in both directions: a uid already holding a pending
    -- claim (on any grant) cannot open a second one.
    if exists (
      select 1 from community.reporter_transfer_grants
      where claimed_by = uid and state = 'pending_claim'
    ) then
      return jsonb_build_object('ok', false, 'code', 'transfer_in_progress');
    end if;

    if exists (select 1 from community.reporter_profiles where user_id = uid) then
      -- Identities never merge. The claimant's existing identity can only be
      -- REPLACED (hard-deleted at finalize), and only when they explicitly
      -- said so — p_replace_claimant, which the client sets solely after the
      -- destructive confirmation dialog.
      if not replace then
        return jsonb_build_object('ok', false, 'code', 'identity_not_fresh');
      end if;
    elsif replace then
      -- Replacement with nothing to replace: a fresh device should import
      -- normally (the confirmation dialog never appears for it).
      return jsonb_build_object('ok', false, 'code', 'nothing_to_replace');
    end if;

    -- The claim: armed -> pending_claim, guarded so a racing claim (or the
    -- owner's abort) loses cleanly. The unique index one_pending_claim_per_
    -- claimant turns the same-uid-two-codes race into an honest envelope.
    begin
      update community.reporter_transfer_grants
      set state = 'pending_claim',
          claimed_by = uid,
          claimed_at = now(),
          claim_deadline = now() + community.transfer_veto_window(),
          replace_claimant = replace
      where id = g.id and state = 'armed'
      returning claim_deadline into deadline;
    exception when unique_violation then
      return jsonb_build_object('ok', false, 'code', 'transfer_in_progress');
    end;
    if not found then
      -- Lost the race. Answer from the grant's actual state.
      select * into g from community.reporter_transfer_grants where id = g.id;
      if g.state = 'pending_claim' and g.claimed_by = uid then
        return jsonb_build_object('ok', true, 'state', 'pending_claim',
                                  'claim_deadline', g.claim_deadline,
                                  'replace_claimant', g.replace_claimant,
                                  'server_now', now());
      end if;
      return jsonb_build_object('ok', false, 'code', 'transfer_in_progress');
    end if;

    return jsonb_build_object('ok', true, 'state', 'pending_claim',
                              'claim_deadline', deadline,
                              'replace_claimant', replace,
                              'server_now', now());
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
  -- (For a replacement claim this is the moment the claimant's identity is
  -- hard-deleted; p_replace_claimant here is ignored — the grant's recorded
  -- flag governs.)
  if g.owner_approved_at is not null or g.claim_deadline <= now() then
    return community.finalize_transfer(g.id);
  end if;

  return jsonb_build_object('ok', true, 'state', 'pending_claim',
                            'claim_deadline', g.claim_deadline,
                            'replace_claimant', g.replace_claimant,
                            'server_now', now());
end;
$$;

-- ============================================================================
-- my_pending_reports — gains the claimant-side flags the destination device
-- needs: claim_pending (this uid holds a pending claim) and
-- claim_replaces_identity (that claim is a replacement, so community actions
-- are frozen server-side). Also retires this party's clock-killed grants so
-- a lapsed replacement claim unfreezes on the next status poll. Additive:
-- older clients ignore unknown fields.
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

  perform community.retire_stale_grants(auth.uid());

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
      where old_user_id = auth.uid()),
    'claim_pending', exists (
      select 1 from community.reporter_transfer_grants
      where claimed_by = auth.uid() and state = 'pending_claim'),
    'claim_replaces_identity', exists (
      select 1 from community.reporter_transfer_grants
      where claimed_by = auth.uid() and state = 'pending_claim' and replace_claimant)
  );
end;
$$;

-- ============================================================================
-- Grants: the client RPC's new signature replaces the old; every helper stays
-- revoked from every client role.
-- ============================================================================

revoke execute on function community.claim_reporting_identity(text, boolean) from public, anon;
grant execute on function community.claim_reporting_identity(text, boolean) to authenticated;

revoke execute on function community.begin_identity_transfer() from public, anon;
revoke execute on function community.approve_identity_transfer() from public, anon;
revoke execute on function community.abort_identity_transfer() from public, anon;

grant execute on function community.begin_identity_transfer() to authenticated;
grant execute on function community.approve_identity_transfer() to authenticated;
grant execute on function community.abort_identity_transfer() to authenticated;

revoke execute on function community.finalize_transfer(uuid) from public, anon, authenticated;
revoke execute on function community.cancel_pending_transfer_if_any() from public, anon, authenticated;
revoke execute on function community.retire_stale_grants(uuid) from public, anon, authenticated;
revoke execute on function community.transfer_veto_window() from public, anon, authenticated;
revoke execute on function community.transfer_armed_ttl() from public, anon, authenticated;

-- ============================================================================
-- app_meta: the recorded policy, for the client to render honest copy.
-- supports_replacement lets the app feature-detect (an old server never shows
-- the destructive path).
-- ============================================================================
insert into community.app_meta (key, value)
values (
  'transfer_policy',
  jsonb_build_object(
    'armed_ttl_hours', 72,
    'veto_window_hours', 24,
    'begin_per_day', 3,
    'supports_replacement', true
  )
)
on conflict (key) do update set value = excluded.value;
