# Telegram Export Annotated Mode & Progress Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Расширить `/export`, добавив выбор режима (`ORIGINAL`/`ANNOTATED`), единый прогресс-статус в чате и интеграцию аннотации видео с `good-model` и `detection-filter.allowed-classes`.

**Architecture:** Оставляем существующий диалог в `FrigateAnalyzerBot`, добавляем шаг выбора режима и один edit/update статусного сообщения. Оркестрацию обработки сохраняем в `VideoExportServiceImpl`, расширив контракт режимом и callback прогресса; для `ANNOTATED` поверх текущего merge/compress вызываем `VideoVisualizationService`. Конфигурацию классов и модели берём из уже существующих `DetectionFilterProperties` и `DetectProperties`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4, Coroutines, R2DBC repository, ktgbotapi 30.0.2, JUnit5, MockK.

---

## Task 1: Ввести доменную модель режима и прогресса экспорта

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/ExportMode.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/VideoExportProgress.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt` (будет создан в Task 2)

**Step 1: Write the failing test (contract compilation gate)**

Создать тест-класс-заглушку `VideoExportServiceImplTest` с импортами новых типов (`ExportMode`, `VideoExportProgress`) и пустым тестом компиляции сигнатуры `exportVideo(..., mode, onProgress)`.

```kotlin
@Test
fun `service contract accepts mode and progress callback`() {
    // compile-time contract check in test sources
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
Expected: FAIL (типы/сигнатуры ещё не существуют).

**Step 3: Write minimal implementation**

1. Добавить enum:
```kotlin
enum class ExportMode { ORIGINAL, ANNOTATED }
```

2. Добавить модель прогресса (YAGNI: только нужные поля):
```kotlin
data class VideoExportProgress(
    val stage: Stage,
    val percent: Int? = null,
) {
    enum class Stage { PREPARING, MERGING, COMPRESSING, ANNOTATING, SENDING, DONE }
}
```

3. Расширить интерфейс `VideoExportService`:
```kotlin
suspend fun exportVideo(
    startInstant: Instant,
    endInstant: Instant,
    camId: String,
    mode: ExportMode,
    onProgress: suspend (VideoExportProgress) -> Unit = {},
): Path
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
Expected: PASS (или compile success без runtime assertion).

**Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/ExportMode.kt \
       modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/VideoExportProgress.kt \
       modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt
git commit -m "feat: add export mode and progress contract (FA-18)"
```

---

## Task 2: Реализовать ORIGINAL/ANNOTATED flow в `VideoExportServiceImpl` через TDD

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt` (необязательно, только если нужен отдельный helper accessor; по умолчанию не менять)
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectionFilterProperties.kt` (не менять контракт)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt` (создать полноценный набор)

**Step 1: Write failing tests for ORIGINAL path**

Добавить тесты:
1. `export original emits PREPARING MERGING DONE and returns merged path`
2. `export original emits COMPRESSING when threshold exceeded`
3. `export throws when no recordings`
4. `export throws when all files missing`

Тестовая сборка сервиса: mock `RecordingEntityRepository`, `VideoMergeHelper`, `TempFileHelper`; фиксированные `Path`.

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
Expected: FAIL.

**Step 3: Implement minimal ORIGINAL logic with progress callback**

В `VideoExportServiceImpl`:
- emit `PREPARING` перед выборкой/валидацией;
- emit `MERGING` перед `mergeVideos`;
- emit `COMPRESSING` только если сработал порог;
- emit `DONE` перед возвратом `Path`.

Сохранить текущую обработку ошибок и cleanup при исключениях.

**Step 4: Run tests to verify ORIGINAL tests pass**

Run: `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
Expected: ORIGINAL-тесты PASS.

**Step 5: Write failing tests for ANNOTATED path**

Добавить тесты:
1. `annotated mode calls VideoVisualizationService with goodModel and allowed classes`
2. `annotated mode emits ANNOTATING progress from JobStatusResponse`
3. `annotated mode deletes intermediate merged file after success`
4. `annotated mode deletes intermediate merged file on annotation error and rethrows`

Проверки:
- `model == detectProperties.goodModel`
- `classes == detectionFilterProperties.allowedClasses.joinToString(",")` (с trim/filter blank)

**Step 6: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
Expected: FAIL.

**Step 7: Implement minimal ANNOTATED logic**

Изменить конструктор `VideoExportServiceImpl`, добавить зависимости:
- `VideoVisualizationService`
- `DetectionFilterProperties`
- `DetectProperties`

Логика `ANNOTATED`:
1. получить original path через текущий flow;
2. emit `ANNOTATING`;
3. вызвать `videoVisualizationService.annotateVideo(`
   - `videoPath = mergedPath`
   - `classes = allowedClassesCsv`
   - `model = detectProperties.goodModel`
   - `onProgress = { status -> onProgress(VideoExportProgress(Stage.ANNOTATING, status.progress)) }`
   `)`
4. удалить `mergedPath` в finally после annotate попытки;
5. вернуть `annotatedPath`.

**Step 8: Run tests to verify all pass**

Run: `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
Expected: PASS.

**Step 9: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt
git commit -m "feat: support annotated export mode in video export service (FA-18)"
```

---

## Task 3: Добавить шаг выбора режима в `/export` и wiring прогресса в одно статусное сообщение

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt` (если требуется подправить imports)
- (Optional) Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/ExportProgressRenderer.kt` (только если функция внутри бота станет громоздкой)

**Step 1: Write failing tests for bot export flow (focused unit-style)**

Создать тест-класс `FrigateAnalyzerBotExportFlowTest` (в `modules/telegram/src/test/.../bot/`) с mock зависимостей:
- `TelegramBot`
- `AuthorizationFilter`
- `TelegramUserService`
- `VideoExportService`
- `TelegramProperties`
- `Clock`

Проверяем минимум:
1. после выбора камеры появляется шаг выбора режима;
2. при выборе `original` вызывается `exportVideo(..., ExportMode.ORIGINAL, ...)`;
3. при выборе `annotated` вызывается `exportVideo(..., ExportMode.ANNOTATED, ...)`.

(Если прямой e2e-тест с waiters слишком тяжёлый, допускается покрыть ключевую логику через выделенные helper-функции и их unit tests.)

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-telegram:test --tests '*FrigateAnalyzerBotExportFlowTest*'`
Expected: FAIL.

**Step 3: Implement mode selection UI in `handleExport`**

После camera callback (`export:cam:*`) добавить inline keyboard:
- `export:mode:original`
- `export:mode:annotated`
- `export:cancel`

Результат диалога: `Quadruple(startInstant, endInstant, camId, mode)`.

**Step 4: Implement single status message progress updates**

В `handleExport`:
1. отправить одно стартовое сообщение статуса и сохранить его;
2. перед вызовом сервиса передать `onProgress` callback;
3. в callback обновлять текст этого же сообщения (если редактирование недоступно в API — fallback: ограниченное количество `sendTextMessage`, но интерфейс должен быть совместим с заменой на edit API).
4. обновлять только при смене стадии или заметном изменении процента (>= 5).

Прогресс-текст должен различать:
- этапы без процента (`PREPARING`, `MERGING`, `COMPRESSING`, `SENDING`, `DONE`)
- `ANNOTATING` c процентом.

**Step 5: Wire service call with mode and sending stages**

Вызов:
```kotlin
videoExportService.exportVideo(startInstant, endInstant, camId, mode, onProgress = { ... })
```

Перед `sendVideo` эмитить `SENDING`; после успешной отправки — `DONE`.

**Step 6: Enforce annotated error policy**

Если mode `ANNOTATED` и сервис выбросил ошибку аннотации/таймаута:
- отправить явное сообщение об ошибке экспорта с объектами;
- завершить без повторной отправки original.

**Step 7: Run bot tests**

Run: `./gradlew :frigate-analyzer-telegram:test --tests '*FrigateAnalyzerBotExportFlowTest*'`
Expected: PASS.

**Step 8: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt \
       modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBotExportFlowTest.kt
git commit -m "feat: add export mode selection and progress status updates (FA-18)"
```

---

## Task 4: Проверить интеграцию core↔telegram и cleanup в error/success путях

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt` (если по итогам тестов нужны мелкие фиксы)
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt` (если нужны мелкие фиксы)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBotExportFlowTest.kt`

**Step 1: Add/adjust regression tests for cleanup**

1. original success: cleanup финального файла вызывается после `sendVideo`
2. original failure before send: нет утечки temp-файлов
3. annotated failure: intermediate merged удаляется + пользователю отправляется ошибка без fallback

**Step 2: Run targeted tests**

Run:
- `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
- `./gradlew :frigate-analyzer-telegram:test --tests '*FrigateAnalyzerBotExportFlowTest*'`

Expected: PASS.

**Step 3: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt \
       modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBotExportFlowTest.kt \
       modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt \
       modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "test: cover export cleanup and annotated error policy (FA-18)"
```

---

## Task 5: Линт, ревью, сборка и финальная валидация

**Files:**
- Modify if needed: любые файлы, затронутые ktlint/test fixes

**Step 1: Internal code review checkpoint**

Use required reviewer workflow:
1. вызвать `superpowers:code-reviewer` агент;
2. исправить критичные замечания;
3. при необходимости повторить до clean результата.

**Step 2: Run formatting if ktlint fails**

Run: `./gradlew ktlintFormat` (через build skill / build-runner).

**Step 3: Run targeted module tests**

Run (через build skill / build-runner):
- `./gradlew :frigate-analyzer-core:test --tests '*VideoExportServiceImplTest*'`
- `./gradlew :frigate-analyzer-telegram:test --tests '*FrigateAnalyzerBotExportFlowTest*'`

Expected: PASS.

**Step 4: Run broader verification**

Run (через build skill / build-runner):
- `./gradlew :frigate-analyzer-core:test`
- `./gradlew :frigate-analyzer-telegram:test`

Expected: PASS.

**Step 5: Final commit(s) for fixes**

```bash
git add <fixed-files>
git commit -m "fix: address review and verification feedback (FA-18)"
```

---

## Dependency Order

1. Task 1 → ввод типов и контракта.
2. Task 2 → реализация core-логики режимов и прогресса.
3. Task 3 → Telegram UX и wiring прогресса.
4. Task 4 → cleanup/error регрессии.
5. Task 5 → review + verification.

---

## Validation Checklist (must be true before completion)

- [ ] `/export` содержит шаг выбора режима после выбора камеры.
- [ ] `VideoExportService` принимает `ExportMode` и callback прогресса.
- [ ] `ORIGINAL` режим работает как раньше + emits progress.
- [ ] `ANNOTATED` вызывает `VideoVisualizationService.annotateVideo`.
- [ ] Для annotate API:
  - [ ] `classes` = `detection-filter.allowed-classes`
  - [ ] `model` = `detect.good-model`
- [ ] При ошибке аннотации нет fallback на original.
- [ ] Временные файлы очищаются в success/error путях.
- [ ] Тесты на `VideoExportServiceImpl` и bot export flow проходят.

---

## Notes for Executor

- Не добавлять новые пользовательские параметры аннотации в `/export` (out of scope).
- Не делать архитектурный рефакторинг в отдельный orchestrator (out of scope).
- Следовать DRY/YAGNI: минимальные изменения, закрывающие текущую задачу.
- Для запуска gradle-команд использовать skill `/build` (делегирование build-runner).
