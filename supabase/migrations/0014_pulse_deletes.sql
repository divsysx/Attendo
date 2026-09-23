-- 0014_pulse_deletes.sql
-- A wipe must reach the phones. The two-device verification on 2026-09-10
-- passed every live test, but the follow-up found the last silent case: the
-- admin wipe (delete from community.observations / polls via SQL Editor)
-- cleared the server while every open phone kept rendering the deleted
-- cards — deletes fired no pulse, so no refetch happened until some other
-- change or a screen re-entry.
--
-- 0012 pulsed INSERT and UPDATE only, on the reasoning that the only
-- deletions were retention's, which remove rows already invisible to every
-- subscriber. That reasoning held for retention and missed the admin wipe:
-- a wipe removes *visible* rows, and their disappearance is exactly the
-- news a subscriber needs. The honest rule is simpler than the exception
-- was: every change to a pulsed table is news, whatever the operation.
--
-- Cost of dropping the exception: retention's deletes now nudge clients
-- into one refetch they did not strictly need — the sweep runs on a
-- ten-minute drumbeat and the rows it removes are invisible anyway, so the
-- refetch returns the same list and nothing flickers. One cheap fetch per
-- sweep on open apps, against wipes that actually propagate. Worth it.
--
-- pulse_row() itself needs no change: it records only (table_name,
-- clock_timestamp()) and does not branch on TG_OP, so it serves DELETE the
-- same way it serves INSERT and UPDATE. Only the triggers change, and only
-- by adding the one word.

-- Drop-then-create for the same reason as 0012: CREATE TRIGGER has no IF
-- NOT EXISTS, and a rerun must not error on the first pass's work.
drop trigger if exists observations_pulse on community.observations;
create trigger observations_pulse
  after insert or update or delete on community.observations
  for each row execute function community.pulse_row();

drop trigger if exists polls_pulse on community.polls;
create trigger polls_pulse
  after insert or update or delete on community.polls
  for each row execute function community.pulse_row();
