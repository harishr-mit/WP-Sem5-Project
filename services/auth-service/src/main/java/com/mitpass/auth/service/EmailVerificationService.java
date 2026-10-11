package com.mitpass.auth.service;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.domain.EmailVerificationToken;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.mail.VerificationRequested;
import com.mitpass.auth.repository.EmailVerificationTokenRepository;
import com.mitpass.auth.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single-use, expiring email verification links. */
@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokens;
    private final UserRepository users;
    private final EmailPolicy emailPolicy;
    private final ApplicationEventPublisher events;
    private final AuthProperties properties;
    private final Clock clock;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokens,
            UserRepository users,
            EmailPolicy emailPolicy,
            ApplicationEventPublisher events,
            AuthProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.emailPolicy = emailPolicy;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Stores a new token and asks for the email; the mailer sends it only if this transaction commits. */
    @Transactional
    public void issueFor(User user) {
        Instant now = clock.instant();
        String raw = OpaqueTokens.generate();
        tokens.save(new EmailVerificationToken(
                user, OpaqueTokens.hash(raw), now, now.plus(properties.verificationTokenTtl())));
        events.publishEvent(new VerificationRequested(user.getEmail(), user.getFullName(), raw));
    }

    /** Clicking the same link twice is fine; an unknown, expired or superseded link is a 400. */
    @Transactional
    public void verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw AuthProblems.invalidVerificationToken();
        }
        Instant now = clock.instant();
        EmailVerificationToken token = tokens.findByTokenHash(OpaqueTokens.hash(rawToken.trim()))
                .orElseThrow(AuthProblems::invalidVerificationToken);
        User user = token.getUser();
        if (token.getUsedAt() != null && user.isEmailVerified()) {
            return;
        }
        if (!token.isUsable(now)) {
            throw AuthProblems.invalidVerificationToken();
        }
        token.markUsed(now);
        user.markEmailVerified(now);
    }

    /**
     * Sends a fresh link to an unverified account, at most once per {@code resend-interval}. Says nothing about whether
     * the account exists: the controller always answers 202.
     */
    @Transactional
    public void resend(String email) {
        Instant now = clock.instant();
        users.findByCanonicalEmail(emailPolicy.canonicalize(email))
                .filter(user -> !user.isEmailVerified())
                .filter(user -> tokens.findFirstByUserOrderByCreatedAtDesc(user)
                        .map(last -> last.getCreatedAt().isBefore(now.minus(properties.resendInterval())))
                        .orElse(true))
                .ifPresent(this::issueFor);
    }
}
