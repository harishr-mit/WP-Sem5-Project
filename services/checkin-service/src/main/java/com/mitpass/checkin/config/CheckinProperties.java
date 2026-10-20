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
        @DefaultValue ServiceClient serviceClient,
        @DefaultValue Live live,
        @DefaultValue Offline offline) {

    /**
     * Offline scans synced later (docs/plans/phase-7.md §8).
     *
     * @param maxAge a queued scan older than this is refused (owner Q12)
     * @param maxFuture a scan this far ahead of the server's clock is refused
     */
    public record Offline(
            @DefaultValue("12h") Duration maxAge,
            @DefaultValue("2m") Duration maxFuture) {}

    /**
     * The live dashboard (docs/plans/phase-7.md §4, §5).
     *
     * @param rebuildEvery the Redis counters are rebuilt from PostgreSQL at least this often (heals any drift)
     * @param recentSize scans in the ticker
     * @param bucket width of a bar in the arrivals chart
     * @param keyTtl Redis keys of an event expire this long after their last write
     * @param throttle at most one pushed snapshot per event this often
     */
    public record Live(
            @DefaultValue("60s") Duration rebuildEvery,
            @DefaultValue("20") int recentSize,
            @DefaultValue("5m") Duration bucket,
            @DefaultValue("2d") Duration keyTtl,
            @DefaultValue("500ms") Duration throttle) {}

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
