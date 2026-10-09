# Phase 1 plan: Foundation & Event Catalogue

> **Source:** `PROJECT.md` §P1. **Tracked in:** `log.md` as T03, sub-tasks T03.1–T03.10 (same numbering as the steps below).
> **Written:** 2026-10-09, after project initialization (T13). This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md`.

## 1. Goal and exit criteria

**Goal:** a running microservice skeleton with one real service (`event-service`) behind a gateway, registered in Eureka, backed by PostgreSQL through Flyway, and documented in Swagger.

**Exit demo** (all must pass before T03 is marked done):

1. `docker compose -f infra/docker-compose.yml up -d` brings up PostgreSQL, RabbitMQ and Mailpit, all `healthy`.
2. `discovery-server`, `api-gateway` and `event-service` start cleanly. The Eureka dashboard (http://localhost:8761) lists `API-GATEWAY` and `EVENT-SERVICE`.
3. Swagger UI **at the gateway** (http://localhost:8080/swagger-ui.html) shows the event-service API. "Try it out" creates an event and then finds it with `GET /api/events?category=…&q=…`.
4. An invalid payload returns **`400`** with `Content-Type: application/problem+json` and a per-field `errors` array (example in §6).
5. `./mvnw verify` is green locally (Testcontainers included) and in GitHub Actions.

## 2. Starting point and scope

**Already done in T13:** the root `pom.xml` (Boot 4.1.1, Cloud 2025.1.3, Java 21, enforcer, springdoc and MapStruct versions), the Maven wrapper, the project-local JDK, Docker Desktop, `.gitignore` / `.gitattributes` / `.editorconfig`. From PROJECT.md's first P1 bullet, only Spotless remains (T03.1).

**In scope:** everything in PROJECT.md §P1.

**Out of scope (and where it lands):**

| Not in P1 | Phase | What P1 does instead |
|---|---|---|
| Authentication / authorization | P2 | All endpoints are open; the auditor is the constant `"system"` |
| Event state transitions, seats, `@Version` conflict UX | P3 | `status` column exists (CHECK lists every state); new events are `DRAFT`; `version` column exists and a stale write already maps to 409 |
| RabbitMQ usage, outbox | P3 | RabbitMQ only runs in compose |
| Redis, rate limiting | P7/P8 | — |
| Service containers in compose | P8 | Services run with `./mvnw … spring-boot:run` or from the IDE |

## 3. Module layout after Phase 1

```
pom.xml                          # <modules> in this order ↓
libs/common-web/                 # jar library, auto-configured
platform/discovery-server/       # Eureka :8761
platform/api-gateway/            # Spring Cloud Gateway (WebFlux) :8080
services/event-service/          # :8082
infra/docker-compose.yml
infra/postgres/init.sql
docs/http/phase-1.http           # demo requests (VS Code REST Client / IntelliJ)
.github/workflows/ci.yml
```

| Module | Base package | Key dependencies (artifactIds) |
|---|---|---|
| `common-web` | `com.mitpass.common.web` | `spring-boot-starter-webmvc` (provided/optional), `spring-boot-starter-validation`; test: `spring-boot-starter-webmvc-test` |
| `discovery-server` | `com.mitpass.discovery` | `spring-cloud-starter-netflix-eureka-server`, `spring-boot-starter-actuator` |
| `api-gateway` | `com.mitpass.gateway` | `spring-cloud-starter-gateway-server-webflux`, `spring-cloud-starter-netflix-eureka-client`, `springdoc-openapi-starter-webflux-ui`, `spring-boot-starter-actuator` |
| `event-service` | `com.mitpass.event` | `spring-boot-starter-webmvc`, `-data-jpa`, `-validation`, `-flyway`, `-actuator`, `flyway-database-postgresql`, `postgresql` (runtime), `spring-cloud-starter-netflix-eureka-client`, `springdoc-openapi-starter-webmvc-ui`, `mapstruct` (+ `mapstruct-processor` on the compiler's processor path), `common-web`; test: `spring-boot-starter-webmvc-test`, `-data-jpa-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter` |

Every module's parent is the root POM. Apps declare `spring-boot-maven-plugin`; `common-web` does not (it stays a plain jar).

**event-service package layout** (layered, so an evaluator can point at each concept):

```
com.mitpass.event
├── EventServiceApplication
├── config/       JpaConfig (@EnableJpaAuditing, AuditorAware), OpenApiConfig, TimeConfig
├── domain/       Event, EventSession, Venue, Category, EventStatus, AuditableEntity (@MappedSuperclass)
├── repository/   EventRepository (+ JpaSpecificationExecutor), EventSpecifications, VenueRepository, CategoryRepository
├── service/      EventService, SessionService, VenueService, CategoryService, EventSearchCriteria
├── web/          EventController, SessionController, VenueController, CategoryController
│   └── dto/      request/response records
├── mapper/       EventMapper, VenueMapper, CategoryMapper (MapStruct)
└── validation/   @ValidTimeWindow + validator (class-level, cross-field)
```

## 4. Key decisions for this phase

| Topic | Choice | Why |
|---|---|---|
| Formatting / lint | **Spotless** with palantir-java-format; `spotless:check` bound to `verify`; no Checkstyle | One automatic rule set (`./mvnw spotless:apply` fixes everything). Checkstyle would add friction without showing any course concept. Revisit in P8 if wanted |
| URL paths | Services serve `/api/...` themselves (`/api/events`); the gateway routes by path with **no rewriting** | Swagger "Try it out" through the gateway then hits the right URLs with no server-URL hacks |
| Gateway flavour | Spring Cloud Gateway **Server WebFlux** | Redis `RequestRateLimiter` (P8) lives there |
| IDs | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | Short ids are easy to type in demos (`/api/events/3`). Auth user ids will be UUIDs (P2) |
| Event code | `code` column, `^[A-Z0-9]{3,16}$`, unique (e.g. `TECHFEST26`) | P4 pass numbers are `TECHFEST26-000042-7`; adding the code now avoids a later migration |
| Time | `Instant` in entities and the API (UTC, ISO-8601), `timestamptz` columns, `hibernate.jdbc.time_zone=UTC`. Search `from` / `to` are **dates** read in `Asia/Kolkata` (`mitpass.time-zone`) | Unambiguous storage, friendly search |
| Fetch plans | Every `@ManyToOne` is explicitly `LAZY`; `sessions` is `LAZY`. `@EntityGraph`: list/search = `{category, venue}`, detail = `{category, venue, sessions}`. `spring.jpa.open-in-view=false`, `ddl-auto=validate` | This is the "explicit fetch strategy" P1 asks for. No N+1 and no collection fetch while paginating (which would make Hibernate paginate in memory) |
| Sessions API | Created inline with `POST /api/events` (shows cascade), then managed through the sub-resource `/api/events/{id}/sessions` | Session ids stay stable. checkin-service records attendance per session (P5), so ids must never churn |
| Errors | One `@RestControllerAdvice` in `common-web` extending `ResponseEntityExceptionHandler`; every error is a `ProblemDetail` | Consistent RFC 9457 bodies across all future services |
| Pagination | Own `PageResponse<T>` record; `max-page-size: 100`; default sort `startsAt,asc` | Spring Data warns against serializing `PageImpl`; this gives a stable JSON contract |
| DTOs | Java records, MapStruct `componentModel = "spring"`, `unmappedTargetPolicy = ERROR` | Entities never leak; a forgotten field fails at compile time |
| Lombok | Not used | Records cover DTOs; four plain entities are fine |
| Seed data | Categories and venues: versioned `V2__reference_data.sql`. Demo events: repeatable `db/seed/R__demo_seed.sql` (idempotent, `ON CONFLICT (code) DO NOTHING`), loaded by default and switched off in tests | Swagger has data to search on first start. A repeatable migration avoids version-ordering trouble with later `V3+` migrations |
| Test profile | Test classes use `@ActiveProfiles("test")`; `application-test.yml` sets `spring.flyway.locations=classpath:db/migration`, `eureka.client.enabled=false` | Don't put an `application.yml` in `src/test/resources`; it would hide the main one completely |

## 5. Data model (`V1__init.sql`)

Column names are snake_case (Boot's default naming strategy maps camelCase fields).

| Table | Columns | Constraints / indexes |
|---|---|---|
| `category` | `id`, `code` varchar(32), `name` varchar(80) | `code` unique |
| `venue` | `id`, `name` varchar(120), `building` varchar(80) null, `capacity` int, `map_url` varchar(500) null, audit cols, `version` bigint | `name` unique; `capacity > 0` |
| `event` | `id`, `code` varchar(16), `title` varchar(160), `description` text null, `category_id`, `venue_id`, `department` varchar(80) null, `capacity` int, `starts_at`, `ends_at`, `registration_opens_at` null, `registration_closes_at` null, `status` varchar(24) default `'DRAFT'`, `version` bigint default 0, audit cols | `code` unique + `CHECK (code ~ '^[A-Z0-9]{3,16}$')`; FKs to category/venue; `capacity > 0`; `ends_at > starts_at`; `registration_opens_at < registration_closes_at <= starts_at` when set; `status IN (DRAFT, PUBLISHED, REGISTRATION_OPEN, REGISTRATION_CLOSED, LIVE, COMPLETED, CANCELLED)`; indexes on `starts_at`, `category_id`, `venue_id`, `department` |
| `event_session` | `id`, `event_id`, `title` varchar(160), `starts_at`, `ends_at` | FK `ON DELETE CASCADE`; `ends_at > starts_at`; unique `(event_id, starts_at)`; index `event_id` |

Audit cols: `created_at`, `created_by`, `updated_at`, `updated_by` (filled by Spring Data auditing through `AuditableEntity`). P3 adds `seats_taken` and `event_state_history`; P2 adds the organizer link.

Seed (placeholders, the owner can change them): categories `TECHNICAL, CULTURAL, SPORTS, WORKSHOP, SEMINAR, CLUB`; about 5 venues; about 6 demo events, including `TECHFEST26` ("TechFest Hackathon", 2 sessions), which the demo script uses.

## 6. REST API (event-service; the same paths work through the gateway)

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/api/events` | 200 `PageResponse<EventSummary>` | 400 bad params |
| GET | `/api/events/{id}` | 200 `EventDetail` (with sessions, venue, category) | 404 |
| POST | `/api/events` | 201 + `Location`, `EventDetail` | 400, 409 duplicate code, 422 |
| PUT | `/api/events/{id}` | 200 `EventDetail` (event fields only, not sessions) | 400, 404, 409 (code / stale version), 422 |
| DELETE | `/api/events/{id}` | 204 | 404, 409 if not `DRAFT` |
| GET / POST | `/api/events/{id}/sessions` | 200 list / 201 + `Location` | 404, 400, 422 (outside event window) |
| PUT / DELETE | `/api/events/{id}/sessions/{sessionId}` | 200 / 204 | 404, 400, 422 |
| GET / POST | `/api/venues` | 200 paged / 201 | 400, 409 duplicate name |
| GET / PUT | `/api/venues/{id}` | 200 | 404, 400, 409 |
| GET | `/api/categories` | 200 list (reference data, read-only) | — |

**Search:** `GET /api/events?q=&category=&department=&from=&to=&status=&page=&size=&sort=`.
- `q`: case-insensitive substring of title or description.
- `category`: category code.
- `from` / `to`: inclusive dates on `starts_at` in `Asia/Kolkata`.
- `status`: an `EventStatus`.

`EventSearchCriteria` (record) → `EventSpecifications` (one static factory per filter; a `null` filter adds no predicate) → combined with `Specification.allOf(...)` → `findAll(spec, pageable)` with the list entity graph. Repeated filters are ANDed.

**Validation:**
- Bean Validation on request records (`@NotBlank`, `@Size`, `@Pattern` for `code`, `@Positive`, `@Future` for `startsAt` on create).
- A class-level `@ValidTimeWindow` (custom constraint) checks `endsAt > startsAt` and the registration-window order.
- `@Valid` on nested session lists.

Rules that need the database return **422** from the service layer: capacity > venue capacity, unknown `categoryId` / `venueId`, a session outside the event window.

**Error contract** (`application/problem+json`), the response for exit criterion 4:

```json
{
  "type": "urn:mitpass:problem:validation",
  "title": "Validation failed",
  "status": 400,
  "detail": "2 fields are invalid",
  "instance": "/api/events",
  "correlationId": "5f0c…",
  "errors": [
    { "field": "title", "message": "must not be blank" },
    { "field": "endsAt", "message": "must be after startsAt" }
  ]
}
```

| Exception | Status | `type` slug |
|---|---|---|
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` | 400 | `validation` |
| `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` | 400 | `malformed-request` |
| `NotFoundException` (common-web) | 404 | `not-found` |
| `ConflictException` (common-web), `DataIntegrityViolationException`, `OptimisticLockingFailureException` | 409 | `conflict` |
| `BusinessRuleException` (common-web) | 422 | `business-rule` |
| anything else | 500 | `internal` (generic detail, logged with the correlation id, no stack trace in the body) |

## 7. `libs/common-web`

- `error/GlobalExceptionHandler` (the table above), `NotFoundException`, `ConflictException`, `BusinessRuleException`, `ProblemTypes` (the URN constants).
- `paging/PageResponse<T>` with `static from(Page<T>)`.
- `correlation/CorrelationIdFilter` (`OncePerRequestFilter`, highest precedence): reads `X-Correlation-Id` or generates a UUID, puts it in MDC `correlationId`, echoes the response header, clears MDC in `finally`. Log pattern: `logging.pattern.level: "%5p [%X{correlationId:-}]"`.
- `CommonWebAutoConfiguration`, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and `@ConditionalOnWebApplication(type = SERVLET)`. Services get everything by adding the dependency.

## 8. Platform services

**discovery-server:** `@EnableEurekaServer`, port 8761, `eureka.client.register-with-eureka=false`, `fetch-registry=false`, self-preservation off in dev.

**api-gateway:** port 8080.

```yaml
spring.cloud.gateway.server.webflux:
  routes:
    - id: event-service
      uri: lb://event-service
      predicates: [ "Path=/api/events/**,/api/venues/**,/api/categories/**" ]
    - id: event-service-openapi
      uri: lb://event-service
      predicates: [ "Path=/v3/api-docs/event-service" ]
      filters: [ "SetPath=/v3/api-docs" ]
springdoc.swagger-ui.urls:
  - { name: event-service, url: /v3/api-docs/event-service }
```

- A `GlobalFilter` adds `X-Correlation-Id` at the edge when it's missing (the gateway is reactive, so the servlet filter in `common-web` doesn't apply).
- Actuator exposes `health, info, gateway`, so `/actuator/gateway/routes` lists the routes.
- Services set `server.forward-headers-strategy: framework`, so springdoc builds server URLs pointing at the gateway when it is reached through it.
- Each future service adds one route pair and one `swagger-ui.urls` entry.

## 9. Infrastructure (`infra/docker-compose.yml`, project name `mitpass`)

| Service | Image | Ports | Notes |
|---|---|---|---|
| postgres | `postgres:16-alpine` | 5432 | `infra/postgres/init.sql` mounted into `/docker-entrypoint-initdb.d`; healthcheck `pg_isready`; named volume |
| rabbitmq | `rabbitmq:4-management-alpine` | 5672, 15672 | user `mitpass` / `mitpass` (the `guest` user can't log in from outside the container); healthcheck `rabbitmq-diagnostics -q ping` |
| mailpit | `axllent/mailpit` (pin a `v1.x` tag) | 1025 SMTP, 8025 UI | healthcheck via the image's `readyz` |

`init.sql` creates **all five** service databases and roles now: `auth_db`/`auth_svc`, `event_db`/`event_svc`, `pass_db`/`pass_svc`, `checkin_db`/`checkin_svc`, `notification_db`/`notification_svc`. Init scripts run only on an empty volume, so creating them up front means later phases never need a `down -v`. Dev passwords live in compose as defaults that a gitignored `.env` can override.

## 10. Ports and running locally

| Port | What |
|---|---|
| 8761 | Eureka dashboard |
| 8080 | Gateway (Swagger UI at `/swagger-ui.html`) |
| 8082 | event-service (direct; Swagger at `/swagger-ui.html`) |
| 5432 / 5672 / 15672 / 1025 / 8025 | Postgres / AMQP / RabbitMQ UI / SMTP / Mailpit UI |

```bash
docker compose -f infra/docker-compose.yml up -d
./mvnw -q install -DskipTests                                   # once, so common-web is in ~/.m2
./mvnw -pl platform/discovery-server spring-boot:run           # terminal 1
./mvnw -pl platform/api-gateway spring-boot:run                # terminal 2
./mvnw -pl services/event-service spring-boot:run              # terminal 3
# stop: Ctrl+C in each, then: docker compose -f infra/docker-compose.yml down
```

Optional: a `.vscode/launch.json` compound "MITPass: P1" that starts all three (add `!.vscode/launch.json` to `.gitignore`).

## 11. Tests

| Test | Kind | Proves |
|---|---|---|
| `GlobalExceptionHandlerTest` (common-web) | `@WebMvcTest` with a dummy controller | Each exception → right status, `application/problem+json`, `errors[]`, `correlationId` |
| `CorrelationIdFilterTest` | unit | Header is propagated or generated; MDC is cleared |
| `EventRepositoryTest` | `@DataJpaTest` + Testcontainers Postgres (`@ServiceConnection`) | Flyway V1/V2 apply and `ddl-auto=validate` passes; saving an event cascades sessions; auditing fills `created_*`; the detail entity graph loads sessions outside a transaction |
| `EventSpecificationsTest` | `@DataJpaTest` + Testcontainers | Each filter alone and combined: category, department, date range across the IST midnight boundary, case-insensitive `q`, paging + sort |
| `EventControllerTest`, `VenueControllerTest` | `@WebMvcTest` + `@MockitoBean` services | Invalid payload → 400 with field errors; unknown id → 404; create → 201 + `Location`; search params bind |
| `EventServiceTest` | unit (Mockito) | 422 rules (capacity vs venue, unknown category/venue, session window), 409 on delete of a non-DRAFT event |
| `EventMapperTest` | unit | Request → entity and entity → DTO mappings |
| `EventServiceApplicationIT` | `@SpringBootTest` + Testcontainers | Context starts against real Postgres; `/v3/api-docs` is served |
| `DiscoveryServerApplicationTests`, `ApiGatewayApplicationTests` | `@SpringBootTest` | Contexts load; the gateway's route ids exist |

A shared `TestcontainersConfiguration` (`@TestConfiguration` with a `@ServiceConnection PostgreSQLContainer`) is reused by the DB tests. Docker Desktop must be running locally; GitHub's Ubuntu runners have Docker.

## 12. Steps (log.md T03.1–T03.10)

Each step ends with `./mvnw verify` green and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T03.1 | **Build conventions:** Spotless (palantir) in the root POM with `check` bound to `verify`; compiler `-Xlint:all,-processing,-serial`; MapStruct processor in the root `pluginManagement` with `-Amapstruct.defaultComponentModel=spring`, `-Amapstruct.unmappedTargetPolicy=ERROR` | S | `./mvnw verify` runs Spotless; an unformatted file fails it and `spotless:apply` fixes it |
| T03.2 | **Infra:** `infra/docker-compose.yml`, `infra/postgres/init.sql` | S | `docker compose … up -d` → 3× healthy; `psql -l` lists the 5 DBs; RabbitMQ UI login and Mailpit UI load; `down` cleans up |
| T03.3 | **discovery-server** module | S | Dashboard at :8761; context test green |
| T03.4 | **common-web** library + tests | M | Handler and filter tests green; auto-config imports file present |
| T03.5 | **event-service persistence:** module, app class, `application.yml` / `application-test.yml`, Flyway V1 + V2, entities + auditing, repositories, `TestcontainersConfiguration`, repository tests | M | `@DataJpaTest`s green on Testcontainers; the service starts against compose Postgres and registers in Eureka |
| T03.6 | **event-service REST:** DTO records, MapStruct mappers, services, the 4 controllers, `EventSpecifications`, `@ValidTimeWindow` | L | Controller, specification, service and mapper tests green; the §6 table behaves as written (check with `curl` on :8082) |
| T03.7 | **OpenAPI + gateway:** springdoc in event-service (title, tags, example payloads), `api-gateway` module (routes, correlation filter, aggregated Swagger UI) | M | Swagger UI at :8080 shows event-service; "Try it out" works through the gateway; `X-Correlation-Id` shows up in event-service logs |
| T03.8 | **Demo data and requests:** `db/seed/R__demo_seed.sql`, `docs/http/phase-1.http` (create, search, invalid → 400, unknown → 404, duplicate code → 409, capacity → 422); optional VS Code launch compound | S | A fresh start shows the seed events; every request in the `.http` file returns the commented status |
| T03.9 | **CI:** `.github/workflows/ci.yml` (`actions/checkout`, `actions/setup-java` Temurin 21 with Maven cache, `./mvnw -B -ntp verify`) on push and PR | S | First push builds green. `git ls-files -s mvnw` shows `100755` (already staged that way; see DECISIONS D5) |
| T03.10 | **Exit demo + docs:** run §1 end to end; README "Run Phase 1" section; tick the PROJECT.md §P1 boxes; update `log.md` Current Status and Commands; promote any cross-phase decision to `docs/DECISIONS.md` | S | All five exit criteria pass; nothing left running (services stopped, `docker compose down`) |

Order of dependencies: T03.1 → T03.2 → (T03.3 ∥ T03.4) → T03.5 → T03.6 → T03.7 → T03.8 → T03.9 → T03.10. CI (T03.9) can move earlier if wanted; it only needs T03.1.

## 13. Boot 4 / Cloud 2025.1 gotchas

Most online examples target Boot 3. Check against these first when something doesn't compile or autoconfigure:

1. **Modular starters.** Use `spring-boot-starter-webmvc` (`-web` is the deprecated alias). Flyway needs **`spring-boot-starter-flyway`**, since plain `flyway-core` no longer auto-configures, plus `flyway-database-postgresql`.
2. **Test slices moved.** `@WebMvcTest` comes from `spring-boot-starter-webmvc-test` (package `org.springframework.boot.webmvc.test.autoconfigure`), and `@DataJpaTest` from `spring-boot-starter-data-jpa-test` (`org.springframework.boot.data.jpa.test.autoconfigure`). `@MockBean` is gone; use `@MockitoBean`.
3. **Jackson 3.** `ObjectMapper` is `tools.jackson.databind.ObjectMapper`; annotations stay in `com.fasterxml.jackson.annotation`. Java time support is built in.
4. **Testcontainers 2.** Artifacts are `testcontainers-postgresql` / `testcontainers-junit-jupiter`. Use `org.testcontainers.postgresql.PostgreSQLContainer` (new package, no generic type parameter). The old `org.testcontainers.containers.PostgreSQLContainer` still ships in 2.0.5 but is deprecated.
5. **Gateway 5.** The starter is `spring-cloud-starter-gateway-server-webflux`, and properties live under `spring.cloud.gateway.server.webflux.*` (the old `spring.cloud.gateway.routes` prefix is deprecated).
6. **Spring Data JPA 4.1.** Collect the non-null filter specs in a list and combine them with `Specification.allOf(list)`; `Specification.unrestricted()` is the "no filter" value. Don't pass `null` specs, since the 4.x APIs are null-annotated. (`where(...)` still exists, now with a `PredicateSpecification` overload, but isn't needed.)
7. **`@DataJpaTest` with Testcontainers.** If the embedded-database replacement kicks in, add `@AutoConfigureTestDatabase(replace = NONE)`.
8. **Windows path with spaces** (`WP Sem 5`). The wrapper handles it (verified). Quote paths in scripts and compose bind mounts.
9. **Terminals opened before the Docker install** have no `docker` on PATH; open a new one.

## 14. Open questions for the owner

Answering these before T03.5 keeps renames cheap. Defaults are in brackets and the plan proceeds with them.

1. Java coordinates: groupId / package root **[`com.mitpass`]** (`docs/DECISIONS.md` D4).
2. Lint: Spotless only, no Checkstyle **[Spotless only]**.
3. Seed categories and venues **[placeholders in §5]**. Swap in real MIT ones if you have them.
