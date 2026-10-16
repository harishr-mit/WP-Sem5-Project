package com.mitpass.pass;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class PassTestConfig {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    @Primary
    FakeEventCatalog fakeEventCatalog() {
        return new FakeEventCatalog();
    }
}
