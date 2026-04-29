# Notification Controls and Object Tracking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-user and global on/off toggles for two Telegram notification streams (recording detections; camera signal-loss/recovery), plus a system-level IoU-based object tracker that suppresses duplicate notifications when an object stays in view across consecutive recordings.

**Architecture:** New domain services in `service/` — `ObjectTrackerService` (per-camera IoU tracking with sliding-TTL state in PostgreSQL), `AppSettingsService` (key/value table for global toggles, in-memory cached), `NotificationDecisionService` (orchestrates tracker + global checks before recording fan-out). Per-user flags on `telegram_users`. Bot dialog `/notifications` with inline keyboards manages both per-user and (for OWNER) global state. `RecordingProcessingFacade` calls the decision service before invoking Telegram fan-out, which now also filters recipients by their per-user flags.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, R2DBC PostgreSQL, Coroutines + kotlinx.coroutines.sync.Mutex, Liquibase, ktgbotapi, MockK + JUnit5 for tests.

**Spec:** `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`

---

## File Map

**New:**
- `docker/liquibase/migration/1.0.4.xml` — three changesets (tables + columns)
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/ObjectTrackEntity.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/AppSettingEntity.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RepresentativeBbox.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/ObjectTrackRepository.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/AppSettingRepository.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelper.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelper.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingsService.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/ObjectTrackerService.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationDecisionService.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/ObjectTracksCleanupTask.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/NotificationsViewState.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRenderer.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsCommandHandler.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandler.kt`

**Modified:**
- `docker/liquibase/migration/master_frigate_analyzer.xml` — register 1.0.4
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt` — two columns
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/TelegramUserDto.kt` — two fields
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/UserZoneInfo.kt` — two fields
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt` — toggle queries
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt` — toggle methods
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` — global check + per-user filter, both flows
- `modules/telegram/src/main/resources/messages_ru.properties` — new keys
- `modules/telegram/src/main/resources/messages_en.properties` — new keys
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt` — call decision service
- `modules/core/src/main/resources/application.yaml` — `application.notifications.tracker` block
- `docker/deploy/.env.example` — new env vars
- `.claude/rules/configuration.md`, `.claude/rules/telegram.md`, `.claude/rules/database.md` — docs

**New tests:**
- `modules/service/src/test/kotlin/.../helper/IouHelperTest.kt`
- `modules/service/src/test/kotlin/.../helper/BboxClusteringHelperTest.kt`
- `modules/service/src/test/kotlin/.../impl/AppSettingsServiceImplTest.kt`
- `modules/service/src/test/kotlin/.../impl/ObjectTrackerServiceImplTest.kt`
- `modules/service/src/test/kotlin/.../impl/NotificationDecisionServiceImplTest.kt`
- `modules/core/src/test/kotlin/.../task/ObjectTracksCleanupTaskTest.kt`
- `modules/telegram/src/test/kotlin/.../bot/handler/notifications/NotificationsMessageRendererTest.kt`
- `modules/telegram/src/test/kotlin/.../bot/handler/notifications/NotificationsCommandHandlerTest.kt`
- `modules/telegram/src/test/kotlin/.../bot/handler/notifications/NotificationsSettingsCallbackHandlerTest.kt`
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramUserServiceImplNotificationsTest.kt`

**Modified tests:**
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramNotificationServiceImplTest.kt` — recipient filtering + global gate
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramNotificationServiceImplSignalLossTest.kt` — same

---

## Notes for Implementer

- **Build/test:** Per project conventions, do NOT run `./gradlew build` directly. After each task, dispatch `build-runner` to run `./gradlew :module:test` for the affected module. On ktlint errors run `./gradlew ktlintFormat` then retry.
- **Module dependencies:** `core → telegram → service → model → common`. Don't accidentally pull telegram into service or model.
- **Coordinates:** YOLO returns `Double`, persists as `Float` in `DetectionEntity`. Tracker DTOs use `Float` (matches DB and IoU math).
- **Time:** Always inject `java.time.Clock` for testability — never call `Instant.now()` directly.
- **Logging:** `kotlin-logging` (`io.github.oshai.kotlinlogging.KotlinLogging`).
- **Coroutines `Mutex`:** Use `kotlinx.coroutines.sync.Mutex` (suspend-friendly), not `java.util.concurrent.locks`.
- **Spring R2DBC:** repositories implement `CoroutineCrudRepository`; `@Modifying` on UPDATE/DELETE custom queries; `@Transactional` (`org.springframework.transaction.annotation.Transactional`) on service methods that mutate.
- **Commit per task** (no batching across tasks). After committing, `git add` the new files individually — never `git add .`.

---

## Task 1: Liquibase migration `1.0.4`
✅ Done — see commit(s): `c51ab81`


## Task 2: `ObjectTrackEntity` + `ObjectTrackRepository`
✅ Done — see commit(s): `a0f9610`


## Task 3: `AppSettingEntity` + `AppSettingRepository` + `AppSettingKeys`
✅ Done — see commit(s): `9e02186`


## Task 4: Domain DTOs (`RepresentativeBbox`, `DetectionDelta`, `NotificationDecision`)
✅ Done — see commit(s): `c5066ec`


## Task 5: `IouHelper` + tests (TDD)
✅ Done — see commit(s): `8582c34`


## Task 6: `BboxClusteringHelper` + tests (TDD)
✅ Done — see commit(s): `6efc4e5`


## Task 7: `ObjectTrackerProperties` (Spring config)
✅ Done — see commit(s): `c76ecbe`


## Task 8: `ObjectTrackerService` interface + impl + tests
✅ Done — see commit(s): `83a5a7e`, `9b2190b`, `0316cbd`, `13cf068`


## Task 9: `AppSettingsService` interface + impl + tests
✅ Done — see commit(s): `2950df7`, `ea40192`


## Task 10: `NotificationDecisionService` interface + impl + tests
✅ Done — see commit(s): `07b5030`


## Task 11: `ObjectTracksCleanupTask` (`@Scheduled`)
✅ Done — see commit(s): `920820a`, `0c311a7`


## Task 12: Telegram user model extensions (entity, DTO, UserZoneInfo, repo, service)
✅ Done — see commit(s): `58a9179`, `37dcd6e`


## Task 13: `TelegramNotificationServiceImpl` global gate + recipient filtering (both flows)
✅ Done — see commit(s): `67bfa6f`


## Task 14: i18n message keys
✅ Done — see commit(s): `0a8db20`


## Task 15: `NotificationsMessageRenderer` + tests
✅ Done — see commit(s): `b2d07f5`


## Task 16: `NotificationsCommandHandler`
✅ Done — see commit(s): `7f1c9b5`


## Task 17: `NotificationsSettingsCallbackHandler` + tests
✅ Done — see commit(s): `a8e8980`


## Task 18: Wire callback subscription into the bot startup
✅ Done — see commit(s): `32aba4e`


## Task 19: `RecordingProcessingFacade` — call `NotificationDecisionService`
✅ Done — see commit(s): `7540a43`


## Task 20: `application.yaml` + `.env.example`
✅ Done — see commit(s): `bb17d18`


## Task 21: Documentation updates
✅ Done — see commit(s): `e585921`


## Task 22: Final integration test + remove design/plan from PR
✅ Done — see commit(s): `38e43c2`, `a4b107e`, `0a1e8fa`


## Done

The branch `feature/notification-controls` should now contain:
- One Liquibase migration `1.0.4.xml`
- New domain services: `ObjectTrackerService`, `AppSettingsService`, `NotificationDecisionService`
- Helpers: `IouHelper`, `BboxClusteringHelper`
- Per-user notification flags through Telegram entity/DTO/service
- Bot dialog `/notifications` with inline keyboard
- Wired into `RecordingProcessingFacade`
- Configuration in `application.yaml` and `.env.example`
- Updated docs in `.claude/rules/`
- Comprehensive unit test coverage

**Open a PR against `master`.**
