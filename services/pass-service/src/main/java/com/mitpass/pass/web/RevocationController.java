package com.mitpass.pass.web;

import com.mitpass.pass.service.PassQueryService;
import com.mitpass.pass.web.dto.PassDtos.RevocationFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/revocations")
@Validated
@Tag(name = "Revocations", description = "Delta feed of revoked passes for scanners")
public class RevocationController {

    private final PassQueryService queries;

    public RevocationController(PassQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('VOLUNTEER', 'ORGANIZER', 'ADMIN')")
    @Operation(
            summary = "Revocations after a cursor (VOLUNTEER, ORGANIZER, ADMIN)",
            description = "Start with `since=0`, then pass the returned `nextSince`. A cursor, not a time, so "
                    + "scanner clocks don't matter and nothing is missed.")
    public RevocationFeed revocations(
            @RequestParam(defaultValue = "0") @Min(0) long since,
            @RequestParam(required = false) Long eventId,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit) {
        return queries.revocations(since, eventId, limit);
    }
}
