## TASK

Continue post-implementation work on `feature/notification-controls`. All 22 implementation tasks, the first post-review fix pass, and the GitHub P2 review fix pass are complete. Remaining work: decide on outstanding "Спорно" findings (apply / reject / defer), then prepare and open/update the PR.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the design and plan documents.
2. Report a brief summary (2-4 sentences) of what you understood.
3. **WAIT for explicit user instructions** before taking ANY action.

**DO NOT:**
- Start implementing fixes (the user decides which Спорные to address).
- Run `git rm` or create the PR without explicit instruction.
- Run any gradle / build commands.
- Re-litigate already-accepted architectural decisions.

**The user will tell you exactly what to do.**

## DOCUMENTS

**Primary:**
- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan (trimmed — all 22 ✅): `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
- Telegram outbox follow-up: `docs/telegram-outbox.md`

**Decision history (skim only — do NOT relitigate):**
- Iteration 1: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-1.md`
- Iteration 2: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-2.md`
- Iteration 3: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-3.md`

## PROGRESS

**Implementation (22/22 tasks complete):** см. план — каждая помечена `✅ Done` с SHA коммитов.

**Post-review fix pass (commit `1d4ac06`):**
- `FrigateAnalyzerBot`: `nfs:*` callbacks теперь требуют ACTIVE user (`findActiveByUsername` вместо `findByUsernameAsDto`)
- `RecordingProcessingFacade`: split try/catch — save в своём блоке, post-save (evaluate/notify) в отдельном; post-save больше не rethrow (save уже committed)
- `ObjectTrackerServiceImpl`: clustering вынесен ИЗ mutex+transaction (pure CPU); удалён duplicate empty-detections guard в `evaluateLocked`; удалён dead `requireNotNull(recording.recordTimestamp)`
- `IouHelper`: defensive `maxOf(0f, ...)` clamps на areaA/areaB для malformed bboxes; добавлен KDoc объясняющий zero-area / disjoint → 0
- `ObjectTrackEntity`: добавлен комментарий объясняющий `isNew()=true` (matching `AppSettingEntity`)

**GitHub P2 review fix pass (this commit):**
- `ObjectTrackRepository.findActive(...)`: active-track lookup теперь ограничен с двух сторон: `recordTimestamp - TTL` through `recordTimestamp + TTL`, чтобы out-of-order recordings не матчились с треками далеко в будущем.
- `TelegramNotificationServiceImpl`: signal-loss / signal-recovery global AppSettings read теперь fail-open на transient failure и rethrow только для `CancellationException`; переходы signal monitor больше не теряются из-за временной ошибки settings DB.
- `RecordingProcessingFacade` + `NotificationDecisionService`: recording global notification gate читается до `saveProcessingResult(...)`; read failure оставляет запись retryable, а post-save best-effort catch ограничен Telegram delivery.

**Build state:**
- Previous full build after `1d4ac06`: clean (454 tests passed, ktlint clean).
- After GitHub P2 fixes:
  - targeted tests clean:
    - `ObjectTrackerServiceImplTest`
    - `NotificationDecisionServiceImplTest`
    - `TelegramNotificationServiceImplSignalLossTest`
    - `RecordingProcessingFacadeTest`
    - `ObjectTrackRepositoryTest`
  - `ktlintCheck` clean.
  - `git diff --check` and `git diff --cached --check` clean.

**Branch state:** check `git status` / PR branch state before publishing; this handoff file is now committed by explicit user request.

## OPEN "СПОРНЫЕ" FINDINGS (not yet decided)

Эти findings от внешних ревьюеров — **не применены** в pass `1d4ac06` и не входили в GitHub P2 fix pass. Каждый требует решения user-а: применить, отклонить, или отложить на отдельный PR.

| # | Severity | Источник | File / Line | Описание |
|---|---|---|---|---|
| 1 | Important (gemini) / Minor (claude) | gemini, claude | `AppSettingsServiceImpl.kt:51-56` | Cache stampede on missing DB keys: `ConcurrentHashMap` не хранит null, missing keys никогда не кэшируются → каждое чтение бьёт в DB. В production keys seeded в migration, проблемы нет. Fix: sentinel value. |
| 2 | Important (claude) / KDoc-only (2 reviewers) | claude, ccs-albb-glm, ccs-ollama-kimi | `BboxClusteringHelper.kt:46-49` | Greedy union-IoU clustering: матчит против running union, не centroid. На outdoor cameras с движущимися объектами возможна фрагментация → false NEW_OBJECTS. Fix: либо алгоритм (риск), либо KDoc. |
| 3 | Important (claude) | claude | `ObjectTrackerServiceImpl.kt:76-127` | Per-representative DB round-trips inside held mutex+tx. Design требовал batch UPDATE+INSERT. Для 1-3 reps unmeasurable. Fix: `saveAll(...)` + single UPDATE CASE. |
| 4 | Important (claude) | claude | `ObjectTracksCleanupTaskTest.kt:22-32` | Swallow-test passes by accident — не ловит regression если catch сузится. Fix: добавить `assertDoesNotThrow`. |
| 5 | Important (kimi) | ccs-ollama-kimi | `RecordingProcessingFacade.kt:60-66` | Нет short-circuit на `recording.detectionsCount == 0`. `NotificationDecisionService` внутренне обрабатывает, но suspend-вызов всё равно. GitHub P2 pass добавил pre-save settings short-circuit по `request.hasDetections()`, но не заменил post-save evaluate ранним return. |
| 6 | Important (kimi) | ccs-ollama-kimi | `NotificationsSettingsCallbackHandler.kt:66, 77` | `UNAUTHORIZED` outcome silent в callback flow (в command flow → reply). Fix: surface `common.error.owner.only` в `FrigateAnalyzerBot`. |
| 7 | Important (kimi) | ccs-ollama-kimi | `AppSettingsServiceImpl.kt:38-49` | Eventual-consistency окно между `repository.upsert(...)` и `cache.remove(key)`. Для toggle UX приемлемо. Fix: документация. |
| 8 | Important (albb-glm) | ccs-albb-glm | `NotificationsSettingsCallbackHandler.kt:57` | Нет defensive `require(chatId == currentUser.chatId)`. Latent если wiring изменится. Fix: assertion. |
| 9 | Minor (claude) | claude | `NotificationDecisionServiceImpl.kt:39-42` | Fragile inference of `NO_VALID_DETECTIONS` из `(0,0,0)` triple. Fix: dedicated reason field или sealed `TrackerOutcome`. |
| 10 | Minor (claude) | claude | `NotificationsSettingsCallbackHandlerTest` | Нет тестов на IGNORE corner cases (malformed callback с 3 частями). Fix: добавить test case. |
| 11 | Minor (gemini) | gemini | `NotificationDecisionServiceImpl.kt:65-66` | Log string `globalEnabled=$globalEnabled, shouldNotify=$globalEnabled` дублировал значение. GitHub P2 pass переименовал resolved value, но смысл лог-строки всё ещё можно упростить отдельно. |

## REVIEW SESSION SUMMARY

External code review запускался через 9 reviewers в team mode:

| Reviewer | Status | Findings |
|---|---|---|
| superpowers:code-reviewer (Claude) | ✅ done | 0 Crit / 6 Imp / 6 Min |
| codex-code-reviewer | ⏳ timed out (xhigh reasoning) | — |
| ccs-glm | ❌ HTTP 429 rate limit | — |
| ccs-albb-glm | ✅ done | 0 Crit / 4 Imp / 0 Min |
| ccs-albb-qwen | ❌ upstream stream cut off | — |
| ccs-ollama-kimi | ✅ done | 0 Crit / 8 Imp / 4 Min |
| ccs-ollama-minimax | ⚠️ partial (model stalled) | 0 Crit / 2 Imp / 0 Min |
| ccs-ollama-deepseek | ⏳ timed out / killed | — |
| gemini-code-reviewer | ✅ done | 0 Crit / 2 Imp / 1 Min |

**Ни один reviewer не нашёл Critical issues.** Все Important — это улучшения (auth tightening, perf, test gaps), не блокеры.

## PRE-PR CLEANUP NEEDED

Перед `gh pr create` нужно:

1. `git rm docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
2. `git rm docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
3. (Опционально) `git rm` review-iter файлов
4. Untracked файлы (НЕ коммитить в PR без отдельного решения):
   - `docs/reset-liquibase-checksums.sh`
   - `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking-continuation-prompt.md`
   - `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking-execution-prompt.md`
5. Этот файл (`docs/superpowers/plans/2026-04-29-notification-controls-post-review-continuation-prompt.md`) уже добавлен в commit по явной просьбе user-а; не удалять его без отдельного указания.
6. Закоммитить удаление старых superpowers docs отдельным commit при подготовке PR: `chore: remove superpowers docs before PR (kept in branch history)`

## SESSION CONTEXT

**Build environment:**
- Java 25 required (default `java` is 21).
- Перед любой gradle командой: `export JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64` или `JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew ...`.
- В обычном planning flow использовать `build-runner` agent, НЕ запускать `./gradlew` напрямую. В GitHub P2 fix pass targeted tests и ktlint были запущены напрямую по текущей задаче.

**Already-accepted architectural decisions (do NOT relitigate):**
- Single-instance deployment only (`ConcurrentHashMap<String, Mutex>` для tracker — intentional).
- `RecordingProcessingFacade`: settings-backed recording global gate читается ДО `saveProcessingResult(...)`; tracker decision остаётся post-save, после commit detections.
- At-most-once delivery for tracker+telegram (`docs/telegram-outbox.md`); для signal global settings read transient failures выбран fail-open, чтобы не терять LOSS/RECOVERY переходы.
- `ObjectTracksCleanupTask` каждый час без conditional gate (`cleanupExpired()` дешёвый no-op).
- Pre-existing flaky `ExportExecutorTest` — не связан с этой веткой.

**Key architectural patterns в этой фиче:**
- Best-match IoU (`maxByOrNull`), не first-match (cross-recording matching).
- Per-camera `Mutex`; `TransactionalOperator.executeAndAwait` ВНУТРИ mutex; clustering ВНЕ mutex (после `1d4ac06`).
- Active track lookup bounded by absolute TTL window around recording timestamp (after GitHub P2 fix pass).
- Sliding TTL — совпавшие треки живут бесконечно.
- Fail-open при tracker error + global ON; global OFF всегда побеждает.
- `AppSettingsService.getBoolean()` corrupt value → WARN+default; recording notification read failure before save → propagates/retry; signal notification read failure → fail-open.
- Callback auth по `callback.user.username` (sender, не message chat); ACTIVE-only после `1d4ac06`.
- `nfs:*` callbacks несут explicit target state (1/0), не toggle.
- `CancellationException` всегда rethrown перед generic catch.

## INSTRUCTIONS

1. Read the design and plan documents.
2. Understand current state and the open Спорные findings.
3. Provide a brief summary (2-4 sentences) of what you understood.
4. **STOP and WAIT** — do NOT proceed with any implementation, do NOT decide which Спорные to apply.
5. Ask: "Какие Спорные findings применить, отклонить или отложить? И когда готовить PR?"
