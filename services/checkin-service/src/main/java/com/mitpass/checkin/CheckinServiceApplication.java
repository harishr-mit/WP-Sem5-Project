package com.mitpass.checkin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gate scans (docs/plans/phase-5.md): a verification pipeline turns what the attendee shows into a typed result, and an
 * accepted scan becomes attendance and presence in one race-safe transaction. Pass data is a reconciled local copy of
 * pass-service's (D14), so a scan never waits for pass-service.
 */
@SpringBootApplication
public class CheckinServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckinServiceApplication.class, args);
    }
}
