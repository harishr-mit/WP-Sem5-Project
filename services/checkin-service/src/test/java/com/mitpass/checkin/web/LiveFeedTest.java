package com.mitpass.checkin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinFixtures;
import com.mitpass.checkin.CheckinTestConfig;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.TestcontainersConfiguration;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.service.ScanService;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestSecurityConfig;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** The live feed over a real WebSocket: owners get a snapshot right after a scan, others are refused (D16). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, CheckinTestConfig.class, ScanScenario.class})
class LiveFeedTest {

    @LocalServerPort
    int port;

    @Autowired
    ScanScenario scenario;

    @Autowired
    ScanService scans;

    @Autowired
    TestClock clock;

    WebSocketStompClient client;
    StompSession session;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        client.stop();
    }

    @Test
    void theOwnerGetsASnapshotRightAfterAScanAndABurstFoldsIntoFewFrames() throws Exception {
        Gate gate = scenario.liveEvent();
        BlockingQueue<Map<String, Object>> frames = subscribe(
                TestJwtIssuer.tokenFor(CheckinFixtures.ORGANIZER, Roles.ORGANIZER),
                gate.event().id(),
                null);

        long started = System.nanoTime();
        scan(gate, scenario.pass(gate, 1, "Priya Raman"));
        Map<String, Object> first = frames.poll(3, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
        assertThat(((Number) first.get("checkedIn")).longValue()).isEqualTo(1);
        assertThat(first.get("recent").toString()).contains("Priya");

        for (int i = 2; i <= 21; i++) {
            scan(gate, scenario.pass(gate, i, "Guest " + i));
        }
        Thread.sleep(1500);
        List<Map<String, Object>> burst = new java.util.ArrayList<>();
        frames.drainTo(burst);
        assertThat(burst).isNotEmpty().hasSizeLessThanOrEqualTo(4);
        assertThat(((Number) burst.getLast().get("checkedIn")).longValue()).isEqualTo(21);
    }

    @Test
    void aStudentCannotFollowTheFeed() throws Exception {
        Gate gate = scenario.liveEvent();
        CompletableFuture<String> error = new CompletableFuture<>();
        BlockingQueue<Map<String, Object>> frames =
                subscribe(TestJwtIssuer.token(Roles.STUDENT), gate.event().id(), error);

        assertThat(error.get(3, TimeUnit.SECONDS)).isNotBlank();
        scan(gate, scenario.pass(gate, 1, "Priya Raman"));
        assertThat(frames.poll(1, TimeUnit.SECONDS)).isNull();
    }

    private BlockingQueue<Map<String, Object>> subscribe(String token, long eventId, CompletableFuture<String> error)
            throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setBearerAuth(token);
        // as the gateway forwards it: Origin = this service, X-Forwarded-Host = the gateway (P7 exit-demo bug)
        handshake.add("Origin", "http://localhost:" + port);
        handshake.add("X-Forwarded-Host", "localhost:8080");
        session = client.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        handshake,
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                // ERROR frames come here
                                if (error != null) {
                                    error.complete(String.valueOf(headers.getFirst("message")));
                                }
                            }
                        })
                .get(5, TimeUnit.SECONDS);
        BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        session.subscribe("/topic/events/" + eventId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add((Map<String, Object>) payload);
            }
        });
        Thread.sleep(300); // the SUBSCRIBE frame is handled asynchronously; the simple broker sends no receipt
        return frames;
    }

    private void scan(Gate gate, PassRecord pass) {
        scans.scan(
                gate.volunteerUser(),
                new ScanCommand(scenario.liveToken(pass), null, gate.main().id(), "feed-test", null, ScanMode.IN));
    }
}
