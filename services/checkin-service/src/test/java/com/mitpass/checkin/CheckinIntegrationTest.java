package com.mitpass.checkin;

import com.mitpass.security.test.TestSecurityConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The full checkin-service on a Testcontainers PostgreSQL, with a settable {@link TestClock} and in-memory event-,
 * pass- and key services ({@link CheckinTestConfig}). Tokens come from {@code TestJwtIssuer}. All classes with this
 * annotation share one context and one database, so tests use their own event ids.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, CheckinTestConfig.class, ScanScenario.class})
public @interface CheckinIntegrationTest {}
