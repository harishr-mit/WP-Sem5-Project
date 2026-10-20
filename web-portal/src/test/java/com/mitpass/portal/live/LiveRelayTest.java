package com.mitpass.portal.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.portal.config.PortalProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The relay against a real STOMP server that stands in for gateway + checkin-service: it wants {@code Bearer good} on
 * the handshake and refuses event 999. Frames to the browser are caught on a fake outbound channel.
 */
class LiveRelayTest {

    static ConfigurableApplicationContext upstream;
    static int port;

    /** The stand-in for checkin-service's /ws behind the gateway's /ws/checkin. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableWebSocketMessageBroker
    static class Upstream implements WebSocketMessageBrokerConfigurer {
        @Override
        public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("/ws/checkin").addInterceptors(new HandshakeInterceptor() {
                @Override
                public boolean beforeHandshake(
                        ServerHttpRequest request,
                        ServerHttpResponse response,
                        WebSocketHandler handler,
                        Map<String, Object> attributes) {
                    return "Bearer good".equals(request.getHeaders().getFirst("Authorization"));
                }

                @Override
                public void afterHandshake(
                        ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler h, Exception e) {}
            });
        }

        @Override
        public void configureMessageBroker(MessageBrokerRegistry registry) {
            registry.enableSimpleBroker("/topic");
        }

        @Override
        public void configureClientInboundChannel(ChannelRegistration registration) {
            registration.interceptors(new ChannelInterceptor() {
                @Override
                public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                    if (stomp != null
                            && stomp.getCommand() == StompCommand.SUBSCRIBE
                            && "/topic/events/999".equals(stomp.getDestination())) {
                        throw new MessageDeliveryException("Not your event");
                    }
                    return message;
                }
            });
        }
    }

    @BeforeAll
    static void startUpstream() {
        upstream = new SpringApplicationBuilder(Upstream.class)
                .properties(
                        "server.port=0",
                        "spring.config.name=no-such-config", // not the portal's application.yml
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                                + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
                        "eureka.client.enabled=false",
                        "spring.cloud.discovery.enabled=false")
                .run();
        port = ((WebServerApplicationContext) upstream).getWebServer().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.close();
        }
    }

    final BlockingQueue<Message<?>> toBrowser = new LinkedBlockingQueue<>();
    final MessageChannel browserChannel = (message, timeout) -> toBrowser.add(message);
    LiveRelay relay;

    @AfterEach
    void stopRelay() {
        if (relay != null) {
            relay.stop();
        }
    }

    private LiveRelay relay(Duration reconnectEvery) {
        PortalProperties defaults = new PortalProperties(
                "http://localhost:" + port,
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
                List.of("en"),
                new PortalProperties.Live(reconnectEvery, Duration.ofSeconds(5)),
                new PortalProperties.Offline(Duration.ofSeconds(4), Duration.ofSeconds(60), Duration.ofSeconds(15)));
        relay = new LiveRelay(browserChannel, JsonMapper.builder().build(), defaults);
        return relay;
    }

    @Test
    void snapshotsReachOnlyTheSubscribingBrowserSession() throws Exception {
        LiveRelay relay = relay(Duration.ofMinutes(10));
        relay.subscribe("browser-1", "sub-0", 7, "good");
        awaitUpstreams(relay, 1);

        upstream.getBean(SimpMessagingTemplate.class)
                .convertAndSend("/topic/events/7", (Object) Map.of("checkedIn", 3));

        Message<?> frame = toBrowser.poll(3, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(frame);
        assertThat(headers.getCommand()).isEqualTo(StompCommand.MESSAGE);
        assertThat(headers.getSessionId()).isEqualTo("browser-1");
        assertThat(headers.getSubscriptionId()).isEqualTo("sub-0");
        assertThat(new String((byte[]) frame.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo("{\"checkedIn\":3}");

        relay.unsubscribe("browser-1", "sub-0");
        assertThat(relay.openUpstreams()).isZero();
    }

    @Test
    void aRefusedSubscriptionBecomesAnErrorFrame() throws Exception {
        relay(Duration.ofMinutes(10)).subscribe("browser-2", "sub-0", 999, "good");

        Message<?> frame = toBrowser.poll(3, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        assertThat(StompHeaderAccessor.wrap(frame).getCommand()).isEqualTo(StompCommand.ERROR);
    }

    @Test
    void aRefusedHandshakeTellsThePageToReconnect() throws Exception {
        relay(Duration.ofMinutes(10)).subscribe("browser-3", "sub-0", 7, "expired");

        Message<?> frame = toBrowser.poll(3, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        assertThat(new String((byte[]) frame.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo(LiveRelay.RECONNECT);
        assertThat(relay.openUpstreams()).isZero();
    }

    @Test
    void theUpstreamIsClosedAfterReconnectEveryAndThePageToldToReconnect() throws Exception {
        LiveRelay relay = relay(Duration.ofMillis(400));
        relay.subscribe("browser-4", "sub-0", 7, "good");
        awaitUpstreams(relay, 1);

        Message<?> frame = toBrowser.poll(3, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        assertThat(new String((byte[]) frame.getPayload(), StandardCharsets.UTF_8))
                .isEqualTo(LiveRelay.RECONNECT);
        assertThat(relay.openUpstreams()).isZero();
    }

    private static void awaitUpstreams(LiveRelay relay, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (relay.openUpstreams() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(relay.openUpstreams()).isEqualTo(n);
        Thread.sleep(200); // the upstream SUBSCRIBE is processed asynchronously; the simple broker sends no receipt
    }
}
