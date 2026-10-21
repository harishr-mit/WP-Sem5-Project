package com.mitpass.notification.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link ReminderJob} on a timer, at most one instance at a time (ShedLock row {@code notification-reminders}). */
@Component
@ConditionalOnProperty(prefix = "mitpass.notifications.reminders", name = "enabled", matchIfMissing = true)
public class ReminderScheduler {

    private final ReminderJob job;

    public ReminderScheduler(ReminderJob job) {
        this.job = job;
    }

    @Scheduled(
            fixedDelayString = "${mitpass.notifications.reminders.interval:1m}",
            initialDelayString = "${mitpass.notifications.reminders.interval:1m}")
    @SchedulerLock(
            name = "notification-reminders",
            lockAtMostFor = "${mitpass.notifications.reminders.lock-at-most-for:4m}")
    public void run() {
        job.runOnce();
    }
}
