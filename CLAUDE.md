# Frigate Analyzer

Video recording analysis system for Frigate security cameras using YOLO-based object detection.

**Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, WebFlux, R2DBC/PostgreSQL, Coroutines, Java 25, ktgbotapi 36.1.0, Jackson 3

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
| `./gradlew :frigate-analyzer-telegram:test` | Single module tests — every module is `:frigate-analyzer-<name>` (see `settings.gradle.kts`) |
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
| ai-description | AI-generated detection descriptions; named presets (provider + model + effort) over Claude Code SDK and Grok Build CLI |
| telegram | Bot, notifications, authorization, AI description editing, `/ai` preset dialog |
| core | Spring Boot app, controllers, pipeline, tasks, signal-loss monitor |

Main chain: `core` → `telegram` → `service` → `model` → `common`. Cross-cutting: `core` and `telegram` both depend on `ai-description`.

### Key Patterns

- **Pipeline:** Coroutine-based producer-consumer with Kotlin Channels
- **Detection:** Priority-based load balancing across multiple servers
- **Signal-loss monitor:** Polls latest recording per camera, alerts on gap > threshold
- **Object tracking:** Cross-recording IoU matching to suppress duplicate notifications
- **AI description:** Provider-neutral agent (semaphore, retry, auth-loss alert to owner) over `DescriptionBackend`; Claude Code SDK or headless Grok Build CLI; edits the notification message
- **Description presets:** yaml declares named presets (provider + model + effort), one backend per preset; the owner switches the active one and turns descriptions off from `/ai`, stored in `app_settings` and surviving a restart
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
| telegram-export.md | `/export` + Quick Export, size limit (`core.video`), cancellation, lock-ordering invariant | `**/handler/export/**`, `**/handler/quickexport/**`, `**/handler/cancel/**`, `core/**/video/**` |
| telegram-notifications.md | `/notifications` dialog, `nfs:*` callbacks, per-user/global flag storage | `**/handler/notifications/**` |
| ai-description.md | Presets and catalog, provider SPI and factories, Claude and Grok backends, `/ai` dialog, auth alerts, rate limiter | `modules/ai-description/**`, `**/handler/aisettings/**` |
| configuration.md | All environment variables | `**/application.yaml` |
| database.md | Schema, migrations | `**/liquibase/**`, `**/repository/**`, `**/entity/**`, `**/persistent/**` |
| telegram-timeout-bug.md | ktgbotapi long-polling timeout workaround status | `**/TelegramAutoConfiguration*` |

