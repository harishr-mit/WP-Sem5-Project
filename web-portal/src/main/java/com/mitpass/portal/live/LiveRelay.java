package com.mitpass.portal.live;

import com.mitpass.portal.config.PortalProperties;
import jakarta.annotation.PreDestroy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relays checkin-service's live feed to the browser (owner Q1, D16). For each browser subscription to
 * {@code /topic/events/{id}} on the portal's {@code /portal-ws}, it opens an upstream STOMP session to the gateway's
 * {@code /ws/checkin} with the organizer's access token on the handshake, subscribes to the same topic there, and
 * sends every snapshot to that one browser session. checkin-service decides who may follow an event; its refusal
 * reaches the browser as an ERROR frame. No token ever reaches the browser.
 *
 * <p>After {@code mitpass.portal.live.reconnect-every} the upstream is closed and the page is told to reconnect, which
 * makes a new handshake with a freshly refreshed token: a withdrawn organizer loses the feed within that time.
 */
@Component
public class LiveRelay {

    private static final Logger log = LoggerFactory.getLogger(LiveRelay.class);
    static final String RECONNECT = "{\"reconnect\":true}";

    private record Upstream(StompSession session, ScheduledFuture<?> expiry) {}

    private final MessageChannel toBrowsers;
    private final JsonMapper json;
    private final String upstreamUrl;
    private final long reconnectEveryMillis;
    private final WebSocketStompClient client;
    private final Map<String, Upstream> upstreams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "live-relay");
        thread.setDaemon(true);
        return thread;
    });

    public LiveRelay(
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel toBrowsers,
            JsonMapper json,
            PortalProperties properties) {
        this.toBrowsers = toBrowsers;
        this.json = json;
        this.upstreamUrl = properties.gatewayUrl().replaceFirst("^http", "ws") + "/ws/checkin";
        this.reconnectEveryMillis = properties.live().reconnectEvery().toMillis();
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @PreDestroy
    void stop() {
        upstreams.keySet().forEach(this::close);
        timer.shutdownNow();
        client.stop();
    }

    /** A browser session subscribed to an event's feed (the subscription id is the browser's). */
    public void subscribe(String sessionId, String subscriptionId, long eventId, String accessToken) {
        String key = key(sessionId, subscriptionId);
        String destination = "/topic/events/" + eventId;
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setBearerAuth(accessToken);
        upstreams.put(key, new Upstream(null, null)); // claimed: an unsubscribe before the connect cancels it
        client.connectAsync(upstreamUrl, handshake, new StompHeaders(), new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        // ERROR from checkin-service: not this user's event, or something went wrong there
                        error(sessionId, String.valueOf(headers.getFirst("message")));
                        close(key);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        if (upstreams.containsKey(key)) {
                            forward(sessionId, subscriptionId, destination, RECONNECT);
                            close(key);
                        }
                    }
                })
                .thenAccept(session -> {
                    if (!upstreams.containsKey(key)) {
                        session.disconnect(); // the browser left meanwhile
                        return;
                    }
                    session.subscribe(destination, new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            forward(sessionId, subscriptionId, destination, json.writeValueAsString(payload));
                        }
                    });
                    ScheduledFuture<?> expiry = timer.schedule(
                            () -> {
                                forward(sessionId, subscriptionId, destination, RECONNECT);
                                close(key);
                            },
                            reconnectEveryMillis,
                            TimeUnit.MILLISECONDS);
                    upstreams.put(key, new Upstream(session, expiry));
                })
                .exceptionally(e -> {
                    log.warn("Live feed for event {} not opened: {}", eventId, e.getMessage());
                    if (upstreams.containsKey(key)) {
                        forward(sessionId, subscriptionId, destination, RECONNECT);
                        upstreams.remove(key);
                    }
                    return null;
                });
    }

    public void unsubscribe(String sessionId, String subscriptionId) {
        close(key(sessionId, subscriptionId));
    }

    /** Every way a browser socket ends (DISCONNECT frame or a dropped connection) ends here. */
    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        String prefix = event.getSessionId() + "|";
        upstreams.keySet().stream().filter(k -> k.startsWith(prefix)).toList().forEach(this::close);
    }

    int openUpstreams() {
        return (int)
                upstreams.values().stream().filter(u -> u.session() != null).count();
    }

    private void close(String key) {
        Upstream upstream = upstreams.remove(key);
        if (upstream == null) {
            return;
        }
        if (upstream.expiry() != null) {
            upstream.expiry().cancel(false);
        }
        if (upstream.session() != null && upstream.session().isConnected()) {
            try {
                upstream.session().disconnect();
            } catch (RuntimeException e) {
                log.debug("Upstream already gone: {}", e.getMessage());
            }
        }
    }

    private void forward(String sessionId, String subscriptionId, String destination, String body) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setSubscriptionId(subscriptionId);
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setLeaveMutable(true);
        toBrowsers.send(
                MessageBuilder.createMessage(body.getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders()));
    }

    private void error(String sessionId, String message) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setSessionId(sessionId);
        headers.setMessage(message);
        headers.setLeaveMutable(true);
        toBrowsers.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
    }

    private static String key(String sessionId, String subscriptionId) {
        return sessionId + "|" + subscriptionId;
    }
}
