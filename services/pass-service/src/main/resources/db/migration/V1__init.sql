-- pass-service schema (docs/plans/phase-4.md §6). Database pass_db, owner pass_svc (infra/postgres/init.sql).

-- Event facts pass-service needs for its own state (unlock, expiry, saga), kept up to date from domain events.
-- Display data (venue, colour, static-QR switch, organizer) is read from event-service on demand instead.
CREATE TABLE event_ref (
    event_id            BIGINT PRIMARY KEY,
    code                VARCHAR(16)  NOT NULL,
    title               VARCHAR(160) NOT NULL,
    starts_at           TIMESTAMPTZ  NOT NULL,
    ends_at             TIMESTAMPTZ  NOT NULL,
    status              VARCHAR(24),                      -- last EventStateChanged.toStatus; null until one arrives
    status_changed_at   TIMESTAMPTZ,                      -- reordering guard: older state changes are ignored
    schedule_changed_at TIMESTAMPTZ,                      -- same for EventRescheduled
    last_pass_seq       INTEGER      NOT NULL DEFAULT 0,  -- per-event pass number sequence
    updated_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE pass (
    id              UUID PRIMARY KEY,
    pass_number     VARCHAR(32)  NOT NULL,
    registration_id UUID         NOT NULL,
    event_id        BIGINT       NOT NULL REFERENCES event_ref (event_id),
    user_id         UUID         NOT NULL,
    holder_name     VARCHAR(120) NOT NULL,
    holder_email    VARCHAR(254) NOT NULL,
    status          VARCHAR(8)   NOT NULL,
    issued_at       TIMESTAMPTZ  NOT NULL,
    activated_at    TIMESTAMPTZ,
    expired_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoked_by      VARCHAR(64),
    revoke_reason   VARCHAR(24),
    revoke_note     VARCHAR(200),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(64)  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(64)  NOT NULL,
    CONSTRAINT uk_pass_number UNIQUE (pass_number),
    -- One pass per registration: backs up the idempotent consumer (D13)
    CONSTRAINT uk_pass_registration UNIQUE (registration_id),
    CONSTRAINT ck_pass_status CHECK (status IN ('ISSUED', 'ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_pass_revoke_reason CHECK (
        revoke_reason IS NULL OR revoke_reason IN ('ORGANIZER', 'ADMIN', 'REGISTRATION_CANCELLED', 'EVENT_CANCELLED')),
    CONSTRAINT ck_pass_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX ix_pass_user ON pass (user_id, issued_at);
CREATE INDEX ix_pass_event_status ON pass (event_id, status);

CREATE TABLE pass_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pass_id     UUID        NOT NULL REFERENCES pass (id) ON DELETE CASCADE,
    from_status VARCHAR(8),
    to_status   VARCHAR(8)  NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL,
    changed_by  VARCHAR(64) NOT NULL,
    trigger     VARCHAR(16) NOT NULL,
    reason      VARCHAR(200),
    CONSTRAINT ck_pass_history_trigger CHECK (trigger IN ('MANUAL', 'SCHEDULER', 'SYSTEM'))
);
CREATE INDEX ix_pass_history_pass ON pass_history (pass_id, changed_at);

-- The scanners' delta feed: GET /api/revocations?since=<seq>
CREATE TABLE revocation (
    seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pass_id    UUID        NOT NULL REFERENCES pass (id),
    event_id   BIGINT      NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL,
    reason     VARCHAR(24) NOT NULL,
    CONSTRAINT uk_revocation_pass UNIQUE (pass_id)
);
CREATE INDEX ix_revocation_event ON revocation (event_id, seq);

-- A registration cancelled before its RegistrationConfirmed arrived (redelivery out of order): never issue a pass.
CREATE TABLE cancelled_registration (
    registration_id UUID PRIMARY KEY,
    cancelled_at    TIMESTAMPTZ NOT NULL
);

-- ES256 keys for pass tokens (D12), separate from auth-service keys. Private half encrypted (KeyEncryptor).
CREATE TABLE signing_key (
    kid             VARCHAR(48) PRIMARY KEY,
    algorithm       VARCHAR(8)  NOT NULL,
    public_jwk      TEXT        NOT NULL,
    private_key_enc TEXT        NOT NULL,
    status          VARCHAR(8)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    retired_at      TIMESTAMPTZ,
    max_token_exp   TIMESTAMPTZ,      -- latest exp this key signed; a retired key stays published until then
    CONSTRAINT ck_signing_key_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);
CREATE UNIQUE INDEX uk_signing_key_one_active ON signing_key (status) WHERE status = 'ACTIVE';

-- Idempotent consumer (copied from common-messaging: mitpass/messaging/processed-message-postgresql.sql)
CREATE TABLE processed_message (
    consumer     VARCHAR(60) NOT NULL,
    message_id   UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
CREATE INDEX ix_processed_message_at ON processed_message (processed_at);

-- Transactional outbox (copied from common-messaging: mitpass/messaging/outbox-postgresql.sql)
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
