-- ============================================================================
-- Attendo TEST-ONLY — DELETE ONE USER
-- ============================================================================
--
-- Deletes exactly one Auth user (by uid or email) and that user's Community
-- + Attendance Sync rows. Other users are left alone.
--
-- If you only wanted to look:     scripts/inspect-test-data.sql
-- If you wanted everyone gone:    scripts/reset-all-test-data.sql
--
-- THIS FILE IS NOT A MIGRATION. Never `supabase db push` it.
-- Permission: postgres / Dashboard SQL Editor (project owner).
-- Do NOT run against a project that holds real students.
--
-- How to run:
--   1. Run inspect-test-data.sql. Copy the uid from the per-user sketch.
--   2. Paste the uid BETWEEN the single quotes on target_uid below.
--      Quotes are required. Do not leave the all-zeros placeholder.
--      To target by email instead, set target_uid back to the zeros
--      placeholder and set target_email := 'the@email'.
--   3. Paste this whole file into SQL Editor and Run ONCE.
--      The Results tab must show status = DELETED and leftover counts at 0.
--      "Success. No rows returned" used to mean the script only printed a
--      NOTICE (invisible in Results) and deleted nothing. That is fixed.
--   4. Run inspect-test-data.sql. That uid must be gone; other users remain.
--
-- Preview without deleting: set dry_run := true. Results will say DRY RUN.
--
-- Aborts with nothing deleted when:
--   * the uid is still the all-zeros placeholder and email is null
--   * both uid and email are set
--   * the uid is missing from auth.users
--   * the email matches 0 rows, or more than 1 row (refuses to guess)
-- ============================================================================

DROP TABLE IF EXISTS attendo_delete_one_result;
CREATE TEMP TABLE attendo_delete_one_result (
  status text,
  uid uuid,
  email text,
  leftover jsonb
);

DO $$
DECLARE
  -- EDIT: paste the uid BETWEEN the quotes. Do not remove the quotes.
  target_uid   uuid := '00000000-0000-0000-0000-000000000000';
  target_email text := NULL;     -- or: 'you@users.noreply.github.com'

  -- Armed. Set true only if you want a preview and no deletes.
  dry_run      boolean := false;

  resolved_id    uuid;
  resolved_email text;
  n              int;
  owned_grants   bigint;
  claimed_grants bigint;
  leftover       jsonb;
BEGIN
  -- The all-zeros placeholder in the file is "not set", not a real uid.
  IF target_uid = '00000000-0000-0000-0000-000000000000'::uuid THEN
    target_uid := NULL;
  END IF;

  IF target_uid IS NOT NULL AND target_email IS NOT NULL THEN
    RAISE EXCEPTION
      'Refusing: set exactly one of target_uid or target_email, not both. Nothing deleted.';
  END IF;
  IF target_uid IS NULL AND target_email IS NULL THEN
    RAISE EXCEPTION
      'Refusing: paste a uid between the quotes on target_uid (or set target_email). Nothing deleted.';
  END IF;

  IF target_uid IS NOT NULL THEN
    SELECT u.id, u.email
      INTO resolved_id, resolved_email
    FROM auth.users u
    WHERE u.id = target_uid;
    IF NOT FOUND THEN
      RAISE EXCEPTION
        'No auth.users row for uid %. Nothing deleted.', target_uid;
    END IF;
  ELSE
    SELECT count(*) INTO n
    FROM auth.users u
    WHERE lower(u.email) = lower(target_email);

    IF n = 0 THEN
      RAISE EXCEPTION
        'No auth.users row for email %. Nothing deleted.', target_email;
    END IF;
    IF n > 1 THEN
      RAISE EXCEPTION
        'Email % matches % auth.users rows; refusing to guess. Nothing deleted.',
        target_email, n;
    END IF;

    SELECT u.id, u.email
      INTO resolved_id, resolved_email
    FROM auth.users u
    WHERE lower(u.email) = lower(target_email);
  END IF;

  SELECT count(*) INTO owned_grants
  FROM community.reporter_transfer_grants
  WHERE owner_user_id = resolved_id;

  SELECT count(*) INTO claimed_grants
  FROM community.reporter_transfer_grants
  WHERE claimed_by = resolved_id;

  leftover := jsonb_build_object(
    'id', resolved_id,
    'email', resolved_email,
    'identities', (SELECT count(*) FROM auth.identities i WHERE i.user_id = resolved_id),
    'reporter_profiles', (SELECT count(*) FROM community.reporter_profiles WHERE user_id = resolved_id),
    'observations', (SELECT count(*) FROM community.observations WHERE reporter_id = resolved_id),
    'verifications', (SELECT count(*) FROM community.verifications WHERE user_id = resolved_id),
    'polls', (SELECT count(*) FROM community.polls WHERE creator_id = resolved_id),
    'poll_options (via own polls)', (
      SELECT count(*) FROM community.poll_options o
      JOIN community.polls p ON p.id = o.poll_id
      WHERE p.creator_id = resolved_id),
    'poll_votes', (SELECT count(*) FROM community.poll_votes WHERE user_id = resolved_id),
    'abuse_events', (SELECT count(*) FROM community.abuse_events WHERE user_id = resolved_id),
    'grants_owned', owned_grants,
    'grants_as_claimant (will SET NULL, not deleted)', claimed_grants,
    'superseded_identities', (SELECT count(*) FROM community.superseded_identities WHERE old_user_id = resolved_id),
    'attendance.accounts', (SELECT count(*) FROM attendance.accounts WHERE user_id = resolved_id),
    'attendance.semesters', (SELECT count(*) FROM attendance.semesters WHERE user_id = resolved_id),
    'attendance.courses', (SELECT count(*) FROM attendance.courses WHERE user_id = resolved_id),
    'attendance.patterns', (SELECT count(*) FROM attendance.patterns WHERE user_id = resolved_id),
    'attendance.sessions', (SELECT count(*) FROM attendance.sessions WHERE user_id = resolved_id),
    'other_auth_users (must survive)', (SELECT count(*) FROM auth.users WHERE id <> resolved_id)
  );

  IF dry_run THEN
    INSERT INTO attendo_delete_one_result (status, uid, email, leftover)
    VALUES ('DRY RUN — nothing deleted', resolved_id, resolved_email, leftover);
    RETURN;
  END IF;

  -- Grants this user OWNS: delete first so a stale .atid of this uid finds
  -- nothing. Grants this user only claimed stay; claimed_by FK SET NULLs them.
  DELETE FROM community.reporter_transfer_grants
  WHERE owner_user_id = resolved_id;

  DELETE FROM auth.users WHERE id = resolved_id;

  -- Defence in depth, scoped to this uid only. Others' votes/options on this
  -- user's polls, and others' verifications on this user's reports, go with
  -- the parent row — those are this identity's community residue, not the
  -- other user's identity.
  DELETE FROM community.superseded_identities WHERE old_user_id = resolved_id;
  DELETE FROM community.abuse_events WHERE user_id = resolved_id;
  DELETE FROM community.poll_votes WHERE user_id = resolved_id
     OR poll_id IN (SELECT id FROM community.polls WHERE creator_id = resolved_id);
  DELETE FROM community.poll_options
    WHERE poll_id IN (SELECT id FROM community.polls WHERE creator_id = resolved_id);
  DELETE FROM community.polls WHERE creator_id = resolved_id;
  DELETE FROM community.verifications WHERE user_id = resolved_id
     OR observation_id IN (SELECT id FROM community.observations WHERE reporter_id = resolved_id);
  DELETE FROM community.observations WHERE reporter_id = resolved_id;
  DELETE FROM community.reporter_profiles WHERE user_id = resolved_id;
  DELETE FROM attendance.sessions WHERE user_id = resolved_id;
  DELETE FROM attendance.patterns WHERE user_id = resolved_id;
  DELETE FROM attendance.courses WHERE user_id = resolved_id;
  DELETE FROM attendance.semesters WHERE user_id = resolved_id;
  DELETE FROM attendance.accounts WHERE user_id = resolved_id;

  leftover := jsonb_build_object(
    'auth.users', (SELECT count(*) FROM auth.users WHERE id = resolved_id),
    'auth.identities', (SELECT count(*) FROM auth.identities WHERE user_id = resolved_id),
    'community.reporter_profiles', (SELECT count(*) FROM community.reporter_profiles WHERE user_id = resolved_id),
    'community.observations', (SELECT count(*) FROM community.observations WHERE reporter_id = resolved_id),
    'community.verifications', (SELECT count(*) FROM community.verifications WHERE user_id = resolved_id),
    'community.polls', (SELECT count(*) FROM community.polls WHERE creator_id = resolved_id),
    'community.poll_options', (
      SELECT count(*) FROM community.poll_options o
      JOIN community.polls p ON p.id = o.poll_id
      WHERE p.creator_id = resolved_id),
    'community.poll_votes', (SELECT count(*) FROM community.poll_votes WHERE user_id = resolved_id),
    'community.abuse_events', (SELECT count(*) FROM community.abuse_events WHERE user_id = resolved_id),
    'community.reporter_transfer_grants_owned', (SELECT count(*) FROM community.reporter_transfer_grants WHERE owner_user_id = resolved_id),
    'community.reporter_transfer_grants_as_claimant', (SELECT count(*) FROM community.reporter_transfer_grants WHERE claimed_by = resolved_id),
    'community.superseded_identities', (SELECT count(*) FROM community.superseded_identities WHERE old_user_id = resolved_id),
    'attendance.accounts', (SELECT count(*) FROM attendance.accounts WHERE user_id = resolved_id),
    'attendance.semesters', (SELECT count(*) FROM attendance.semesters WHERE user_id = resolved_id),
    'attendance.courses', (SELECT count(*) FROM attendance.courses WHERE user_id = resolved_id),
    'attendance.patterns', (SELECT count(*) FROM attendance.patterns WHERE user_id = resolved_id),
    'attendance.sessions', (SELECT count(*) FROM attendance.sessions WHERE user_id = resolved_id),
    'other_auth_users (must survive)', (SELECT count(*) FROM auth.users WHERE id <> resolved_id)
  );

  IF (leftover ->> 'auth.users')::bigint <> 0
     OR (leftover ->> 'auth.identities')::bigint <> 0
     OR (leftover ->> 'community.reporter_profiles')::bigint <> 0
     OR (leftover ->> 'community.observations')::bigint <> 0
     OR (leftover ->> 'community.verifications')::bigint <> 0
     OR (leftover ->> 'community.polls')::bigint <> 0
     OR (leftover ->> 'community.poll_options')::bigint <> 0
     OR (leftover ->> 'community.poll_votes')::bigint <> 0
     OR (leftover ->> 'community.abuse_events')::bigint <> 0
     OR (leftover ->> 'community.reporter_transfer_grants_owned')::bigint <> 0
     OR (leftover ->> 'community.reporter_transfer_grants_as_claimant')::bigint <> 0
     OR (leftover ->> 'community.superseded_identities')::bigint <> 0
     OR (leftover ->> 'attendance.accounts')::bigint <> 0
     OR (leftover ->> 'attendance.semesters')::bigint <> 0
     OR (leftover ->> 'attendance.courses')::bigint <> 0
     OR (leftover ->> 'attendance.patterns')::bigint <> 0
     OR (leftover ->> 'attendance.sessions')::bigint <> 0 THEN
    RAISE EXCEPTION
      'DELETE ONE USER incomplete for %, rolling back. leftover=%',
      resolved_id, leftover;
  END IF;

  INSERT INTO attendo_delete_one_result (status, uid, email, leftover)
  VALUES ('DELETED', resolved_id, resolved_email, leftover);
END
$$;

-- This SELECT is what the Dashboard Results tab shows. status must be DELETED.
select status, uid, email, leftover
from attendo_delete_one_result;
