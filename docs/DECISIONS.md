# Decisions

Architecture and tooling decisions, newest last. Each entry says what was chosen, why, and what it costs. Phase plans in `docs/plans/` hold their own detailed choices. A choice is promoted here when it affects more than one phase.

---

## D1: Spring Boot 4.1.1 + Spring Cloud 2025.1.3 (Oakwood), Java 21

*2026-10-09 · accepted*

**Decision.** The root `pom.xml` inherits `spring-boot-starter-parent` **4.1.1** and imports `spring-cloud-dependencies` **2025.1.3**. Java stays at **21**, as `PROJECT.md` planned.

**Why.**
- Spring Cloud 2025.1.x is the only supported release train. 2025.0 (Boot 3.5) is end of life on spring.io. 2025.1.x supports Boot 4.0.x and, from 2025.1.2, Boot 4.1.x.
- Boot 4.1.1 (2026-08-20) is the newest GA. 4.2 is still at milestones, and there is no matching GA Cloud train yet (2026.0.0-M1).
- springdoc-openapi 3.1.x is the line built for Boot 4.1 (3.1.0's notes say "Upgrade Spring Boot to version 4.1.0").

**Versions managed by the BOMs** (do not override without a reason logged here):

| Library | Version | | Library | Version |
|---|---|---|---|---|
| Spring Framework | 7.0.9 | | Testcontainers | 2.0.5 |
| Hibernate ORM | 7.4.5.Final | | JUnit Jupiter | 6.0.3 |
| Hibernate Validator | 9.1.3.Final | | Mockito | 5.23.0 |
| Spring Data | 2026.0.1 (JPA 4.1.1) | | Jackson | 3.1.5 (Jackson 2 BOM 2.21.5) |
| Flyway | 12.4.0 | | Spring AMQP | 4.1.1 |
| PostgreSQL JDBC | 42.7.13 | | Spring Cloud Gateway / Netflix | 5.0.3 / 5.0.2 |

**Pinned in the root POM:** springdoc-openapi 3.1.1, MapStruct 1.6.3.

**Consequences.** Boot 4 differs from most tutorials (which target Boot 3). The practical differences are listed in `docs/plans/phase-1.md` ("Boot 4 gotchas"). Patch upgrades within 4.1.x are free. Moving to 4.2 waits for a Cloud train that supports it.

---

## D2: The toolchain stays project-local

*2026-10-09 · accepted (AGENTS.md "Dependencies (strict)")*

**Decision.**
- **JDK:** Eclipse Temurin `jdk-21.0.12.1+1`, pinned with its SHA-256 in `scripts/setup-jdk.ps1` and unpacked into `.tools/jdk-21/` (gitignored). Nothing outside the repo changes.
- **Maven:** Maven Wrapper 3.3.4 (`only-script`) running Maven **3.9.16**, pinned by SHA-256 in `.mvn/wrapper/maven-wrapper.properties`. Maven itself lands in `~/.m2/wrapper/`, which AGENTS.md allows.
- **Wrapper patch:** `mvnw` and `mvnw.cmd` each carry a small block marked `MITPass:`. It sets `JAVA_HOME` to `.tools/jdk-21` when `JAVA_HOME` is unset, because the machine's `java` on PATH is Java 8. **Reapply it after any wrapper upgrade.**
- **Guard:** `maven-enforcer-plugin` requires Java 21+ and Maven 3.9+, so a wrong JDK fails with a message that names the fix.
- **IDE:** `.vscode/settings.json` points VS Code terminals at the same JDK.
- **CI:** GitHub Actions uses `actions/setup-java` (Temurin 21) instead of the script.

**Consequences.** A fresh clone on Windows needs one command (`scripts/setup-jdk.ps1`) before `./mvnw` works. On Linux or macOS, any JDK 21 on `JAVA_HOME` works.

---

## D3: Docker Desktop installed globally (owner-approved)

*2026-10-09 · accepted*

**Decision.** Docker Desktop **4.94.0** was installed with `winget install Docker.DockerDesktop` (engine 29.8.2, Compose v5.5.1, WSL2 backend). The owner approved it explicitly on 2026-10-09, as AGENTS.md requires for a global install.

**Why.** A container runtime cannot be project-local. `docker compose` (PostgreSQL, RabbitMQ, Mailpit) and Testcontainers both need it from Phase 1 on.

**Consequences.** Docker Desktop must be running before `docker compose up` or any Testcontainers test. Terminals opened before the install don't have `docker` on PATH; open a new one.

---

## D4: Coordinates and module layout

*2026-10-09 · accepted (Phase 1 plan approved with these defaults; code now uses them)*

**Decision.** groupId `com.mitpass`, root artifact `mitpass-parent`, version `0.1.0-SNAPSHOT`, Java packages `com.mitpass.<module>` (for example `com.mitpass.event`). The root POM lists modules by path (`libs/common-web`, `services/event-service`, …), and every module's parent is the root POM. There are no intermediate aggregator POMs.

---

## D5: Line endings

*2026-10-09 · accepted*

**Decision.** `.gitattributes` keeps LF in the repo and the working tree. `.cmd`, `.bat` and `.ps1` use CRLF, and `mvnw` is always LF. `MITPass App Screens.html` is marked `-diff`, because it is a gzip+base64 bundle and line diffs of it are noise.

**Watch out.** Git on Windows doesn't record the executable bit (`core.filemode=false`), so `mvnw` must get it in the index, or CI on Linux fails with "Permission denied". Done on 2026-10-09 with `git add --chmod=+x mvnw` (`git update-index --chmod=+x` only works on files already tracked). Later `git add` runs keep the bit. Check with `git ls-files -s mvnw`, which should show `100755`.

---

## D6: REST conventions every backend service follows

*2026-10-10 · accepted (built in Phase 1; details in `docs/plans/phase-1.md` §4, §6, §14)*

**Decision.**
- **Paths.** A service serves its resources under `/api/<resource>` itself. The gateway routes by path prefix **without rewriting**, so Swagger "Try it out" and `Location` headers work through `:8080` unchanged.
- **API docs at the gateway.** Each service's OpenAPI document is proxied at **`/docs/<service-name>`** (`SetPath=/v3/api-docs`) and listed in `springdoc.swagger-ui.urls`. It is not under `/v3/api-docs/**`, because springdoc's own handler in the gateway would answer first.
- **Errors.** A service depends on `libs/common-web`, which auto-configures the `GlobalExceptionHandler`. Every error is `application/problem+json` with `type` = `urn:mitpass:problem:<slug>`, `title`, `status`, `detail`, `instance`, `correlationId`, plus `errors[]` (`field`, `message`) for validation. The status map is 400 validation/malformed, 404 not found, 409 conflict (duplicates, wrong state, stale version), 422 business rule needing the database, and 500 generic with no internals leaked.
- **Correlation ids.** The gateway stamps `X-Correlation-Id` (keeping a safe client value). Services put it in the MDC, the log pattern (`%5p [%X{correlationId:-}]`) and every ProblemDetail.
- **Paging.** List endpoints return `PageResponse<T>` (`content, page, size, totalElements, totalPages`), never Spring's `PageImpl`. `max-page-size: 100`.
- **Exceptions to throw.** `NotFoundException`, `ConflictException`, `BusinessRuleException` from `common-web`.

**Consequences.** Adding a service (Phase 2 onward) means adding one `common-web` dependency, one route pair in the gateway and one `swagger-ui.urls` entry. A Phase 2 caveat: `GlobalExceptionHandler` has a catch-all `Exception` → 500 handler, so method-security `AccessDeniedException`s must be mapped to 403 explicitly when Spring Security arrives.
