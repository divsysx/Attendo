-- 0010_poll_parity.sql
-- Part 9: polls parity for the self-actioning rule (Part 5's own-report rule).
-- A poll's creator must not cast the poll's first vote — the same trust-seeding
-- attack the observation side blocks with verify's own_report check, and
-- Part 5's requirement that the block exist in the UI AND the server RPC.
--
-- Small and auditable: a copy of 0003's cast_vote with the one guard added,
-- plus the CREATE OR REPLACE re-creation of nothing else. (0009's grant
-- statement for cast_vote still applies — CREATE OR REPLACE of a function
-- keeps its existing grants, so no re-grant is needed.)
--
-- Semantics: the creator may not vote in their own poll at all. Their voice
-- was already heard — they chose the question.

-- ---------------------------------------------------------------------------
-- cast_vote — with the own-poll guard.
-- ---------------------------------------------------------------------------
create or replace function community.cast_vote(
  p_poll_id uuid,
  p_option_index smallint
)
returns jsonb
language plpgsql
security definer
set search_path = community, public, extensions
as $$
declare
  profile community.reporter_profiles;
  poll community.polls;
begin
  profile := community.require_profile();

  select * into poll from community.polls p where p.id = p_poll_id;
  if poll.id is null then
    return jsonb_build_object('ok', false, 'code', 'not_found');
  end if;

  -- Server clock decides openness, never the client's.
  if poll.status <> 'open' or poll.closes_at <= now() then
    perform community.close_poll_if_due(p_poll_id);
    return jsonb_build_object('ok', false, 'code', 'poll_closed');
  end if;

  -- The own-poll rule (Part 9 / Part 5 parity): the creator cannot vote in
  -- their own poll. Without this the creator seeds the first vote and every
  -- displayed count starts biased — the poll equivalent of verifying your
  -- own report.
  if poll.creator_id = profile.user_id then
    return jsonb_build_object('ok', false, 'code', 'own_poll');
  end if;

  if p_option_index is null or not exists (
    select 1 from community.poll_options
    where poll_id = p_poll_id and option_index = p_option_index) then
    return jsonb_build_object('ok', false, 'code', 'invalid_option');
  end if;

  if not community.check_rate_limit('vote', 60, interval '1 hour') then
    return jsonb_build_object('ok', false, 'code', 'rate_limited_short');
  end if;

  begin
    insert into community.poll_votes (poll_id, user_id, option_index)
    values (p_poll_id, profile.user_id, p_option_index);
  exception
    when unique_violation then
      return jsonb_build_object('ok', true, 'code', 'already_voted');
  end;

  perform community.record_abuse_event('vote');

  return jsonb_build_object('ok', true, 'server_now', now());
end;
$$;
