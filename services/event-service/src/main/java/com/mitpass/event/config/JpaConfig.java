package com.mitpass.event.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing (fills {@code created_*} / {@code updated_*}). Kept out of the application class so web-slice tests
 * ({@code @WebMvcTest}) don't need a JPA context.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    /** Phase 1 has no authentication; Phase 2 replaces this with the JWT subject. */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
