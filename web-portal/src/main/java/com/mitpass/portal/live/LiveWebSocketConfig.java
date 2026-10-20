package com.mitpass.portal.live;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.auth.TokenRefresher;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * The browser side of the live dashboard: STOMP at {@code /portal-ws}, same origin only (Spring's default, so another
 * site can't open it with the user's cookie), for ORGANIZER and ADMIN (SecurityConfig). The handshake is a normal
 * request of the logged-in session, so the access token is refreshed there and kept with the socket, server side.
 * Browsers may only subscribe to {@code /topic/events/{id}}; {@link LiveRelay} fills that subscription.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class LiveWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String TOKEN = "mitpass.accessToken";
    private static final Pattern EVENT_TOPIC = Pattern.compile("/topic/events/(\\d+)");

    private final TokenRefresher tokens;
    private final LiveRelay relay;

    public LiveWebSocketConfig(TokenRefresher tokens, LiveRelay relay) {
        this.tokens = tokens;
        this.relay = relay;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/portal-ws").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(
                    ServerHttpRequest request,
                    ServerHttpResponse response,
                    WebSocketHandler handler,
                    Map<String, Object> attributes) {
                PortalUser user = tokens.ensureFresh();
                if (user == null) {
                    return false;
                }
                attributes.put(TOKEN, user.accessToken());
                return true;
            }

            @Override
            public void afterHandshake(
                    ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception e) {}
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
                if (stomp == null || stomp.getCommand() == null) {
                    return message;
                }
                switch (stomp.getCommand()) {
                    case SEND -> throw new MessageDeliveryException("This feed is read-only");
                    case SUBSCRIBE -> subscribe(stomp);
                    case UNSUBSCRIBE -> relay.unsubscribe(stomp.getSessionId(), stomp.getSubscriptionId());
                    default -> {}
                }
                return message;
            }
        });
    }

    private void subscribe(StompHeaderAccessor stomp) {
        Matcher topic = EVENT_TOPIC.matcher(String.valueOf(stomp.getDestination()));
        Object token = stomp.getSessionAttributes() == null
                ? null
                : stomp.getSessionAttributes().get(TOKEN);
        if (!topic.matches() || token == null) {
            throw new MessageDeliveryException("Only /topic/events/{id} can be followed");
        }
        relay.subscribe(
                stomp.getSessionId(), stomp.getSubscriptionId(), Long.parseLong(topic.group(1)), token.toString());
    }
}
