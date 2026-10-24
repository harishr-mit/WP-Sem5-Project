package com.mitpass.checkin.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.checkin.CheckinTestConfig;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.TestcontainersConfiguration;
import com.mitpass.common.resilience.CircuitBreakerInterceptor;
import com.mitpass.security.test.TestSecurityConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** Calls to other services go through the circuit breaker, ahead of the load balancer (D17). */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class, CheckinTestConfig.class, ScanScenario.class})
class ClientResilienceTest {

    @Autowired
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder;

    @Test
    void theBreakerSeesTheServiceNameBeforeTheLoadBalancerResolvesIt() {
        List<ClientHttpRequestInterceptor> chain = new ArrayList<>();
        loadBalancedRestClientBuilder.clone().requestInterceptors(chain::addAll);
        assertThat(chain).first().isInstanceOf(CircuitBreakerInterceptor.class);
        assertThat(chain)
                .anySatisfy(i -> assertThat(i.getClass().getSimpleName()).contains("LoadBalancer"));
    }
}
