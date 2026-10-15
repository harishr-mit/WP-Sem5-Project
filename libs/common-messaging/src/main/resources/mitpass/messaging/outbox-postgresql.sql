-- Reference DDL for the transactional outbox (docs/DECISIONS.md D11).
-- Each service copies this into one of its own Flyway migrations (never edit an applied migration; add a new one).
-- common-messaging's tests run this exact file, so it stays in step with OutboxPublisher / OutboxRelay.

CREATE TABLE outbox_message (
    id             UUID PRIMARY KEY,                       -- the AMQP messageId; consumers deduplicate on it
    seq            BIGINT GENERATED ALWAYS AS IDENTITY,    -- publish order (rows of one transaction share occurred_at)
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,                  -- also the routing key, e.g. registration.confirmed
    payload        JSONB        NOT NULL,                  -- the full envelope, sent as the message body
    headers        JSONB        NOT NULL,                  -- correlationId, userId
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,                            -- set after the broker confirmed the message
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);
CREATE INDEX ix_outbox_unpublished ON outbox_message (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published ON outbox_message (published_at) WHERE published_at IS NOT NULL;
