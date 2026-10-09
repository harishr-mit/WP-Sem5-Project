package com.mitpass.event.web;

import com.mitpass.event.service.SessionService;
import com.mitpass.event.web.dto.SessionRequest;
import com.mitpass.event.web.dto.SessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
@RequestMapping("/api/events/{eventId}/sessions")
@Tag(name = "Sessions", description = "Sessions of a (multi-session) event; ids stay stable across edits")
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "List an event's sessions in start order")
    public List<SessionResponse> list(@PathVariable long eventId) {
        return sessions.list(eventId);
    }

    @PostMapping
    @Operation(summary = "Add a session (must lie within the event)")
    public ResponseEntity<SessionResponse> add(@PathVariable long eventId, @Valid @RequestBody SessionRequest request) {
        SessionResponse created = sessions.add(eventId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{sessionId}")
    @Operation(summary = "Edit a session in place")
    public SessionResponse update(
            @PathVariable long eventId, @PathVariable long sessionId, @Valid @RequestBody SessionRequest request) {
        return sessions.update(eventId, sessionId, request);
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a session")
    public void delete(@PathVariable long eventId, @PathVariable long sessionId) {
        sessions.delete(eventId, sessionId);
    }
}
