package com.mitpass.notification.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.notifications.*} (docs/plans/phase-7.md §11).
 *
 * @param from the sender of every email
 * @param portalUrl links in emails point here (the wallet, the event page)
 * @param timeZone times in emails are shown in this zone
 * @param catalogCacheTtl event details read from event-service are kept this long
 * @param reminders the 24 h / 1 h reminder job
 * @param serviceClient this service's identity for SERVICE reads (D7)
 */
@ConfigurationProperties("mitpass.notifications")
public record NotificationProperties(
        @DefaultValue("MITPass <no-reply@mitpass.local>") String from,
        @DefaultValue("http://localhost:8090") String portalUrl,
        @DefaultValue("Asia/Kolkata") ZoneId timeZone,
        @DefaultValue("60s") Duration catalogCacheTtl,
        @DefaultValue Reminders reminders,
        @DefaultValue ServiceClient serviceClient) {

    /**
     * @param enabled run the job at all (tests call it themselves)
     * @param interval pause between runs
     * @param offsets how long before the start each reminder goes out
     * @param window an event is due when {@code startsAt - offset} falls in the last window (a few runs)
     */
    public record Reminders(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1m") Duration interval,
            @DefaultValue({"24h", "1h"}) List<Duration> offsets,
            @DefaultValue("5m") Duration window) {}

    public record ServiceClient(
            @DefaultValue("notification-service") String id,
            @DefaultValue("dev-only-notification-secret") String secret) {

        @Override
        public String toString() {
            return "ServiceClient[id=" + id + "]";
        }
    }
}
