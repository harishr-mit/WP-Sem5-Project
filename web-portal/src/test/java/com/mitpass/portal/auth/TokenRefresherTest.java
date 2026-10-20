package com.mitpass.portal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.client.ApiModels.TokenResponse;
import com.mitpass.portal.client.RefreshClient;
import com.mitpass.portal.client.SessionExpiredException;
import com.mitpass.portal.config.PortalProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The refresh lock (docs/plans/phase-6.md §7, gotcha 1): auth-service treats a reused refresh token as stolen and
 * ends the whole login (D7), so parallel requests of one session must share one refresh.
 */
class TokenRefresherTest {

    static final PortalProperties PROPERTIES = new PortalProperties(
            "http://gateway.test",
            "",
            new PortalProperties.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(1)),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofMillis(300),
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(15),
            Duration.ofSeconds(3),
            List.of("en", "ta"),
            new PortalProperties.Live(Duration.ofMinutes(10), Duration.ofSeconds(5)),
            new PortalProperties.Offline(Duration.ofSeconds(4), Duration.ofSeconds(60), Duration.ofSeconds(15)));

    final RefreshClient refreshClient = mock(RefreshClient.class);
    final TokenRefresher refresher = new TokenRefresher(refreshClient, PROPERTIES, Clock.systemUTC());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    static MockHttpSession sessionOf(PortalUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(TestTokens.login(user)));
        return session;
    }

    /** What SecurityContextHolderFilter and RequestContextFilter set up at the start of a request. */
    static void startRequest(MockHttpSession session, PortalUser seenAtStart) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(TestTokens.login(seenAtStart));
        SecurityContextHolder.setContext(context);
    }

    static PortalUser userIn(MockHttpSession session) {
        SecurityContext context = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (PortalUser) context.getAuthentication().getPrincipal();
    }

    static TokenResponse newTokens() {
        return TestTokens.tokenResponse(UUID.randomUUID(), "Priya Raman", List.of("STUDENT"), "CSE", 1);
    }

    @Test
    void tenParallelRequestsAtExpiryCauseExactlyOneRefresh() throws Exception {
        PortalUser expiring = TestTokens.user(Instant.now().plusSeconds(10), "STUDENT");
        MockHttpSession session = sessionOf(expiring);
        AtomicInteger calls = new AtomicInteger();
        given(refreshClient.refresh(TestTokens.REFRESH)).willAnswer(invocation -> {
            calls.incrementAndGet();
            Thread.sleep(150); // slow enough that the other nine are all waiting for the lock
            return newTokens();
        });

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> tokensUsed = ConcurrentHashMap.newKeySet();
        try {
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                done.add(pool.submit(() -> {
                    startRequest(session, expiring);
                    start.await();
                    tokensUsed.add(refresher.current());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(calls).hasValue(1);
        verify(refreshClient, times(1)).refresh(anyString());
        assertThat(tokensUsed).hasSize(1).doesNotContain(TestTokens.ACCESS);
        assertThat(userIn(session).accessToken())
                .isEqualTo(tokensUsed.iterator().next());
    }

    /** Found live with 1 min tokens: the 60 s window covered the whole lifetime, so every request refreshed. */
    @Test
    void theWindowIsAtMostAQuarterOfTheTokensLifetime() {
        Instant now = Instant.now();
        PortalUser oneMinute = new PortalUser(
                UUID.randomUUID(),
                "e",
                "n",
                Set.of("STUDENT"),
                null,
                null,
                "a",
                "r",
                now.plusSeconds(50),
                now.minusSeconds(10));
        assertThat(refresher.needsRefresh(oneMinute)).as("50 s left of 60 s").isFalse();
        PortalUser nearlyDone = new PortalUser(
                UUID.randomUUID(),
                "e",
                "n",
                Set.of("STUDENT"),
                null,
                null,
                "a",
                "r",
                now.plusSeconds(10),
                now.minusSeconds(50));
        assertThat(refresher.needsRefresh(nearlyDone)).as("10 s left of 60 s").isTrue();
        PortalUser fifteenMinutes = new PortalUser(
                UUID.randomUUID(),
                "e",
                "n",
                Set.of("STUDENT"),
                null,
                null,
                "a",
                "r",
                now.plusSeconds(50),
                now.minusSeconds(850));
        assertThat(refresher.needsRefresh(fifteenMinutes))
                .as("50 s left of 15 min: inside the 60 s window")
                .isTrue();
    }

    @Test
    void aFreshTokenIsSentAsItIs() {
        PortalUser user = TestTokens.user(Instant.now().plusSeconds(600), "STUDENT");
        startRequest(sessionOf(user), user);

        assertThat(refresher.current()).isEqualTo(TestTokens.ACCESS);
        verify(refreshClient, never()).refresh(anyString());
    }

    @Test
    void aRejectedTokenIsRefreshedEvenIfItLooksValid() {
        PortalUser user = TestTokens.user(Instant.now().plusSeconds(600), "STUDENT");
        MockHttpSession session = sessionOf(user);
        startRequest(session, user);
        given(refreshClient.refresh(TestTokens.REFRESH)).willReturn(newTokens());

        String fresh = refresher.afterRejection(TestTokens.ACCESS);

        assertThat(fresh).isNotEqualTo(TestTokens.ACCESS);
        assertThat(userIn(session).accessToken()).isEqualTo(fresh);
        // the request's own context sees the new user too, for the rest of this request
        assertThat(((PortalUser) SecurityContextHolder.getContext()
                                .getAuthentication()
                                .getPrincipal())
                        .accessToken())
                .isEqualTo(fresh);
    }

    @Test
    void aRefusedRefreshTokenEndsTheSession() {
        PortalUser expired = TestTokens.user(Instant.now().minusSeconds(1), "STUDENT");
        startRequest(sessionOf(expired), expired);
        given(refreshClient.refresh(anyString())).willThrow(new SessionExpiredException());

        assertThatThrownBy(refresher::current).isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void withoutASessionThereIsNothingToRefresh() {
        PortalUser expired = TestTokens.user(Instant.now().minusSeconds(1), "STUDENT");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest(), new MockHttpServletResponse()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(TestTokens.login(expired));
        SecurityContextHolder.setContext(context);

        assertThatThrownBy(refresher::current).isInstanceOf(SessionExpiredException.class);
        verify(refreshClient, never()).refresh(anyString());
    }
}
