-- checkin-service (docs/plans/phase-5.md §6). Owns scans, attendance and presence. pass_ref and event_ref are
-- read-only copies of pass-service's and event-service's data (D14): filled from their events and by the reconciler,
-- never edited by hand. If a copy and its owner disagree, the owner wins.

-- Event status, kept from EventStateChanged (newest changed_at wins) and refreshed by the reconciler.
CREATE TABLE event_ref (
    event_id          BIGINT PRIMARY KEY,
    code              VARCHAR(16) NOT NULL,
    status            VARCHAR(24),
    status_changed_at TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL
);

-- Passes, kept from PassIssued / PassRevoked and the reconciler. A revocation can arrive before the pass itself
-- (reordering, or the revocation feed), so number and holder may be missing until the pass's details arrive.
-- REVOKED never turns back into VALID (pass-service has no such move either).
CREATE TABLE pass_ref (
    pass_id         UUID PRIMARY KEY,
    pass_number     VARCHAR(32) UNIQUE,
    event_id        BIGINT       NOT NULL,
    registration_id UUID,
    user_id         UUID,
    holder_name     VARCHAR(120),
    holder_email    VARCHAR(254),
    status          VARCHAR(8)   NOT NULL,
    issued_at       TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(24),
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_pass_ref_status CHECK (status IN ('VALID', 'REVOKED'))
);
CREATE INDEX ix_pass_ref_event ON pass_ref (event_id, status);
CREATE INDEX ix_pass_ref_user ON pass_ref (user_id);

-- One row per pass and session that was checked in. session_id 0 = the whole event (no sessions); never NULL, so the
-- unique constraint also holds for events without sessions (NULLs would not collide).
CREATE TABLE attendance (
    id            UUID PRIMARY KEY,
    pass_id       UUID         NOT NULL,
    event_id      BIGINT       NOT NULL,
    session_id    BIGINT       NOT NULL,
    gate_id       BIGINT       NOT NULL,
    gate_code     VARCHAR(16)  NOT NULL,
    volunteer_id  UUID         NOT NULL,
    method        VARCHAR(12)  NOT NULL,
    checked_in_at TIMESTAMPTZ  NOT NULL,
    scanned_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_attendance_pass_session UNIQUE (pass_id, session_id),
    CONSTRAINT ck_attendance_method CHECK (method IN ('QR_LIVE', 'QR_STATIC', 'MANUAL'))
);
CREATE INDEX ix_attendance_event ON attendance (event_id, session_id);

-- Where the attendee is now (PROJECT.md §3 Presence). The check-in transaction locks this row.
CREATE TABLE presence (
    pass_id    UUID PRIMARY KEY,
    event_id   BIGINT      NOT NULL,
    state      VARCHAR(12) NOT NULL,
    session_id BIGINT      NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL,
    CONSTRAINT ck_presence_state CHECK (state IN ('NOT_ARRIVED', 'IN', 'OUT'))
);
CREATE INDEX ix_presence_event ON presence (event_id, state);

CREATE TABLE presence_history (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pass_id    UUID        NOT NULL REFERENCES presence (pass_id),
    from_state VARCHAR(12) NOT NULL,
    to_state   VARCHAR(12) NOT NULL,
    session_id BIGINT      NOT NULL,
    gate_id    BIGINT      NOT NULL,
    scan_id    UUID        NOT NULL,
    actor      VARCHAR(64) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_presence_history_pass ON presence_history (pass_id, id);

-- Every scan and lookup, accepted or not: the audit trail. scanned_at is the device clock (P7 offline merge),
-- received_at the server's.
CREATE TABLE scan_log (
    id           UUID PRIMARY KEY,
    received_at  TIMESTAMPTZ  NOT NULL,
    scanned_at   TIMESTAMPTZ  NOT NULL,
    volunteer_id UUID         NOT NULL,
    device_id    VARCHAR(64)  NOT NULL,
    gate_id      BIGINT       NOT NULL,
    event_id     BIGINT,
    pass_id      UUID,
    pass_number  VARCHAR(32),
    token_jti    VARCHAR(64),
    method       VARCHAR(12)  NOT NULL,
    mode         VARCHAR(8)   NOT NULL,
    result       VARCHAR(24)  NOT NULL,
    session_id   BIGINT,
    detail       VARCHAR(200),
    CONSTRAINT ck_scan_log_mode CHECK (mode IN ('IN', 'OUT', 'LOOKUP'))
);
CREATE INDEX ix_scan_log_event ON scan_log (event_id, received_at);
CREATE INDEX ix_scan_log_pass ON scan_log (pass_id);

-- Cursors of the reconciler's jobs (docs/plans/phase-5.md §4a), e.g. the revocation feed position.
CREATE TABLE sync_cursor (
    name       VARCHAR(40) PRIMARY KEY,
    position   BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- From common-messaging's reference DDL (D13): idempotent consumer.
CREATE TABLE processed_message (
    consumer     VARCHAR(60) NOT NULL,
    message_id   UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
CREATE INDEX ix_processed_message_at ON processed_message (processed_at);

-- From common-messaging's reference DDL (D11): transactional outbox (AttendanceMarked).
CREATE TABLE outbox_message (
    id             UUID PRIMARY KEY,
    seq            BIGINT GENERATED ALWAYS AS IDENTITY,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);
CREATE INDEX ix_outbox_unpublished ON outbox_message (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published ON outbox_message (published_at) WHERE published_at IS NOT NULL;
