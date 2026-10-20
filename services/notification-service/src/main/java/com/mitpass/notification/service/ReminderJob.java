package com.mitpass.notification.service;

import com.mitpass.notification.client.EventDirectory;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.client.EventDirectory.Recipient;
import com.mitpass.notification.config.NotificationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reminders 24 h and 1 h before an event (PROJECT.md §P7). For each offset, an event is due when
 * {@code startsAt - offset} falls within the last {@code window}; it stays due for a few runs, and the
 * {@code reminder:<offset>:<registrationId>} key sends each reminder once. {@link ReminderScheduler} runs it under
 * ShedLock, so several instances never run it at the same time.
 */
@Service
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);
    private static final Set<String> UPCOMING = Set.of("PUBLISHED", "REGISTRATION_OPEN", "REGISTRATION_CLOSED", "LIVE");

    private final EventDirectory events;
    private final Notifications notifications;
    private final NotificationProperties properties;
    private final Clock clock;

    public ReminderJob(
            EventDirectory events, Notifications notifications, NotificationProperties properties, Clock clock) {
        this.events = events;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return how many reminders went out now */
    public int runOnce() {
        Instant now = clock.instant();
        Duration window = properties.reminders().window();
        int sent = 0;
        for (Duration offset : properties.reminders().offsets()) {
            Instant from = now.minus(window).plus(offset);
            Instant to = now.plus(offset);
            for (long id : events.startingBetween(from, to.plusMillis(1))) {
                EventInfo event = events.event(id).orElse(null);
                if (event == null || !UPCOMING.contains(event.status())) {
                    continue;
                }
                for (Recipient recipient : events.registrations(id, "CONFIRMED")) {
                    try {
                        sent += notifications.remind(event, offset, recipient) ? 1 : 0;
                    } catch (RuntimeException e) {
                        // one bad address or SMTP hiccup must not stop the others; the next run retries it
                        log.warn("Reminder for registration {} failed: {}", recipient.id(), e.getMessage());
                    }
                }
            }
        }
        return sent;
    }
}
