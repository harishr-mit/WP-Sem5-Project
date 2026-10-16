-- Reference DDL for idempotent consumers (docs/DECISIONS.md D13).
-- Each consuming service copies this into one of its own Flyway migrations (never edit an applied migration).
-- common-messaging's tests run this exact file, so it stays in step with IdempotentConsumer.

CREATE TABLE processed_message (
    consumer     VARCHAR(60) NOT NULL,   -- e.g. pass-service; one service may run several consumers
    message_id   UUID        NOT NULL,   -- the envelope's messageId (= the producer's outbox id)
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
CREATE INDEX ix_processed_message_at ON processed_message (processed_at);
