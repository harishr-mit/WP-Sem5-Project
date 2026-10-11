package com.mitpass.event;

import com.mitpass.event.client.AuthServiceClient;
import com.mitpass.security.test.TestSecurityConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The full event-service on a Testcontainers PostgreSQL. Tokens come from {@code TestJwtIssuer}; auth-service is
 * replaced by {@link FakeAuthService}. All classes with this annotation share one cached context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, EventIntegrationTest.AuthStub.class})
public @interface EventIntegrationTest {

    /** Accounts known to the fake auth-service, by lower-case email. Tests register the ones they need. */
    class FakeAuthService extends AuthServiceClient {

        private final Map<String, UserAccount> accounts = new ConcurrentHashMap<>();

        FakeAuthService() {
            super(RestClient.create());
        }

        public void register(UserAccount account) {
            accounts.put(account.email().toLowerCase(Locale.ROOT), account);
        }

        @Override
        public UserAccount findByEmail(String email) {
            UserAccount account = accounts.get(email.toLowerCase(Locale.ROOT));
            if (account == null) {
                throw new com.mitpass.common.web.error.BusinessRuleException(
                        "No MITPass account uses the email " + email);
            }
            return account;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AuthStub {

        @Bean
        @Primary
        FakeAuthService fakeAuthService() {
            return new FakeAuthService();
        }
    }
}
