package com.mitpass.checkin.config;

import com.mitpass.checkin.security.CheckinSecurity;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The live dashboard feed (docs/plans/phase-7.md §4, D16): STOMP over a plain WebSocket at {@code /ws}. The handshake
 * is an ordinary request through the resource-server filter chain, so it needs a valid bearer token (the portal's
 * server sends the organizer's; no browser connects here). A subscription to {@code /topic/events/{id}} is allowed only
 * to whoever may read that event's attendance; clients can't send anything.
 *
 * <p>shortcut: the in-memory simple broker serves one checkin-service instance; switch to the RabbitMQ STOMP relay
 * before running several.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Pattern EVENT_TOPIC = Pattern.compile("/topic/events/(\\d+)");

    private final CheckinSecurity security;

    public WebSocketConfig(CheckinSecurity security) {
        this.security = security;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Any origin: the handshake is authenticated by its bearer token, not a cookie, so cross-site use gains
        // nothing.
        // The same-origin default refused every connection through the gateway: Reactor Netty sends Origin = the
        // service's address, while X-Forwarded-Host makes this service see the gateway's (found live, P7 exit demo).
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
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
                if (stomp.getCommand() == StompCommand.SEND) {
                    throw new MessageDeliveryException("This feed is read-only");
                }
                if (stomp.getCommand() == StompCommand.SUBSCRIBE && !maySubscribe(stomp)) {
                    throw new MessageDeliveryException("Not allowed to follow " + stomp.getDestination());
                }
                return message;
            }
        });
    }

    private boolean maySubscribe(StompHeaderAccessor stomp) {
        Matcher topic = EVENT_TOPIC.matcher(String.valueOf(stomp.getDestination()));
        if (!topic.matches() || !(stomp.getUser() instanceof Authentication user)) {
            return false;
        }
        // CheckinSecurity reads the security context, which a STOMP frame doesn't carry: lend it the handshake's user.
        var previous = SecurityContextHolder.getContext();
        try {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(user);
            SecurityContextHolder.setContext(context);
            return security.ownsEvent(Long.parseLong(topic.group(1)));
        } catch (RuntimeException e) {
            return false; // an unknown event, or event-service out of reach
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
