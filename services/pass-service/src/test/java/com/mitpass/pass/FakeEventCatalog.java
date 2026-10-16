package com.mitpass.pass;

import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.pass.client.EventCatalogClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.client.RestClient;

/** event-service's event details, as the tests set them. No cache, no HTTP. */
public class FakeEventCatalog extends EventCatalogClient {

    private final Map<Long, EventInfo> events = new ConcurrentHashMap<>();

    public FakeEventCatalog() {
        super(RestClient.create(), Duration.ZERO, Clock.systemUTC());
    }

    public void put(EventInfo event) {
        events.put(event.id(), event);
    }

    @Override
    public EventInfo get(long eventId) {
        EventInfo event = events.get(eventId);
        if (event == null) {
            throw NotFoundException.of("Event", eventId);
        }
        return event;
    }
}
