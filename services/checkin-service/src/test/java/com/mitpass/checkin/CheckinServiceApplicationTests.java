package com.mitpass.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The context starts on the real schema (Flyway V1 + {@code ddl-auto=validate}). */
@CheckinIntegrationTest
class CheckinServiceApplicationTests {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndSchemaIsThere() {
        for (String table : new String[] {
            "event_ref",
            "pass_ref",
            "attendance",
            "presence",
            "presence_history",
            "scan_log",
            "sync_cursor",
            "processed_message",
            "outbox_message"
        }) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table)
                            .query(Integer.class)
                            .single())
                    .as(table)
                    .isNotNull();
        }
    }
}
