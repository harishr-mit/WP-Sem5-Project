package com.mitpass.event.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.events.scheduler.*}: the time-based lifecycle job.
 *
 * @param enabled run the timer (off in tests, which call the job directly)
 * @param interval pause between runs (read by {@code @Scheduled} from the same key)
 * @param doorsOpenLead how long before {@code startsAt} the event goes LIVE, so early arrivals can check in
 */
@ConfigurationProperties("mitpass.events.scheduler")
public record LifecycleSchedulerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("30m") Duration doorsOpenLead) {}
