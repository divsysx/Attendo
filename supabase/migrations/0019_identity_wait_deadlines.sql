-- ============================================================================
-- 0019: the wait's deadline, said to whoever is waiting it out.
--
-- my_pending_reports already tells each side THAT a move is waiting on it
-- (transfer_pending for the owner, claim_pending for the claimant, both from
-- 0015/0017), but not UNTIL WHEN — so both screens could only say "24 hours"
-- in the abstract, when the honest thing to show is the actual moment the
-- window ends, computed from the claim the way finalize_transfer itself does.
--
-- Two additive fields, both read from the same pending grant, both null when
-- no wait is live (jsonb_build_object keeps the key present but null, so a
-- client build that predates this cannot be confused by a missing shape):
--
--   * transfer_claim_deadline — for the owner: the grant's claim_deadline.
--     Until it, nothing moves without the owner's answer; past it, the
--     claimant can finalize alone (re-claiming completes the move), and the
--     grant itself is retired by retire_stale_grants only after a further
--     grace of the same length.
--   * claim_deadline — for the claimant: the same instant, seen from the
--     side that waits on the old phone's veto.
--
-- No table, policy, or behavior changes — the grants were already readable
-- only by these security-definer reads.
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
      where claimed_by = auth.uid() and state = 'pending_claim' and replace_claimant),
    'transfer_claim_deadline', (
      select claim_deadline from community.reporter_transfer_grants
      where owner_user_id = auth.uid() and state = 'pending_claim'
      order by claim_deadline desc
      limit 1),
    'claim_deadline', (
      select claim_deadline from community.reporter_transfer_grants
      where claimed_by = auth.uid() and state = 'pending_claim'
      order by claim_deadline desc
      limit 1)
  );
end;
$$;

comment on function community.my_pending_reports() is
  'The community home read: this identity''s pending reports, plus the transfer state flags (0015/0017) and each side''s pending-window deadline (0019).';
