# Phase 2 plan: Identity, Authentication & Authorization

> **Source:** `PROJECT.md` §P2. **Tracked in:** `log.md` as T04, sub-tasks T04.1–T04.12 (same numbering as the steps in §13).
> **Written:** 2026-10-11, after Phase 1 was verified complete (CI green on `baa98cd` and `6b2699d`). This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md` and in a closing "As built" section here.

## 1. Goal and exit criteria

**Goal:** every write is authenticated and every action is authorized, across services, with no shared secret: `auth-service` signs RS256 access tokens, and the gateway and each service verify them against its JWKS.

**Exit demo** (all must pass before T04 is marked done):

1. `discovery-server`, `auth-service`, `event-service` and `api-gateway` start on compose infra; the Eureka dashboard lists `AUTH-SERVICE`, `EVENT-SERVICE` and `API-GATEWAY`.
2. **Sign-up → verify → login** through `http://localhost:8080`: `2026509032@student.annauniv.edu` signs up, the verification email shows up in Mailpit (http://localhost:8025), the link verifies the account, login returns an access token and a refresh token. Logging in before verifying gives `403 email-not-verified`. A `@yahoo.com` address, or a malformed register number, gives `400` with a field error on `email`.
3. `GET http://localhost:8080/.well-known/jwks.json` returns the public RSA key(s) with `kid`. A token pasted into jwt.io shows `kid` in the header and `sub, roles, dept, year, …` in the payload.
4. **Role × endpoint matrix printed, all green**: the security matrix tests (§12) print one table per service (anonymous / STUDENT / VOLUNTEER / ORGANIZER-owner / ORGANIZER-other / ADMIN × every protected endpoint) and assert each cell. The tables are copied into `docs/security-matrix.md`.
5. **Forged token rejected everywhere**: `scripts/forge-token.sh` flips `roles` to `["ADMIN"]` in a real token (signature kept). The forged token gets `401` from the gateway (:8080) *and* directly from auth-service (:8081) and event-service (:8082).
6. **Refresh-token reuse kills the session**: refresh once (new pair returned, old one marked used), then replay the old refresh token → `401 refresh-token-reused`, and the *new* refresh token from step one is now rejected too (the whole family is revoked).
7. **Ownership**: organizer A can edit A's event, gets `403` on organizer B's event, and admin can edit both. Admin promotes a student to VOLUNTEER; organizer A assigns that volunteer to `TECHFEST26` at gate `MAIN`; the volunteer sees the assignment at `GET /api/volunteers/me/assignments`.
8. **Lockout**: 5 wrong passwords → `423 account-locked` with `Retry-After`, even with the right password, until the lock expires or an admin unlocks.
9. Swagger UI at :8080 lists `auth-service` and `event-service`; "Authorize" with a bearer token makes protected "Try it out" calls work.
10. `./mvnw verify` is green locally and in GitHub Actions.

## 2. Starting point and scope

**Built in Phase 1:** `libs/common-web`, `discovery-server`, `api-gateway` (one route pair), `event-service` (all endpoints open, auditor `"system"`), compose with `auth_db` / `auth_svc` already created, Mailpit running. Conventions: `docs/DECISIONS.md` D6.

**Owner decisions taken for this phase (2026-10-11):**

| Question | Decision |
|---|---|
| Who may sign up | `<regno>@student.annauniv.edu` **and** Gmail addresses, both as students (Gmail users type their register number on the form, optionally) |
| Register number | 10 digits = admission year (4) + programme code (3, `501`–`512`) + roll number (3), e.g. `2026509032`. Sometimes 2 digits longer (PG, PhD, staff; layout unknown). Validated as `^\d{10}(\d{2})?$`, configurable |
| Catalogue reads | **Public.** `GET` on events, sessions, venues and categories needs no token; every write and everything else does. (PROJECT.md §P2 goal reworded to match) |
| Access-token revocation | **Stateless.** Logout and "revoke sessions" kill the refresh-token family; an issued access token lives out its ≤ 15 min. No Redis in P2 |

**Out of scope (and where it lands):**

| Not in P2 | Phase | What P2 does instead |
|---|---|---|
| Event lifecycle transitions, registrations | P3 | Ownership rules cover today's edit/delete endpoints; P3 adds its own `@PreAuthorize` rules on the same `eventSecurity` bean |
| Scan-time checks of volunteer assignment | P5 | P2 stores assignments (event + optional gate) and exposes them; checkin-service reads them in P5 |
| `UserRegistered` event, templated emails via notification-service | P3 (outbox) / P7 | auth-service sends the verification email itself (Spring Mail → Mailpit), after commit |
| Login pages, BFF session, token refresh in the browser | P6 | APIs only; Swagger + `.http` file for the demo |
| Rate limiting on `/login`, access-token denylist | P8 (Redis) | Account lockout covers brute force on one account |
| Circuit breaker on event → auth calls | P8 | Plain timeouts on the client |

## 3. Module layout after Phase 2

```
pom.xml                              # <modules>: + libs/common-security, libs/security-test-support, services/auth-service
libs/common-web/                     # + ProblemTypes for 401/403/423, public ProblemDetail factory
libs/common-security/                # NEW: servlet resource-server auto-config (JWKS via Eureka), roles, CurrentUser, 401/403 ProblemDetail
libs/security-test-support/          # NEW (test scope only): in-memory RSA key, token minting for every role, forged/expired variants
platform/discovery-server/
platform/api-gateway/                # + reactive resource server, CORS, auth-service routes, X-User-Id
services/auth-service/               # NEW :8081
services/event-service/              # + security, ownership, gates, volunteer assignments
scripts/forge-token.sh               # NEW (exec bit: git add --chmod=+x)
docs/http/phase-2.http               # NEW demo requests
docs/security-matrix.md              # NEW, generated by the matrix tests and copied in at T04.12
```

| Module | Base package | Key dependencies (artifactIds; all versions from the Boot / Cloud BOMs) |
|---|---|---|
| `common-security` | `com.mitpass.common.security` | `spring-boot-starter-security-oauth2-resource-server`, `spring-cloud-starter-loadbalancer` (for the `@LoadBalanced` JWKS client), `common-web`, `spring-boot-starter-webmvc` (optional); test: `spring-boot-starter-security-test`, `-webmvc-test`, `security-test-support` |
| `security-test-support` | `com.mitpass.security.test` | `spring-security-oauth2-jose`, `spring-boot-test`, `spring-security-test` (compile scope here; consumers add the module with `<scope>test</scope>`) |
| `auth-service` | `com.mitpass.auth` | `spring-boot-starter-webmvc`, `-validation`, `-data-jpa`, `-flyway`, `-actuator`, `-mail`, `-security-oauth2-resource-server`, `flyway-database-postgresql`, `postgresql` (runtime), eureka client, springdoc webmvc, mapstruct, `common-web`, `common-security`; test: `-webmvc-test`, `-data-jpa-test`, `-security-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `security-test-support` |
| `event-service` (adds) | | `common-security`; test: `-security-test`, `security-test-support` |
| `api-gateway` (adds) | | `spring-boot-starter-security-oauth2-resource-server`; test: `spring-boot-starter-security-test`, `security-test-support` |

`spring-boot-starter-oauth2-resource-server` still exists in Boot 4.1.1 but its POM says *"deprecated in favor of spring-boot-starter-security-oauth2-resource-server"* (checked 2026-10-11). Use the new name everywhere.

**auth-service package layout:**

```
com.mitpass.auth
├── AuthServiceApplication
├── config/      SecurityConfig (own chain + local JwtDecoder), JpaConfig, OpenApiConfig, AsyncConfig, AuthProperties
├── domain/      User, Role, RoleName, Department, RefreshToken, EmailVerificationToken, SigningKey, SigningKeyStatus
├── repository/  UserRepository, RoleRepository, DepartmentRepository, RefreshTokenRepository,
│                EmailVerificationTokenRepository, SigningKeyRepository
├── keys/        SigningKeyService (generate, encrypt at rest, rotate, JWK set), KeyEncryptor
├── service/     EmailPolicy, SignupService, EmailVerificationService, LoginService (lockout),
│                AccessTokenService (mint JWT), RefreshTokenService (rotate, reuse detection, revoke), UserAdminService
├── mail/        VerificationMailer (@TransactionalEventListener AFTER_COMMIT + @Async)
├── web/         AuthController, UserController, JwksController, KeyAdminController
│   └── dto/     request/response records
└── mapper/      UserMapper (MapStruct)
```

## 4. Key decisions for this phase

| Topic | Choice | Why |
|---|---|---|
| Token issuer | **Own `auth-service`** minting JWTs with Spring Security's `NimbusJwtEncoder`; *not* Spring Authorization Server | PROJECT.md wants a visible login → JWT → rotating refresh flow with reuse detection. SAS would hide that behind OAuth2 grant types and adds a lot of config for no course concept |
| Access token | RS256 JWT, 15 min. Header `kid`. Claims `iss=mitpass-auth`, `aud=mitpass-api`, `sub` (user UUID), `email`, `name`, `roles` (`["STUDENT"]`), `dept`, `year`, `regNo` (last three only when set), `iat`, `exp`, `jti` | Services check signature, `iss`, `aud`, `exp`/`nbf` (30 s clock skew) |
| Signing keys | RSA-2048, stored in `signing_key` (auth_db). The private key is **encrypted at rest** with Spring Security Crypto `Encryptors.delux` (AES-256-GCM, PBKDF2) under `MITPASS_KEY_SECRET` (dev default in `application.yml`). Generated on first start; `POST /api/auth/keys/rotate` (ADMIN) makes a new ACTIVE key. The JWKS keeps a retired key for access-TTL + 5 min so tokens it signed stay valid | Survives restarts (an in-memory key would log everyone out on each restart) and shows `kid`-based rotation. A partial unique index allows only one ACTIVE key, so two instances starting together can't both create one |
| JWKS for services | `http://auth-service/.well-known/jwks.json` fetched by a **`@LoadBalanced` RestTemplate** (`NimbusJwtDecoder.withJwkSetUri(..).restOperations(..)`); gateway uses a load-balanced `WebClient` (`NimbusReactiveJwtDecoder…webClient(..)`). Both builder methods checked in the 7.1.1 jar | No hard-coded port; Nimbus caches the set and refetches on an unknown `kid` (rotation needs no restart) |
| Refresh token | Opaque, 32 random bytes base64url; only its **SHA-256** is stored (high-entropy, so a fast hash is enough). Sliding 7-day expiry, 30-day absolute family lifetime. Every use **rotates** it (old row gets `used_at`, new row same `family_id`). Presenting a used or revoked token **revokes the family** → `401 refresh-token-reused` | PROJECT.md "theft detection". The rotation is one conditional `UPDATE … WHERE used_at IS NULL AND revoked_at IS NULL`, so two concurrent refreshes with the same token can't both win |
| Reuse revocation must commit | `@Transactional(noRollbackFor = RefreshTokenReusedException.class)` | Throwing to produce the 401 would otherwise roll back the family revocation |
| Roles on refresh | Read from the DB on every refresh, never copied from the old token | A role change takes effect at the next refresh (≤ 15 min); admin "revoke sessions" forces it now |
| Passwords | `DelegatingPasswordEncoder` (`{bcrypt}`, strength 10). Policy: 8–72 **bytes** UTF-8, at least one letter and one digit, must not contain the email's local part | BCrypt ignores/rejects input past 72 bytes (Spring Security throws), so the limit is in bytes, validated before encoding |
| Email policy | `mitpass.auth.signup.domains` (config list). `student.annauniv.edu`: local part must match `^\d{10}(\d{2})?$` and becomes `register_number`. `gmail.com`: dots and `+tag` stripped into `canonical_email` (unique), so `a.b+x@gmail.com` and `ab@gmail.com` are one account. Emails stored lower-case | Owner's rule (§2); canonical form stops duplicate Gmail sign-ups |
| Register number | Stored as typed (unique, nullable). From an annauniv email it is taken from the local part (a different value in the form → 400); Gmail users may enter one. It is never parsed: department and year come from the form | Owner decision (§15 Q1) |
| Email verification | Random token, SHA-256 stored, 24 h, single use. Link: `mitpass.auth.verify-link` = `http://localhost:8080/api/auth/verify-email?token={token}` (GET and POST both verify). Resend always answers `202` and sends at most once a minute | No user enumeration. P6 points the link at a portal page instead |
| Unverified login | Correct password but unverified → `403 email-not-verified` | Wrong password still gives the generic 401 first, so this doesn't leak more than "account exists with this password" |
| Mail sending | `SignupService` publishes `VerificationRequested`; `VerificationMailer` sends it `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`. Spring Mail → Mailpit `localhost:1025` | No email for a rolled-back sign-up; an SMTP hiccup doesn't fail the sign-up. P7 moves this to notification-service |
| Lockout | 5 consecutive failures → `locked_until = now + 15 min`; success resets the counter; admin `POST /api/users/{id}/unlock`. Unknown email: BCrypt still runs against a dummy hash | Constant-ish response time. Known trade-off: the lockout itself reveals that an email exists (rate limiting in P8) |
| Error statuses | 401 `unauthorized` (no/invalid token), 401 `invalid-credentials` (generic "Email or password is wrong"), 401 `refresh-token-reused`, 401 `invalid-refresh-token`, 403 `forbidden`, 403 `email-not-verified`, 423 `account-locked` + `Retry-After`, 400 `invalid-token` (bad/expired verification token), 409 duplicate email / register number | Same ProblemDetail contract as D6 |
| Authorization style | URL rules in the filter chain only for *public vs authenticated*. Role and ownership rules are **`@PreAuthorize` on controller methods**, e.g. `@PreAuthorize("hasRole('ADMIN') or (hasRole('ORGANIZER') and @eventSecurity.isOwner(#id))")` | PROJECT.md asks for `@PreAuthorize` per endpoint; one place to read each endpoint's rule |
| Ownership | `event.organizer_id` (UUID, the creator's `sub`) + `organizer_name` snapshot from the `name` claim. `EventSecurity.isOwner(id)` uses a one-column query; a missing event returns `true` so the controller produces the normal 404 | Organizer edits only their own events |
| Auditing | `AuditorAware` = JWT `sub`; `"system"` when there's no authenticated user (seed, schedulers) | `created_by` (varchar 64) fits a UUID |
| Gates + volunteers | Owned by **event-service**: `event_gate (event_id, code, name)` and `volunteer_assignment (event_id, volunteer_id, gate_id null = any gate)`, one assignment per volunteer per event. Organizer assigns by **email**; event-service resolves it through auth-service `GET /api/users/lookup?email=` (ORGANIZER/ADMIN) with a `@LoadBalanced RestClient`, relaying the caller's bearer token. The user must hold VOLUNTEER, else 422 | The event's owner owns its staffing. Shows service-to-service REST through Eureka with token relay. Name/email are snapshotted so lists need no cross-service call |
| Gateway role | Pre-check only: valid token or public path, else 401 at the edge (`application/problem+json`). It strips client-supplied `X-User-*` headers and adds `X-User-Id` (for logs). The `Authorization` header passes through untouched; **services re-verify** and do all role checks | Defence in depth; a request that bypasses the gateway (exit criterion 5) is still rejected |
| CORS | At the gateway only: `mitpass.cors.allowed-origins` (default `http://localhost:8090`, the P6 portal), methods GET/POST/PUT/DELETE, headers `Authorization, Content-Type, Idempotency-Key, X-Correlation-Id`, exposes `Location, X-Correlation-Id` | One place; services never see browser preflights |
| Logging | common-security puts `userId` (the `sub`) in MDC; log pattern becomes `%5p [%X{correlationId:-}] [%X{userId:-}]` | Who did what, next to the correlation id |
| Demo accounts | Repeatable `db/seed/R__demo_users.sql` (off in tests) with fixed UUIDs, all verified, dev password `Passw0rd!` (documented): `admin@mitpass.test`, `organizer.a@mitpass.test`, `organizer.b@mitpass.test`, `volunteer@mitpass.test`, students `2026509001@student.annauniv.edu`, `2026505014@student.annauniv.edu`. `.test` is a reserved TLD, so seeded staff addresses can't belong to anyone | Seed bypasses the sign-up domain rule on purpose; real sign-ups still follow it |
| Seed events' owner | V3 back-fills existing events with organizer A's fixed UUID; `R__demo_seed.sql` gains `organizer_id` (its checksum changes, so it reruns; `ON CONFLICT DO NOTHING` keeps it idempotent) | The demo's ownership 403 needs two organizers with known events |

## 5. Data model

### auth-service (`auth_db`, Flyway `V1__init.sql`, `V2__reference_data.sql`)

| Table | Columns | Constraints / indexes |
|---|---|---|
| `department` | `code` varchar(16) PK, `name` varchar(120) | reference data: `CSE, IT, ECE, EIE, MECH, AERO, AUTO, PROD, RPT, ROBO, AIDS` (§15 Q2) |
| `role` | `id` smallint PK, `name` varchar(16) | `name IN (STUDENT, ORGANIZER, VOLUNTEER, ADMIN)`, unique; seeded in V2 |
| `app_user` | `id` uuid PK, `email` varchar(254), `canonical_email` varchar(254), `full_name` varchar(120), `department_code` FK, `year_of_study` smallint null, `register_number` varchar(12) null, `password_hash` varchar(100), `email_verified_at` timestamptz null, `enabled` bool, `failed_login_attempts` int, `locked_until` timestamptz null, `last_login_at` null, `version`, audit cols | unique `canonical_email`, unique `register_number`; `year_of_study BETWEEN 1 AND 5`; `register_number ~ '^\d{10}(\d{2})?$'` |
| `user_role` | `user_id`, `role_id` | PK both; FK `ON DELETE CASCADE` |
| `email_verification_token` | `id` uuid, `user_id`, `token_hash` char(64), `expires_at`, `used_at` null, `created_at` | unique `token_hash`; index `user_id` |
| `refresh_token` | `id` uuid, `user_id`, `family_id` uuid, `token_hash` char(64), `issued_at`, `expires_at`, `family_expires_at`, `used_at` null, `replaced_by` uuid null, `revoked_at` null, `revoke_reason` varchar(24) null, `user_agent` varchar(200) null, `ip` varchar(45) null | unique `token_hash`; index `(family_id)`, `(user_id)`; `revoke_reason IN (LOGOUT, REUSE, ADMIN, ROLE_CHANGE)` |
| `signing_key` | `kid` varchar(40) PK, `algorithm` varchar(8), `public_jwk` text, `private_key_enc` text, `status` varchar(8), `created_at`, `retired_at` null | `status IN (ACTIVE, RETIRED)`; partial unique index `ON signing_key (status) WHERE status = 'ACTIVE'` |

`User` ↔ `Role` is `@ManyToMany` (LAZY, `Set<Role>`), loaded with `@EntityGraph` on login, refresh and `/me`. UUIDs come from `@UuidGenerator` (time-ordered v7).

### event-service (`V3__organizers_gates_volunteers.sql`)

| Change | Details |
|---|---|
| `event` + `organizer_id uuid NOT NULL`, `organizer_name varchar(120) NOT NULL` | Back-filled with organizer A's fixed UUID / name, then `SET NOT NULL`; index `organizer_id` |
| `event_gate` | `id` bigint identity, `event_id` FK `ON DELETE CASCADE`, `code` varchar(16) `^[A-Z0-9-]{2,16}$`, `name` varchar(80); unique `(event_id, code)` |
| `volunteer_assignment` | `id` bigint identity, `event_id` FK cascade, `gate_id` FK null (`ON DELETE SET NULL`), `volunteer_id` uuid, `volunteer_name`, `volunteer_email`, `assigned_at`, `assigned_by`; unique `(event_id, volunteer_id)`; index `volunteer_id` |

`EventSearchCriteria` gains `organizerId` (lets P6 list "my events").

## 6. REST API

### auth-service (:8081, the same paths through the gateway)

| Method | Path | Who | Success | Errors |
|---|---|---|---|---|
| POST | `/api/auth/signup` | public | 201 `UserResponse` (+ `Location: /api/users/{id}`), verification email sent | 400 (field errors incl. email domain / register number / password policy), 409 email or register number taken |
| GET / POST | `/api/auth/verify-email?token=` / `{token}` | public | 200 `{ "status": "VERIFIED" }` (idempotent for an already-verified user) | 400 `invalid-token` |
| POST | `/api/auth/verify-email/resend` `{email}` | public | 202 always | 400 |
| POST | `/api/auth/login` `{email, password}` | public | 200 `TokenResponse {accessToken, tokenType, expiresIn, refreshToken, refreshExpiresIn}` | 401 `invalid-credentials`, 403 `email-not-verified`, 423 `account-locked` |
| POST | `/api/auth/refresh` `{refreshToken}` | public (the refresh token is the credential) | 200 new `TokenResponse` | 401 `invalid-refresh-token`, 401 `refresh-token-reused` |
| POST | `/api/auth/logout` `{refreshToken}` | public | 204 (idempotent) | — |
| GET | `/.well-known/jwks.json` | public | 200 JWK set (`Cache-Control: max-age=300`) | — |
| GET | `/api/users/me` | authenticated | 200 `UserResponse` | 401 |
| DELETE | `/api/users/me/sessions` | authenticated | 204, all own refresh families revoked | 401 |
| GET | `/api/users?q=&role=&page=` | ADMIN | 200 `PageResponse<UserResponse>` | 401, 403 |
| GET | `/api/users/{id}` | ADMIN or self | 200 | 401, 403, 404 |
| GET | `/api/users/lookup?email=` | ORGANIZER, ADMIN | 200 `UserSummary {id, fullName, email, roles}` | 401, 403, 404 |
| PUT | `/api/users/{id}/roles` `{roles: [...]}` | ADMIN | 200 `UserResponse`; the user's refresh families revoked (`ROLE_CHANGE`) | 400, 404, 422 (an admin can't remove their own ADMIN) |
| POST | `/api/users/{id}/unlock` | ADMIN | 204 | 404 |
| DELETE | `/api/users/{id}/sessions` | ADMIN | 204 | 404 |
| POST | `/api/auth/keys/rotate` | ADMIN | 201 `{kid}` | 401, 403 |

### event-service: rules on existing endpoints, plus new ones

| Method | Path | Who |
|---|---|---|
| GET | `/api/events`, `/api/events/{id}`, `/api/events/{id}/sessions`, `/api/venues`, `/api/venues/{id}`, `/api/categories` | public |
| POST | `/api/events` | ORGANIZER, ADMIN (owner = caller) |
| PUT / DELETE | `/api/events/{id}` | owner ORGANIZER, ADMIN |
| POST / PUT / DELETE | `/api/events/{id}/sessions[/{sessionId}]` | owner ORGANIZER, ADMIN |
| POST | `/api/venues` | ORGANIZER, ADMIN |
| PUT | `/api/venues/{id}` | ADMIN |
| GET | `/api/events/{id}/gates` | authenticated |
| POST / DELETE | `/api/events/{id}/gates[/{gateId}]` | owner ORGANIZER, ADMIN (409 duplicate code) |
| GET | `/api/events/{id}/volunteers` | owner ORGANIZER, ADMIN |
| POST | `/api/events/{id}/volunteers` `{email, gateId?}` | owner ORGANIZER, ADMIN (422 unknown user / not a VOLUNTEER / gate of another event; 409 already assigned; 503 auth-service unreachable) |
| DELETE | `/api/events/{id}/volunteers/{volunteerId}` | owner ORGANIZER, ADMIN |
| GET | `/api/volunteers/me/assignments` | VOLUNTEER |

**Method security runs after argument binding.** A STUDENT who sends an *invalid* body to `POST /api/events` gets 400, not 403, because `@Valid @RequestBody` is resolved before the `@PreAuthorize` proxy is called. Acceptable (no data leaks); the matrix test therefore sends *valid* bodies so that it tests authorization only.

## 7. `libs/common-web` changes

- `ProblemTypes`: add `UNAUTHORIZED`, `FORBIDDEN`, plus the auth-specific slugs in §4 (or keep those in auth-service; only the 401/403 ones are shared).
- Expose a small public factory (`Problems.create(status, type, title, detail)`) that adds `correlationId` from MDC, so filter-level handlers in common-security and the gateway build identical bodies.
- `GlobalExceptionHandler` stays security-agnostic. common-security's advice is ordered before it (see §8), which closes the D6 caveat (`AccessDeniedException` → 500).

## 8. `libs/common-security` (servlet services)

Auto-configuration (`@ConditionalOnWebApplication(SERVLET)`, registered in `AutoConfiguration.imports`):

- **`SecurityFilterChain`** (`@ConditionalOnMissingBean`): stateless, CSRF off, `permitAll` for `mitpass.security.public-paths` (list of `METHOD /pattern`, built with `PathPatternRequestMatcher`) + `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`; everything else `authenticated()`. `oauth2ResourceServer(jwt)`.
- **`JwtDecoder`** (`@ConditionalOnMissingBean`): JWKS from `mitpass.security.jwk-set-uri` (default `http://auth-service/.well-known/jwks.json`) through a `@LoadBalanced RestTemplate` (`mitpass.security.load-balanced=true`); RS256 only; validators: timestamps (30 s skew), issuer, audience.
- **`MitpassJwtAuthenticationConverter`**: `roles` claim → `ROLE_*` authorities; principal name = `sub`.
- **`CurrentUser`** record (`id, email, name, roles, dept, year, regNo`) + a `HandlerMethodArgumentResolver`, so controllers take `CurrentUser me` as a parameter.
- **`Roles`** constants (`STUDENT`, `ORGANIZER`, `VOLUNTEER`, `ADMIN`).
- **401 / 403 as ProblemDetail**: an `AuthenticationEntryPoint` and an `AccessDeniedHandler` for filter-level failures (they also set `WWW-Authenticate: Bearer …`), plus a `@RestControllerAdvice @Order(HIGHEST_PRECEDENCE)` mapping `AccessDeniedException` / `AuthenticationException` thrown by method security.
- **`@EnableMethodSecurity`**.
- **MDC `userId`** filter after authentication.
- **`BearerTokenRelayInterceptor`** (`ClientHttpRequestInterceptor`) copying the current request's token onto outgoing `RestClient` calls.

auth-service supplies its own `JwtDecoder` (built straight from `SigningKeyService`'s JWK set, no HTTP call to itself), so the `@ConditionalOnMissingBean` default steps aside.

**`libs/security-test-support`:** `TestJwtIssuer` (a fixed in-memory RSA key with `kid=test-key`; `token(role…)`, `tokenFor(uuid, roles…)`, `expired()`, `forgedPayload(token)`, `wrongIssuer()`, `signedByOtherKey()`, `algNone()`), and `@TestConfiguration TestSecurityConfig` that exposes a `JwtDecoder` (servlet) / `ReactiveJwtDecoder` (gateway) trusting that key with the *production* validators. The tests check the real verification path, not a mock.

## 9. Gateway

- Dependency `spring-boot-starter-security-oauth2-resource-server` (works with WebFlux). `SecurityWebFilterChain`: CSRF off, public paths permitted (§6 public rows + `/swagger-ui*`, `/webjars/**`, `/docs/**`, `/v3/api-docs`, `/actuator/health`), `OPTIONS /**` permitted (preflight), the rest `authenticated()`; `ReactiveJwtDecoder` via the load-balanced `WebClient`; a ProblemDetail entry point (401, `correlationId` from the exchange attribute set by `CorrelationIdGlobalFilter`).
- `UserHeadersGlobalFilter`: removes any incoming `X-User-*`, adds `X-User-Id` for authenticated requests.
- Routes: `auth-service` (`/api/auth/**, /api/users/**, /.well-known/jwks.json`), `auth-service-openapi` (`/docs/auth-service`), and event-service's predicate gains `/api/volunteers/**`. A second `swagger-ui.urls` entry.
- CORS through `spring.cloud.gateway.server.webflux.globalcors` (values in §4).

Public paths now live in two places (gateway config and each service's `mitpass.security.public-paths`). The services are the authority; the gateway list is only a pre-filter. A gateway test asserts that each service's public paths are also public at the edge.

## 10. Configuration additions

```yaml
# every servlet service (common-security defaults shown)
mitpass.security:
  jwk-set-uri: http://auth-service/.well-known/jwks.json
  load-balanced: true
  issuer: mitpass-auth
  audience: mitpass-api
  public-paths: [ "GET /api/events/**", "GET /api/venues/**", "GET /api/categories/**" ]   # event-service

# auth-service
mitpass.auth:
  access-token-ttl: 15m
  refresh-token-ttl: 7d
  refresh-family-max-lifetime: 30d
  key-secret: ${MITPASS_KEY_SECRET:dev-only-change-me}
  lockout: { max-attempts: 5, duration: 15m }
  verify-link: http://localhost:8080/api/auth/verify-email?token={token}
  mail-from: "MITPass <no-reply@mitpass.test>"
  signup:
    domains:
      - { domain: student.annauniv.edu, local-part-pattern: '\d{10}(\d{2})?', register-number-from-local-part: true }
      - { domain: gmail.com, canonicalize-gmail: true }
spring.mail: { host: ${MAIL_HOST:localhost}, port: 1025 }
```

Tests: `application-test.yml` in each service sets `mitpass.security.load-balanced: false` and imports `TestSecurityConfig`; auth-service tests replace `JavaMailSender` with a capturing fake.

## 11. Ports and running locally

| Port | What |
|---|---|
| 8081 | **auth-service** (new) |
| 8082 / 8080 / 8761 | event-service / gateway / Eureka (unchanged) |
| 8025 | Mailpit UI (verification emails) |

Start order: discovery → **auth** → event → gateway. A service that starts before auth-service is registered still boots; JWT checks fail with 401 until the JWKS can be fetched (logged once with the cause).

## 12. Tests

| Test | Kind | Proves |
|---|---|---|
| `MitpassJwtAuthenticationConverterTest`, `CurrentUserTest` | unit | `roles` → authorities; claims → `CurrentUser` |
| `ResourceServerAutoConfigurationTest` (common-security) | `@WebMvcTest` + probe controller + `TestSecurityConfig` | public path open; protected path: no token / expired / forged / wrong `iss` / wrong `aud` / other key / `alg:none` → 401 ProblemDetail with `correlationId`; wrong role → 403 ProblemDetail (method security, not 500) |
| `EmailPolicyTest` | unit | domain allow-list, register-number pattern (10 and 12 digits; 9, 11, letters rejected), Gmail canonicalization, case folding |
| `PasswordPolicyValidatorTest` | unit | 72-byte limit with multibyte characters; letter + digit; contains local part |
| `SigningKeyServiceTest` | `@DataJpaTest` + Testcontainers | key created once; encrypt/decrypt round trip; rotate → JWKS has old + new; retired key drops out after its window; second ACTIVE insert violates the partial index |
| `RefreshTokenServiceTest` | `@DataJpaTest` + Testcontainers | rotation; reuse revokes the family **and the revocation is committed**; expired / family-expired rejected; **two threads refresh the same token → exactly one succeeds** |
| `LoginServiceTest` | unit (Mockito) | lockout after 5, reset on success, unknown email still runs BCrypt, unverified → 403 |
| `AuthControllerTest`, `UserControllerTest` | `@WebMvcTest` | validation → 400 field errors; status codes / problem types of §6 |
| `AuthFlowTests` | `@SpringBootTest` + Testcontainers | sign-up → captured email → verify → login → `/users/me` → refresh → replay old → 401 + family dead; JWKS verifies the issued token |
| `EventSecurityTest` | `@DataJpaTest` + unit | owner / non-owner / missing event |
| `VolunteerAssignmentServiceTest` | unit + `MockRestServiceServer` | lookup relays the bearer token; 404 → 422, no VOLUNTEER role → 422, timeout → 503; gate of another event → 422 |
| Existing event-service controller tests | `@WebMvcTest` | updated to send tokens (`jwt()` post-processor or `TestJwtIssuer`); behaviour unchanged |
| `SecurityMatrixTest` (auth-service, event-service) | `@SpringBootTest(webEnvironment = MOCK)` + Testcontainers + `TestSecurityConfig` | every endpoint × {anonymous, STUDENT, VOLUNTEER, ORGANIZER-owner, ORGANIZER-other, ADMIN} → expected 401 / 403 / *allowed* (any other status). Cases are a data table; a JUnit extension prints the table and writes `target/security-matrix/<service>.md` |
| `EdgeSecurityTest` (gateway) | `@SpringBootTest` + `WebTestClient` | public paths pass the edge, protected ones need a valid token (forged/expired → 401 ProblemDetail), spoofed `X-User-Id` removed, CORS preflight from `:8090` allowed and from elsewhere refused |

## 13. Steps (log.md T04.1–T04.12)

Each step ends with `./mvnw verify` green and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T04.1 | **common-web additions:** 401/403 problem types, public `Problems` factory | S | Existing tests green; factory unit-tested |
| T04.2 | **`libs/common-security` + `libs/security-test-support`:** auto-config, decoder, converter, `CurrentUser`, 401/403 handling, MDC `userId`, token relay interceptor; test issuer | M | `ResourceServerAutoConfigurationTest` covers every rejection case of §12 |
| T04.3 | **auth-service skeleton + persistence:** module, `application.yml` / `-test.yml`, Flyway V1/V2, entities, repositories, Testcontainers config, `R__demo_users.sql` | M | `@DataJpaTest`s green; the service starts on compose Postgres and registers in Eureka |
| T04.4 | **Signing keys + access tokens:** `SigningKeyService` (generate, encrypt, rotate), JWKS endpoint, `AccessTokenService`, auth-service's own `SecurityConfig` | M | JWKS served; a minted token verifies against it; `SigningKeyServiceTest` green |
| T04.5 | **Sign-up + verification:** `EmailPolicy`, password policy, `SignupService`, verification token + mailer (after commit, async), verify/resend endpoints | M | A sign-up email lands in Mailpit; the link verifies; policy tests green |
| T04.6 | **Login, lockout, refresh rotation, logout** | L | `RefreshTokenServiceTest` (incl. the concurrent refresh) and `LoginServiceTest` green; `AuthFlowTests` green |
| T04.7 | **User admin endpoints + OpenAPI:** `/me`, list, lookup, roles, unlock, sessions, key rotation; bearer `SecurityScheme` in springdoc | M | Controller tests green; Swagger "Authorize" works on :8081 |
| T04.8 | **event-service security:** common-security dependency, `AuditorAware` from JWT, V3 migration (organizer columns), seed update, `EventSecurity`, `@PreAuthorize` on every write, existing tests updated | L | All 38 existing tests green again (with tokens); ownership rules behave as §6 |
| T04.9 | **Gates + volunteer assignments:** V3 tables, gate CRUD, assignment via auth-service lookup (load-balanced `RestClient` + token relay, timeouts), `/api/volunteers/me/assignments` | M | `VolunteerAssignmentServiceTest` green; live assignment works through the gateway |
| T04.10 | **Gateway edge security:** reactive resource server, public paths, CORS, `UserHeadersGlobalFilter`, auth-service routes + Swagger entry | M | `EdgeSecurityTest` green; Swagger at :8080 lists both services |
| T04.11 | **Security matrix:** `SecurityMatrixTest` in auth-service and event-service, printed tables → `docs/security-matrix.md`; `scripts/forge-token.sh` (exec bit) | M | Every matrix cell green; forged token → 401 at :8080, :8081, :8082 |
| T04.12 | **Exit demo + docs:** `docs/http/phase-2.http`; README (start order, demo accounts, forge script); PROJECT.md §P2 ticks; DECISIONS D7 (security model) + D8 (demo accounts / dev secrets); "As built" section here; log Current Status | S | All ten exit criteria pass; nothing left running |

Order: T04.1 → T04.2 → T04.3 → T04.4 → T04.5 → T04.6 → T04.7 → (T04.8 → T04.9) ∥ T04.10 → T04.11 → T04.12.

## 14. Boot 4.1 / Security 7.1 gotchas

Checked against the 7.1.1 jars and the Boot 4.1.1 BOM on 2026-10-11 unless marked *(check at build time)*.

1. **Starter names.** `spring-boot-starter-security-oauth2-resource-server` (the old `spring-boot-starter-oauth2-resource-server` is deprecated). Test support: `spring-boot-starter-security-test`. Mail: `spring-boot-starter-mail`. Spring Security is **7.1.1**.
2. **JWKS through Eureka.** `NimbusJwtDecoder.withJwkSetUri(uri).restOperations(RestOperations)` and `NimbusReactiveJwtDecoder.withJwkSetUri(uri).webClient(WebClient)` exist (verified). Boot's `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` can't use a load-balanced client, so define the decoder bean. `RestTemplate` is **not** deprecated in Framework 7.0.9 (verified).
3. **Security 7 DSL** *(check at build time)*: lambda DSL only (no `.and()`); `AntPathRequestMatcher` / `MvcRequestMatcher` are gone, use `PathPatternRequestMatcher` (`PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/events/**")`).
4. **`@WebMvcTest` picks up security.** Once common-security is on the classpath, every web-slice test runs the filter chain and needs a `JwtDecoder`: import `TestSecurityConfig` (or `@MockitoBean JwtDecoder` + `jwt()` post-processor).
5. **Catch-all swallows `AccessDeniedException`.** common-web's `Exception` handler would turn method-security denials into 500. common-security's advice must be `@Order(Ordered.HIGHEST_PRECEDENCE)` (advice order decides which `@ExceptionHandler` wins).
6. **Validation before authorization** for `@PreAuthorize` on controllers (§6 note).
7. **BCrypt input over 72 bytes** throws `IllegalArgumentException` in recent Spring Security: validate the byte length first.
8. **Exception = rollback.** Reuse detection throws *after* revoking the family: `noRollbackFor` (§4).
9. **`@TransactionalEventListener` + `@Async`** needs `@EnableAsync`; otherwise the mail goes out on the request thread.
10. **Jackson 3** in entry points / access-denied handlers (`tools.jackson.databind.json.JsonMapper`), or reuse the injected mapper.
11. **Repeatable migrations run after all versioned ones.** V3 makes `organizer_id` NOT NULL, so `R__demo_seed.sql` must supply it, or a fresh DB fails at the seed.
12. **Gateway + springdoc + security:** permit `/v3/api-docs`, `/swagger-ui*`, `/webjars/**` and `/docs/**`, or Swagger UI itself gets 401.

## 15. Owner questions (resolved 2026-10-11)

The owner approved the plan on 2026-10-11 with these answers:

1. **Programme code → department:** not needed. The register number is never classified; sign-up asks for the department.
2. **Department list:** placeholders are fine, with Robotics and AI & DS added and Civil removed: `CSE, IT, ECE, EIE, MECH, AERO, AUTO, PROD, RPT, ROBO, AIDS`.
3. **12-digit register numbers:** accepted as typed.
4. **Demo password:** `Passw0rd!` (dev only, documented in README).

## 16. As built: deviations and findings (2026-10-11)

Phase 2 was implemented as planned. Where the build differs from the sections above, this list is authoritative.

| Topic | Plan said | As built / why |
|---|---|---|
| Errors with their own status | auth-service problem types | `common-web` gained **`ProblemException`** (status + type + title + headers, handled by `GlobalExceptionHandler`) and a public **`Problems`** factory. auth-service raises 401/403/423 through it (`AuthProblems`); no second advice needed |
| common-security's own tests | in `common-security` | Unit tests there; the **filter-chain test** (`ResourceServerAutoConfigurationTest`) lives in `security-test-support/src/test`, because a test dependency from common-security on security-test-support would be a reactor cycle |
| `@WebMvcTest` slices | import `TestSecurityConfig` | common-security registers its auto-config in **`META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest.imports`**, so every web slice runs the real filter chain; the test only supplies the `JwtDecoder` |
| auth-service security | own `SecurityConfig` chain | Only a **`JwtDecoder` bean** (a `JWKSource` over `SigningKeyService`); filter chain, public paths and 401/403 bodies come from common-security |
| Key encryption | `Encryptors.delux` | `Encryptors.*` and `AesBytesEncryptor` are **deprecated in Security 7.1**. Uses **`AesGcmBytesEncryptor.withPassword(secret, salt)`** (AES-256-GCM, PBKDF2-HMAC-SHA256 600 000 rounds); decrypted keys are cached per `kid` |
| Token hash columns | `char(64)` | `VARCHAR(64)`: `CHAR` reads back as `bpchar` and trips `ddl-auto=validate` |
| UUIDs | v7 | `@UuidGenerator(style = TIME)` |
| `GET /api/departments` | — | Added (public), for the sign-up form's department list |
| Lockout | 5 failures → locked | The **5th failure itself answers 423** (with `Retry-After`), so the demo shows the lock immediately |
| Concurrent refresh with one token | exactly one succeeds | Exactly one succeeds; the others count as reuse, so the family (including the winner's new token) is revoked. Strict on purpose: two holders of one refresh token is the theft signal |
| `expiresIn` | seconds | Rounded **up** (`899.98 s` → `900`) |
| Gateway correlation id | `GlobalFilter` | **`CorrelationIdWebFilter`** (renamed, `WebFilter` at highest precedence): Spring Security's WebFilter runs before gateway `GlobalFilter`s, so a 401 from the edge also gets an id; it's kept in an exchange attribute for the 401 body |
| Gateway CORS | `globalcors` | Spring Security's **`.cors()` with a `CorsConfigurationSource`** (`mitpass.cors.allowed-origins`): CORS headers are then on 401s from the edge too, which a browser needs to read them. Disallowed origins get 403 on preflight |
| event-service → auth-service | load-balanced `RestClient` | `@LoadBalanced RestClient.Builder` bean + `BearerTokenRelayInterceptor`; 404 → 422, unreachable → **503 `dependency-unavailable`** |
| Gate integrity | gate of the same event (422 in code) | Also in the schema: composite FK `(gate_id, event_id) → event_gate (id, event_id) ON DELETE SET NULL (gate_id)` (PostgreSQL 15+ column list), so deleting a gate keeps its volunteers as "any gate" |
| Seed ownership | all to organizer A | `ROBOWARS26` and `CRICKET26` belong to organizer B (V3 back-fill and `R__demo_seed.sql`), so the ownership 403 can be shown on seed data. Seed also adds gates (`MAIN`, `NORTH`, `OAT`, `LAB`) |
| `EventService.create` | `create(request)` | `create(organizer, request)`: the caller becomes the owner; `organizerId` / `organizerName` are in the detail and summary DTOs and a search filter |
| Matrix "allowed" | not 401/403 | Not a **security** rejection: 401 with type `unauthorized` or 403 with `forbidden`. A business 401 (`invalid-credentials`, `invalid-refresh-token`) or a 4xx from validation still means "passed authorization" (`SecurityMatrix.verdictOf`) |
| Test wiring | per-class imports | `@AuthIntegrationTest` / `@EventIntegrationTest` meta-annotations (one cached context and one container per service). event-service replaces auth-service with an in-memory `FakeAuthService` |
| `UserMapper` (MapStruct) | planned | `UserResponse.from(user, now)`: `locked` depends on the clock, roles map to names |
| Load-balanced client beans | `@LoadBalanced` beans | **`@Bean(defaultCandidate = false)`**, injected by the `@LoadBalanced` qualifier. Found in the live demo, not by tests (Eureka is off there): event-service's load-balanced `RestClient.Builder` became the only `RestClient.Builder`, the Eureka client used it, tried to resolve `localhost:8761` as a service id and never registered. Same fix applied to common-security's JWKS `RestTemplate` and the gateway's `WebClient.Builder` |
| Phase 1 demo requests | — | `docs/http/phase-1.http` logs in as organizer A first and sends the token on writes; its final delete targets TECHFEST26 (id 2), since id 1 is RoboWars (organizer B → 403) |

**Verified live** (2026-10-11, compose infra, jars on the project JDK, the Phase 1 database volume kept so V3's back-fill ran on existing events): all ten exit criteria. A scripted run of 36 checks passed: Eureka lists the three apps; college sign-up 201, Yahoo and non-register-number 400 on `email`; login before verifying 403; the verification email in Mailpit, link 200, login 200; token `kid` in the JWKS and claims as planned; a forged token 401 at :8080, :8081 and :8082; refresh 200, replay 401 `refresh-token-reused`, rotated token 401; organizer B 403 / A 201 / admin 201 on A's event, A 403 on B's; admin promotion; assignment through the auth-service lookup 201, non-volunteer 422, volunteer sees the gate; lockout 423 (also with the right password), admin unlock, login again; Swagger UI and both API docs (with the bearer scheme) through the gateway. Logs show `[correlationId] [userId]` on every service line.

**Tests:** 158, all green with `./mvnw verify` (common-web 20, common-security 9, security-test-support 15, discovery 1, gateway 17, auth-service 49, event-service 47). 0 compiler warnings. One run of the event-service suite failed once without a captured cause while compose was also running; the next two full runs were green. Watch for a flaky test there.
