package com.mitpass.common.messaging;

import com.mitpass.common.messaging.contract.EventType;
import java.util.UUID;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots just the auto-configuration under test. The outbox table comes from the reference DDL (spring.sql.init). */
@SpringBootApplication
public class TestApplication {

    @EventType("test.happened")
    public record TestHappened(UUID thingId, String note) {}

    public record NotAContract(String value) {}
}
