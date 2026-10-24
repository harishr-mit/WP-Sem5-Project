-- Admin overrides (RL10, D17). An admin can un-cancel an event back to the state it was cancelled from, so the event
-- keeps that state; events cancelled before this migration get it from their history.
ALTER TABLE event ADD COLUMN cancelled_from VARCHAR(24);

UPDATE event e SET cancelled_from = (
    SELECT h.from_status FROM event_state_history h
    WHERE h.event_id = e.id AND h.to_status = 'CANCELLED'
    ORDER BY h.changed_at DESC LIMIT 1)
WHERE e.status = 'CANCELLED';

-- Admin moves are recorded as such in the history.
ALTER TABLE event_state_history DROP CONSTRAINT ck_history_trigger;
ALTER TABLE event_state_history
    ADD CONSTRAINT ck_history_trigger CHECK (trigger IN ('MANUAL', 'SCHEDULER', 'SYSTEM', 'ADMIN'));
