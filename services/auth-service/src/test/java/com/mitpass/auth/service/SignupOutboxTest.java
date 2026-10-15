package com.mitpass.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.web.dto.SignupRequest;
import com.mitpass.common.web.error.BusinessRuleException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sign-up writes UserRegistered to the outbox in the same transaction (docs/DECISIONS.md D11). */
@AuthIntegrationTest
class SignupOutboxTest {

    @Autowired
    SignupService signups;

    @Autowired
    JdbcClient jdbc;

    @Test
    void signupPublishesUserRegistered() {
        String email = "outbox." + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";

        User user = signups.signup(new SignupRequest("Outbox Tester", email, "Techfest2026", "CSE", 2, null));

        String payload = jdbc.sql("SELECT payload::text FROM outbox_message WHERE aggregate_id = :id")
                .param("id", user.getId().toString())
                .query(String.class)
                .single();
        assertThat((String) JsonPath.read(payload, "$.type")).isEqualTo("user.registered");
        assertThat((String) JsonPath.read(payload, "$.payload.email")).isEqualTo(email);
        assertThat((String) JsonPath.read(payload, "$.payload.department")).isEqualTo("CSE");
        assertThat((Integer) JsonPath.read(payload, "$.payload.year")).isEqualTo(2);
    }

    @Test
    void aFailedSignupWritesNothing() {
        long before = count();

        assertThatThrownBy(() -> signups.signup(new SignupRequest(
                        "Nobody",
                        "nobody." + UUID.randomUUID().toString().substring(0, 6) + "@gmail.com",
                        "Techfest2026",
                        "NOPE",
                        1,
                        null)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(count()).isEqualTo(before);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM outbox_message").query(Long.class).single();
    }
}
