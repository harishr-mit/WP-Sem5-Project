package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * The message body every MITPass domain event travels in (docs/DECISIONS.md D11), as {@code OutboxPublisher} writes
 * it. Consumers read it with {@code EnvelopeReader}; {@code payload} is the contract record.
 */
public record EventEnvelope<T>(
        UUID messageId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String correlationId,
        T payload) {}
