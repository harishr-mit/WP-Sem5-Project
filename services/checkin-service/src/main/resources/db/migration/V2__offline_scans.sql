-- Offline scans synced later (docs/plans/phase-7.md §8, D16). local_id is the phone's own id for a queued scan: a
-- resent batch finds its earlier answer instead of scanning twice. Online scans leave both columns at their defaults.
ALTER TABLE scan_log ADD COLUMN local_id VARCHAR(64);
ALTER TABLE scan_log ADD COLUMN offline BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX uk_scan_log_device_local ON scan_log (device_id, local_id) WHERE local_id IS NOT NULL;
