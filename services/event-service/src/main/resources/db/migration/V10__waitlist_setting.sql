-- Per-event waitlist (RL1, D17), off by default. Events that already have someone waiting keep it on, so nobody
-- on a waitlist is stranded by the migration.
ALTER TABLE event ADD COLUMN waitlist_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE event SET waitlist_enabled = TRUE
WHERE id IN (SELECT event_id FROM registration WHERE status = 'WAITLISTED');
