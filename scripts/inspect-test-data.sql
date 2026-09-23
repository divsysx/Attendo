-- ============================================================================
-- Attendo TEST-ONLY — inspect (read-only)
-- ============================================================================
--
-- Safe to paste into the Dashboard SQL Editor and Run. No writes.
--
-- Sister files (destructive — do not run those if you only wanted counts):
--   scripts/reset-all-test-data.sql     wipe every Auth user + user-owned rows
--   scripts/delete-one-test-user.sql    wipe one uid or email
--
-- Permission: postgres / Dashboard SQL Editor. Not a migration.
-- After a full reset: every user-owned count is 0; app_meta and
-- activity_pulses must stay non-zero.
-- ============================================================================

select 'auth.users' as table_name, count(*)::bigint as n from auth.users
union all select 'auth.identities', count(*) from auth.identities
union all select 'community.reporter_profiles', count(*) from community.reporter_profiles
union all select 'community.observations', count(*) from community.observations
union all select 'community.verifications', count(*) from community.verifications
union all select 'community.polls', count(*) from community.polls
union all select 'community.poll_options', count(*) from community.poll_options
union all select 'community.poll_votes', count(*) from community.poll_votes
union all select 'community.abuse_events', count(*) from community.abuse_events
union all select 'community.reporter_transfer_grants', count(*) from community.reporter_transfer_grants
union all select 'community.superseded_identities', count(*) from community.superseded_identities
union all select 'attendance.accounts', count(*) from attendance.accounts
union all select 'attendance.semesters', count(*) from attendance.semesters
union all select 'attendance.courses', count(*) from attendance.courses
union all select 'attendance.patterns', count(*) from attendance.patterns
union all select 'attendance.sessions', count(*) from attendance.sessions
union all select 'community.app_meta (must survive)', count(*) from community.app_meta
union all select 'community.activity_pulses (must survive)', count(*) from community.activity_pulses
order by 1;

-- Preserved global rows: expect app_meta keys from 0001/0004/0015/0017 and
-- exactly two activity_pulses rows (observations, polls).
select key from community.app_meta order by key;
select table_name, at from community.activity_pulses order by table_name;

-- Per-user sketch. Copy a uid/email from here into delete-one-test-user.sql.
-- Email is null on anonymous users.
select
  u.id,
  u.email,
  u.created_at,
  u.last_sign_in_at,
  (select count(*) from auth.identities i where i.user_id = u.id) as identities,
  (select count(*) from community.reporter_profiles p where p.user_id = u.id) as profiles,
  (select count(*) from attendance.accounts a where a.user_id = u.id) as attendance_accounts
from auth.users u
order by u.created_at;
