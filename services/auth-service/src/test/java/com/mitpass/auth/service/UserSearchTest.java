package com.mitpass.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.TestAccounts;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The admin user search goes through the trigram index and puts the best match first (V5, D17). */
@AuthIntegrationTest
class UserSearchTest {

    @Autowired
    UserAdminService admin;

    @Autowired
    TestAccounts accounts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void partOfANameEmailOrNumberFindsTheUserBestMatchFirstAndTheRoleFilterStillApplies() {
        User exact = accounts.verifiedUser("zephyrine.quartz@gmail.com", RoleName.STUDENT);
        User near = accounts.verifiedUser("zephyrine.quartzite.long.address@gmail.com", RoleName.ORGANIZER);

        List<String> found = admin.search(
                        "ZEPHYRINE.QUARTZ",
                        null,
                        PageRequest.of(0, 20, Sort.by("email").descending()))
                .map(User::getEmail)
                .getContent();
        assertThat(found).containsExactly(exact.getEmail(), near.getEmail());

        assertThat(admin.search("zephyrine", RoleName.ORGANIZER, PageRequest.of(0, 20))
                        .map(User::getId)
                        .getContent())
                .containsExactly(near.getId());
        assertThat(admin.search("zephyrine", null, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void theSearchUsesTheTrigramIndexNotATableScan() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO app_user (id, email, canonical_email, full_name, department_code, password_hash,
                                                  created_at, created_by, updated_at, updated_by)
                            SELECT gen_random_uuid(), 'bulk' || g || '@gmail.com', 'bulk' || g || '@gmail.com',
                                   'Bulk Student ' || g, 'CSE', 'x', now(), 'test', now(), 'test'
                            FROM generate_series(1, 2000) g""").update();
            jdbc.sql("ANALYZE app_user").update();
            // At a few thousand short rows a table scan is legitimately cheaper, so the planner may pick it; switch it
            // off for this transaction to show the index serves LIKE '%…%' (it is what keeps a large table fast).
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            String plan = String.join(
                    "\n",
                    jdbc.sql("EXPLAIN SELECT id FROM app_user WHERE search_text LIKE '%student 1234%'")
                            .query(String.class)
                            .list());
            assertThat(plan).contains("ix_app_user_search_trgm");
            status.setRollbackOnly(); // the 2,000 rows never reach other tests
        });
    }
}
