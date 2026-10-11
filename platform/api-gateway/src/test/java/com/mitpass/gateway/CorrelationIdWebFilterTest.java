package com.mitpass.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    @Test
    void forwardsAValidIncomingId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/events").header(CorrelationIdWebFilter.HEADER, "client-7"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();
        exchange.getResponse().setComplete().block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdWebFilter.HEADER))
                .isEqualTo("client-7");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER))
                .isEqualTo("client-7");
        assertThat(forwarded.get().<String>getAttribute(CorrelationIdWebFilter.ATTRIBUTE))
                .isEqualTo("client-7");
    }

    @Test
    void sendsTheHeaderOnceEvenWhenTheServiceEchoesIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/events").header(CorrelationIdWebFilter.HEADER, "client-7"));
        WebFilterChain serviceEchoesHeader = forwarded -> {
            // What the routing filter does with the downstream response headers.
            forwarded.getResponse().getHeaders().add(CorrelationIdWebFilter.HEADER, "client-7");
            return Mono.empty();
        };

        filter.filter(exchange, serviceEchoesHeader).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdWebFilter.HEADER))
                .containsExactly("client-7");
    }

    @Test
    void generatesAnIdWhenMissingOrUnsafe() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/events").header(CorrelationIdWebFilter.HEADER, "bad id with spaces"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();
        exchange.getResponse().setComplete().block();

        String id = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdWebFilter.HEADER);
        assertThat(id).hasSize(36).doesNotContain(" ");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER))
                .isEqualTo(id);
    }

    private static WebFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }
}
