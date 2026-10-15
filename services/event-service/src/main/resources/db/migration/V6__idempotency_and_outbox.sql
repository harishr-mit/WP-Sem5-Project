-- Phase 3 (docs/plans/phase-3.md §6): Idempotency-Key records and the transactional outbox.

-- One row per (user, key). Written in the same transaction as the registration it guards (docs/DECISIONS.md D10),
-- so a crash can't lose the stored response and two concurrent retries can't both register.
CREATE TABLE idempotency_record (
    user_id         UUID         NOT NULL,
    idem_key        VARCHAR(80)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status SMALLINT,
    response_body   TEXT,
    location        VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, idem_key)
);
CREATE INDEX ix_idempotency_expires ON idempotency_record (expires_at);

-- Copied from libs/common-messaging: src/main/resources/mitpass/messaging/outbox-postgresql.sql (D11).
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
