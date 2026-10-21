package com.mitpass.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.notification.NotificationIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The scheduled job as production wires it: the "1m"/"4m" style durations parse, and a run takes and records the
 * ShedLock row. (Other tests switch the scheduler off and call {@link ReminderJob} directly.)
 */
@NotificationIntegrationTest
@TestPropertySource(
        properties = {"mitpass.notifications.reminders.enabled=true", "mitpass.notifications.reminders.interval=1h"})
class ReminderSchedulerTest {

    @Autowired
    ReminderScheduler scheduler;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aRunHoldsTheShedLockRow() {
        scheduler.run();

        assertThat(jdbc.sql("SELECT count(*) FROM shedlock WHERE name = 'notification-reminders'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
