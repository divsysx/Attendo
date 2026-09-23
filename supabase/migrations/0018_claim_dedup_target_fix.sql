-- 0018_claim_dedup_target_fix.sql
-- Fix: 0013 rebuilt obs_dedup_unique for the withdrawal-frees-the-slot change
-- but dropped 0007's coalesce(target_start_hour, -1) from the key. A future
-- claim about a LATER slot of the same room therefore collided with an earlier
-- claim filed in the same hour — duplicate_report for a genuinely different
-- claim, breaking 0007's contract ("the target hour is part of a claim's
-- identity"). Found 2026-09-11 by future_claims_test's dedup scenario (its
-- day branch; the evening branch skips the assertion, which is how the
-- regression slipped past the previous green run).
--
-- The index keeps BOTH concerns: 0013's partiality (a withdrawn row frees its
-- dedup slot) and 0007's target column (a different target slot is its own
-- claim).
-- ============================================================================

drop index if exists community.obs_dedup_unique;

create unique index obs_dedup_unique
  on community.observations (
    reporter_id, kind, room,
    coalesce(section, ''),
    coalesce(subject, ''),
    coalesce(class_date, '1900-01-01'::date),
    coalesce(start_hour, -1),
    coalesce(payload->>'new_room', ''),
    coalesce(target_start_hour, -1),
    date_trunc('hour', dedup_hour))
  where status <> 'withdrawn';
