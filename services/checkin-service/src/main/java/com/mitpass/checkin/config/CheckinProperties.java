package com.mitpass.checkin.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code mitpass.checkin.*} (docs/plans/phase-5.md §12). */
@ConfigurationProperties("mitpass.checkin")
public record CheckinProperties(
        @DefaultValue("mitpass-pass") String passIssuer,
        @DefaultValue("10s") Duration tokenClockSkew,
        @DefaultValue("30m") Duration doorsOpenBeforeSession,
        @DefaultValue("30s") Duration assignmentCacheTtl,
        @DefaultValue("60s") Duration eventCacheTtl,
        @DefaultValue("30s") Duration jwksRefetchMinInterval,
        @DefaultValue("Asia/Kolkata") ZoneId timeZone,
        @DefaultValue Reconcile reconcile,
        @DefaultValue ServiceClient serviceClient) {

    /** The pass-copy reconciler (§4a). */
    public record Reconcile(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration revocationsInterval,
            @DefaultValue("5m") Duration windowInterval,
            @DefaultValue("3h") Duration windowAhead,
            @DefaultValue("100") int pageSize,
            @DefaultValue("30s") Duration onDemandMinInterval) {}

    /** This service's client id and secret for service tokens (§4b). */
    public record ServiceClient(
            @DefaultValue("checkin-service") String id,
            @DefaultValue("") String secret) {

        @Override
        public String toString() {
            return "ServiceClient[id=" + id + "]";
        }
    }
}
