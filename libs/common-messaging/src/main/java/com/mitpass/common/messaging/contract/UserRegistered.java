package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/** Someone signed up (auth-service). notification-service (P7) sends the welcome email. Contract: add fields only. */
@EventType("user.registered")
public record UserRegistered(
        UUID userId, String email, String fullName, String department, Integer year, Instant registeredAt) {}
