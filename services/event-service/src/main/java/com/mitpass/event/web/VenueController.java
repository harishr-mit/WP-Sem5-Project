package com.mitpass.event.web;

import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.service.VenueService;
import com.mitpass.event.web.dto.VenueRequest;
import com.mitpass.event.web.dto.VenueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/venues")
@Tag(name = "Venues")
public class VenueController {

    private final VenueService venues;

    public VenueController(VenueService venues) {
        this.venues = venues;
    }

    @GetMapping
    @Operation(summary = "List venues")
    public PageResponse<VenueResponse> list(
            @ParameterObject @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return venues.list(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one venue")
    public VenueResponse get(@PathVariable long id) {
        return venues.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @Operation(summary = "Create a venue (ORGANIZER, ADMIN; names are unique, ignoring case)")
    public ResponseEntity<VenueResponse> create(@Valid @RequestBody VenueRequest request) {
        VenueResponse created = venues.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a venue's fields (ADMIN)")
    public VenueResponse update(@PathVariable long id, @Valid @RequestBody VenueRequest request) {
        return venues.update(id, request);
    }
}
