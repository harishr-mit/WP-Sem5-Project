package com.mitpass.pass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Passes for confirmed registrations (docs/plans/phase-4.md): issued from {@code RegistrationConfirmed}, signed as ES256
 * JWS tokens, rendered as QR codes and PDF tickets, revoked by organizers or by the cancellation saga.
 */
@SpringBootApplication
public class PassServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PassServiceApplication.class, args);
    }
}
