package com.mitpass.notification.web;

import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.notification.client.EventDirectory;
import com.mitpass.notification.client.EventDirectory.EventInfo;
import com.mitpass.notification.config.NotificationProperties;
import com.mitpass.notification.service.IcsBuilder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Add to calendar" (PROJECT.md §2, D16): the same invite the emails carry. Public, like the catalogue. */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Calendar", description = "Calendar invites (.ics) for published events")
public class CalendarController {

    private final EventDirectory events;
    private final IcsBuilder ics;
    private final NotificationProperties properties;

    public CalendarController(EventDirectory events, IcsBuilder ics, NotificationProperties properties) {
        this.events = events;
        this.ics = ics;
        this.properties = properties;
    }

    @GetMapping("/events/{id}/calendar.ics")
    @Operation(summary = "The event as an iCalendar file (404 for an unknown or unpublished event)")
    public ResponseEntity<String> calendar(@PathVariable long id) {
        EventInfo event = events.event(id).orElseThrow(() -> new NotFoundException("No event " + id));
        boolean cancelled = "CANCELLED".equals(event.status());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(IcsBuilder.CONTENT_TYPE))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(IcsBuilder.fileName(event))
                                .build()
                                .toString())
                .body(ics.build(event, properties.portalUrl() + "/events/" + id, cancelled));
    }
}
