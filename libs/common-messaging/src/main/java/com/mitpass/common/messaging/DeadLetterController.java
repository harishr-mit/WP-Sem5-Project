package com.mitpass.common.messaging;

import java.util.List;
import java.util.Map;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADMIN tooling for this service's dead-letter queues (D13, completed in P7). The gateway exposes it as
 * {@code /api/admin/dead-letters/<service>/**}.
 */
@RestController
@RequestMapping("/api/dead-letters")
@PreAuthorize("hasRole('ADMIN')")
public class DeadLetterController {

    public record Overview(List<DeadLetters.QueueDepth> queues, List<DeadLetters.DeadLetter> messages) {}

    private final DeadLetters deadLetters;

    public DeadLetterController(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public Overview list(@RequestParam(defaultValue = "50") int limit) {
        return new Overview(deadLetters.depths(), deadLetters.list(Math.clamp(limit, 1, 200)));
    }

    @PostMapping("/{messageId}/replay")
    public ResponseEntity<Void> replay(@PathVariable String messageId) {
        if (!deadLetters.replay(messageId)) {
            throw notFound(messageId);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/replay-all")
    public Map<String, Integer> replayAll() {
        return Map.of("replayed", deadLetters.replayAll());
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> discard(@PathVariable String messageId) {
        if (!deadLetters.discard(messageId)) {
            throw notFound(messageId);
        }
        return ResponseEntity.noContent().build();
    }

    /** The broker is out of reach: 503, not a 500. */
    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<ProblemDetail> brokerUnavailable(AmqpException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ is not reachable"));
    }

    private static ResponseStatusException notFound(String messageId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No dead-lettered message " + messageId);
    }
}
