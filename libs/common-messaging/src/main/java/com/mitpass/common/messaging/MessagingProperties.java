package com.mitpass.common.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.messaging.*} (docs/DECISIONS.md D11).
 *
 * @param exchange the topic exchange every MITPass domain event goes to
 * @param relay the outbox → RabbitMQ poller
 * @param tapQueue a dev-only queue bound to every event, so messages are visible before any consumer exists
 */
@ConfigurationProperties("mitpass.messaging")
public record MessagingProperties(
        @DefaultValue("mitpass.events") String exchange,
        @DefaultValue Relay relay,
        @DefaultValue TapQueue tapQueue) {

    /**
     * @param enabled create the relay at all (tests that don't test messaging switch it off)
     * @param autoStartup start polling with the application; tests that drive {@code pollOnce()} turn it off
     * @param pollInterval pause between polls
     * @param batchSize rows per poll
     * @param confirmTimeout how long to wait for the broker's publisher confirms
     * @param retention published rows older than this are deleted
     */
    public record Relay(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean autoStartup,
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("5s") Duration confirmTimeout,
            @DefaultValue("7d") Duration retention) {}

    public record TapQueue(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("mitpass.events.tap") String name,
            @DefaultValue("1000") int maxLength) {}
}
