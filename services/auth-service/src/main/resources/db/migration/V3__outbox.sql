-- Phase 3 (docs/DECISIONS.md D11): transactional outbox for UserRegistered.
-- Copied from libs/common-messaging: src/main/resources/mitpass/messaging/outbox-postgresql.sql.
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
