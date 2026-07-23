# Frigate Analyzer

Video recording analysis system for Frigate security cameras using YOLO-based object detection.

**Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, WebFlux, R2DBC/PostgreSQL, Coroutines, Java 25, ktgbotapi 35.1.0, Jackson 3

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
| ai-description | AI-generated detection descriptions via Claude Code SDK |
| telegram | Bot, notifications, authorization, AI description editing |
| core | Spring Boot app, controllers, pipeline, tasks, signal-loss monitor |

Main chain: `core` → `telegram` → `service` → `model` → `common`. Cross-cutting: `core` and `telegram` both depend on `ai-description`.

### Key Patterns

- **Pipeline:** Coroutine-based producer-consumer with Kotlin Channels
- **Detection:** Priority-based load balancing across multiple servers
- **Signal-loss monitor:** Polls latest recording per camera, alerts on gap > threshold
- **Object tracking:** Cross-recording IoU matching to suppress duplicate notifications
- **AI description:** Async Claude Code CLI invocation with rate-limit + queue, edits notification message
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
| pipeline.md | Pipeline, facade, tasks, signal-loss monitor, watchers | `**/pipeline/**`, `**/facade/**`, `**/task/**` |
| detection.md | Load balancer, detect/filter/visualization/export services | `**/loadbalancer/**`, `**/Detect*`, `**/Visualization*`, `**/Filter*` |
| telegram.md | Bot core: components, queue, auth, ktgbotapi waiter API | `modules/telegram/**` |
| telegram-export.md | `/export` + Quick Export, cancellation, lock-ordering invariant | `**/handler/export/**`, `**/handler/quickexport/**`, `**/handler/cancel/**` |
| telegram-notifications.md | `/notifications` dialog, `nfs:*` callbacks, per-user/global flag storage | `**/handler/notifications/**` |
| ai-description.md | Claude Code SDK integration, rate limiter, description agent | `modules/ai-description/**` |
| configuration.md | All environment variables | `**/application.yaml` |
| database.md | Schema, migrations | `**/liquibase/**`, `**/repository/**`, `**/entity/**`, `**/persistent/**` |
| telegram-timeout-bug.md | ktgbotapi long-polling timeout workaround status | `**/TelegramAutoConfiguration*` |

