package com.mitpass.auth.web;

import com.mitpass.auth.service.AccessTokenService.IssuedAccessToken;
import com.mitpass.auth.service.ServiceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service tokens (docs/plans/phase-5.md §4b). Internal only: the gateway refuses this path, so it is reachable only
 * from inside the network (through Eureka).
 */
@RestController
@Tag(name = "Service tokens", description = "Tokens for background jobs of other MITPass services (internal)")
public class ServiceTokenController {

    private final ServiceTokenService serviceTokens;
    private final Clock clock;

    public ServiceTokenController(ServiceTokenService serviceTokens, Clock clock) {
        this.serviceTokens = serviceTokens;
        this.clock = clock;
    }

    public record ServiceTokenRequest(
            @NotBlank @Size(max = 60) String clientId,
            @NotBlank @Size(max = 200) String clientSecret) {

        @Override
        public String toString() {
            return "ServiceTokenRequest[clientId=" + clientId + "]";
        }
    }

    /** Lifetime in seconds. No refresh token: the client asks again. */
    public record ServiceTokenResponse(String accessToken, String tokenType, long expiresIn) {

        @Override
        public String toString() {
            return "ServiceTokenResponse[expiresIn=" + expiresIn + "]";
        }
    }

    @PostMapping("/api/auth/service-token")
    @SecurityRequirements
    @Operation(
            summary = "Get a service token (internal; client id + secret)",
            description = "Role SERVICE, 5 minutes. 401 invalid-client for an unknown client or a wrong secret.")
    public ResponseEntity<ServiceTokenResponse> token(@Valid @RequestBody ServiceTokenRequest request) {
        IssuedAccessToken token = serviceTokens.issue(request.clientId(), request.clientSecret());
        long seconds = (Duration.between(clock.instant(), token.expiresAt()).toMillis() + 999) / 1000;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ServiceTokenResponse(token.value(), "Bearer", seconds));
    }
}
