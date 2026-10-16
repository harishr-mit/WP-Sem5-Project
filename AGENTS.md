## Context \& Status Tracking (required)

Before doing anything, read till the latest record of Change log of `log.md` to get the current context and task status. While working, use `log.md` as your todo list: add tasks as `\[ ]`, mark `\[\~]` when you start, `\[x]` when done (or `\[!]` with notes if it fails), and log every change to files in this directory so later agents can diagnose and resume after a mid-run failure.

**`archive/` (skip by default):** old CI and error logs live here. Don't read, search or list it unless the current task needs a past failure (a recurring or similar error, or a regression). Token saving is key.

## Project & UI reference

The project is **MITPass** (College Event Pass System). The plan is in `PROJECT.md`. For anything user-facing, open `MITPass App Screens.html` (repo root, view it in a browser). It is a rough guide to the flow and visual direction, not a spec: adapt it where that gives a better or simpler result, and follow `PROJECT.md` on behaviour and data. Rules for using it: `PROJECT.md` §2 "UI reference". The owner updates the file, so open the current copy before UI work.

## Workflow for any change (agents)
1. **Orient** as in *Context & Status Tracking*, plus the `PROJECT.md` sections the change touches, the current phase plan (`docs/plans/phase-N.md`) and the relevant `docs/DECISIONS.md` entries. Work on a branch (for example `phase3`), not on `main`.
2. **Decide.** If the change alters a goal, a phase's scope, a decision or a design rule, say so and get the owner's agreement first. Before writing code, record it in `PROJECT.md` (the section it changes) and in `docs/DECISIONS.md` (a new or updated D-entry).
3. **Plan** the tasks in `log.md`. Split anything bigger than one sitting into steps that each have a "done when" check. Phase work also gets a plan in `docs/plans/phase-N.md`, written before the code.
4. **Build test-first, in small steps.** Write the failing test, then the code. Business rules go in a service's domain/service layer, where unit and slice tests reach them, not in controllers, Thymeleaf templates or SQL seeds. Code shared by several services goes in `libs/`.
5. **Check.** For each module you touched, run `./mvnw -pl <module> -am verify`. Then run the full suite with `./mvnw verify`. Run `./mvnw spotless:apply` first if formatting fails. A new or changed endpoint also needs its row in that service's `SecurityMatrixTest` (D7).
6. **Review** the diff before committing, and fix what the review finds.
7. **Document** as in *End of Run Behavior*: `README.md` quick start, `PROJECT.md` ticks and status, the phase plan's as-built section, `docs/http/phase-N.http` demo requests, `docs/security-matrix.md`, and `docs/DECISIONS.md`. Finally, the parts to keep in mind section of @AGENTS.md
8. **Review Later** as in `dock/REVIEW-LATER.md` is to be updated with any decisions to be reviewed by owner later.
9. **Commit** only as *Git & Commits* allows (owner permission for each commit).
10. **Finish** as in *End of Run Behavior*.

## Parts to keep in mind
Rows marked (P3+) and so on describe parts that don't exist yet. They apply once that phase builds them.

| Part | Where | When you change it, also … |
|---|---|---|
| Build and versions | root `pom.xml`, `mvnw`, `.mvn/` | versions are pinned only in the root POM (D1); CI (`.github/workflows/ci.yml`) must stay green |
| Shared libraries | `libs/common-web`, `libs/common-security`, `libs/security-test-support`, `libs/common-messaging` (P3+) | every service depends on them, so run the full `./mvnw verify`; register auto-configuration in `META-INF/spring/…AutoConfiguration.imports`; keep D6/D7 in step |
| Infrastructure | `infra/docker-compose.yml`, `infra/postgres/init.sql` | `init.sql` runs only on an empty data volume, so a new database or role needs `docker compose down -v` (say so in README and `log.md`); update README ports |
| Gateway and discovery | `platform/api-gateway`, `platform/discovery-server` | a new service needs a route pair, a `swagger-ui.urls` entry (D6) and its public paths; JWT pre-check rules (D7) |
| Database schema | `services/*/src/main/resources/db/migration` | never edit an applied `V*` migration; add a new one. Demo data lives in repeatable `db/seed/R__*.sql`, which is off in tests |
| REST APIs | `services/*` controllers and DTOs | D6 conventions (paths, ProblemDetail, `PageResponse`); `@PreAuthorize`; a row in the service's `SecurityMatrixTest` and `docs/security-matrix.md`; the phase's `docs/http/*.http` |
| Authentication | `services/auth-service`, `libs/common-security` | the security matrix and the forged-token check (`scripts/forge-token.sh`); token claims are a contract with every service |
| Domain events (P3+) | outbox and consumers, `libs/common-messaging` | add fields only, never rename or remove; consumers stay idempotent; the outbox row commits in the business transaction. A new consumer declares its queue with `InboxQueues.inbox(...)` (gets a `.dlq`; D13), handles messages through `IdempotentConsumer`, and copies the `processed_message` reference DDL into a migration |
| State machines (P3+) | Event, Registration, Pass, Presence | the enum transition table is the only source; an illegal move → 409 with the allowed states; `PROJECT.md` §3 table |
| QR and signing (P4+) | `services/pass-service`, `scripts/VerifyPassToken.java` | the JWS claims are a contract with scanners, including the offline PWA (P7) and the verify script; a rotated key stays in the JWKS until old tokens expire (D12) |
| Web portal (P3+) | `web-portal` | tokens never reach the browser (BFF); D9 slice rules until P6; the UI reference (`PROJECT.md` §2) |
| Demo accounts | `db/seed` in auth- and event-service | fixed ids shared by both seeds (D8); change them together |
| Tests | `src/test` in each module | tests for new behaviour; the full suite passes before a commit |
| Docs | `PROJECT.md`, `docs/DECISIONS.md`, README, `log.md`, `docs/plans/`, `docs/http/`, `docs/security-matrix.md`, `docs/REVIEW-LATER.md` (questions parked for the owner, reviewed after P8) | `PROJECT.md` first, then everything else that states the old behaviour |

## Working rules
- Follow `PROJECT.md`. If a task conflicts with it, say so before coding, and update the file once the owner agrees.
- **If a goal or requirement changes at any point, reflect it in every document it affects.** Update `PROJECT.md` first, then `docs/DECISIONS.md`, the phase plan, `README.md`, `log.md`, `application.yml` defaults, and anything else that states the old goal or requirement.
- **Database per service.** A service never reads another service's database. Cross-service work goes through REST (queries) or RabbitMQ (facts), as in `PROJECT.md` §3.
- Tunable values (lifetimes, limits, windows, URLs) go in `application.yml` under `mitpass.*`, bound with `@ConfigurationProperties`, not hard-coded.
- Published contracts only grow. Never edit an applied Flyway migration, and never rename or remove fields in REST responses, domain events, JWT claims or QR payloads that another component reads. Add a new field or version instead.
- Platform is Windows and the project path has spaces: quote paths in shell commands. The system `java` is Java 8, so use `./mvnw` or `.tools/jdk-21/bin/java`. CI runs on Linux, so mind line endings (D5) and the executable bit (*Git & Commits*).
- Build in small, testable steps. After a security change, run the service's `SecurityMatrixTest`. After a change to seat claims or check-in (P3+), run the concurrency test.
- Add appropriate tests to test what you build or change.

## Dependencies (strict)

Keep every dependency install local to the project: Maven/Gradle dependencies through the project build (use the committed wrapper, `./mvnw`), npm packages without `-g` (run tools with `npx`), and services through `docker compose`. Never install anything globally (`npm -g`, system packages, a JDK/Maven/Node on the machine, global PATH or registry changes) unless it is absolutely necessary, and then only after asking the user and getting explicit permission. Log any approved global install in `log.md`. Maven's shared download cache (`~/.m2`) doesn't count as a global install.

## Git & Commits

* **Prompt to commit before and after each phase.** Before starting a phase (T03–T10), and again when it is finished, ask the user whether to commit the current state. Commit only with their explicit permission, for that commit only (one approval doesn't cover the next). Don't push unless they ask for that too. If they decline, record it in `log.md` and carry on.
* Commit messages: `Phase N: <summary>` for phase work, `chore: <summary>` for setup and docs.
* **Executable bit:** Git on Windows doesn't record it (`core.filemode=false`). A new shell script that must run on Linux/CI (like `mvnw`) is staged with `git add --chmod=+x <file>`. For a file that is already tracked, use `git update-index --chmod=+x <file>`. Check with `git ls-files -s <file>`, which should show `100755`.

## Mid-run Behavior
* Do not leave any download or dependancy artifact unhandled
* Always go through .gitignore after every install and update if necessary
* No dependany shall be tracked or pushed, unless absolutely necessary

## Working with the owner
- Feel free to ask for any critical decisions midrun.
- If any bugs are found midrun, do the needful: fix them (with a test), and log them in `log.md`.

## End of Run Behavior
At the end of the run, before the fininshing summary, please ensure the following:

* Ensure all running instances are terminated, unless explicitly requested for a running instance.
* Ensure 0 background tasks and 0 open sockets remaining.
* Review the docs related to the current task/bug and add/modify them to reflect changes/fixes made.