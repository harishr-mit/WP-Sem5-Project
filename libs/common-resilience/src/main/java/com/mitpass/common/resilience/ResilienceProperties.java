package com.mitpass.common.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.resilience.*}: one circuit breaker per target service (D17). Over the last {@code sliding-window-size}
 * calls (at least {@code minimum-calls}), a failure rate of {@code failure-rate-threshold} % opens it; it stays open for
 * {@code wait-in-open-state}, then lets a few calls through to test the target again.
 */
@ConfigurationProperties("mitpass.resilience")
public record ResilienceProperties(
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumCalls,
        @DefaultValue("10s") Duration waitInOpenState,
        @DefaultValue("true") boolean retryGets) {}
