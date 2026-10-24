-- Re-issued passes (RL10, D17): REVOKED and EXPIRED stay final, so bringing an event back issues a new pass for the
-- same registration. "One pass per registration" becomes "one valid pass per registration"; existing rows already
-- satisfy it (each registration has one pass).
ALTER TABLE pass DROP CONSTRAINT uk_pass_registration;
CREATE UNIQUE INDEX uk_pass_registration_valid ON pass (registration_id) WHERE status IN ('ISSUED', 'ACTIVE');
CREATE INDEX ix_pass_registration ON pass (registration_id);
