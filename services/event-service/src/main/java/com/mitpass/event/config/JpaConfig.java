package com.mitpass.event.config;

import com.mitpass.common.security.CurrentUser;
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

    /** The caller's user id (JWT {@code sub}); {@code "system"} when there is no caller (seed data, jobs). */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(
                CurrentUser.current().map(user -> user.id().toString()).orElse("system"));
    }
}
