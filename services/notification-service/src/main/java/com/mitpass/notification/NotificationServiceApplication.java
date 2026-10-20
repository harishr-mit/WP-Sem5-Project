package com.mitpass.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Emails from the domain events (docs/plans/phase-7.md §6, D16): passes with their QR code and a calendar invite,
 * waitlist and cancellation notes, event changes, and reminders 24 h and 1 h before. It keeps no copy of anyone's
 * data: recipients of bulk emails are read from event-service when the email goes out.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
