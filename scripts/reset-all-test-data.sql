-- ============================================================================
-- Attendo TEST-ONLY — RESET EVERYTHING
-- ============================================================================
--
--  ██████╗  █████╗ ████████╗███████╗    ██████╗  █████╗ ████████╗███████╗
--  ██╔════╝ ██╔══██╗╚══██╔══╝██╔════╝    ██╔════╝ ██╔══██╗╚══██╔══╝██╔════╝
--  ██║  ███╗███████║   ██║   █████╗      ██║  ███╗███████║   ██║   █████╗
--  ██║   ██║██╔══██║   ██║   ██╔══╝      ██║   ██║██╔══██║   ██║   ██╔══╝
--  ╚██████╔╝██║  ██║   ██║   ███████╗    ╚██████╔╝██║  ██║   ██║   ███████╗
--   ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝     ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝
--
-- THIS FILE DELETES EVERY AUTH USER AND EVERY USER-OWNED ROW.
--
-- If you only wanted to look:          scripts/inspect-test-data.sql
-- If you only wanted one account gone: scripts/delete-one-test-user.sql
--
-- THIS FILE IS NOT A MIGRATION. Never `supabase db push` it.
-- Permission: postgres / Dashboard SQL Editor (project owner).
-- Do NOT run against a project that holds real students.
--
-- How to run:
--   1. Run inspect-test-data.sql first so you know what you are wiping.
--   2. Paste THIS file into SQL Editor and Run. It is already armed.
--      Messages tab: "RESET EVERYTHING starting" then "complete".
--   3. Run inspect-test-data.sql again. User-owned counts must be 0;
--      app_meta and activity_pulses must still be non-zero.
--
-- To dry-run / abort: change confirmation below back to '' and it will
-- raise P0001 and roll back, deleting nothing.
--
-- Deleted (auth.users CASCADE + grants first to close claimed_by SET NULL):
--   auth.users + GoTrue identities/sessions
--   community.reporter_profiles, observations, verifications,
--     polls, poll_options, poll_votes, abuse_events,
--     reporter_transfer_grants, superseded_identities
--   attendance.accounts, semesters, courses, patterns, sessions
--
-- Preserved:
--   community.app_meta, community.activity_pulses, attendance.change_seq,
--   schema / RLS / RPCs / cron, timetable/faculty/subjects (not in this DB)
--
-- Cannot delete from here (the phone): Room attendance, attendance_sync_owner,
-- outbox, leftover JWT. After this wipe, GitHub mints a new uid; local
-- attendance still names the old account until Clear All or SWITCH_APPROVED.
-- ============================================================================

DO $$
DECLARE
  -- Armed. Set this back to '' if you pasted the file by mistake.
  confirmation text := 'RESET EVERYTHING';
  users_before bigint;
  grants_before bigint;
  leftover jsonb;
BEGIN
  IF confirmation IS DISTINCT FROM 'RESET EVERYTHING' THEN
    RAISE EXCEPTION
      'Refusing RESET EVERYTHING: set confirmation := ''RESET EVERYTHING'' (got %). Transaction aborted, nothing deleted.',
      confirmation;
  END IF;

  SELECT count(*) INTO users_before FROM auth.users;
  SELECT count(*) INTO grants_before FROM community.reporter_transfer_grants;

  RAISE NOTICE 'RESET EVERYTHING starting. auth.users=% reporter_transfer_grants=%',
    users_before, grants_before;

  -- Close the claimed_by SET NULL hole before touching auth.users.
  DELETE FROM community.reporter_transfer_grants;

  -- Authoritative wipe: cascades attendance.* and the community identity graph
  -- (profiles → observations/verifications/polls/options/votes/abuse_events)
  -- and superseded_identities. GoTrue identities/sessions go with the user.
  DELETE FROM auth.users;

  -- Defence in depth. Never touch app_meta or activity_pulses.
  DELETE FROM community.superseded_identities;
  DELETE FROM community.abuse_events;
  DELETE FROM community.poll_votes;
  DELETE FROM community.poll_options;
  DELETE FROM community.polls;
  DELETE FROM community.verifications;
  DELETE FROM community.observations;
  DELETE FROM community.reporter_profiles;
  DELETE FROM attendance.sessions;
  DELETE FROM attendance.patterns;
  DELETE FROM attendance.courses;
  DELETE FROM attendance.semesters;
  DELETE FROM attendance.accounts;

  leftover := jsonb_build_object(
    'auth.users', (SELECT count(*) FROM auth.users),
    'auth.identities', (SELECT count(*) FROM auth.identities),
    'community.reporter_profiles', (SELECT count(*) FROM community.reporter_profiles),
    'community.observations', (SELECT count(*) FROM community.observations),
    'community.verifications', (SELECT count(*) FROM community.verifications),
    'community.polls', (SELECT count(*) FROM community.polls),
    'community.poll_options', (SELECT count(*) FROM community.poll_options),
    'community.poll_votes', (SELECT count(*) FROM community.poll_votes),
    'community.abuse_events', (SELECT count(*) FROM community.abuse_events),
    'community.reporter_transfer_grants', (SELECT count(*) FROM community.reporter_transfer_grants),
    'community.superseded_identities', (SELECT count(*) FROM community.superseded_identities),
    'attendance.accounts', (SELECT count(*) FROM attendance.accounts),
    'attendance.semesters', (SELECT count(*) FROM attendance.semesters),
    'attendance.courses', (SELECT count(*) FROM attendance.courses),
    'attendance.patterns', (SELECT count(*) FROM attendance.patterns),
    'attendance.sessions', (SELECT count(*) FROM attendance.sessions),
    'community.app_meta', (SELECT count(*) FROM community.app_meta),
    'community.activity_pulses', (SELECT count(*) FROM community.activity_pulses)
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
     OR (leftover ->> 'community.reporter_transfer_grants')::bigint <> 0
     OR (leftover ->> 'community.superseded_identities')::bigint <> 0
     OR (leftover ->> 'attendance.accounts')::bigint <> 0
     OR (leftover ->> 'attendance.semesters')::bigint <> 0
     OR (leftover ->> 'attendance.courses')::bigint <> 0
     OR (leftover ->> 'attendance.patterns')::bigint <> 0
     OR (leftover ->> 'attendance.sessions')::bigint <> 0 THEN
    RAISE EXCEPTION
      'RESET EVERYTHING incomplete, rolling back. leftover=%', leftover;
  END IF;

  IF (leftover ->> 'community.app_meta')::bigint = 0
     OR (leftover ->> 'community.activity_pulses')::bigint = 0 THEN
    RAISE EXCEPTION
      'RESET EVERYTHING refused to finish: preserved tables were emptied. leftover=%',
      leftover;
  END IF;

  RAISE NOTICE 'RESET EVERYTHING complete. leftover=%', leftover;
END
$$;
