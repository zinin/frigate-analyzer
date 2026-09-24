# Description Storyboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать модели AI-описаний раскадровку события — сетку до 16 кадров с подписями времени вокруг детекции, при необходимости из соседних сегментов Frigate, — вместе с полноразмерными кадрами, и удлинить короткое описание вдвое.

**Architecture:** В `core` появляется пакет `storyboard`: чистый планировщик окна и клеток, поиск соседних сегментов с ожиданием следующего, нарезка ffmpeg-ом, сборка сетки Java2D и сборщик с fail-open. Фасад строит раскадровку внутри supplier-а описания, до второго чтения выключателя. Модуль `ai-description` только рисует её в промпте: `VisionRequest` несёт картинки с подписями, подписи и порядок задаёт задача, провайдеры печатают как есть; промпт судьи не меняется ни на символ.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines (`Semaphore`, `kotlinx-coroutines-test`), R2DBC/PostgreSQL, ffmpeg/ffprobe (`FfmpegProcessRunner`, `VideoProbe`), Java2D/ImageIO, MockK, kotlin-test JUnit5, AssertJ, Testcontainers (compose), ktlint.

**Spec:** `docs/superpowers/specs/2026-09-23-description-storyboard-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) запускаются **только** через агента `claude-forge:build-runner`, никогда напрямую (правило `CLAUDE.md`). На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Тесты модуля: `./gradlew :frigate-analyzer-ai-description:test`, `:frigate-analyzer-service:test`, `:frigate-analyzer-core:test`, `:frigate-analyzer-telegram:test`. Один класс: `--tests <FQCN>`. Интеграционные тесты `core` (наследники `IntegrationTestBase`) поднимают Postgres через `docker/test-compose.yml` — нужен Docker. Тесты с настоящим ffmpeg пропускаются (`assumeTrue`), если его нет в `/usr/bin` или в `FFMPEG_PATH`/`FFPROBE_PATH`.
- После создания или изменения файла обязательно `git add <file>` (правило `CLAUDE.md`).
- Коммиты в стиле репозитория (`feat(core): …`, `refactor(ai-description): …`). Если у сессии исполнителя есть URL (Claude Code on the web), сообщение заканчивается отдельным `-m "Claude-Session: <URL сессии исполнителя>"`; в CLI без URL строка опускается.
- Ветка `feat/description-storyboard`. Перед PR файлы `docs/superpowers/**` удаляются из индекса отдельным коммитом (`git rm -r docs/superpowers/`), в диффе PR их быть не должно (правило владельца).
- Промпты — на английском; описания пишутся на языке `APP_AI_DESCRIPTION_LANGUAGE`. Формулировки живут только в `DescriptionTask` и `JudgeTask`. Правила про инструменты — только в бэкендах: `ClaudeBackend.FRAME_READING_RULE` и `GrokBackend.TOOL_RULE` не меняются, `DescriptionTask` не упоминает инструменты.
- Промпт судьи не меняется ни на символ: подписи `Frame N` по `frameIndex`, заголовок `Frames (in chronological order):`.
- Константы раскадровки: ширина сетки 2560 px; JPEG 0.85; шаг клеток не меньше 0.5 с; клеток не меньше 4; последние 0.1 с отрезка не используются; не больше двух одновременных ffprobe/ffmpeg в сборщике (ожидание сегмента семафор не держит); таймаут одного запуска ffmpeg 20 с; опрос следующего сегмента раз в секунду; допуск стыка сегментов 1.5 с; «файл устоялся» — не менялся 1 с; окно поиска предыдущего сегмента 60 с; следующий ищется в `(cur, cur + D + 5 s]`.
- Настройки: `APP_AI_DESCRIPTION_STORYBOARD_ENABLED=true`, `_BEFORE=5s` и `_AFTER=5s` (0–10 с), `_TILES=16` (4–25), `_NEXT_SEGMENT_WAIT=30s` (0–120 с); `APP_AI_DESCRIPTION_MAX_FRAMES` 10 → 4; `APP_AI_DESCRIPTION_SHORT_MAX` 200 → 400.
- Fail-open: сбой раскадровки никогда не теряет описание; `CancellationException` всегда пробрасывается.
- Миграций базы нет: новые запросы идут по существующему индексу `idx_recordings_record_timestamp`.
- Комментарии и KDoc в новом коде — по-русски, как в соседнем коде `ai-description` и `core/judge`; имена тестов — английские в обратных кавычках.

## Review Focus

- **Запись из накопившейся очереди** (часы после съёмки, после простоя или `FIRST_SCAN`): описание не ждёт следующий сегмент, выполняется ровно одна проверка. Тесты: `AdjacentSegmentFinderTest` «a deadline in the past means exactly one lookup and no waiting» (Task 7), `StoryboardBuilderTest` «the next-segment deadline of a backlog recording is already past» (Task 10).
- **Frigate не сохранил следующий сегмент** (запись только по движению или разрыв): ожидание конечно, `missingAfter` выставлен, описание всё равно уходит. Тесты: «a next segment that starts after a gap is not used and not waited for», «a next segment that never appears is given up at the deadline» (Task 7), «a next segment that never came leaves the end of the footage open» (Task 10).
- **Кадры без времени** (старый vision-api или кадр, которому сервер не дал `timestamp`): раскадровка по всей записи, соседей не ищем, детекции перечислены без времени. Тесты: «frames without times cover the whole recording» (Task 10), «a detection without a time is listed without one» (Task 4).
- **Вертикальная камера** (дверной звонок 9:16): пропорции клеток сохраняются, сетка не сплющена. Тест: «a portrait camera keeps its proportions» (Task 9).
- **Строка следующего сегмента уже в базе, а файл Frigate ещё дописывает**: файл не читается, пока не устоялся, потом берётся. Тест: «a next segment still being written is probed only once it settles» (Task 7).

---

## Структура файлов

### `model` (`modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/`)

| Файл | Изменение |
|---|---|
| `dto/FrameData.kt` | + `offsetSeconds: Double? = null` — время кадра от начала записи |

### `service` (`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/`)

| Файл | Изменение |
|---|---|
| `repository/RecordingEntityRepository.kt` | + `findPreviousSegment`, `findNextSegment` |
| `RecordingEntityService.kt`, `impl/RecordingEntityServiceImpl.kt` | те же два метода, `RecordingDto?` |

### `ai-description` (`modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/`)

| Файл | Изменение |
|---|---|
| `api/DescriptionRequest.kt` | `FrameImage.offsetSeconds`; `Storyboard`, `DetectionMark`, поле `storyboard` |
| `core/VisionRequest.kt` | `VisionImage(bytes, caption)`, `VisionRequest.images`, `VisionInstructions.imagesHeader` |
| `core/DescriptionTask.kt` | `images()`, `visionRequest()`, подписи, заголовки, тексты раскадровки, правило `short` |
| `core/JudgeTask.kt` | `images()`, `visionRequest()` с прежними подписями и заголовком |
| `core/DefaultDescriptionAgent.kt`, `core/DefaultJudgeAgent.kt` | через `visionRequest()` задачи |
| `core/VisionCallExecutor.kt` | уменьшение `images` |
| `claude/ClaudeImageStager.kt`, `claude/ClaudePromptBuilder.kt` | порядок из запроса, подписи задачи |
| `grok/GrokPromptFileWriter.kt`, `grok/GrokBackend.kt` | то же; строка лога |

### `core` (`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/`)

| Файл | Ответственность |
|---|---|
| `pipeline/frame/FrameExtractorProducer.kt` (modify) | `toFrameData` сохраняет `timestamp` кадра |
| `storyboard/StoryboardPlanner.kt` (create) | Чистая арифметика: окно, соседи, стык, отрезок, клетки, пометки, сетка, подписи |
| `storyboard/AdjacentSegmentFinder.kt` (create) | Предыдущий и следующий сегмент, ожидание до срока, «файл устоялся» |
| `storyboard/StoryboardFrameSampler.kt` (create) | Один запуск ffmpeg на сегмент |
| `storyboard/StoryboardComposer.kt` (create) | Сетка, подписи, JPEG |
| `storyboard/StoryboardBuilder.kt` (create) | Оркестрация, семафор, fail-open, строка INFO |
| `config/properties/StoryboardProperties.kt` (create) | `application.ai.description.storyboard.*` |
| `FrigateAnalyzerApplication.kt` (modify) | регистрация `StoryboardProperties` |
| `facade/RecordingProcessingFacade.kt` (modify) | раскадровка → выключатель → `describe` |
| `resources/application.yaml` (modify) | блок `storyboard`, `SHORT_MAX` 400, `MAX_FRAMES` 4 |

### Документация

`.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `.claude/rules/pipeline.md`, `docker/deploy/.env.example`, `README.md`, `CLAUDE.md` — в задачах, которые меняют описываемое поведение.

---

### Task 1: Время кадра доходит до фасада

✅ Done — see commit(s): `55faa80`

---

### Task 2: Картинки с подписями в `VisionRequest`

✅ Done — see commit(s): `321dd14`

---

### Task 3: Короткое описание вдвое длиннее

✅ Done — see commit(s): `bc3ab96`

---

### Task 4: Раскадровка в промпте описания

✅ Done — see commit(s): `b8045a4`

---

### Task 5: `StoryboardPlanner`

✅ Done — see commit(s): `965eaf7`

---

### Task 6: Запросы соседних сегментов

✅ Done — see commit(s): `552e3e6`

---

### Task 7: `AdjacentSegmentFinder`

✅ Done — see commit(s): `6dca779`

---

### Task 8: `StoryboardFrameSampler`

✅ Done — see commit(s): `816c0fe`, `ab6f8c4`

---

### Task 9: `StoryboardComposer`

✅ Done — see commit(s): `8b08a3b`, `cd422a8`, `87cdf8e`

---

### Task 10: `StoryboardProperties` и `StoryboardBuilder`

✅ Done — see commit(s): `8a96310`, `b73a321`

---

### Task 11: Фасад отдаёт раскадровку модели

✅ Done — see commit(s): `518591b`, `52d0ae5`, `46d82b4`

---

## Перед PR

- `git rm -r docs/superpowers/` и отдельный коммит `chore: remove design and plan documents before PR` (правило владельца: в диффе PR их быть не должно, они остаются в истории ветки).
- В рабочем `.env` на стенде убрать явные `APP_AI_DESCRIPTION_MAX_FRAMES` и `APP_AI_DESCRIPTION_SHORT_MAX`, если они там заданы, иначе новые значения по умолчанию не подействуют.
- После выката проверить строки `Storyboard for …` в логе: как часто `next: missing`, сколько `built in`; посмотреть описания нескольких реальных событий с машинами.
