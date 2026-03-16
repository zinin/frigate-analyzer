# Frigate Analyzer

Video recording analysis system for Frigate security cameras using YOLO-based object detection.

**Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, WebFlux, R2DBC/PostgreSQL, Coroutines, Java 25

## Critical Rules

**Git Workflow:** ALWAYS `git add <file>` after creating or modifying files.

**Planning Mode:**
- Do NOT run `./gradlew build` directly
- After implementation: run `superpowers:code-reviewer` agent first
- Fix critical comments, repeat until clean
- Then use `build-runner` agent for build
- On ktlint errors: `./gradlew ktlintFormat`, then retry build

## Commands

| Command | Purpose |
|---------|---------|
| `./gradlew build` | Full build with tests |
| `./gradlew build -x test` | Skip tests |
| `./gradlew test` | All tests |
| `./gradlew :module-name:test` | Single module tests |
| `./gradlew ktlintCheck` | Lint check |
| `./gradlew ktlintFormat` | Auto-format |

Use `/build` command for automated build with error handling.

## Architecture

### Modules

| Module | Purpose |
|--------|---------|
| common | Utilities (UUID, clock) |
| model | Entities, DTOs, requests/responses |
| service | Business logic, repositories, MapStruct mappers |
| telegram | Bot, notifications, authorization |
| core | Spring Boot app, controllers, pipeline, tasks |

Dependencies: `core` -> `telegram` -> `service` -> `model` -> `common`

### Key Patterns

- **Pipeline:** Coroutine-based producer-consumer with Kotlin Channels
- **Detection:** Priority-based load balancing across multiple servers
- **Database:** R2DBC reactive, Liquibase migrations in `docker/liquibase/migration/`
- **Mapping:** MapStruct with KAPT (`unmappedTargetPolicy=error`)
- **Logging:** kotlin-logging with Log4j2

## Database

PostgreSQL with R2DBC. Tables: `recordings`, `detections`, `telegram_users`.

See `.claude/rules/database.md` for schema details.

## API

- Health: `http://localhost:8080/frigate-analyzer/actuator/health`
- Swagger: `http://localhost:8080/frigate-analyzer/swagger-ui/index.html`

## Modular Documentation

Detailed docs in `.claude/rules/` with conditional loading via `paths:` frontmatter:

| File | Content | Loads when working with |
|------|---------|-------------------------|
| pipeline.md | Pipeline, facade, tasks | `**/pipeline/**`, `**/facade/**`, `**/task/**` |
| detection.md | Load balancer, detect/filter/visualization services | `**/loadbalancer/**`, `**/Detect*`, `**/Visualization*`, `**/Filter*` |
| telegram.md | Bot, notifications, queue, user management | `modules/telegram/**` |
| configuration.md | All environment variables | `**/application.yaml` |
| database.md | Schema, migrations | `**/liquibase/**`, `**/repository/**`, `**/entity/**`, `**/persistent/**` |

