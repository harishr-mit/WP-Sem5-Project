package com.mitpass.event.web.dto;

import java.time.Instant;

public record SessionResponse(Long id, String title, Instant startsAt, Instant endsAt) {}
