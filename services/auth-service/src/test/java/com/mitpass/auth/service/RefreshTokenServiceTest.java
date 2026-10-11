package com.mitpass.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.TestAccounts;
import com.mitpass.auth.domain.RefreshToken;
import com.mitpass.auth.domain.RevokeReason;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.RefreshTokenRepository;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.common.web.error.ProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@AuthIntegrationTest
class RefreshTokenServiceTest {

    @Autowired
    RefreshTokenService sessions;

    @Autowired
    RefreshTokenRepository tokens;

    @Autowired
    UserRepository users;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TransactionTemplate transaction;

    TokenPair login;

    @BeforeEach
    void logIn() {
        User user = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT);
        login = transaction.execute(status ->
                sessions.startSession(users.findWithRolesById(user.getId()).orElseThrow(), ClientInfo.unknown()));
    }

    @Test
    void refreshRotatesTheToken() {
        TokenPair next = sessions.refresh(login.refreshToken(), ClientInfo.unknown());

        assertThat(next.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(next.accessToken()).isNotBlank();
        RefreshToken old =
                tokens.findByTokenHash(OpaqueTokens.hash(login.refreshToken())).orElseThrow();
        RefreshToken current =
                tokens.findByTokenHash(OpaqueTokens.hash(next.refreshToken())).orElseThrow();
        assertThat(old.getUsedAt()).isNotNull();
        assertThat(old.getReplacedBy()).isEqualTo(current.getId());
        assertThat(current.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(current.getFamilyExpiresAt()).isEqualTo(old.getFamilyExpiresAt());
    }

    @Test
    void replayingAUsedTokenRevokesTheWholeFamilyAndTheRevocationIsCommitted() {
        TokenPair next = sessions.refresh(login.refreshToken(), ClientInfo.unknown());

        assertThatThrownBy(() -> sessions.refresh(login.refreshToken(), ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).endsWith("refresh-token-reused"));

        // Read back in a fresh transaction: the revocation survived the exception.
        RefreshToken current =
                tokens.findByTokenHash(OpaqueTokens.hash(next.refreshToken())).orElseThrow();
        assertThat(current.getRevokedAt()).isNotNull();
        assertThat(current.getRevokeReason()).isEqualTo(RevokeReason.REUSE);
        assertThatThrownBy(() -> sessions.refresh(next.refreshToken(), ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).endsWith("invalid-refresh-token"));
    }

    @Test
    void twoConcurrentRefreshesWithTheSameTokenSucceedExactlyOnce() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<TokenPair>> attempts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            attempts.add(() -> {
                start.await();
                return sessions.refresh(login.refreshToken(), ClientInfo.unknown());
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<TokenPair>> results =
                    attempts.stream().map(pool::submit).toList();
            start.countDown();
            int succeeded = 0;
            int rejected = 0;
            for (Future<TokenPair> result : results) {
                try {
                    result.get();
                    succeeded++;
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(ProblemException.class);
                    rejected++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(rejected).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logoutEndsTheFamilyAndIsIdempotent() {
        sessions.logout(login.refreshToken());
        sessions.logout(login.refreshToken());
        sessions.logout("never-issued");

        assertThatThrownBy(() -> sessions.refresh(login.refreshToken(), ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).endsWith("invalid-refresh-token"));
    }

    @Test
    void unknownTokenIsInvalid() {
        assertThatThrownBy(() -> sessions.refresh("made-up", ClientInfo.unknown()))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        ex -> assertThat(ex.getType().toString()).endsWith("invalid-refresh-token"));
    }
}
