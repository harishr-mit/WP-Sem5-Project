-- Phase 5 (docs/plans/phase-5.md §6, owner Q2): checkin-service's AttendanceMarked is recorded on the pass, without a
-- new pass state. checked_in_at = the first check-in; used_at = set too when the event allows no re-entry.
ALTER TABLE pass ADD COLUMN checked_in_at TIMESTAMPTZ;
ALTER TABLE pass ADD COLUMN used_at TIMESTAMPTZ;
