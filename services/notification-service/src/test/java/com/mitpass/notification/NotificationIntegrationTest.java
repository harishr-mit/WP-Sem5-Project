package com.mitpass.notification;

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
 * The full notification-service on a Testcontainers PostgreSQL, with GreenMail as the SMTP server, a settable
 * {@link TestClock} and in-memory event- and pass-service ({@link NotificationTestConfig}). Classes with this annotation
 * share one context, so tests use their own event ids and addresses.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, NotificationTestConfig.class})
public @interface NotificationIntegrationTest {}
