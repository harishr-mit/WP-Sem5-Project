package com.mitpass.event.web;

import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.service.EventSearchCriteria;
import com.mitpass.event.service.EventService;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventSummaryResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Create, edit, search and delete events")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @GetMapping
    @Operation(
            summary = "Search events",
            description = "All filters are optional and combine with AND. Sort by any event field, e.g. "
                    + "`sort=startsAt,desc`. Page size is capped at 100.")
    public PageResponse<EventSummaryResponse> search(
            @Valid @ParameterObject EventSearchCriteria criteria,
            @ParameterObject @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
        return events.search(criteria, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one event with its venue, category and sessions")
    public EventDetailResponse get(@PathVariable long id) {
        return events.get(id);
    }

    @PostMapping
    @Operation(summary = "Create an event (starts in DRAFT), optionally with its sessions")
    public ResponseEntity<EventDetailResponse> create(@Valid @RequestBody EventCreateRequest request) {
        EventDetailResponse created = events.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace an event's fields (not its sessions or status)")
    public EventDetailResponse update(@PathVariable long id, @Valid @RequestBody EventUpdateRequest request) {
        return events.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a DRAFT event (any other state: 409)")
    public void delete(@PathVariable long id) {
        events.delete(id);
    }
}
