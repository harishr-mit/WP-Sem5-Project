package com.mitpass.checkin.service;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.service.LiveStats.LiveChanged;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes the event's whole {@link LiveStats.LiveSnapshot} to {@code /topic/events/{id}} after a change, at most once
 * per {@code throttle}: changes within that time fold into one push. A whole snapshot (not a delta) means a lost or
 * doubled frame never leaves the dashboard wrong.
 */
@Component
public class LiveFeedPublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveFeedPublisher.class);

    private final LiveStats live;
    private final EventCatalog catalog;
    private final SimpMessagingTemplate broker;
    private final long throttleMillis;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "live-feed");
        thread.setDaemon(true);
        return thread;
    });

    public LiveFeedPublisher(
            LiveStats live, EventCatalog catalog, SimpMessagingTemplate broker, CheckinProperties properties) {
        this.live = live;
        this.catalog = catalog;
        this.broker = broker;
        this.throttleMillis = properties.live().throttle().toMillis();
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    @EventListener
    public void changed(LiveChanged change) {
        if (pending.add(change.eventId())) {
            timer.schedule(() -> push(change.eventId()), throttleMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void push(long eventId) {
        pending.remove(eventId); // first, so a change during the push schedules the next one
        try {
            broker.convertAndSend(
                    "/topic/events/" + eventId,
                    live.snapshot(eventId, catalog.event(eventId).capacity()));
        } catch (RuntimeException e) {
            log.warn("Live snapshot for event {} not pushed: {}", eventId, e.getMessage());
        }
    }
}
