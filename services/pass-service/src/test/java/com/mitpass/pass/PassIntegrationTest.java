package com.mitpass.pass;

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
 * The full pass-service on a Testcontainers PostgreSQL, with a settable {@link TestClock} and a {@link FakeEventCatalog}
 * instead of event-service. Tokens come from {@code TestJwtIssuer}. All classes with this annotation share one context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, PassTestConfig.class})
public @interface PassIntegrationTest {}
