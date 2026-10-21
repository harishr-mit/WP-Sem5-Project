package com.mitpass.notification;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory owners, a settable clock, and GreenMail on {@code spring.mail.port} (3025, application-test.yml). */
@TestConfiguration(proxyBeanMethods = false)
public class NotificationTestConfig {

    /** One server for the JVM: test classes with mock beans get their own context, but the port is fixed. */
    private static final GreenMail GREEN_MAIL = start();

    private static GreenMail start() {
        GreenMail server = new GreenMail(ServerSetupTest.SMTP);
        server.start();
        return server;
    }

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    @Primary
    FakeEventDirectory fakeEventDirectory() {
        return new FakeEventDirectory();
    }

    @Bean
    @Primary
    FakePassMedia fakePassMedia() {
        return new FakePassMedia();
    }

    @Bean(destroyMethod = "")
    GreenMail greenMail() {
        return GREEN_MAIL;
    }
}
