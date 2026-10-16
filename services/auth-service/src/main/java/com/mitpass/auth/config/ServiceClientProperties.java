package com.mitpass.auth.config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mitpass.auth.service.*}: the services allowed to get a service token (docs/plans/phase-5.md §4b, D7). Keyed
 * by client id (the service's name).
 *
 * @param tokenTtl lifetime of a service token; there is no refresh, a client simply asks again
 */
@ConfigurationProperties("mitpass.auth.service")
public record ServiceClientProperties(@DefaultValue("5m") Duration tokenTtl, Map<String, Client> clients) {

    public ServiceClientProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /**
     * @param subject fixed id that goes into the token's {@code sub} (services identify callers by UUID)
     * @param secret shared secret; from an environment variable outside development (D8)
     */
    public record Client(UUID subject, String secret) {

        @Override
        public String toString() {
            return "Client[subject=" + subject + "]";
        }
    }
}
