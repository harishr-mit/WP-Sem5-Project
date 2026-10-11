package com.mitpass.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.domain.Department;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.common.web.error.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class LoginServiceTest {

    static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    static final String EMAIL = "priyaraman@gmail.com";

    final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    final UserRepository users = mock(UserRepository.class);
    final RefreshTokenService sessions = mock(RefreshTokenService.class);
    final AuthProperties properties = new AuthProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            new AuthProperties.Lockout(5, Duration.ofMinutes(15)),
            null,
            null,
            EmailPolicyTest.properties().signup());
    final LoginService login = new LoginService(
            users, new EmailPolicy(properties), encoder, sessions, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    User user;

    @BeforeEach
    void setUp() {
        user = new User(EMAIL, EMAIL, "Priya", new Department("CSE", "CSE"), 1, null, encoder.encode("Passw0rd!"));
        user.markEmailVerified(NOW.minusSeconds(60));
        given(users.findByCanonicalEmail(EMAIL)).willReturn(Optional.of(user));
        given(sessions.startSession(any(), any()))
                .willReturn(new TokenPair("access", NOW.plusSeconds(900), "refresh", NOW.plusSeconds(3600)));
    }

    @Test
    void rightPasswordStartsASessionAndResetsTheCounter() {
        user.recordFailedLogin(5, Duration.ofMinutes(15), NOW);

        TokenPair pair = login.login("Priya.Raman@gmail.com", "Passw0rd!", ClientInfo.unknown());

        assertThat(pair.accessToken()).isEqualTo("access");
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSameAnswer() {
        assertThatThrownBy(() -> login.login("nobody@gmail.com", "Passw0rd!", ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).endsWith("invalid-credentials"));
        assertThatThrownBy(() -> login.login(EMAIL, "wrong-pass1", ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).endsWith("invalid-credentials"));
    }

    @Test
    void fifthWrongPasswordLocksTheAccountEvenForTheRightPassword() {
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> login.login(EMAIL, "wrong-pass1", ClientInfo.unknown()))
                    .isInstanceOfSatisfying(
                            ProblemException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        assertThatThrownBy(() -> login.login(EMAIL, "wrong-pass1", ClientInfo.unknown()))
                .isInstanceOfSatisfying(ProblemException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.LOCKED);
                    assertThat(ex.getHeaders()).containsEntry("Retry-After", "900");
                });

        assertThatThrownBy(() -> login.login(EMAIL, "Passw0rd!", ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.LOCKED));
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(sessions, never()).startSession(any(), any());
    }

    @Test
    void unverifiedEmailIsA403OnlyWithTheRightPassword() {
        User unverified = new User(
                "new@gmail.com",
                "new@gmail.com",
                "New",
                new Department("CSE", "CSE"),
                1,
                null,
                encoder.encode("pw123456"));
        given(users.findByCanonicalEmail("new@gmail.com")).willReturn(Optional.of(unverified));

        assertThatThrownBy(() -> login.login("new@gmail.com", "pw123456", ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> login.login("new@gmail.com", "wrong-pass1", ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
