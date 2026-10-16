-- Per-event pass settings (docs/plans/phase-4.md §6, docs/DECISIONS.md D12). pass-service reads them through
-- GET /api/events/{id}; event-service owns them.

-- May pass-service hand out a long-lived (static) QR for the PDF ticket and email? The live code works either way.
ALTER TABLE event ADD COLUMN static_qr_allowed BOOLEAN NOT NULL DEFAULT TRUE;

-- Colour of the QR's dark modules and the ticket header; null = MITPass maroon. Contrast is checked by the API.
ALTER TABLE event ADD COLUMN pass_color VARCHAR(7);
ALTER TABLE event ADD CONSTRAINT ck_event_pass_color CHECK (pass_color IS NULL OR pass_color ~ '^#[0-9A-F]{6}$');
