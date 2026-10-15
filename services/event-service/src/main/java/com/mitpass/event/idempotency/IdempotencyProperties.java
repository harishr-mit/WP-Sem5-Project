package com.mitpass.event.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.idempotency.*}.
 *
 * @param ttl how long a key replays its first result
 * @param cleanupInterval how often expired keys are deleted
 */
@ConfigurationProperties("mitpass.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("1h") Duration cleanupInterval) {}
