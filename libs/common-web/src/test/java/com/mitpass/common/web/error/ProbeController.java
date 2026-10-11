package com.mitpass.common.web.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Throws each kind of error so the handler's mapping can be tested. */
@RestController
class ProbeController {

    record Probe(@NotBlank String name, @Positive int size) {}

    @PostMapping("/probe")
    Probe create(@Valid @RequestBody Probe probe) {
        return probe;
    }

    @GetMapping("/probe/limit")
    int limit(@RequestParam @Max(10) int value) {
        return value;
    }

    @GetMapping("/probe/{id}")
    String byId(@PathVariable long id) {
        throw NotFoundException.of("Probe", id);
    }

    @GetMapping("/probe/fail/{kind}")
    String fail(@PathVariable String kind) {
        throw switch (kind) {
            case "conflict" -> new ConflictException("Probe is in the wrong state");
            case "integrity" -> new DataIntegrityViolationException("duplicate key value violates unique constraint");
            case "stale" -> new OptimisticLockingFailureException("stale");
            case "rule" -> new BusinessRuleException("Capacity exceeds the venue");
            case "locked" ->
                new ProblemException(
                        HttpStatus.LOCKED,
                        ProblemTypes.of("account-locked"),
                        "Account locked",
                        "Too many failed logins",
                        Map.of(HttpHeaders.RETRY_AFTER, "900"));
            default -> new IllegalStateException("secret internal detail");
        };
    }
}
