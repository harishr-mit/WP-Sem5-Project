package com.mitpass.event.client;

import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls auth-service through Eureka with the caller's own access token (relayed by {@code BearerTokenRelayInterceptor}),
 * so auth-service applies its rules to the organizer, not to event-service.
 */
public class AuthServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceClient.class);

    /** The fields of auth-service's {@code UserSummary} that event-service needs. */
    public record UserAccount(UUID id, String fullName, String email, List<String> roles) {}

    private final RestClient client;

    public AuthServiceClient(RestClient client) {
        this.client = client;
    }

    /**
     * @throws BusinessRuleException 422 if no account has this email
     * @throws ProblemException 503 if auth-service can't be reached
     */
    public UserAccount findByEmail(String email) {
        try {
            UserAccount account = client.get()
                    .uri(uri -> uri.path("/api/users/lookup")
                            .queryParam("email", email)
                            .build())
                    .retrieve()
                    .body(UserAccount.class);
            if (account == null) {
                throw unavailable("auth-service returned an empty answer");
            }
            return account;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new BusinessRuleException("No MITPass account uses the email " + email);
        } catch (ResourceAccessException ex) {
            throw unavailable(ex.getMessage());
        } catch (RestClientException ex) {
            log.warn("auth-service lookup failed: {}", ex.getMessage());
            throw unavailable(ex.getMessage());
        }
    }

    private static ProblemException unavailable(String cause) {
        log.warn("auth-service unavailable: {}", cause);
        return new ProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.of("dependency-unavailable"),
                "Service unavailable",
                "Could not reach auth-service to look the user up. Try again in a moment.");
    }
}
