# Изменившиеся кадры — сразу в good-model: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Днём кадр, отличающийся от первого кадра записи, анализируется сразу `good-model`.

**Architecture:** Продюсер после извлечения кадров вызывает новый `ChangedFrameDetector` и помечает изменившиеся кадры в `FrameTask`; консьюмер для помеченных вызывает `DetectionPostProcessor.processChanged` (good-model с откатом на прежний каскад), остальные идут прежним путём.

**Tech Stack:** Kotlin, Spring Boot, coroutines, `javax.imageio` (через `JpegCodec` из ai-description), JUnit 5, AssertJ, mockk.

**Spec:** `docs/superpowers/specs/2026-11-28-changed-frames-good-model-design.md`

## Global Constraints

- vision-api не меняется; запросы `/detect` идут с теми же `conf` и `imgsz`, что и сейчас.
- Fail-open: любая ошибка сравнения или `good-model` даёт прежнее поведение.
- Первый кадр записи и записи с монохромным первым кадром обрабатываются как сейчас.
- Режим включён по умолчанию; выключается `DETECT_CHANGED_FRAMES_ENABLED=false`.
- Значения сравнения (из прототипа): рабочая ширина ≈640; монохромность — среднее `max(R,G,B) − min(R,G,B)` < 1.5; размытие 3×3; компенсация разности медиан яркости; порог разности 22; верхние 8 % не учитываются; открытие 3×3, расширение 7×7; изменение — связная (8-связность) область ≥ 300 px при ширине 640 (площадь масштабируется квадратом ширины) или изменено > 8 % кадра.
- Картинки в тестах только синтетические; кадры с камер не коммитить.
- Тесты и сборку запускать только через агент `build-forge:build-runner`.

## Review Focus

1. ИК-кадр с лёгким оттенком — считается монохромным (тест в задаче 1).
2. Битые байты кадра — не изменившийся, исключение наружу не выходит (задача 1).
3. Кадр другого размера, чем первый, — не сравнивается, обрабатывается как раньше (задача 1).
4. Изменилась большая часть кадра (метель, переключение света) — все такие кадры помечаются, ошибок нет (задача 1).
5. `good-model` недоступна или зависла — кадр проходит прежний каскад, а не теряется (задача 2).

---

### Task 1: `ChangedFrameDetector` и настройка

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/pipeline/frame/ChangedFrameDetector.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt`
- Modify: `modules/core/src/main/resources/application.yaml` (секция `application.detect`)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/pipeline/frame/ChangedFrameDetectorTest.kt`

**Interfaces:**
- Produces: `data class ChangedFramesConfig(val enabled: Boolean = true)`; в `DetectProperties` последний параметр `@field:Valid val changedFrames: ChangedFramesConfig = ChangedFramesConfig()`.
- Produces: `@Component class ChangedFrameDetector(detectProperties: DetectProperties)` с `fun detect(frames: List<FrameData>): ChangedFrames` (не suspend, не бросает).
- Produces: `data class ChangedFrames(val indexes: Set<Int> = emptySet(), val skip: Skip? = null) { enum class Skip { DISABLED, NOTHING_TO_COMPARE, MONOCHROME, FAILED } }`; `indexes` — значения `FrameData.frameIndex`, никогда не включают первый кадр.

- [ ] **Step 1: Тесты** (`ChangedFrameDetectorTest`; кадры 1280×960, цветной фон `Color(90,140,90)`, кодирование `JpegCodec.encode(img, 0.95f)`):
  - `a new object in a daylight frame marks that frame` — [фон, фон + прямоугольник 160×80, фон] → `indexes == {1}`, `skip == null`.
  - `a monochrome first frame skips the check` — фон `Color(100,100,100)`, объект в кадре 1 → пусто, `MONOCHROME`.
  - `a slightly tinted grey frame is still monochrome` — фон `Color(101,100,100)` → `MONOCHROME`.
  - `a speck smaller than the minimal area is not a change` — прямоугольник 10×10 → пусто.
  - `a global brightness change is not a change` — кадр 1: фон `Color(110,160,110)` → пусто.
  - `the camera clock strip is ignored` — различие только в верхних 8 % → пусто.
  - `a change of most of the picture counts as changed` — кадр 1 залит другим цветом наполовину → `{1}`.
  - `a single frame has nothing to compare` → `NOTHING_TO_COMPARE`.
  - `an unreadable first frame gives the previous behaviour` → `FAILED`, пусто.
  - `an unreadable later frame is not marked and others still are` — [фон, мусор, фон+объект] → `{2}`.
  - `a frame of another size is not compared` — кадр 1 800×600 → не помечен.
  - `disabled by configuration` → `DISABLED`.
- [ ] **Step 2: Прогон** — `./gradlew :frigate-analyzer-core:test --tests '*ChangedFrameDetectorTest'`; ожидается ошибка компиляции (класса нет).
- [ ] **Step 3: Реализация.** Декодирование `JpegCodec.decode`; уменьшение усреднением блоков `f×f`, `f = max(1, width / 640)` (без прореживания пикселей); все значения — из Global Constraints, константами с пояснением в KDoc. Ошибка на первом кадре → `FAILED`; на следующем кадре → он не помечен, один WARN на запись.
- [ ] **Step 4: Настройка.** Добавить `ChangedFramesConfig` в `DetectProperties`; в `application.yaml` под `application.detect`: `changed-frames:` → `enabled: ${DETECT_CHANGED_FRAMES_ENABLED:true}` (с комментарием).
- [ ] **Step 5: Прогон тестов задачи** — все зелёные.
- [ ] **Step 6: Commit** — `feat(core): определение кадров, изменившихся относительно первого`.

### Task 2: `DetectionPostProcessor.processChanged`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectionPostProcessor.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectionPostProcessorTest.kt`

**Interfaces:**
- Produces: `suspend fun processChanged(bytes: ByteArray, frameIndex: Int, consumerId: Int): DetectResponse` — отфильтрованный ответ `good-model`; при её ошибке/таймауте — прежний каскад (`detectWithRetry(bytes)` + `process(...)`); `CancellationException` пробрасывается.

- [ ] **Step 1: Тесты** (mockk `DetectService`, настоящий `DetectionFilterServiceImpl`):
  - `a changed frame is analyzed by the good model only` — вызван только `detectWithRetry(bytes, model = "yolo26x.pt")`, результат отфильтрован по классам.
  - `a good model timeout falls back to the cascade` — `DetectTimeoutException` → вызван вызов с моделью по умолчанию, вернулся его отфильтрованный результат (с перепроверкой, если он не пуст).
  - `a good model failure falls back to the cascade` — то же для `RuntimeException`.
  - `cancellation is not swallowed` — `CancellationException` вылетает наружу, модель по умолчанию не вызвана.
- [ ] **Step 2: Прогон** `--tests '*DetectionPostProcessorTest'` — ошибка компиляции.
- [ ] **Step 3: Реализация** в стиле `process` (те же логи уровня WARN при откате).
- [ ] **Step 4: Прогон** — зелёные.
- [ ] **Step 5: Commit** — `feat(core): анализ изменившегося кадра сразу good-model с откатом на каскад`.

### Task 3: Проброс признака через пайплайн, лог, документация

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/pipeline/frame/FrameTask.kt`, `FrameExtractorProducer.kt`, `FrameAnalyzerConsumer.kt`
- Modify: `.claude/rules/detection.md`, `.claude/rules/pipeline.md`, `.claude/rules/configuration.md`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/pipeline/frame/FrameExtractorProducerChangedFramesTest.kt`, `FrameAnalyzerConsumerTest.kt`

**Interfaces:**
- Consumes: `ChangedFrameDetector.detect`, `ChangedFrames`, `DetectionPostProcessor.processChanged`.
- Produces: `FrameTask(recordId, frameIndex, frameBytes, changed: Boolean = false)`; конструктор `FrameExtractorProducer` получает `changedFrameDetector: ChangedFrameDetector`.
- Produces: `internal fun changedFramesSuffix(changes: ChangedFrames, total: Int): String` — хвост INFO-строки `Extracted … frames`: `; changed 1/3 -> good model`, `; monochrome, changed-frame check skipped`, `; changed-frame check failed`, пусто для `DISABLED` и `NOTHING_TO_COMPARE`.

- [ ] **Step 1: Тесты:**
  - `frames the detector marks are sent as changed` — детектор (mockk) возвращает `{1}`, сервер извлечения (mockk) отдаёт 2 кадра, файл во `@TempDir`; из канала приходят задачи с `changed == [false, true]`; после этого продюсер отменяется.
  - `the log suffix names the outcome` — четыре значения `changedFramesSuffix` из Interfaces.
  - `a changed frame goes to the good model` — консьюмер получает задачу `changed = true`: вызван `processChanged`, `detectWithRetry` консьюмером не вызывается, `markCompleted` получает его ответ.
  - `an unchanged frame keeps the cascade` — `detectWithRetry` и `process`, `processChanged` не вызывается.
- [ ] **Step 2: Прогон** `--tests '*FrameExtractorProducerChangedFramesTest' --tests '*FrameAnalyzerConsumerTest'` — ошибка компиляции.
- [ ] **Step 3: Реализация.** Детектор вызывать в `withContext(Dispatchers.Default)`; `FrameTask.toString` печатает `changed`.
- [ ] **Step 4: Прогон** — зелёные; затем все тесты модуля `:frigate-analyzer-core:test`.
- [ ] **Step 5: Документация** (на английском, как в rules): что делает режим, где код, переменная `DETECT_CHANGED_FRAMES_ENABLED`, строка лога, цифры прогона и то, что ночь и первый кадр не меняются.
- [ ] **Step 6: Commit** — `feat(core): изменившиеся кадры уходят в good-model, документация`.

---

## После задач

1. Замер на кадрах с камер (локально, ничего не коммитить): метки детектора на записи с кроссовером и на статичных записях, время на кадр.
2. Ревью: агент `general-purpose` на `opus` по шаблону `code-reviewer.md` из `superpowers:requesting-code-review`; Critical/Important/реальные Minor — исправить.
3. Полная сборка `build-forge:build-runner` (`./gradlew build`), при ktlint — `./gradlew ktlintFormat`.
4. Перед PR: `git rm` своих файлов из `docs/superpowers/` и commit. Push и PR не делать.
