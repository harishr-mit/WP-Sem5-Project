package com.mitpass.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void forwardsAValidIncomingId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/events").header(CorrelationIdGlobalFilter.HEADER, "client-7"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();
        exchange.getResponse().setComplete().block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo("client-7");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo("client-7");
    }

    @Test
    void sendsTheHeaderOnceEvenWhenTheServiceEchoesIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/events").header(CorrelationIdGlobalFilter.HEADER, "client-7"));
        GatewayFilterChain serviceEchoesHeader = forwarded -> {
            // What the routing filter does with the downstream response headers.
            forwarded.getResponse().getHeaders().add(CorrelationIdGlobalFilter.HEADER, "client-7");
            return Mono.empty();
        };

        filter.filter(exchange, serviceEchoesHeader).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdGlobalFilter.HEADER))
                .containsExactly("client-7");
    }

    @Test
    void generatesAnIdWhenMissingOrUnsafe() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/events")
                .header(CorrelationIdGlobalFilter.HEADER, "bad id with spaces"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();
        exchange.getResponse().setComplete().block();

        String id = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
        assertThat(id).hasSize(36).doesNotContain(" ");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo(id);
    }

    private static GatewayFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }
}
