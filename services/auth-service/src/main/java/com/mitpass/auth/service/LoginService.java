package com.mitpass.auth.service;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.common.web.error.ProblemException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email + password login with account lockout: {@code max-attempts} wrong passwords in a row lock the account for
 * {@code lockout.duration}. Order of checks: lock → password → verified → enabled, so a locked account answers 423
 * even to the right password, and "not verified" is only revealed to someone who knows the password.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserRepository users;
    private final EmailPolicy emailPolicy;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService sessions;
    private final AuthProperties properties;
    private final Clock clock;

    /** Checked against when the email is unknown, so both cases cost one BCrypt comparison. */
    private final String dummyHash;

    public LoginService(
            UserRepository users,
            EmailPolicy emailPolicy,
            PasswordEncoder passwordEncoder,
            RefreshTokenService sessions,
            AuthProperties properties,
            Clock clock) {
        this.users = users;
        this.emailPolicy = emailPolicy;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-1");
    }

    /** {@code noRollbackFor}: a failed attempt must still be counted (and a lock stored) when we answer 401/423. */
    @Transactional(noRollbackFor = ProblemException.class)
    public TokenPair login(String email, String password, ClientInfo client) {
        Instant now = clock.instant();
        User user = users.findByCanonicalEmail(emailPolicy.canonicalize(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw AuthProblems.invalidCredentials();
        }
        if (user.isLocked(now)) {
            throw AuthProblems.accountLocked(user.getLockedUntil(), now);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            AuthProperties.Lockout lockout = properties.lockout();
            user.recordFailedLogin(lockout.maxAttempts(), lockout.duration(), now);
            if (user.isLocked(now)) {
                log.warn("Account {} locked after {} failed logins", user.getId(), lockout.maxAttempts());
                throw AuthProblems.accountLocked(user.getLockedUntil(), now);
            }
            throw AuthProblems.invalidCredentials();
        }
        if (!user.isEmailVerified()) {
            throw AuthProblems.emailNotVerified();
        }
        if (!user.isEnabled()) {
            throw AuthProblems.accountDisabled();
        }
        user.recordSuccessfulLogin(now);
        return sessions.startSession(user, client);
    }
}
