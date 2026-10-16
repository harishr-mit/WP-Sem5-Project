-- Per-event check-in settings (docs/plans/phase-5.md §6, docs/DECISIONS.md D14). checkin-service reads them through
-- GET /api/events/{id}; event-service owns them.

-- May an attendee who checked out come back in during the same session? Off by default: single entry.
ALTER TABLE event ADD COLUMN reentry_allowed BOOLEAN NOT NULL DEFAULT FALSE;

-- Sessions an attendee must attend for a certificate; NULL = all of them (1 for an event without sessions).
ALTER TABLE event ADD COLUMN certificate_min_sessions SMALLINT;
ALTER TABLE event ADD CONSTRAINT ck_event_certificate_min_sessions
    CHECK (certificate_min_sessions IS NULL OR certificate_min_sessions > 0);
