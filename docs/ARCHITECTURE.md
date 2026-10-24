# MITPass architecture

How the system fits together, in about two pages, and **where each concept of the course lives in the code** (the table in the second half). The plan and the reasons are in [PROJECT.md](../PROJECT.md) and [DECISIONS.md](DECISIONS.md); this page is the map.

## The shape

```
 browser ──► web-portal :8090 ──┐   (Spring MVC + Thymeleaf; BFF: tokens stay in the server session)
 phone scanner (PWA) ───────────┤
                                ▼
                       api-gateway :8080 ── JWT check · CORS · rate limits (Redis) · routing (Eureka)
                                │
      ┌───────────┬─────────────┼─────────────┬───────────────┐
      ▼           ▼             ▼             ▼               ▼
 auth-service  event-service  pass-service  checkin-service  notification-service
   users         events         passes        scans            emails, reminders
   JWT           registrations QR, PDF       attendance        .ics
   JWKS          waitlist      revocations   live counters
      │            │ outbox        │ outbox       │ outbox          ▲
      └────────────┴───────► RabbitMQ (events) ────┴────────────────┘
 one PostgreSQL database per service · Redis (rate limits, live counters) · Mailpit (all mail)
 Prometheus ► Grafana · Zipkin · Eureka (discovery)
```

- **Database per service.** A service never reads another's database. Questions go over REST, facts over RabbitMQ. A copy of another service's data (pass-service's `event_ref`, checkin-service's `pass_ref`) is a read-only cache that is reconciled automatically ([D14](DECISIONS.md)).
- **Transactional outbox.** A business change and the event announcing it commit in one transaction; a relay publishes it to RabbitMQ at least once; consumers deduplicate on the message id ([D11](DECISIONS.md), [D13](DECISIONS.md)). Messages that keep failing wait in a dead-letter queue that an admin can replay.
- **Signed passes.** pass-service signs a compact ES256 token; checkin-service verifies it against pass-service's public keys, with no call to pass-service per scan ([D12](DECISIONS.md)).
- **One request, one trace.** The trace id travels in HTTP headers and, through the outbox, in the RabbitMQ message, so a scan shows up in Zipkin from the gateway to pass-service ([D17](DECISIONS.md)).

## The core flow

register (`event-service`: seat claim + registration + outbox row in one transaction) → `RegistrationConfirmed` → pass issued and signed (`pass-service`) → `PassIssued` → check-in copy + email → live QR (rotating, 45 s) → scan (`checkin-service`: signature → expiry → event → gate → revoked → duplicate; one transaction records attendance) → `AttendanceMarked` → the pass is marked used, the live dashboard ticks, and the organizer sees it.

## The ten concepts and where they live

| # | Concept | Where to look |
|---|---|---|
| 1 | **Spring MVC** | [`web-portal`](../web-portal/src/main/java/com/mitpass/portal/web/): `@Controller`s with `Model`, form objects and `BindingResult` ([`EventForm`](../web-portal/src/main/java/com/mitpass/portal/web/forms/EventForm.java)), Post/Redirect/Get with flash messages, interceptors, custom 403/404/500 pages, Thymeleaf templates in `src/main/resources/templates`, English and Tamil bundles |
| 2 | **REST** | every service: ProblemDetail errors ([`GlobalExceptionHandler`](../libs/common-web/src/main/java/com/mitpass/common/web/error/GlobalExceptionHandler.java)), `PageResponse`, [`Idempotency-Key`](../services/event-service/src/main/java/com/mitpass/event/idempotency/), OpenAPI/Swagger UI at the gateway; conventions in [D6](DECISIONS.md) |
| 3 | **JPA / Hibernate** | [`Event`](../services/event-service/src/main/java/com/mitpass/event/domain/Event.java) and its relationships, Flyway migrations (`db/migration` in each service), [Specifications](../services/event-service/src/main/java/com/mitpass/event/repository/EventSpecifications.java) for search (with a trigram index), auditing, `@Version`, `@EntityGraph` |
| 4 | **Authentication** | [`LoginService`](../services/auth-service/src/main/java/com/mitpass/auth/service/LoginService.java) (BCrypt, lockout, ban), [`RefreshTokenService`](../services/auth-service/src/main/java/com/mitpass/auth/service/RefreshTokenService.java) (rotation and reuse detection), RS256 access tokens, the JWKS endpoint, email verification |
| 5 | **Authorization** | `@PreAuthorize` on every endpoint with ownership checks ([`EventSecurity`](../services/event-service/src/main/java/com/mitpass/event/security/)), the volunteer-to-gate assignment checked at scan time, the role × endpoint matrix in [security-matrix.md](security-matrix.md) |
| 6 | **QR generation** | [`QrRenderer`](../services/pass-service/src/main/java/com/mitpass/pass/qr/QrRenderer.java) (ZXing, logo, per-event colours), [`PassTokenService`](../services/pass-service/src/main/java/com/mitpass/pass/token/PassTokenService.java) (the signed JWS, rotating live code), [`TicketPdfRenderer`](../services/pass-service/src/main/java/com/mitpass/pass/ticket/TicketPdfRenderer.java) |
| 7 | **QR verification** | the pipeline in [`checkin-service/.../verify`](../services/checkin-service/src/main/java/com/mitpass/checkin/verify/), [`PassTokenVerifier`](../libs/common-pass/src/main/java/com/mitpass/common/pass/PassTokenVerifier.java), and offline in the browser with WebCrypto ([`offline.js`](../web-portal/src/main/resources/static/js/offline.js)) |
| 8 | **Transactional DB operations** | the seat claim in [`RegistrationService`](../services/event-service/src/main/java/com/mitpass/event/service/RegistrationService.java) (500 parallel registrations for 100 seats: [`SeatClaimConcurrencyTest`](../services/event-service/src/test/java/com/mitpass/event/service/SeatClaimConcurrencyTest.java)), the race-safe [`CheckInTransaction`](../services/checkin-service/src/main/java/com/mitpass/checkin/service/CheckInTransaction.java), [`OutboxPublisher`](../libs/common-messaging/src/main/java/com/mitpass/common/messaging/OutboxPublisher.java), [`IdempotentConsumer`](../libs/common-messaging/src/main/java/com/mitpass/common/messaging/IdempotentConsumer.java) |
| 9 | **State management** | enum transition tables: [`EventStatus`](../services/event-service/src/main/java/com/mitpass/event/domain/EventStatus.java), [`RegistrationStatus`](../services/event-service/src/main/java/com/mitpass/event/domain/RegistrationStatus.java), [`PassStatus`](../services/pass-service/src/main/java/com/mitpass/pass/domain/PassStatus.java), [`PresenceState`](../services/checkin-service/src/main/java/com/mitpass/checkin/domain/PresenceState.java); an illegal move is a 409 listing the allowed states; every move is recorded in a history table |
| 10 | **Microservices** | [`api-gateway`](../platform/api-gateway/) and [`discovery-server`](../platform/discovery-server/), RabbitMQ events, the cancellation saga ([`PassLifecycleService`](../services/pass-service/src/main/java/com/mitpass/pass/service/PassLifecycleService.java)), circuit breakers ([`CircuitBreakerInterceptor`](../libs/common-resilience/src/main/java/com/mitpass/common/resilience/CircuitBreakerInterceptor.java)), gateway [rate limits](../platform/api-gateway/src/main/java/com/mitpass/gateway/ratelimit/), Prometheus, Grafana and Zipkin |

## Running it and checking it

- **Everything:** `docker compose up --build` ([README](../README.md)), then follow the [demo script](DEMO_SCRIPT.md).
- **Tests:** `./mvnw verify` (unit, slice and Testcontainers tests for every module); `./mvnw -Pe2e -pl e2e-tests verify` against the running stack; the Gatling load test in [load-test/](load-test/).
- **Operations:** health at *Admin · Health* in the portal (also dead-letter queues), metrics in Grafana, traces in Zipkin. Services expose actuator only on a separate management port (10000 + the service port) that is never published.
