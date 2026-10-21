package com.mitpass.common.messaging;

import com.mitpass.common.messaging.contract.EventType;
import java.util.UUID;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Boots just the auto-configuration under test, without component scanning (the lib's own classes are wired by it).
 * The outbox table comes from the reference DDL (spring.sql.init).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {

    @EventType("test.happened")
    public record TestHappened(UUID thingId, String note) {}

    public record NotAContract(String value) {}
}
