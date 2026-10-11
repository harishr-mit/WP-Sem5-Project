package com.mitpass.auth.config;

import com.mitpass.common.security.CurrentUser;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA auditing. Kept out of the application class so web-slice tests don't need a JPA context. */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    /** The caller's user id; {@code "system"} for anonymous requests (sign-up, login) and seed data. */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(
                CurrentUser.current().map(user -> user.id().toString()).orElse("system"));
    }
}
