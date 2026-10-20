-- notification-service (docs/plans/phase-7.md, D16). Keeps no copy of other services' data: only what it sent.

-- Idempotent consumer (D13): copied from common-messaging's processed-message-postgresql.sql.
CREATE TABLE processed_message (
    consumer     VARCHAR(60) NOT NULL,
    message_id   UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
CREATE INDEX ix_processed_message_at ON processed_message (processed_at);

-- One row per email sent. dedup_key = <messageId>:<recipient> or reminder:<offset>:<registrationId>; claiming it
-- first is what makes a redelivery, a retried bulk mail or a second reminder run skip what already went out.
CREATE TABLE notification_log (
    id              BIGSERIAL    PRIMARY KEY,
    dedup_key       VARCHAR(200) NOT NULL,
    kind            VARCHAR(40)  NOT NULL,
    event_id        BIGINT,
    registration_id UUID,
    recipient       VARCHAR(320) NOT NULL,
    subject         VARCHAR(300) NOT NULL,
    sent_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_notification_log_dedup UNIQUE (dedup_key)
);
CREATE INDEX ix_notification_log_event ON notification_log (event_id);

-- ShedLock (JdbcTemplateLockProvider): the reminder job runs on one instance at a time.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
