package com.mitpass.pass;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The context starts on the real schema (Flyway V1 + {@code ddl-auto=validate}). */
@PassIntegrationTest
class PassServiceApplicationTests {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndSchemaIsThere() {
        assertThat(jdbc.sql("SELECT count(*) FROM pass").query(Integer.class).single())
                .isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message")
                        .query(Integer.class)
                        .single())
                .isNotNull();
    }
}
