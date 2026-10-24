package com.mitpass.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A circuit breaker per target ({@code pass-service} for {@code http://pass-service} before the load balancer resolves
 * it, host:port otherwise; D17). Connection errors, timeouts and 5xx answers count as failures; 4xx answers mean the
 * target works. An open breaker fails at once with an {@link IOException}, which RestClient turns into the
 * {@code ResourceAccessException} every client already handles as "target unavailable". A GET that fails with an I/O
 * error is tried once more (Framework 7 rebuilds the rest of the interceptor chain on each call, tokens included).
 */
public class CircuitBreakerInterceptor implements ClientHttpRequestInterceptor {

    /** Thrown without calling the target while its breaker is open. */
    public static class CircuitOpenException extends IOException {
        CircuitOpenException(String target) {
            super("circuit breaker for " + target + " is open");
        }
    }

    private final CircuitBreakerRegistry breakers;
    private final boolean retryGets;

    public CircuitBreakerInterceptor(CircuitBreakerRegistry breakers, boolean retryGets) {
        this.breakers = breakers;
        this.retryGets = retryGets;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        CircuitBreaker breaker = breakers.circuitBreaker(target(request.getURI()));
        try {
            return attempt(breaker, request, body, execution);
        } catch (CircuitOpenException open) {
            throw open;
        } catch (IOException first) {
            if (!retryGets || request.getMethod() != HttpMethod.GET) {
                throw first;
            }
            return attempt(breaker, request, body, execution);
        }
    }

    private static ClientHttpResponse attempt(
            CircuitBreaker breaker, HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!breaker.tryAcquirePermission()) {
            throw new CircuitOpenException(breaker.getName());
        }
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long took = System.nanoTime() - start;
            if (response.getStatusCode().is5xxServerError()) {
                breaker.onError(took, TimeUnit.NANOSECONDS, new IOException("HTTP " + response.getStatusCode()));
            } else {
                breaker.onSuccess(took, TimeUnit.NANOSECONDS);
            }
            return response;
        } catch (IOException | RuntimeException ex) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, ex);
            throw ex;
        }
    }

    static String target(URI uri) {
        return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }
}
