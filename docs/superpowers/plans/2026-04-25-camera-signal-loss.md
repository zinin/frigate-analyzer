# Camera Signal Loss Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a polling-based detector that notifies Telegram subscribers when a Frigate camera stops writing recordings (signal loss) and when it resumes (recovery).

**Architecture:** A Spring `@Scheduled` task with a `suspend fun tick()` (Spring 6.1+ supports suspend in `@Scheduled`, no `runBlocking` required) wakes every `pollInterval` (default 30s), queries `MAX(record_timestamp) per cam_id` from the `recordings` table for cameras active in the last 24h, and runs each through a pure `decide()` state-machine function (`Healthy` ↔ `SignalLost`, with `notificationSent` flag for late-alert flow). State transitions enqueue text-only notifications via the existing `TelegramNotificationQueue` (suspend backpressure). State is in-memory; restart safety comes from a `startupGrace` window during which alerts are deferred (state seeded as `SignalLost(notificationSent=false)`); a late LOSS alert fires on the first tick after grace ends.

**IMPORTANT — review feedback applied:** Iter-1 review consolidated several decisions into this plan; if you read an earlier draft, note these changes:
- `tick()` is `suspend fun` (no `runBlocking` anywhere).
- Cleanup keeps `SignalLost` entries (only `Healthy` are removed).
- Conflict-fail check lives in a separate `SignalLossTelegramGuard` (mirrors `AiDescriptionTelegramGuard`).
- `@ConditionalOnProperty(matchIfMissing = false)` (default `true` in `application.yaml`, but missing-property contexts don't activate).
- `decide()` is a pure function for testability without mocks.
- `NotificationTask` sealed interface refactor includes an explicit audit step.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, Coroutines, R2DBC/PostgreSQL, JUnit 5, MockK, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-04-25-camera-signal-loss-design.md`

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt` | Projection DTO `(camId, lastRecordTimestamp)` |
| Modify | `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt` | New method `findLastRecordingPerCamera(activeSince)` |
| Modify | `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepositoryTest.kt` | Integration tests for new method |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossProperties.kt` | `@ConfigurationProperties` with `@PostConstruct` validation |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossPropertiesTest.kt` | Validation unit tests |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt` | Refactor data class → sealed interface with `RecordingNotificationTask` and `SimpleTextNotificationTask` |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt` | Dispatch on sealed type; add simple-text branch |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` | Use `RecordingNotificationTask`; add new methods |
| Modify | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt` | Update existing tests for renamed type |
| Modify | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt` | Update for sealed type |
| Modify | `modules/telegram/src/main/resources/messages_en.properties` | EN i18n keys for signal-loss/recovery/duration |
| Modify | `modules/telegram/src/main/resources/messages_ru.properties` | RU i18n keys |
| Create | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatter.kt` | Build localized loss/recovery messages; format `Duration` in human form |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatterTest.kt` | Unit tests for formatter |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt` | Add 2 new interface methods |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt` | No-op implementations |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt` | Tests for new methods |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/CameraSignalState.kt` | Sealed state class with `notificationSent` flag |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDecider.kt` | Pure `decide()` function: `(prev, observation, config) -> Decision(newState, event?)` |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDeciderTest.kt` | Parameterized table-driven tests for every row of the decision table |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt` | Main scheduled task: `suspend fun tick()`, holds state map, calls `decide()` and `TelegramNotificationService` |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt` | Behavior/integration tests: cleanup, grace, late-alert, repo throws, cancellation, skew |
| Create | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuard.kt` | `@Component` mirroring `AiDescriptionTelegramGuard`: throws `IllegalStateException` if `signal-loss.enabled=true && telegram.enabled=false` |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuardTest.kt` | Unit tests for the guard |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt` | `@SpringBootTest` conflict-fail test (signal-loss=true + telegram=false → context fails) |
| Modify | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` | Add `SignalLossProperties::class` to `@EnableConfigurationProperties` (verify file location first) |
| Modify | `modules/core/src/main/resources/application.yaml` | New `application.signal-loss` block |
| Modify | `.claude/rules/configuration.md` | Document new env-vars (incl. `SIGNAL_LOSS_ACTIVE_WINDOW >= Frigate retention` guidance) |

---

### Task 1: Repository — `LastRecordingPerCameraDto` and `findLastRecordingPerCamera`

✅ Done — see commit(s): `8d0e5d7`

---

### Task 2: `SignalLossProperties` + validation

✅ Done — see commit(s): `5fdd10f`, `c2eadf9`

---

### Task 3: Refactor `NotificationTask` to sealed interface

✅ Done — see commit(s): `519ad8a`

---

### Task 4: Add i18n message keys

✅ Done — see commit(s): `239c721`

---

### Task 5: `SignalLossMessageFormatter` (TDD)

✅ Done — see commit(s): `bc31872`, `8a4df7d`

---

### Task 6: Extend `TelegramNotificationService` with signal-loss methods + Sender simple-text branch

✅ Done — see commit(s): `ec0c410`, `0ebdc56`

---

### Task 7: `CameraSignalState` sealed class

✅ Done — see commit(s): `5eb2a86`

---

### Task 8a: Pure `decide()` function + parameterized table-driven tests

✅ Done — see commit(s): `cfd7c23`, `82c0d02`

---

### Task 8b: `SignalLossMonitorTask` (suspend tick) + behavior tests

✅ Done — see commit(s): `4a8e1c9`, `5236a88`, `89053f6`

---

### Task 8c: `SignalLossTelegramGuard` (conflict-fail)

✅ Done — see commit(s): `8e2ed30`

---

### Task 9: Configuration — `application.yaml` and `.claude/rules/configuration.md`

✅ Done — see commit(s): `65872e7`, `32f021d`

---

### Task 10: Integration test — Spring conflict-fail when telegram is disabled

✅ Done — see commit(s): `81c6dd9`

---

## Final Verification

After all tasks are complete, run the full build and lint pipeline. Per the project's CLAUDE.md, do not run `./gradlew build` directly during planning mode — invoke `superpowers:code-reviewer` first, fix critical comments, repeat until clean, then use `build-runner` agent for the build. On `ktlint` errors run `./gradlew ktlintFormat` and re-build.

```bash
# Conceptual final check (run via build-runner agent):
./gradlew ktlintCheck
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests green, no lint warnings.

## Definition of Done

- All 12 tasks committed (1, 2, 3, 4, 5, 6, 7, 8a, 8b, 8c, 9, 10).
- All listed unit + integration tests pass.
- `ktlintCheck` passes.
- `./gradlew build` passes.
- The full feature works end-to-end (manual smoke test if possible: with a running Frigate instance, stop one camera and observe the Telegram message after ~3 minutes; restart it and observe the recovery message).
- Existing test suites (`core` and `service` integration tests) remain green WITHOUT any patches to their `application.yaml` — verified by `matchIfMissing = false` semantics and the test config compatibility check from the spec.
