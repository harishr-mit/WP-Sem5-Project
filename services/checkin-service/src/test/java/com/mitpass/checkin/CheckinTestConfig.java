package com.mitpass.checkin;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory stand-ins for the clock and the three services checkin-service talks to. */
@TestConfiguration(proxyBeanMethods = false)
public class CheckinTestConfig {

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

    @Bean
    @Primary
    FakePassDirectory fakePassDirectory() {
        return new FakePassDirectory();
    }

    @Bean
    @Primary
    TestPassKeys testPassKeys() {
        return new TestPassKeys();
    }
}
