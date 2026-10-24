package com.mitpass.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.event.EventIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The catalogue's text search goes through the trigram index on {@code search_text} (V9, D17), not a table scan. */
@EventIntegrationTest
class EventSearchIndexTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void textSearchUsesTheTrigramIndex() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO event (code, title, description, category_id, venue_id, capacity, starts_at,
                                               ends_at, organizer_id, organizer_name, created_at, created_by, updated_at, updated_by)
                            SELECT 'BULK' || g, 'Bulk Workshop ' || g, 'Generated for the index check', 4, 3, 50,
                                   now() + interval '30 days', now() + interval '30 days 2 hours', gen_random_uuid(), 'Test Organizer',
                                   now(), 'test', now(), 'test'
                            FROM generate_series(1, 2000) g""").update();
            jdbc.sql("ANALYZE event").update();
            // At a few thousand short rows a table scan is legitimately cheaper, so the planner may pick it; switch it
            // off for this transaction to show the index serves LIKE '%…%' (it is what keeps a large table fast).
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            String plan = String.join(
                    "\n",
                    jdbc.sql("EXPLAIN SELECT id FROM event WHERE search_text LIKE '%workshop 1234%'")
                            .query(String.class)
                            .list());
            assertThat(plan).contains("ix_event_search_trgm");
            status.setRollbackOnly(); // the 2,000 rows never reach other tests
        });
    }
}
