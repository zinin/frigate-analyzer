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

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionRequest.kt`
- Modify: `…/ai/description/core/DescriptionTask.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionTaskTest.kt`
- Docs: `.claude/rules/ai-description.md` (строка Layers про `DescriptionRequest`)

**Interfaces:**
- Consumes: `VisionImage`, `VisionInstructions.imagesHeader`, `DescriptionTask.seconds()` (Task 2).
- Produces:
  - `DescriptionRequest(…, storyboard: Storyboard? = null)` — новый последний параметр.
  - `DescriptionRequest.Storyboard(image: ByteArray, tiles: Int, stepSeconds: Double, durationSeconds: Double, detections: List<DetectionMark>, missingBefore: Boolean, missingAfter: Boolean)`
  - `DescriptionRequest.DetectionMark(className: String, confidence: Double, offsetSeconds: Double?)`
  - `DescriptionTask.IMAGES_HEADER = "Images:"`

- [ ] **Step 1: Write the failing tests**

В `DescriptionTaskTest.kt` добавить хелпер и тесты:

```kotlin
    private fun storyboard(
        missingBefore: Boolean = false,
        missingAfter: Boolean = false,
        detections: List<DescriptionRequest.DetectionMark> = listOf(DescriptionRequest.DetectionMark("car", 0.87, 5.0)),
    ) = DescriptionRequest.Storyboard(
        image = byteArrayOf(9),
        tiles = 16,
        stepSeconds = 1.0,
        durationSeconds = 15.0,
        detections = detections,
        missingBefore = missingBefore,
        missingAfter = missingAfter,
    )

    @Test
    fun `the storyboard goes first, then the full-resolution frames in time order`() {
        val request =
            request().copy(
                frames =
                    listOf(
                        DescriptionRequest.FrameImage(4, byteArrayOf(4), offsetSeconds = 9.0),
                        DescriptionRequest.FrameImage(1, byteArrayOf(1), offsetSeconds = 5.0),
                    ),
                storyboard = storyboard(),
            )

        val images = DescriptionTask.images(request)

        assertEquals(
            listOf(
                "Storyboard: 16 frames, 1.0s apart, left to right, top to bottom",
                "Full-resolution frame at 5.0s",
                "Full-resolution frame at 9.0s",
            ),
            images.map { it.caption },
        )
        assertEquals(listOf<Byte>(9, 1, 4), images.map { it.bytes.single() })
        assertEquals("Images:", DescriptionTask.instructions(request).imagesHeader)
    }

    @Test
    fun `the storyboard preamble explains the timeline and lists detections with times`() {
        val detections =
            listOf(
                DescriptionRequest.DetectionMark("car", 0.87, 5.0),
                DescriptionRequest.DetectionMark("person", 0.71, 9.0),
            )

        val preamble = DescriptionTask.instructions(request().copy(storyboard = storyboard(detections = detections))).preamble

        assertTrue(preamble.contains("Write both descriptions in English."))
        assertTrue(preamble.contains("16 frames taken every 1.0s over 15.0s of footage, read left to right, top to bottom"))
        assertTrue(preamble.contains("The detector found: car 0.87 at 5.0s; person 0.71 at 9.0s."))
        assertTrue(preamble.contains("The remaining images are full-resolution frames for details."))
        assertTrue(preamble.contains("An object that stays in the same place in every tile is stationary."))
        assertFalse(preamble.contains("not available"))
    }

    /** Без этих строк модель выдумала бы, что машина «уехала», когда запись просто кончилась. */
    @Test
    fun `missing footage on either side is spelled out`() {
        val preamble =
            DescriptionTask.instructions(request().copy(storyboard = storyboard(missingBefore = true, missingAfter = true))).preamble

        assertTrue(preamble.contains("Earlier footage is not available."))
        assertTrue(preamble.contains("Footage after 15.0s is not available."))
    }

    @Test
    fun `a detection without a time is listed without one`() {
        val detections = listOf(DescriptionRequest.DetectionMark("car", 0.87, null))

        val preamble = DescriptionTask.instructions(request().copy(storyboard = storyboard(detections = detections))).preamble

        assertTrue(preamble.contains("The detector found: car 0.87."))
    }

    @Test
    fun `without a storyboard the preamble stays as it was`() {
        assertEquals(
            "You are analyzing surveillance camera frames captured during an object detection event.\n" +
                "Write both descriptions in English.",
            DescriptionTask.instructions(request("en")).preamble,
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests "ru.zinin.frigate.analyzer.ai.description.core.DescriptionTaskTest"`
Expected: FAIL компиляции — `Unresolved reference 'Storyboard'`, `No parameter with name 'storyboard'`.

- [ ] **Step 3: Add the storyboard to the request**

В `DescriptionRequest.kt` добавить импорт `java.util.Objects` и последний параметр конструктора:

```kotlin
data class DescriptionRequest(
    val recordingId: UUID,
    val frames: List<FrameImage>,
    val language: String,
    val shortMaxLength: Int,
    val detailedMaxLength: Int,
    /** Раскадровка события; `null` — модель получает только [frames]. */
    val storyboard: Storyboard? = null,
) {
```

Класс `FrameImage` (Task 2) не трогать; после него, внутри `DescriptionRequest`, добавить два класса:

```kotlin
    /**
     * Одна картинка-сетка: [tiles] кадров с шагом [stepSeconds] за [durationSeconds] секунд записи,
     * слева направо и сверху вниз. Все времена — секунды от начала этого отрезка; в них же
     * [FrameImage.offsetSeconds] полноразмерных кадров.
     */
    data class Storyboard(
        val image: ByteArray,
        val tiles: Int,
        val stepSeconds: Double,
        val durationSeconds: Double,
        val detections: List<DetectionMark>,
        /** Окно хотело захватить запись раньше начала отрезка, а её не оказалось. */
        val missingBefore: Boolean,
        /** Окно хотело захватить запись позже конца отрезка, а её не оказалось. */
        val missingAfter: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Storyboard) return false
            return tiles == other.tiles &&
                stepSeconds == other.stepSeconds &&
                durationSeconds == other.durationSeconds &&
                detections == other.detections &&
                missingBefore == other.missingBefore &&
                missingAfter == other.missingAfter &&
                image.contentEquals(other.image)
        }

        override fun hashCode(): Int =
            31 * Objects.hash(tiles, stepSeconds, durationSeconds, detections, missingBefore, missingAfter) +
                image.contentHashCode()

        override fun toString(): String =
            "Storyboard(tiles=$tiles, stepSeconds=$stepSeconds, durationSeconds=$durationSeconds, " +
                "detections=$detections, missingBefore=$missingBefore, missingAfter=$missingAfter, image=${image.size}B)"
    }

    /** Что нашёл детектор на одном кадре: класс, лучший confidence этого класса на кадре и время кадра. */
    data class DetectionMark(
        val className: String,
        val confidence: Double,
        /** Секунды от начала отрезка; `null` — время кадра неизвестно. */
        val offsetSeconds: Double?,
    )
```

- [ ] **Step 4: Word the storyboard in `DescriptionTask`**

Добавить константу, заменить `images` и `instructions` целиком; хелперы `frameCaption` и `seconds` из Task 2 остаются:

```kotlin
    /** Заголовок перед картинками, когда первой идёт раскадровка. */
    const val IMAGES_HEADER = "Images:"

    /** Раскадровка первой, затем полноразмерные кадры по времени; без раскадровки — одни кадры. */
    fun images(request: DescriptionRequest): List<VisionImage> {
        val frames = request.frames.sortedBy { it.frameIndex }
        val storyboard = request.storyboard ?: return frames.map { VisionImage(it.bytes, frameCaption(it)) }
        return listOf(VisionImage(storyboard.image, storyboardCaption(storyboard))) +
            frames.map { VisionImage(it.bytes, fullFrameCaption(it)) }
    }

    fun instructions(request: DescriptionRequest): VisionInstructions {
        val languageName = LanguageNames.of(request.language)
        val storyboard = request.storyboard
        val preamble =
            if (storyboard == null) {
                buildString {
                    appendLine("You are analyzing surveillance camera frames captured during an object detection event.")
                    append("Write both descriptions in $languageName.")
                }
            } else {
                storyboardPreamble(storyboard, languageName, hasFrames = request.frames.isNotEmpty())
            }
        val epilogue =
            buildString {
                appendLine("Return ONLY this JSON object (no prose around it):")
                appendLine("""{"short": "...", "detailed": "..."}""")
                appendLine()
                appendLine("Rules:")
                appendLine(
                    "- \"short\": one to three sentences on what happened, including any movement " +
                        "(who or what, where from, where to, whether it stopped); " +
                        "must not exceed ${request.shortMaxLength} characters.",
                )
                appendLine("- \"detailed\" must not exceed ${request.detailedMaxLength} characters.")
                append("- No markdown, no explanations — just the JSON object.")
            }
        return VisionInstructions(
            systemPrompt = SYSTEM_PROMPT,
            preamble = preamble,
            imagesHeader = if (storyboard == null) FRAMES_HEADER else IMAGES_HEADER,
            epilogue = epilogue,
            jsonSchema = JSON_SCHEMA,
        )
    }

    private fun storyboardPreamble(
        storyboard: DescriptionRequest.Storyboard,
        languageName: String,
        hasFrames: Boolean,
    ): String =
        buildString {
            appendLine("You are analyzing surveillance camera footage around an object detection event.")
            appendLine("Write both descriptions in $languageName.")
            appendLine()
            appendLine(
                "The first image is a storyboard: ${storyboard.tiles} frames taken every ${seconds(storyboard.stepSeconds)} " +
                    "over ${seconds(storyboard.durationSeconds)} of footage, read left to right, top to bottom. " +
                    "Each tile is labeled with its time in seconds from the start of the footage; " +
                    "tiles labeled \"detection\" are the ones closest to a detection.",
            )
            if (storyboard.detections.isNotEmpty()) {
                appendLine("The detector found: ${detectionsLine(storyboard.detections)}.")
            }
            if (storyboard.missingBefore) appendLine("Earlier footage is not available.")
            if (storyboard.missingAfter) appendLine("Footage after ${seconds(storyboard.durationSeconds)} is not available.")
            if (hasFrames) appendLine("The remaining images are full-resolution frames for details.")
            appendLine()
            append(
                "Describe how the scene develops over time: where objects come from, where they move, " +
                    "whether they stop or leave. An object that stays in the same place in every tile is stationary.",
            )
        }

    private fun detectionsLine(marks: List<DescriptionRequest.DetectionMark>): String =
        marks.joinToString("; ") { mark ->
            val base = "${mark.className} ${String.format(Locale.US, "%.2f", mark.confidence)}"
            mark.offsetSeconds?.let { "$base at ${seconds(it)}" } ?: base
        }

    private fun storyboardCaption(storyboard: DescriptionRequest.Storyboard): String =
        "Storyboard: ${storyboard.tiles} frames, ${seconds(storyboard.stepSeconds)} apart, left to right, top to bottom"

    private fun fullFrameCaption(frame: DescriptionRequest.FrameImage): String =
        frame.offsetSeconds?.let { "Full-resolution frame at ${seconds(it)}" } ?: "Full-resolution frame ${frame.frameIndex}"
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS (все тесты модуля, включая промпт судьи).

- [ ] **Step 6: Document it**

`.claude/rules/ai-description.md`, строка Layers `| API | \`DescriptionRequest\` / \`DescriptionResult\` / \`DescriptionException\` | … |` — в конец описания дописать: «; `DescriptionRequest.storyboard` optionally carries a timed grid of the event (`Storyboard`: image, tiles, step, duration, `DetectionMark`s, missing footage before/after), worded by `DescriptionTask` — see "Storyboard"».

- [ ] **Step 7: Commit**

```bash
git add modules/ai-description/src .claude/rules/ai-description.md
git commit -m "feat(ai-description): describe from a storyboard when one is given"
```

---

### Task 5: `StoryboardPlanner`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPlanner.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPlannerTest.kt`

**Interfaces:**
- Produces (пакет `ru.zinin.frigate.analyzer.core.storyboard`):
  - `data class TimeRange(start: Double, end: Double)` с `length`
  - `data class Footage(range: TimeRange, missingBefore: Boolean, missingAfter: Boolean)`
  - `data class TileGrid(moments: List<Double>, stepSeconds: Double)`
  - `enum class SegmentRole { PREVIOUS, CURRENT, NEXT }`, `data class SegmentMoment(role: SegmentRole, localSeconds: Double)`
  - `data class GridLayout(columns: Int, rows: Int)`
  - `object StoryboardPlanner`: `MIN_STEP_SECONDS`, `MIN_TILES`, `GRID_WIDTH`, `END_GUARD_SECONDS`, `CONTIGUITY_TOLERANCE`; `window(detectionSeconds, before, after, currentDuration): TimeRange`, `needsPrevious(window)`, `needsNext(window, currentDuration)`, `contiguous(expected: Instant, actual: Instant)`, `footage(window, currentDuration, previousDuration: Double?, nextDuration: Double?): Footage`, `tileGrid(footage: TimeRange, maxTiles: Int): TileGrid?`, `locate(seconds, currentDuration, previousDuration: Double?): SegmentMoment`, `markedTiles(moments, detectionSeconds): Set<Int>`, `layout(tiles): GridLayout`, `tileWidth(columns): Int`, `tileLabel(seconds, marked): String`

- [ ] **Step 1: Write the failing test**

`modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPlannerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardPlannerTest {
    @Test
    fun `the window spans the detections plus the margins`() {
        assertEquals(TimeRange(0.0, 10.0), StoryboardPlanner.window(listOf(5.0), 5.0, 5.0, 12.0))
        assertEquals(TimeRange(-3.0, 16.0), StoryboardPlanner.window(listOf(11.0, 2.0), 5.0, 5.0, 12.0))
    }

    @Test
    fun `without detection times the window is the whole recording`() {
        val window = StoryboardPlanner.window(emptyList(), 5.0, 5.0, 12.0)

        assertEquals(TimeRange(0.0, 12.0), window)
        assertFalse(StoryboardPlanner.needsPrevious(window))
        assertFalse(StoryboardPlanner.needsNext(window, 12.0))
    }

    @Test
    fun `neighbours are needed only past the recording's edges`() {
        assertTrue(StoryboardPlanner.needsPrevious(TimeRange(-0.1, 5.0)))
        assertFalse(StoryboardPlanner.needsPrevious(TimeRange(0.0, 5.0)))
        assertTrue(StoryboardPlanner.needsNext(TimeRange(5.0, 12.1), 12.0))
        assertFalse(StoryboardPlanner.needsNext(TimeRange(5.0, 12.0), 12.0))
    }

    @Test
    fun `segments butt against each other within a second and a half`() {
        val base = Instant.parse("2026-09-23T10:00:00Z")

        assertTrue(StoryboardPlanner.contiguous(base, base.plusMillis(1400)))
        assertTrue(StoryboardPlanner.contiguous(base, base.minusMillis(1400)))
        assertFalse(StoryboardPlanner.contiguous(base, base.plusMillis(1600)))
    }

    @Test
    fun `footage is the window cut to what the neighbours cover`() {
        val window = TimeRange(-3.0, 16.0)

        assertEquals(Footage(TimeRange(-3.0, 16.0), false, false), StoryboardPlanner.footage(window, 12.0, 10.0, 10.0))
        assertEquals(Footage(TimeRange(0.0, 12.0), true, true), StoryboardPlanner.footage(window, 12.0, null, null))
        assertEquals(Footage(TimeRange(-2.0, 15.0), true, true), StoryboardPlanner.footage(window, 12.0, 2.0, 3.0))
        assertEquals(Footage(TimeRange(1.0, 11.0), false, false), StoryboardPlanner.footage(TimeRange(1.0, 11.0), 12.0, null, null))
    }

    @Test
    fun `a long enough footage gets the full number of tiles, spread evenly`() {
        val grid = assertNotNull(StoryboardPlanner.tileGrid(TimeRange(1.0, 11.0), 16))

        assertEquals(16, grid.moments.size)
        assertEquals(0.66, grid.stepSeconds, 1e-9)
        assertEquals(1.0, grid.moments.first(), 1e-9)
        assertEquals(10.9, grid.moments.last(), 1e-9)
    }

    /** В `double` `7.506 / (7.506 / 15)` равно `14.999999999999998`: деление обратно потеряло бы клетку. */
    @Test
    fun `the tile count does not lose a tile to floating point`() {
        assertEquals(16, assertNotNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 7.606), 16)).moments.size)
    }

    @Test
    fun `a short footage keeps half a second between tiles`() {
        val grid = assertNotNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 5.1), 16))

        assertEquals(11, grid.moments.size)
        assertEquals(0.5, grid.stepSeconds)
    }

    @Test
    fun `fewer than four tiles is no storyboard`() {
        assertEquals(4, assertNotNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 1.6), 16)).moments.size)
        assertNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 1.5), 16))
        assertNull(StoryboardPlanner.tileGrid(TimeRange(6.0, 6.0), 16))
    }

    @Test
    fun `a moment is found in the file that holds it`() {
        assertEquals(SegmentMoment(SegmentRole.PREVIOUS, 8.0), StoryboardPlanner.locate(-2.0, 12.0, 10.0))
        assertEquals(SegmentMoment(SegmentRole.CURRENT, 3.0), StoryboardPlanner.locate(3.0, 12.0, null))
        assertEquals(SegmentMoment(SegmentRole.NEXT, 0.0), StoryboardPlanner.locate(12.0, 12.0, null))
        assertEquals(SegmentMoment(SegmentRole.NEXT, 1.5), StoryboardPlanner.locate(13.5, 12.0, null))
        assertFailsWith<IllegalArgumentException> { StoryboardPlanner.locate(-1.0, 12.0, null) }
    }

    @Test
    fun `the tiles nearest to the detections are marked`() {
        val moments = listOf(0.0, 1.0, 2.0, 3.0)

        assertEquals(setOf(1, 3), StoryboardPlanner.markedTiles(moments, listOf(1.2, 2.9)))
        assertEquals(emptySet(), StoryboardPlanner.markedTiles(moments, emptyList()))
    }

    @Test
    fun `the grid is as square as the tile count allows`() {
        assertEquals(GridLayout(4, 4), StoryboardPlanner.layout(16))
        assertEquals(GridLayout(4, 3), StoryboardPlanner.layout(11))
        assertEquals(GridLayout(2, 2), StoryboardPlanner.layout(4))
        assertEquals(GridLayout(5, 5), StoryboardPlanner.layout(25))
        assertEquals(GridLayout(3, 2), StoryboardPlanner.layout(5))
    }

    @Test
    fun `tiles share the grid width and stay even`() {
        assertEquals(640, StoryboardPlanner.tileWidth(4))
        assertEquals(852, StoryboardPlanner.tileWidth(3))
        assertEquals(512, StoryboardPlanner.tileWidth(5))
    }

    @Test
    fun `a tile label is its time, plus a mark near a detection`() {
        assertEquals("5.0s", StoryboardPlanner.tileLabel(5.0, marked = false))
        assertEquals("12.3s • detection", StoryboardPlanner.tileLabel(12.34, marked = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardPlannerTest"`
Expected: FAIL компиляции — `Unresolved reference 'StoryboardPlanner'`.

- [ ] **Step 3: Implement**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPlanner.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Отрезок на шкале текущей записи, в секундах от её начала; предыдущий сегмент — меньше нуля. */
data class TimeRange(
    val start: Double,
    val end: Double,
) {
    val length: Double get() = end - start
}

/** Запись, доступная раскадровке, и чего не хватило окну с каждой стороны. */
data class Footage(
    val range: TimeRange,
    val missingBefore: Boolean,
    val missingAfter: Boolean,
)

/** Моменты клеток на шкале записи и шаг между ними. */
data class TileGrid(
    val moments: List<Double>,
    val stepSeconds: Double,
)

enum class SegmentRole { PREVIOUS, CURRENT, NEXT }

/** Момент клетки внутри конкретного файла: какой сегмент и сколько секунд от его начала. */
data class SegmentMoment(
    val role: SegmentRole,
    val localSeconds: Double,
)

data class GridLayout(
    val columns: Int,
    val rows: Int,
)

/**
 * Арифметика раскадровки без ввода-вывода. Время — «время записи»: текущий сегмент занимает
 * `[0, D)`, предыдущий — `[-Dp, 0)`, следующий — `[D, D + Dn)`.
 */
object StoryboardPlanner {
    /** Клетки не чаще, чем раз в полсекунды: чаще соседние кадры почти не отличаются. */
    const val MIN_STEP_SECONDS = 0.5

    /** Меньше четырёх клеток — не раскадровка; описание идёт по одним кадрам. */
    const val MIN_TILES = 4

    /** Ширина сетки: Claude 4.7+ уменьшает картинку до 2576 px по длинной стороне. */
    const val GRID_WIDTH = 2560

    /** Последние 0.1 с отрезка не используются: кадр ровно на конце файла ffmpeg уже не отдаёт. */
    const val END_GUARD_SECONDS = 0.1

    /** Время начала в имени файла Frigate округлено до секунды; стык проверяется с этим допуском. */
    val CONTIGUITY_TOLERANCE: Duration = Duration.ofMillis(1500)

    /**
     * Окно вокруг детекций: от первой минус [before] до последней плюс [after]. Без времён детекций
     * окно — вся текущая запись, и соседи не нужны.
     */
    fun window(
        detectionSeconds: List<Double>,
        before: Double,
        after: Double,
        currentDuration: Double,
    ): TimeRange =
        if (detectionSeconds.isEmpty()) {
            TimeRange(0.0, currentDuration)
        } else {
            TimeRange(detectionSeconds.min() - before, detectionSeconds.max() + after)
        }

    fun needsPrevious(window: TimeRange): Boolean = window.start < 0.0

    fun needsNext(
        window: TimeRange,
        currentDuration: Double,
    ): Boolean = window.end > currentDuration

    /** Стыкуются ли два момента: расходятся не больше чем на [CONTIGUITY_TOLERANCE]. */
    fun contiguous(
        expected: Instant,
        actual: Instant,
    ): Boolean = Duration.between(expected, actual).abs() <= CONTIGUITY_TOLERANCE

    /**
     * Окно, обрезанное по доступной записи. [previousDuration] и [nextDuration] — длительности
     * соседей, которые стыкуются и прочитаны; `null` — соседа нет.
     */
    fun footage(
        window: TimeRange,
        currentDuration: Double,
        previousDuration: Double?,
        nextDuration: Double?,
    ): Footage {
        val earliest = previousDuration?.let { -it } ?: 0.0
        val latest = currentDuration + (nextDuration ?: 0.0)
        val start = maxOf(window.start, earliest)
        val end = minOf(window.end, latest)
        return Footage(TimeRange(start, end), missingBefore = window.start < start, missingAfter = window.end > end)
    }

    /**
     * Моменты клеток на отрезке без последних [END_GUARD_SECONDS]: [maxTiles] клеток, если шаг
     * выходит не меньше [MIN_STEP_SECONDS], иначе шаг [MIN_STEP_SECONDS]. Число клеток задаётся
     * ветками явно, а не делением обратно: в `double` `7.506 / (7.506 / 15)` равно
     * `14.999999999999998`, и `floor` потерял бы клетку. `null` — клеток меньше [MIN_TILES].
     */
    fun tileGrid(
        footage: TimeRange,
        maxTiles: Int,
    ): TileGrid? {
        val usable = footage.length - END_GUARD_SECONDS
        if (usable < 0.0) return null
        val count: Int
        val step: Double
        if (usable >= MIN_STEP_SECONDS * (maxTiles - 1)) {
            count = maxTiles
            step = usable / (maxTiles - 1)
        } else {
            count = floor(usable / MIN_STEP_SECONDS).toInt() + 1
            step = MIN_STEP_SECONDS
        }
        if (count < MIN_TILES) return null
        return TileGrid(List(count) { k -> footage.start + k * step }, step)
    }

    /** В каком файле лежит момент [seconds] шкалы записи и сколько секунд от начала этого файла. */
    fun locate(
        seconds: Double,
        currentDuration: Double,
        previousDuration: Double?,
    ): SegmentMoment =
        when {
            seconds < 0.0 -> {
                val previous = requireNotNull(previousDuration) { "moment $seconds needs the previous segment" }
                SegmentMoment(SegmentRole.PREVIOUS, (previous + seconds).coerceAtLeast(0.0))
            }
            seconds < currentDuration -> SegmentMoment(SegmentRole.CURRENT, seconds)
            else -> SegmentMoment(SegmentRole.NEXT, seconds - currentDuration)
        }

    /** Индексы клеток, ближайших к моментам детекций. */
    fun markedTiles(
        moments: List<Double>,
        detectionSeconds: List<Double>,
    ): Set<Int> =
        detectionSeconds
            .mapNotNull { detection -> moments.indices.minByOrNull { abs(moments[it] - detection) } }
            .toSet()

    fun layout(tiles: Int): GridLayout {
        require(tiles > 0) { "tiles must be positive, was $tiles" }
        val columns = ceil(sqrt(tiles.toDouble())).toInt()
        return GridLayout(columns, (tiles + columns - 1) / columns)
    }

    /** Ширина клетки: сетка всегда [GRID_WIDTH] px, ширина чётная — так спокойнее JPEG-кодеру ffmpeg. */
    fun tileWidth(columns: Int): Int = (GRID_WIDTH / columns) and 1.inv()

    /** Подпись клетки: время от начала показанного отрезка; у клетки рядом с детекцией — пометка. */
    fun tileLabel(
        seconds: Double,
        marked: Boolean,
    ): String {
        val time = String.format(Locale.US, "%.1fs", seconds)
        return if (marked) "$time • detection" else time
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardPlannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPlanner.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPlannerTest.kt
git commit -m "feat(core): plan the storyboard around the detection"
```

---

### Task 6: Запросы соседних сегментов

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/RecordingEntityService.kt`, `impl/RecordingEntityServiceImpl.kt`
- Test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/RecordingEntityServiceImplTest.kt`, `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `RecordingEntityRepository.findPreviousSegment(camId: String, from: Instant, before: Instant): RecordingEntity?` — последняя запись камеры с `record_timestamp` в `[from, before)`, `file_path IS NOT NULL`
  - `RecordingEntityRepository.findNextSegment(camId: String, after: Instant, until: Instant): RecordingEntity?` — первая запись камеры с `record_timestamp` в `(after, until]`, `file_path IS NOT NULL`
  - `RecordingEntityService.findPreviousSegment(camId, from, before): RecordingDto?`, `findNextSegment(camId, after, until): RecordingDto?`

- [ ] **Step 1: Write the failing tests**

В `RecordingEntityServiceImplTest.kt` (используя существующие хелперы `recordingEntity` и `toDto`):

```kotlin
    @Test
    fun `findPreviousSegment maps the repository row`() =
        runTest {
            val entity = recordingEntity(UUID.randomUUID(), Instant.parse("2026-04-29T11:59:50Z"))
            val from = Instant.parse("2026-04-29T11:59:00Z")
            val before = Instant.parse("2026-04-29T12:00:00Z")
            coEvery { repository.findPreviousSegment("cam1", from, before) } returns entity
            every { mapper.toDto(entity) } returns entity.toDto()

            assertEquals(entity.toDto(), service.findPreviousSegment("cam1", from, before))
        }

    @Test
    fun `findNextSegment returns null when the repository has nothing`() =
        runTest {
            val after = Instant.parse("2026-04-29T12:00:00Z")
            val until = Instant.parse("2026-04-29T12:00:15Z")
            coEvery { repository.findNextSegment("cam1", after, until) } returns null

            assertEquals(null, service.findNextSegment("cam1", after, until))
        }
```

В `RecordingEntityRepositoryTest.kt`: хелперу `createRecordingEntity` добавить последний параметр `recordTimestamp: Instant = Instant.now()` и подставить его в поле `recordTimestamp = recordTimestamp` (остальные вызовы не меняются). Добавить регион:

```kotlin
    // region adjacent segments

    @Test
    fun `findPreviousSegment picks the latest recording of the camera before the start`() {
        runBlocking {
            val start = Instant.parse("2026-09-23T10:00:00Z")
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.minusSeconds(20)))
            val previous = repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.minusSeconds(10)))
            repository.save(createRecordingEntity(camId = "cam2", recordTimestamp = start.minusSeconds(5)))
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start))

            val found = repository.findPreviousSegment("cam1", start.minusSeconds(60), start)

            assertEquals(previous.id, found?.id)
        }
    }

    @Test
    fun `findPreviousSegment ignores recordings older than the lookback`() {
        runBlocking {
            val start = Instant.parse("2026-09-23T10:00:00Z")
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.minusSeconds(61)))

            assertNull(repository.findPreviousSegment("cam1", start.minusSeconds(60), start))
        }
    }

    @Test
    fun `findNextSegment picks the earliest recording of the camera after the start`() {
        runBlocking {
            val start = Instant.parse("2026-09-23T10:00:00Z")
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start))
            val next = repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.plusSeconds(10)))
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.plusSeconds(20)))
            repository.save(createRecordingEntity(camId = "cam2", recordTimestamp = start.plusSeconds(5)))

            val found = repository.findNextSegment("cam1", start, start.plusSeconds(15))

            assertEquals(next.id, found?.id)
        }
    }

    /** `file_path` в `recordings` допускает NULL; такой строке нечего отдать ffmpeg. */
    @Test
    fun `findNextSegment skips a recording without a file`() {
        runBlocking {
            val start = Instant.parse("2026-09-23T10:00:00Z")
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.plusSeconds(10)).copy(filePath = null))

            assertNull(repository.findNextSegment("cam1", start, start.plusSeconds(15)))
        }
    }

    @Test
    fun `findNextSegment returns null when nothing started within the window`() {
        runBlocking {
            val start = Instant.parse("2026-09-23T10:00:00Z")
            repository.save(createRecordingEntity(camId = "cam1", recordTimestamp = start.plusSeconds(20)))

            assertNull(repository.findNextSegment("cam1", start, start.plusSeconds(15)))
        }
    }

    // endregion
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.RecordingEntityServiceImplTest"`
Expected: FAIL компиляции — `Unresolved reference 'findPreviousSegment'`.

- [ ] **Step 3: Implement**

`RecordingEntityRepository.kt`, в конец интерфейса:

```kotlin
    /**
     * Сегмент камеры перед записью: последняя строка с началом в `[from, before)`. Окно ограничено,
     * чтобы запрос шёл по индексу `record_timestamp`, а не сканировал историю камеры назад.
     */
    @Query(
        """
        SELECT *
        FROM recordings
        WHERE cam_id = :camId
          AND record_timestamp >= :from
          AND record_timestamp < :before
          AND file_path IS NOT NULL
        ORDER BY record_timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun findPreviousSegment(
        @Param("camId") camId: String,
        @Param("from") from: Instant,
        @Param("before") before: Instant,
    ): RecordingEntity?

    /** Сегмент камеры после записи: первая строка с началом в `(after, until]`. */
    @Query(
        """
        SELECT *
        FROM recordings
        WHERE cam_id = :camId
          AND record_timestamp > :after
          AND record_timestamp <= :until
          AND file_path IS NOT NULL
        ORDER BY record_timestamp ASC
        LIMIT 1
        """,
    )
    suspend fun findNextSegment(
        @Param("camId") camId: String,
        @Param("after") after: Instant,
        @Param("until") until: Instant,
    ): RecordingEntity?
```

`RecordingEntityService.kt` (импорт `java.time.Instant`):

```kotlin
    /** Последняя запись камеры, начавшаяся в `[from, before)`; `null` — такой нет. */
    suspend fun findPreviousSegment(
        camId: String,
        from: Instant,
        before: Instant,
    ): RecordingDto?

    /** Первая запись камеры, начавшаяся в `(after, until]`; `null` — такой нет. */
    suspend fun findNextSegment(
        camId: String,
        after: Instant,
        until: Instant,
    ): RecordingDto?
```

`RecordingEntityServiceImpl.kt`, рядом с `getRecording`:

```kotlin
    @Transactional(readOnly = true)
    override suspend fun findPreviousSegment(
        camId: String,
        from: Instant,
        before: Instant,
    ): RecordingDto? = repository.findPreviousSegment(camId, from, before)?.let { mapper.toDto(it) }

    @Transactional(readOnly = true)
    override suspend fun findNextSegment(
        camId: String,
        after: Instant,
        until: Instant,
    ): RecordingDto? = repository.findNextSegment(camId, after, until)?.let { mapper.toDto(it) }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-service:test` и `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.repository.RecordingEntityRepositoryTest"` (нужен Docker).
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/service/src modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt
git commit -m "feat(service): find the segments next to a recording"
```

---

### Task 7: `AdjacentSegmentFinder`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/AdjacentSegmentFinder.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/AdjacentSegmentFinderTest.kt`

**Interfaces:**
- Consumes: `StoryboardPlanner.contiguous` (Task 5); `RecordingEntityService.findPreviousSegment`/`findNextSegment` (Task 6); `VideoProbe.probe(path): VideoInfo`.
- Produces:
  - `data class Segment(recording: RecordingDto, path: Path, info: VideoInfo)`
  - `AdjacentSegmentFinder.previous(current: RecordingDto): Segment?`
  - `AdjacentSegmentFinder.next(current: RecordingDto, currentDuration: Double, deadline: Instant): Segment?`
  - `AdjacentSegmentFinder.durationOf(seconds: Double): java.time.Duration` (internal, companion)

- [ ] **Step 1: Write the failing test**

`modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/AdjacentSegmentFinderTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.video.VideoInfo
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AdjacentSegmentFinderTest {
    @TempDir
    lateinit var dir: Path

    private val service = mockk<RecordingEntityService>()
    private val probe = mockk<VideoProbe>()
    private val base = Instant.parse("2026-09-23T10:00:00Z")

    /** Часы идут за виртуальным временем теста: `delay` в поиске двигает и их. */
    private fun TestScope.finder(): AdjacentSegmentFinder {
        val scope = this
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = base.plusMillis(scope.currentTime)
            }
        return AdjacentSegmentFinder(service, probe, clock)
    }

    private fun file(
        name: String,
        modified: Instant = base.minusSeconds(60),
    ): Path =
        dir.resolve(name).also {
            Files.write(it, byteArrayOf(1))
            Files.setLastModifiedTime(it, FileTime.from(modified))
        }

    private fun recording(
        start: Instant,
        file: Path,
    ) = RecordingDto(
        id = UUID.randomUUID(),
        creationTimestamp = start,
        filePath = file.toString(),
        fileCreationTimestamp = start,
        camId = "cam1",
        recordDate = LocalDate.of(2026, 9, 23),
        recordTime = LocalTime.of(10, 0),
        recordTimestamp = start,
        startProcessingTimestamp = null,
        processTimestamp = null,
        processAttempts = 0,
        detectionsCount = 0,
        analyzeTime = 0,
        analyzedFramesCount = 0,
        errorMessage = null,
    )

    private fun info(duration: Double) = VideoInfo(durationSeconds = duration, width = 2880, height = 1620, fps = 20.0, hasAudio = false)

    private fun current() = recording(base, file("current.mp4"))

    @Test
    fun `a previous segment that ends where the current one starts is used`() =
        runTest {
            val previous = recording(base.minusSeconds(10), file("previous.mp4"))
            coEvery { service.findPreviousSegment("cam1", base.minusSeconds(60), base) } returns previous
            coEvery { probe.probe(Path.of(previous.filePath)) } returns info(10.0)

            val segment = assertNotNull(finder().previous(current()))

            assertEquals(previous, segment.recording)
            assertEquals(10.0, segment.info.durationSeconds)
        }

    @Test
    fun `a previous segment followed by a gap is not used`() =
        runTest {
            val previous = recording(base.minusSeconds(10), file("previous.mp4"))
            coEvery { service.findPreviousSegment(any(), any(), any()) } returns previous
            coEvery { probe.probe(any()) } returns info(7.0)

            assertNull(finder().previous(current()))
        }

    @Test
    fun `a failed lookup of the previous segment counts as no segment`() =
        runTest {
            coEvery { service.findPreviousSegment(any(), any(), any()) } throws RuntimeException("db down")

            assertNull(finder().previous(current()))
        }

    @Test
    fun `a failed lookup of the next segment is retried until the deadline`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            coEvery { service.findNextSegment(any(), any(), any()) } throws RuntimeException("db down") andThen next
            coEvery { probe.probe(any()) } returns info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(1_000L, currentTime)
        }

    @Test
    fun `a next segment already in the database is taken on the first lookup`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            coEvery { service.findNextSegment("cam1", base, base.plusSeconds(15)) } returns next
            coEvery { probe.probe(Path.of(next.filePath)) } returns info(10.0)

            val segment = assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(next, segment.recording)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `a next segment that appears on the third lookup is waited for`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            var calls = 0
            coEvery { service.findNextSegment(any(), any(), any()) } answers { if (++calls < 3) null else next }
            coEvery { probe.probe(any()) } returns info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(3, calls)
            assertEquals(2_000L, currentTime)
        }

    @Test
    fun `a next segment that never appears is given up at the deadline`() =
        runTest {
            coEvery { service.findNextSegment(any(), any(), any()) } returns null

            assertNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(30_000L, currentTime)
            coVerify(exactly = 31) { service.findNextSegment(any(), any(), any()) }
        }

    @Test
    fun `a deadline in the past means exactly one lookup and no waiting`() =
        runTest {
            coEvery { service.findNextSegment(any(), any(), any()) } returns null

            assertNull(finder().next(current(), 10.0, deadline = base.minusSeconds(3600)))

            assertEquals(0L, currentTime)
            coVerify(exactly = 1) { service.findNextSegment(any(), any(), any()) }
        }

    @Test
    fun `a next segment that starts after a gap is not used and not waited for`() =
        runTest {
            val next = recording(base.plusSeconds(13), file("next.mp4"))
            coEvery { service.findNextSegment(any(), any(), any()) } returns next

            assertNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(0L, currentTime)
            coVerify(exactly = 0) { probe.probe(any()) }
        }

    @Test
    fun `a next segment still being written is probed only once it settles`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4", modified = base))
            coEvery { service.findNextSegment(any(), any(), any()) } returns next
            coEvery { probe.probe(any()) } returns info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(1_000L, currentTime)
            coVerify(exactly = 1) { probe.probe(any()) }
        }

    @Test
    fun `a next segment ffprobe cannot read yet is retried`() =
        runTest {
            val next = recording(base.plusSeconds(10), file("next.mp4"))
            coEvery { service.findNextSegment(any(), any(), any()) } returns next
            coEvery { probe.probe(any()) } throws RuntimeException("moov atom not found") andThen info(10.0)

            assertNotNull(finder().next(current(), 10.0, deadline = base.plusSeconds(30)))

            assertEquals(1_000L, currentTime)
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.AdjacentSegmentFinderTest"`
Expected: FAIL компиляции — `Unresolved reference 'AdjacentSegmentFinder'`.

- [ ] **Step 3: Implement**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/AdjacentSegmentFinder.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.video.VideoInfo
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Соседний сегмент, который стыкуется с текущей записью и прочитан ffprobe. */
data class Segment(
    val recording: RecordingDto,
    val path: Path,
    val info: VideoInfo,
)

/**
 * Ищет соседние сегменты камеры для раскадровки. Предыдущий уже на диске — одна проверка. Следующего
 * может ещё не быть: строка появляется, когда watcher заметит файл, а файл Frigate может ещё
 * дописывать, поэтому поиск повторяется раз в секунду до срока. ffprobe соседей идёт мимо семафора
 * сборщика: он дешёвый и выполняется по разу на сегмент.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class AdjacentSegmentFinder(
    private val recordingEntityService: RecordingEntityService,
    private val videoProbe: VideoProbe,
    private val clock: Clock,
) {
    /** Предыдущий сегмент, если он кончается там, где начинается [current]; иначе `null`. */
    suspend fun previous(current: RecordingDto): Segment? {
        val start = current.recordTimestamp
        val candidate =
            lookup("previous", current) {
                recordingEntityService.findPreviousSegment(current.camId, start.minus(PREVIOUS_LOOKBACK), start)
            } ?: return null
        val info = probe(candidate) ?: return null
        val end = candidate.recordTimestamp.plus(durationOf(info.durationSeconds))
        if (!StoryboardPlanner.contiguous(start, end)) {
            logger.debug { "Previous segment ${candidate.id} of ${current.id} ends at $end, not at $start; not used" }
            return null
        }
        return Segment(candidate, Path.of(candidate.filePath), info)
    }

    /**
     * Следующий сегмент, если он начинается там, где кончается [current]. Проверяет раз в секунду до
     * [deadline]; срок в прошлом — ровно одна проверка. Сегмент с разрывом не ждём: Frigate пропустил
     * запись, и дальше ждать нечего.
     */
    suspend fun next(
        current: RecordingDto,
        currentDuration: Double,
        deadline: Instant,
    ): Segment? {
        val expectedStart = current.recordTimestamp.plus(durationOf(currentDuration))
        val until = expectedStart.plus(NEXT_LOOKAHEAD)
        while (true) {
            val candidate =
                lookup("next", current) {
                    recordingEntityService.findNextSegment(current.camId, current.recordTimestamp, until)
                }
            if (candidate != null) {
                if (!StoryboardPlanner.contiguous(expectedStart, candidate.recordTimestamp)) {
                    logger.debug {
                        "Next segment ${candidate.id} of ${current.id} starts at ${candidate.recordTimestamp}, " +
                            "not at $expectedStart; not used"
                    }
                    return null
                }
                if (settled(candidate)) {
                    probe(candidate)?.let { return Segment(candidate, Path.of(candidate.filePath), it) }
                }
            }
            if (!clock.instant().isBefore(deadline)) return null
            delay(POLL_INTERVAL)
        }
    }

    /** Сбой запроса к базе — как «соседа нет»: раскадровка без него лучше, чем никакой. */
    private suspend fun lookup(
        which: String,
        current: RecordingDto,
        query: suspend () -> RecordingDto?,
    ): RecordingDto? =
        try {
            query()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Cannot look up the $which segment of ${current.id}" }
            null
        }

    /** Файл не менялся последнюю секунду: Frigate его дописал. */
    private suspend fun settled(candidate: RecordingDto): Boolean =
        try {
            val modified = withContext(Dispatchers.IO) { Files.getLastModifiedTime(Path.of(candidate.filePath)) }
            !modified.toInstant().isAfter(clock.instant().minus(SETTLE_TIME))
        } catch (e: IOException) {
            logger.debug { "Segment ${candidate.id} is not readable yet: ${e.message}" }
            false
        }

    private suspend fun probe(candidate: RecordingDto): VideoInfo? =
        try {
            videoProbe.probe(Path.of(candidate.filePath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "Cannot probe segment ${candidate.id} yet: ${e.message}" }
            null
        }

    companion object {
        val PREVIOUS_LOOKBACK: Duration = Duration.ofSeconds(60)
        val NEXT_LOOKAHEAD: Duration = Duration.ofSeconds(5)
        val SETTLE_TIME: Duration = Duration.ofSeconds(1)
        val POLL_INTERVAL = 1.seconds

        internal fun durationOf(seconds: Double): Duration = Duration.ofNanos((seconds * 1_000_000_000).toLong())
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.AdjacentSegmentFinderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/AdjacentSegmentFinder.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/AdjacentSegmentFinderTest.kt
git commit -m "feat(core): find and wait for the neighbouring segments"
```

---

### Task 8: `StoryboardFrameSampler`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardFrameSampler.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardFrameSamplerTest.kt`, `StoryboardFrameSamplerIntegrationTest.kt`

**Interfaces:**
- Consumes: `ApplicationProperties.tempFolder`/`ffmpegPath`, `FfmpegProcessRunner.run(command, timeout)`, `TempFileHelper.deleteFiles(paths)`.
- Produces: `StoryboardFrameSampler.sample(file: Path, firstSeconds: Double, stepSeconds: Double, count: Int, tileWidth: Int): List<ByteArray>` — до `count` JPEG по порядку; меньше, если файл кончился раньше; бросает, если ffmpeg упал. `internal fun command(file, firstSeconds, stepSeconds, count, tileWidth, output: Path): List<String>`.

- [ ] **Step 1: Write the failing tests**

`StoryboardFrameSamplerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.mockk
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryboardFrameSamplerTest {
    private val properties =
        ApplicationProperties(
            tempFolder = Path.of("/tmp/fa"),
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            connectionTimeout = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(5),
            writeTimeout = Duration.ofSeconds(5),
            responseTimeout = Duration.ofSeconds(5),
        )
    private val sampler = StoryboardFrameSampler(properties, mockk(), mockk())

    @Test
    fun `seeks to the first moment and samples at the storyboard step`() {
        val command =
            sampler.command(
                Path.of("/rec/cam1/10.00.mp4"),
                firstSeconds = 3.25,
                stepSeconds = 0.66,
                count = 7,
                tileWidth = 640,
                output = Path.of("/tmp/fa/storyboard-x-%03d.jpg"),
            )

        assertEquals("/usr/bin/ffmpeg", command.first())
        assertEquals("/tmp/fa/storyboard-x-%03d.jpg", command.last())
        assertEquals("3.250", command[command.indexOf("-ss") + 1])
        assertTrue(command.indexOf("-ss") < command.indexOf("-i"), "-ss must be an input option: an accurate and fast seek")
        assertEquals("/rec/cam1/10.00.mp4", command[command.indexOf("-i") + 1])
        assertEquals("fps=1.515152,scale=640:-2", command[command.indexOf("-vf") + 1])
        assertEquals("7", command[command.indexOf("-frames:v") + 1])
    }
}
```

`StoryboardFrameSamplerIntegrationTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Настоящий ffmpeg на синтетическом видео. Пропускается (как skipped, не как passed), если ffmpeg
 * нет в `/usr/bin` или в `FFMPEG_PATH`; CI ставит его через apt.
 */
class StoryboardFrameSamplerIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val ffmpeg: Path = Path.of(System.getenv("FFMPEG_PATH") ?: "/usr/bin/ffmpeg")
    private val runner = FfmpegProcessRunner()
    private lateinit var sampler: StoryboardFrameSampler
    private lateinit var video: Path

    @BeforeEach
    fun setUp() {
        assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg not found at $ffmpeg, skipping")
        val properties =
            ApplicationProperties(
                tempFolder = tempDir.resolve("tmp"),
                ffmpegPath = ffmpeg,
                connectionTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
                writeTimeout = Duration.ofSeconds(5),
                responseTimeout = Duration.ofSeconds(5),
            )
        val tempFileHelper = TempFileHelper(properties, Clock.systemUTC()).also { it.init() }
        sampler = StoryboardFrameSampler(properties, runner, tempFileHelper)
        video = tempDir.resolve("segment.mp4")
    }

    private suspend fun makeVideo() {
        runner.run(
            listOf(
                ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=10:size=640x360:rate=20",
                "-c:v", "mpeg4", "-q:v", "5", video.toString(),
            ),
            Duration.ofMinutes(1),
        )
    }

    @Test
    fun `samples the requested frames at the tile width`() =
        runTest(timeout = 2.minutes) {
            makeVideo()

            val frames = sampler.sample(video, firstSeconds = 1.0, stepSeconds = 1.0, count = 5, tileWidth = 320)

            assertEquals(5, frames.size)
            frames.forEach { bytes ->
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertEquals(320, image.width)
                assertEquals(180, image.height)
            }
            assertFalse(frames.first().contentEquals(frames.last()), "testsrc changes over time; the frames must differ")
            assertTrue(tempDir.resolve("tmp").listDirectoryEntries("storyboard-*").isEmpty(), "sampled files are deleted")
        }

    @Test
    fun `a file that ends early gives fewer frames`() =
        runTest(timeout = 2.minutes) {
            makeVideo()

            val frames = sampler.sample(video, firstSeconds = 8.0, stepSeconds = 1.0, count = 5, tileWidth = 320)

            assertTrue(frames.size in 1..3, "expected the frames at 8 and 9 s (maybe one more), got ${frames.size}")
        }

    @Test
    fun `a missing file fails`() =
        runTest(timeout = 2.minutes) {
            assertFailsWith<RuntimeException> {
                sampler.sample(tempDir.resolve("missing.mp4"), 0.0, 1.0, 3, 320)
            }
            assertTrue(tempDir.resolve("tmp").listDirectoryEntries("storyboard-*").isEmpty())
        }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardFrameSampler*"`
Expected: FAIL компиляции — `Unresolved reference 'StoryboardFrameSampler'`.

- [ ] **Step 3: Implement**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardFrameSampler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Нарезает кадры одного сегмента: [sample] делает один запуск ffmpeg — точный переход `-ss` к
 * первому моменту и фильтр `fps` с шагом раскадровки. Кадров может прийти меньше, если файл кончился
 * раньше.
 *
 * Кадры пишутся в temp-папку файлами с уникальным префиксом: `FfmpegProcessRunner` читает вывод
 * процесса как текст, так что JPEG через stdout не забрать. Файлы удаляются в `finally`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class StoryboardFrameSampler(
    private val applicationProperties: ApplicationProperties,
    private val processRunner: FfmpegProcessRunner,
    private val tempFileHelper: TempFileHelper,
) {
    suspend fun sample(
        file: Path,
        firstSeconds: Double,
        stepSeconds: Double,
        count: Int,
        tileWidth: Int,
    ): List<ByteArray> {
        require(count > 0) { "count must be positive, was $count" }
        require(stepSeconds > 0.0) { "stepSeconds must be positive, was $stepSeconds" }
        val dir = applicationProperties.tempFolder.toAbsolutePath().normalize()
        val prefix = "storyboard-${UUID.randomUUID()}-"
        try {
            processRunner.run(
                command(file, firstSeconds, stepSeconds, count, tileWidth, dir.resolve("$prefix%03d.jpg")),
                FFMPEG_TIMEOUT,
            )
            return withContext(Dispatchers.IO) { outputs(dir, prefix).map { Files.readAllBytes(it) } }
        } finally {
            // NonCancellable: сюда часто попадают через отмену или таймаут, а файлы удалить нужно всё равно.
            withContext(NonCancellable) {
                runCatching { tempFileHelper.deleteFiles(withContext(Dispatchers.IO) { outputs(dir, prefix) }) }
                    .onFailure { logger.warn(it) { "Failed to delete storyboard frames $prefix*" } }
            }
        }
    }

    internal fun command(
        file: Path,
        firstSeconds: Double,
        stepSeconds: Double,
        count: Int,
        tileWidth: Int,
        output: Path,
    ): List<String> =
        listOf(
            applicationProperties.ffmpegPath.toString(),
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-y",
            "-ss",
            decimal(firstSeconds, 3),
            "-i",
            file.toString(),
            "-an",
            "-vf",
            "fps=${decimal(1.0 / stepSeconds, 6)},scale=$tileWidth:-2",
            "-frames:v",
            count.toString(),
            "-q:v",
            "3",
            output.toString(),
        )

    private fun outputs(
        dir: Path,
        prefix: String,
    ): List<Path> =
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith(prefix) }.sorted().toList()
        }

    private fun decimal(
        value: Double,
        digits: Int,
    ): String = String.format(Locale.US, "%.${digits}f", value)

    companion object {
        val FFMPEG_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardFrameSampler*"`
Expected: PASS (интеграционный тест — PASS или SKIPPED без ffmpeg; для этой задачи нужен именно PASS: если ffmpeg на машине сборки нет, установить `sudo apt install ffmpeg` и повторить).

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardFrameSampler.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardFrameSamplerTest.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardFrameSamplerIntegrationTest.kt
git commit -m "feat(core): sample storyboard frames with ffmpeg"
```

---

### Task 9: `StoryboardComposer`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardComposer.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardComposerTest.kt`

**Interfaces:**
- Consumes: `GridLayout` (Task 5).
- Produces: `StoryboardComposer.Tile(image: ByteArray, label: String, marked: Boolean)`; `StoryboardComposer.compose(tiles: List<Tile>, layout: GridLayout): ByteArray` — JPEG; размер клетки берётся из первого кадра, холст `columns × rows` клеток, пустые ячейки чёрные.

- [ ] **Step 1: Write the failing test**

`StoryboardComposerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryboardComposerTest {
    private val composer = StoryboardComposer()

    private fun solid(
        color: Color,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun tile(
        color: Color,
        label: String = "0.0s",
        marked: Boolean = false,
        width: Int = 200,
        height: Int = 120,
    ) = StoryboardComposer.Tile(solid(color, width, height), label, marked)

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun BufferedImage.colorAt(
        x: Int,
        y: Int,
    ) = Color(getRGB(x, y))

    private fun assertAbout(
        expected: Color,
        actual: Color,
    ) {
        val distance = abs(expected.red - actual.red) + abs(expected.green - actual.green) + abs(expected.blue - actual.blue)
        assertTrue(distance < 60, "expected about $expected, got $actual")
    }

    @Test
    fun `the canvas is columns by rows of tiles`() {
        val image = decode(composer.compose(List(16) { tile(Color.GRAY) }, GridLayout(4, 4)))

        assertEquals(800, image.width)
        assertEquals(480, image.height)
    }

    @Test
    fun `tiles go left to right, top to bottom, and an unused cell stays black`() {
        val image = decode(composer.compose(listOf(tile(Color.RED), tile(Color.GREEN), tile(Color.BLUE)), GridLayout(2, 2)))

        assertAbout(Color.RED, image.colorAt(100, 60))
        assertAbout(Color.GREEN, image.colorAt(300, 60))
        assertAbout(Color.BLUE, image.colorAt(100, 180))
        assertAbout(Color.BLACK, image.colorAt(300, 180))
    }

    @Test
    fun `a portrait camera keeps its proportions`() {
        val image = decode(composer.compose(List(4) { tile(Color.GRAY, width = 90, height = 160) }, GridLayout(2, 2)))

        assertEquals(180, image.width)
        assertEquals(320, image.height)
    }

    @Test
    fun `every tile carries its label on a dark plate`() {
        val image = decode(composer.compose(listOf(tile(Color.GREEN, label = "5.0s")), GridLayout(1, 1)))

        val plate = image.colorAt(1, 1)
        assertTrue(plate.green < 150, "the label plate must darken the corner, got $plate")
        assertAbout(Color.GREEN, image.colorAt(150, 100))
    }

    @Test
    fun `a marked tile is drawn differently from an unmarked one`() {
        val plain = composer.compose(listOf(tile(Color.GRAY, label = "5.0s", marked = false)), GridLayout(1, 1))
        val marked = composer.compose(listOf(tile(Color.GRAY, label = "5.0s", marked = true)), GridLayout(1, 1))

        assertFalse(plain.contentEquals(marked))
    }

    @Test
    fun `more tiles than cells is a programming error`() {
        assertFailsWith<IllegalArgumentException> { composer.compose(List(5) { tile(Color.GRAY) }, GridLayout(2, 2)) }
    }

    @Test
    fun `a tile that is not an image is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            composer.compose(listOf(StoryboardComposer.Tile(byteArrayOf(1, 2, 3), "0.0s", false)), GridLayout(1, 1))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardComposerTest"`
Expected: FAIL компиляции — `Unresolved reference 'StoryboardComposer'`.

- [ ] **Step 3: Implement**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardComposer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max

/**
 * Собирает клетки раскадровки в одну сетку: слева направо и сверху вниз, подпись на тёмной подложке
 * в левом верхнем углу каждой клетки. Размер клетки — размер первого кадра; пустые ячейки последнего
 * ряда остаются чёрными.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class StoryboardComposer {
    /** Клетка: JPEG кадра, подпись и выделять ли её как ближайшую к детекции. */
    class Tile(
        val image: ByteArray,
        val label: String,
        val marked: Boolean,
    )

    fun compose(
        tiles: List<Tile>,
        layout: GridLayout,
    ): ByteArray {
        require(tiles.isNotEmpty()) { "tiles must not be empty" }
        require(tiles.size <= layout.columns * layout.rows) {
            "${tiles.size} tiles do not fit a ${layout.columns}x${layout.rows} grid"
        }
        val images =
            tiles.map { tile ->
                ImageIO.read(ByteArrayInputStream(tile.image))
                    ?: throw IllegalArgumentException("A storyboard tile is not a readable image")
            }
        val width = images.first().width
        val height = images.first().height
        val canvas = BufferedImage(width * layout.columns, height * layout.rows, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, max(MIN_FONT_SIZE, height / FONT_DIVISOR))
            images.forEachIndexed { index, image ->
                val x = (index % layout.columns) * width
                val y = (index / layout.columns) * height
                graphics.drawImage(image, x, y, width, height, null)
                drawLabel(graphics, tiles[index], x, y)
            }
        } finally {
            graphics.dispose()
        }
        return encodeJpeg(canvas)
    }

    private fun drawLabel(
        graphics: Graphics2D,
        tile: Tile,
        x: Int,
        y: Int,
    ) {
        val metrics = graphics.fontMetrics
        val padding = max(2, metrics.height / 4)
        graphics.color = LABEL_BACKGROUND
        graphics.fillRect(x, y, metrics.stringWidth(tile.label) + padding * 2, metrics.height + padding * 2)
        graphics.color = if (tile.marked) MARKED_TEXT else Color.WHITE
        graphics.drawString(tile.label, x + padding, y + padding + metrics.ascent)
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").takeIf { it.hasNext() }?.next()
                ?: throw IOException("No JPEG writer available")
        val output = ByteArrayOutputStream()
        try {
            // Memory-cache, а не ImageIO.createImageOutputStream: тот по умолчанию заводит временный файл.
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val params =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private companion object {
        const val MIN_FONT_SIZE = 12
        const val FONT_DIVISOR = 10
        const val JPEG_QUALITY = 0.85f
        val LABEL_BACKGROUND = Color(0, 0, 0, 153)
        val MARKED_TEXT = Color(255, 214, 0)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardComposerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardComposer.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardComposerTest.kt
git commit -m "feat(core): compose storyboard tiles into one grid"
```

---

### Task 10: `StoryboardProperties` и `StoryboardBuilder`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/StoryboardProperties.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilder.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt`, `modules/core/src/main/resources/application.yaml`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/StoryboardPropertiesBindingTest.kt`, `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilderTest.kt`
- Docs: `.claude/rules/configuration.md`, `docker/deploy/.env.example`

**Interfaces:**
- Consumes: `StoryboardPlanner` и типы (Task 5), `AdjacentSegmentFinder`, `Segment`, `AdjacentSegmentFinder.durationOf` (Task 7), `StoryboardFrameSampler.sample` (Task 8), `StoryboardComposer` (Task 9), `DescriptionRequest.Storyboard`/`DetectionMark` (Task 4), `FrameData.offsetSeconds` (Task 1), `VideoProbe`.
- Produces:
  - `StoryboardProperties(enabled: Boolean = true, before: Duration = 5s, after: Duration = 5s, tiles: Int = 16, nextSegmentWait: Duration = 30s)`, prefix `application.ai.description.storyboard`
  - `data class BuiltStoryboard(storyboard: DescriptionRequest.Storyboard, zeroSeconds: Double)`
  - `StoryboardBuilder.build(recording: RecordingDto, detectionFrames: List<FrameData>): BuiltStoryboard?` — не бросает ничего, кроме `CancellationException`
  - `internal fun StoryboardBuilder.nextDeadline(recording: RecordingDto, currentDuration: Double, startedAt: Instant): Instant`

- [ ] **Step 1: Write the failing tests**

`StoryboardPropertiesBindingTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class StoryboardPropertiesBindingTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `with nothing set the storyboard is on with the documented defaults`() {
        val props = bind()

        assertThat(props).isEqualTo(
            StoryboardProperties(
                enabled = true,
                before = Duration.ofSeconds(5),
                after = Duration.ofSeconds(5),
                tiles = 16,
                nextSegmentWait = Duration.ofSeconds(30),
            ),
        )
        assertThat(validator.validate(props)).isEmpty()
    }

    @Test
    fun `every knob follows its environment variable`() {
        val props =
            bind(
                env =
                    mapOf(
                        "APP_AI_DESCRIPTION_STORYBOARD_ENABLED" to "false",
                        "APP_AI_DESCRIPTION_STORYBOARD_BEFORE" to "3s",
                        "APP_AI_DESCRIPTION_STORYBOARD_AFTER" to "8s",
                        "APP_AI_DESCRIPTION_STORYBOARD_TILES" to "9",
                        "APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT" to "60s",
                    ),
            )

        assertThat(props).isEqualTo(
            StoryboardProperties(
                enabled = false,
                before = Duration.ofSeconds(3),
                after = Duration.ofSeconds(8),
                tiles = 9,
                nextSegmentWait = Duration.ofSeconds(60),
            ),
        )
    }

    @Test
    fun `a tile count outside 4 to 25 is rejected by validation`() {
        val violations = validator.validate(bind(env = mapOf("APP_AI_DESCRIPTION_STORYBOARD_TILES" to "26")))

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("tiles")
    }

    @Test
    fun `a margin above ten seconds fails the binding`() {
        assertThatThrownBy { bind(env = mapOf("APP_AI_DESCRIPTION_STORYBOARD_BEFORE" to "11s")) }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a wait above two minutes fails the binding`() {
        assertThatThrownBy { bind(env = mapOf("APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT" to "121s")) }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bind(env: Map<String, Any> = emptyMap()): StoryboardProperties =
        ProductionYamlBinder.bind("application.ai.description.storyboard", StoryboardProperties::class.java, env = env)
}
```

`StoryboardBuilderTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties
import ru.zinin.frigate.analyzer.core.video.VideoInfo
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoryboardBuilderTest {
    private val probe = mockk<VideoProbe>()
    private val finder = mockk<AdjacentSegmentFinder>()
    private val sampler = mockk<StoryboardFrameSampler>()
    private val now = Instant.parse("2026-09-23T10:01:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val recordStart = Instant.parse("2026-09-23T10:00:00Z")
    private val currentPath = Path.of("/rec/cam1/00.00.mp4")
    private val previousPath = Path.of("/rec/cam1/59.50.mp4")
    private val nextPath = Path.of("/rec/cam1/00.12.mp4")
    private val tileJpeg: ByteArray =
        BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB)
            .also { image ->
                val graphics = image.createGraphics()
                graphics.color = Color.GRAY
                graphics.fillRect(0, 0, 64, 36)
                graphics.dispose()
            }.let { image -> ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray() }

    init {
        coEvery { probe.probe(currentPath) } returns info(12.0)
        coEvery { sampler.sample(any(), any(), any(), any(), any()) } answers { List(arg<Int>(3)) { tileJpeg } }
    }

    private fun builder(properties: StoryboardProperties = StoryboardProperties()) =
        StoryboardBuilder(properties, probe, finder, sampler, StoryboardComposer(), clock)

    private fun info(duration: Double) = VideoInfo(durationSeconds = duration, width = 2880, height = 1620, fps = 20.0, hasAudio = false)

    private fun recording(
        path: Path = currentPath,
        start: Instant = recordStart,
        fileCreated: Instant = recordStart.plusSeconds(12),
    ) = RecordingDto(
        id = UUID.randomUUID(),
        creationTimestamp = fileCreated,
        filePath = path.toString(),
        fileCreationTimestamp = fileCreated,
        camId = "cam1",
        recordDate = LocalDate.of(2026, 9, 23),
        recordTime = LocalTime.of(10, 0),
        recordTimestamp = start,
        startProcessingTimestamp = null,
        processTimestamp = null,
        processAttempts = 0,
        detectionsCount = 1,
        analyzeTime = 0,
        analyzedFramesCount = 5,
        errorMessage = null,
    )

    private fun segment(
        path: Path,
        start: Instant,
        duration: Double,
    ) = Segment(recording(path = path, start = start), path, info(duration))

    private fun detection(
        className: String,
        confidence: Double,
    ) = Detection(2, className, confidence, BBox(0.0, 0.0, 1.0, 1.0))

    private fun frame(
        index: Int,
        offset: Double?,
        detections: List<Detection> = listOf(detection("car", 0.9)),
    ) = FrameData(
        recordId = UUID.randomUUID(),
        frameIndex = index,
        frameBytes = ByteArray(0),
        detectResponse = DetectResponse(detections, 0, ImageSize(2880, 1620), "m"),
        offsetSeconds = offset,
    )

    private fun MockKMatcherScope.near(value: Double) = match<Double> { abs(it - value) < 1e-9 }

    @Test
    fun `a detection in the middle stays inside the recording`() =
        runTest {
            val built = assertNotNull(builder().build(recording(), listOf(frame(2, 6.0))))

            assertEquals(1.0, built.zeroSeconds)
            val storyboard = built.storyboard
            assertEquals(16, storyboard.tiles)
            assertEquals(0.66, storyboard.stepSeconds, 1e-9)
            assertEquals(10.0, storyboard.durationSeconds, 1e-9)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, 5.0)), storyboard.detections)
            assertFalse(storyboard.missingBefore)
            assertFalse(storyboard.missingAfter)
            assertNotNull(ImageIO.read(ByteArrayInputStream(storyboard.image)))
            coVerify(exactly = 1) { sampler.sample(currentPath, near(1.0), near(0.66), 16, 640) }
            coVerify(exactly = 0) { finder.previous(any()) }
            coVerify(exactly = 0) { finder.next(any(), any(), any()) }
        }

    @Test
    fun `a detection near the start takes the tail of the previous segment`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)

            val built = assertNotNull(builder().build(recording(), listOf(frame(0, 1.0))))

            assertEquals(-4.0, built.zeroSeconds)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, 5.0)), built.storyboard.detections)
            assertFalse(built.storyboard.missingBefore)
            coVerify(exactly = 1) { sampler.sample(previousPath, near(6.0), near(0.66), 7, 640) }
            coVerify(exactly = 1) { sampler.sample(currentPath, near(0.62), near(0.66), 9, 640) }
        }

    @Test
    fun `a detection near the end takes the head of the next segment`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns segment(nextPath, recordStart.plusSeconds(12), 10.0)

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertFalse(built.storyboard.missingAfter)
            assertEquals(10.0, built.storyboard.durationSeconds, 1e-9)
            coVerify(exactly = 1) { sampler.sample(currentPath, near(5.0), near(0.66), 11, 640) }
            coVerify(exactly = 1) { sampler.sample(nextPath, near(0.26), near(0.66), 5, 640) }
        }

    @Test
    fun `a next segment that never came leaves the end of the footage open`() =
        runTest {
            coEvery { finder.next(any(), any(), any()) } returns null

            val built = assertNotNull(builder().build(recording(), listOf(frame(4, 10.0))))

            assertTrue(built.storyboard.missingAfter)
            assertEquals(7.0, built.storyboard.durationSeconds, 1e-9)
            assertEquals(14, built.storyboard.tiles)
            coVerify(exactly = 0) { sampler.sample(nextPath, any(), any(), any(), any()) }
        }

    @Test
    fun `the builder hands the finder its deadline`() =
        runTest {
            val deadline = slot<Instant>()
            coEvery { finder.next(any(), any(), capture(deadline)) } returns null

            builder().build(recording(fileCreated = Instant.parse("2026-09-23T10:00:12Z")), listOf(frame(4, 10.0)))

            assertEquals(Instant.parse("2026-09-23T10:00:54Z"), deadline.captured)
        }

    /** Файл появился в 10:00:12, следующий ожидался в 10:00:24, задача стартовала в 10:01:00. */
    @Test
    fun `the next-segment deadline counts from the expected appearance once that has passed`() {
        val deadline = builder().nextDeadline(recording(fileCreated = Instant.parse("2026-09-23T10:00:12Z")), 12.0, now)

        assertEquals(Instant.parse("2026-09-23T10:00:54Z"), deadline)
    }

    @Test
    fun `the next-segment deadline counts from the start of the job when that comes first`() {
        val deadline = builder().nextDeadline(recording(fileCreated = now.minusSeconds(2)), 12.0, now)

        assertEquals(now.plusSeconds(30), deadline)
    }

    @Test
    fun `the next-segment deadline of a backlog recording is already past`() {
        val deadline = builder().nextDeadline(recording(fileCreated = now.minus(Duration.ofHours(3))), 12.0, now)

        assertTrue(deadline.isBefore(now))
    }

    @Test
    fun `frames without times cover the whole recording`() =
        runTest {
            val built = assertNotNull(builder().build(recording(), listOf(frame(1, null))))

            assertEquals(0.0, built.zeroSeconds)
            assertEquals(12.0, built.storyboard.durationSeconds, 1e-9)
            assertEquals(listOf(DescriptionRequest.DetectionMark("car", 0.9, null)), built.storyboard.detections)
            coVerify(exactly = 0) { finder.previous(any()) }
            coVerify(exactly = 0) { finder.next(any(), any(), any()) }
        }

    @Test
    fun `detections are listed per frame and class with the best confidence`() =
        runTest {
            val frame = frame(2, 6.0, detections = listOf(detection("car", 0.6), detection("car", 0.8), detection("person", 0.7)))

            val built = assertNotNull(builder().build(recording(), listOf(frame)))

            assertEquals(
                listOf(DescriptionRequest.DetectionMark("car", 0.8, 5.0), DescriptionRequest.DetectionMark("person", 0.7, 5.0)),
                built.storyboard.detections,
            )
        }

    @Test
    fun `a recording ffprobe cannot read gives no storyboard`() =
        runTest {
            coEvery { probe.probe(currentPath) } throws RuntimeException("no video stream")

            assertNull(builder().build(recording(), listOf(frame(2, 6.0))))
            coVerify(exactly = 0) { sampler.sample(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a failure on the current recording gives no storyboard`() =
        runTest {
            coEvery { sampler.sample(currentPath, any(), any(), any(), any()) } throws RuntimeException("ffmpeg exited with 1")

            assertNull(builder().build(recording(), listOf(frame(2, 6.0))))
        }

    @Test
    fun `a failure on a neighbour leaves only its tiles out`() =
        runTest {
            coEvery { finder.previous(any()) } returns segment(previousPath, recordStart.minusSeconds(10), 10.0)
            coEvery { sampler.sample(previousPath, any(), any(), any(), any()) } throws RuntimeException("ffmpeg exited with 1")

            val built = assertNotNull(builder().build(recording(), listOf(frame(0, 1.0))))

            assertTrue(built.storyboard.missingBefore)
            assertEquals(9, built.storyboard.tiles)
            assertEquals(-4.0, built.zeroSeconds)
        }

    @Test
    fun `too little footage gives no storyboard`() =
        runTest {
            val tight = StoryboardProperties(before = Duration.ZERO, after = Duration.ZERO)

            assertNull(builder(tight).build(recording(), listOf(frame(2, 6.0))))
            coVerify(exactly = 0) { sampler.sample(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cancellation is not swallowed`() =
        runTest {
            coEvery { probe.probe(currentPath) } throws CancellationException("shutdown")

            assertFailsWith<CancellationException> { builder().build(recording(), listOf(frame(2, 6.0))) }
        }

    /** Держи ожидание семафор — две записи, ждущие следующий сегмент, остановили бы третью. */
    @Test
    fun `waiting for a next segment does not hold a sampling permit`() =
        runTest {
            val gate = CompletableDeferred<Segment?>()
            coEvery { finder.next(any(), any(), any()) } coAnswers { gate.await() }
            val builder = builder()

            val waiting = List(2) { async { builder.build(recording(), listOf(frame(4, 10.0))) } }
            runCurrent()
            val free = builder.build(recording(), listOf(frame(2, 6.0)))

            assertNotNull(free)
            gate.complete(null)
            waiting.awaitAll().forEach { assertNotNull(it) }
        }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.config.properties.StoryboardPropertiesBindingTest" --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardBuilderTest"`
Expected: FAIL компиляции — `Unresolved reference 'StoryboardProperties'`, `'StoryboardBuilder'`.

- [ ] **Step 3: Add the properties and their yaml**

`StoryboardProperties.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Раскадровка для AI-описаний: сетка кадров вокруг детекции, при необходимости из соседних
 * сегментов. Модуль ai-description про неё не знает — настройки читает `core`.
 */
@ConfigurationProperties(prefix = "application.ai.description.storyboard")
@Validated
data class StoryboardProperties(
    /** `false` — описание по одним полноразмерным кадрам, как до раскадровки (с подписями времени). */
    val enabled: Boolean = true,
    /** Сколько захватить до первой детекции. Не больше 10 с: соседний сегмент берётся только один. */
    val before: Duration = Duration.ofSeconds(5),
    /** Сколько захватить после последней детекции, тот же предел. */
    val after: Duration = Duration.ofSeconds(5),
    @field:Min(4)
    @field:Max(25)
    val tiles: Int = 16,
    /** Сколько ждать следующий сегмент сверх ожидаемого момента его появления. */
    val nextSegmentWait: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!before.isNegative && before <= MAX_MARGIN) { "storyboard.before must be within 0..$MAX_MARGIN, was $before" }
        require(!after.isNegative && after <= MAX_MARGIN) { "storyboard.after must be within 0..$MAX_MARGIN, was $after" }
        require(!nextSegmentWait.isNegative && nextSegmentWait <= MAX_WAIT) {
            "storyboard.next-segment-wait must be within 0..$MAX_WAIT, was $nextSegmentWait"
        }
    }

    companion object {
        val MAX_MARGIN: Duration = Duration.ofSeconds(10)
        val MAX_WAIT: Duration = Duration.ofSeconds(120)
    }
}
```

`FrigateAnalyzerApplication.kt`: импорт `ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties` и `StoryboardProperties::class,` в список `@EnableConfigurationProperties` (после `SignalLossProperties::class,`).

`modules/core/src/main/resources/application.yaml`: внутри `application.ai.description`, после блока `common:` (после его `rate-limit:`) и перед `claude:`, на отступе `common:`:

```yaml
      # Раскадровка для описаний: сетка кадров вокруг детекции, при необходимости из соседних
      # сегментов. Подробности — раздел Storyboard в .claude/rules/ai-description.md.
      storyboard:
        enabled: ${APP_AI_DESCRIPTION_STORYBOARD_ENABLED:true}
        before: ${APP_AI_DESCRIPTION_STORYBOARD_BEFORE:5s}
        after: ${APP_AI_DESCRIPTION_STORYBOARD_AFTER:5s}
        tiles: ${APP_AI_DESCRIPTION_STORYBOARD_TILES:16}
        next-segment-wait: ${APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT:30s}
```

`DescriptionProperties` соседний ключ `storyboard` не видит (неизвестные поля игнорируются), `PackagedApplicationYamlTest.every configuration properties class binds from the packaged config` подхватит новый класс сам.

- [ ] **Step 4: Implement the builder**

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilder.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/** Готовая раскадровка и где у неё ноль: секунды от начала записи (с предыдущим сегментом — меньше нуля). */
data class BuiltStoryboard(
    val storyboard: DescriptionRequest.Storyboard,
    val zeroSeconds: Double,
)

/**
 * Строит раскадровку для описания: окно вокруг детекций, соседние сегменты, нарезка ffmpeg-ом, сетка.
 * Любой сбой, кроме отмены, — WARN и `null`: описание уйдёт по одним кадрам.
 *
 * Семафор ограничивает только ffprobe и ffmpeg. Ожидание следующего сегмента идёт без него, иначе две
 * записи, ждущие по 30 с, задержали бы третью, которой ждать нечего.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class StoryboardBuilder(
    private val properties: StoryboardProperties,
    private val videoProbe: VideoProbe,
    private val finder: AdjacentSegmentFinder,
    private val sampler: StoryboardFrameSampler,
    private val composer: StoryboardComposer,
    private val clock: Clock,
) {
    private val permits = Semaphore(MAX_CONCURRENT_BUILDS)

    /** [detectionFrames] — кадры записи с детекциями; байты не нужны, только время и детекции. */
    suspend fun build(
        recording: RecordingDto,
        detectionFrames: List<FrameData>,
    ): BuiltStoryboard? {
        val startedAt = clock.instant()
        val started = TimeSource.Monotonic.markNow()
        return try {
            assemble(recording, detectionFrames, startedAt, started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Storyboard for ${recording.id} failed; describing from the frames alone" }
            null
        }
    }

    /**
     * Срок ожидания следующего сегмента: от более раннего из двух моментов — начала задачи и ожидаемого
     * появления следующего файла (текущий появился в `fileCreationTimestamp`, следующий придёт через
     * длительность сегмента) — плюс `next-segment-wait`. Пайплайн берёт запись через 30 с после
     * появления файла, поэтому обычно раньше наступает ожидаемое появление; у записи из очереди оба
     * момента в прошлом, и поиск делает ровно одну проверку.
     */
    internal fun nextDeadline(
        recording: RecordingDto,
        currentDuration: Double,
        startedAt: Instant,
    ): Instant {
        val expected = recording.fileCreationTimestamp.plus(AdjacentSegmentFinder.durationOf(currentDuration))
        return minOf(startedAt, expected).plus(properties.nextSegmentWait)
    }

    private suspend fun assemble(
        recording: RecordingDto,
        detectionFrames: List<FrameData>,
        startedAt: Instant,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): BuiltStoryboard? {
        val currentPath = Path.of(recording.filePath)
        val duration = permits.withPermit { videoProbe.probe(currentPath) }.durationSeconds
        val detectionSeconds = detectionFrames.mapNotNull { it.offsetSeconds }
        val window =
            StoryboardPlanner.window(
                detectionSeconds,
                properties.before.secondsAsDouble(),
                properties.after.secondsAsDouble(),
                duration,
            )

        val previousWanted = StoryboardPlanner.needsPrevious(window)
        val nextWanted = StoryboardPlanner.needsNext(window, duration)
        val previous = if (previousWanted) finder.previous(recording) else null
        val waitStarted = TimeSource.Monotonic.markNow()
        val next = if (nextWanted) finder.next(recording, duration, nextDeadline(recording, duration, startedAt)) else null
        val waited = waitStarted.elapsedNow()

        val footage = StoryboardPlanner.footage(window, duration, previous?.info?.durationSeconds, next?.info?.durationSeconds)
        val grid = StoryboardPlanner.tileGrid(footage.range, properties.tiles)
        if (grid == null) {
            logger.warn {
                "Storyboard for ${recording.id}: ${fmt(footage.range.length)} s of footage is too little; " +
                    "describing from the frames alone"
            }
            return null
        }
        val layout = StoryboardPlanner.layout(grid.moments.size)
        val tileWidth = StoryboardPlanner.tileWidth(layout.columns)
        val files = mapOf(SegmentRole.PREVIOUS to previous?.path, SegmentRole.CURRENT to currentPath, SegmentRole.NEXT to next?.path)

        var missingBefore = footage.missingBefore
        var missingAfter = footage.missingAfter
        val sampled = mutableListOf<Pair<Double, ByteArray>>()
        val groups =
            grid.moments
                .map { moment -> moment to StoryboardPlanner.locate(moment, duration, previous?.info?.durationSeconds) }
                .groupBy { (_, located) -> located.role }
        for ((role, group) in groups) {
            val file = requireNotNull(files[role]) { "no file for the $role segment" }
            val images =
                try {
                    permits.withPermit {
                        sampler.sample(file, group.first().second.localSeconds, grid.stepSeconds, group.size, tileWidth)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Без текущей записи раскадровки нет; без соседа есть, только короче.
                    if (role == SegmentRole.CURRENT) throw e
                    logger.warn(e) { "Storyboard for ${recording.id}: cannot sample the ${role.name.lowercase()} segment; leaving it out" }
                    if (role == SegmentRole.PREVIOUS) missingBefore = true else missingAfter = true
                    emptyList<ByteArray>()
                }
            group.zip(images).forEach { (entry, image) -> sampled += entry.first to image }
        }
        if (sampled.size < StoryboardPlanner.MIN_TILES) {
            logger.warn {
                "Storyboard for ${recording.id}: ffmpeg returned ${sampled.size} frames, too few; describing from the frames alone"
            }
            return null
        }

        val zero = footage.range.start
        val marked = StoryboardPlanner.markedTiles(sampled.map { it.first }, detectionSeconds)
        val tiles =
            sampled.mapIndexed { index, (moment, image) ->
                StoryboardComposer.Tile(image, StoryboardPlanner.tileLabel(moment - zero, index in marked), index in marked)
            }
        val image = withContext(Dispatchers.Default) { composer.compose(tiles, layout) }
        val storyboard =
            DescriptionRequest.Storyboard(
                image = image,
                tiles = tiles.size,
                stepSeconds = grid.stepSeconds,
                durationSeconds = footage.range.length,
                detections = detectionMarks(detectionFrames, zero),
                missingBefore = missingBefore,
                missingAfter = missingAfter,
            )
        logger.info {
            val parts = listOfNotNull("prev".takeIf { previous != null }, "current", "next".takeIf { next != null })
            val missing =
                buildString {
                    if (previousWanted && previous == null) append(", prev: missing")
                    if (nextWanted) {
                        append(if (next != null) ", waited ${fmt(waited)} s for the next segment" else ", next: missing after ${fmt(waited)} s")
                    }
                }
            "Storyboard for ${recording.id}: footage ${fmt(footage.range.start)}..${fmt(footage.range.end)} s of the recording " +
                "(${parts.joinToString("+")}), ${tiles.size} tiles ${fmt(grid.stepSeconds)} s apart$missing, " +
                "built in ${fmt(started.elapsedNow())} s"
        }
        return BuiltStoryboard(storyboard, zero)
    }

    /** По кадру: каждый класс с лучшим confidence этого класса на кадре; время — от нуля раскадровки. */
    private fun detectionMarks(
        frames: List<FrameData>,
        zero: Double,
    ): List<DescriptionRequest.DetectionMark> =
        frames.sortedBy { it.frameIndex }.flatMap { frame ->
            frame.detectResponse
                ?.detections
                .orEmpty()
                .groupBy { it.className }
                .map { (className, detections) ->
                    DescriptionRequest.DetectionMark(className, detections.maxOf { it.confidence }, frame.offsetSeconds?.minus(zero))
                }
        }

    /** Не `toSeconds()`: член `Duration.toSeconds(): Long` выиграл бы у расширения с тем же именем. */
    private fun java.time.Duration.secondsAsDouble(): Double = toMillis() / 1000.0

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun fmt(value: kotlin.time.Duration): String = fmt(value.inWholeMilliseconds / 1000.0)

    private companion object {
        const val MAX_CONCURRENT_BUILDS = 2
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.config.properties.StoryboardPropertiesBindingTest" --tests "ru.zinin.frigate.analyzer.core.storyboard.StoryboardBuilderTest" --tests "ru.zinin.frigate.analyzer.core.config.PackagedApplicationYamlTest"`
Expected: PASS.

- [ ] **Step 6: Document it**

`.claude/rules/configuration.md`, перед заголовком `### Grok provider (any preset with \`provider: grok\`, or the legacy \`APP_AI_DESCRIPTION_PROVIDER=grok\`)`:

```markdown
### Storyboard (`application.ai.description.storyboard.*`)

Bound by `StoryboardProperties` in `core`; see "Storyboard" in `ai-description.md`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_AI_DESCRIPTION_STORYBOARD_ENABLED` | true | Give the model a timed grid of frames around the detection. `false` describes from the full-resolution frames alone (still captioned with their times). |
| `APP_AI_DESCRIPTION_STORYBOARD_BEFORE` | 5s | Footage before the first detection, `0s..10s`: at most one neighbouring segment is taken per side, and a segment is ~10 s. |
| `APP_AI_DESCRIPTION_STORYBOARD_AFTER` | 5s | Footage after the last detection, same range. |
| `APP_AI_DESCRIPTION_STORYBOARD_TILES` | 16 | Most tiles in the grid, `4..25`; tiles are never closer than 0.5 s. |
| `APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT` | 30s | How long past its expected appearance to wait for the next segment, `0s..120s`. The pipeline takes a recording 30 s after its file appeared, so the next segment is usually there already; the INFO line says `next: missing after N s` when it was not. |
```

`docker/deploy/.env.example`, после строки `# APP_AI_DESCRIPTION_RATE_LIMIT_MAX=30`:

```
# Timed storyboard of the event for the description model: a grid of up to 16 frames around the
# detection, taken from the neighbouring segments too when the event crosses a recording boundary.
# APP_AI_DESCRIPTION_STORYBOARD_ENABLED=true
# APP_AI_DESCRIPTION_STORYBOARD_BEFORE=5s
# APP_AI_DESCRIPTION_STORYBOARD_AFTER=5s
# APP_AI_DESCRIPTION_STORYBOARD_TILES=16
# How long to wait for the next segment past its expected appearance (0s..120s).
# APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT=30s
```

- [ ] **Step 7: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/StoryboardProperties.kt \
  modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilder.kt \
  modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt \
  modules/core/src/main/resources/application.yaml \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/StoryboardPropertiesBindingTest.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilderTest.kt \
  .claude/rules/configuration.md docker/deploy/.env.example
git commit -m "feat(core): build the storyboard with a fail-open fallback"
```

---

### Task 11: Фасад отдаёт раскадровку модели

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`
- Modify: `modules/core/src/main/resources/application.yaml` (`max-frames`)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`, `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/PackagedApplicationYamlTest.kt`
- Docs: `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `docker/deploy/.env.example`, `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `StoryboardBuilder.build`, `BuiltStoryboard` (Task 10), `StoryboardProperties.enabled`, `DescriptionRequest.storyboard`, `FrameImage.offsetSeconds`.
- Produces: конструктор `RecordingProcessingFacade(…, judgeProvider, storyboardBuilderProvider: ObjectProvider<StoryboardBuilder>, storyboardProperties: StoryboardProperties)`.

- [ ] **Step 1: Write the failing tests**

В `RecordingProcessingFacadeTest.kt`:
- импорты: `ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties`, `ru.zinin.frigate.analyzer.core.storyboard.BuiltStoryboard`, `ru.zinin.frigate.analyzer.core.storyboard.StoryboardBuilder`;
- `frameWithDetection(idx, confidence)` получает третий параметр `offsetSeconds: Double? = null` и передаёт его в `FrameData(…, offsetSeconds = offsetSeconds)`;
- хелпер `facade(...)` получает параметры `storyboardBuilder: StoryboardBuilder? = null` и `storyboardEnabled: Boolean = true`; внутри:

```kotlin
        val storyboardProvider = mockk<ObjectProvider<StoryboardBuilder>>()
        every { storyboardProvider.getIfAvailable() } returns storyboardBuilder
```

и в конструктор фасада — `storyboardBuilderProvider = storyboardProvider, storyboardProperties = StoryboardProperties(enabled = storyboardEnabled),`.

Новые тесты:

```kotlin
    private val storyboard =
        DescriptionRequest.Storyboard(
            image = byteArrayOf(7),
            tiles = 16,
            stepSeconds = 1.0,
            durationSeconds = 15.0,
            detections = emptyList(),
            missingBefore = false,
            missingAfter = false,
        )

    @Test
    fun `the storyboard travels with the request and frame times count from its zero`() =
        runTest {
            val builder = mockk<StoryboardBuilder>()
            coEvery { builder.build(any(), any()) } returns BuiltStoryboard(storyboard, zeroSeconds = -3.0)
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } returns DescriptionResult("s", "d")

            val (f, req) =
                facade(agent, framesForRequest = listOf(frameWithDetection(0, offsetSeconds = 2.0)), storyboardBuilder = builder)
            captureSupplierDuring { f.processAndNotify(req) }!!.invoke().await()

            assertEquals(storyboard, captured.captured.storyboard)
            assertEquals(listOf(5.0), captured.captured.frames.map { it.offsetSeconds })
        }

    @Test
    fun `without a storyboard the frames keep the recording's time`() =
        runTest {
            val builder = mockk<StoryboardBuilder>()
            coEvery { builder.build(any(), any()) } returns null
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } returns DescriptionResult("s", "d")

            val (f, req) =
                facade(agent, framesForRequest = listOf(frameWithDetection(0, offsetSeconds = 2.0)), storyboardBuilder = builder)
            captureSupplierDuring { f.processAndNotify(req) }!!.invoke().await()

            assertNull(captured.captured.storyboard)
            assertEquals(listOf(2.0), captured.captured.frames.map { it.offsetSeconds })
        }

    /** Раскадровка может ждать следующий сегмент; выключение за это время тоже должно подействовать. */
    @Test
    fun `the storyboard is built before the switch is read again`() =
        runTest {
            val builder = mockk<StoryboardBuilder>()
            coEvery { builder.build(any(), any()) } returns null
            val agent = mockk<DescriptionAgent>()

            val (f, req) = facade(agent, runtimeSettings = runtimeSettings(true, false), storyboardBuilder = builder)
            val outcome = captureSupplierDuring { f.processAndNotify(req) }!!.invoke().await()

            assertTrue(outcome.isFailure)
            coVerify(exactly = 1) { builder.build(any(), any()) }
            coVerify(exactly = 0) { agent.describe(any()) }
        }

    @Test
    fun `a switched-off storyboard never reaches the builder`() =
        runTest {
            val builder = mockk<StoryboardBuilder>()
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } returns DescriptionResult("s", "d")

            val (f, req) = facade(agent, storyboardBuilder = builder, storyboardEnabled = false)
            captureSupplierDuring { f.processAndNotify(req) }!!.invoke().await()

            coVerify(exactly = 0) { builder.build(any(), any()) }
        }

    @Test
    fun `the builder gets only frames with detections, without their bytes`() =
        runTest {
            val builder = mockk<StoryboardBuilder>()
            val frames = slot<List<FrameData>>()
            coEvery { builder.build(recording, capture(frames)) } returns null
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } returns DescriptionResult("s", "d")
            val mix = listOf(FrameData(recordingId, 0, ByteArray(5)), frameWithDetection(1, offsetSeconds = 4.0))

            val (f, req) = facade(agent, framesForRequest = mix, storyboardBuilder = builder)
            captureSupplierDuring { f.processAndNotify(req) }!!.invoke().await()

            assertEquals(listOf(1), frames.captured.map { it.frameIndex })
            assertTrue(frames.captured.single().frameBytes.isEmpty())
            assertEquals(4.0, frames.captured.single().offsetSeconds)
        }
```

В `PackagedApplicationYamlTest.kt`, тест `description section binds with no environment overrides`, добавить `assertEquals(4, properties.common.maxFrames)`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.facade.RecordingProcessingFacadeTest" --tests "ru.zinin.frigate.analyzer.core.config.PackagedApplicationYamlTest"`
Expected: FAIL компиляции — `No parameter with name 'storyboardBuilderProvider'`.

- [ ] **Step 3: Implement the facade**

Импорты: `ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties`, `ru.zinin.frigate.analyzer.core.storyboard.StoryboardBuilder`, `ru.zinin.frigate.analyzer.model.dto.RecordingDto`.

Конструктор — два последних параметра:

```kotlin
    // ObjectProvider: бинов раскадровки нет при application.ai.description.enabled=false.
    private val storyboardBuilderProvider: ObjectProvider<StoryboardBuilder>,
    private val storyboardProperties: StoryboardProperties,
```

В `processAndNotify`: `buildDescriptionSupplier(recordingId, request)` → `buildDescriptionSupplier(recording, request)`.

`buildDescriptionSupplier` целиком:

```kotlin
    /**
     * Returns a supplier that lazily kicks off a describe-job. Returns null when the agent is
     * absent (feature disabled / provider mismatch), when the runtime switch is off, OR when no
     * frames with detections are available.
     * When non-null, the supplier returns a non-null Deferred when invoked — the rate limiter
     * has already consumed a slot at the call site, and a null return would silently waste it.
     * Inside the job the storyboard is built first (it may wait for the next segment), then the
     * runtime switch is read again, then the model is called.
     */
    private suspend fun buildDescriptionSupplier(
        recording: RecordingDto,
        request: SaveProcessingResultRequest,
    ): (() -> Deferred<Result<DescriptionResult>>)? {
        val recordingId = recording.id
        val agent = descriptionAgentProvider.getIfAvailable() ?: return null
        // Рантайм-выключатель: то же поведение, что «агента нет» — уведомление уходит с
        // DescriptionState.Absent, плейсхолдеров нет, слот rate limiter не тратится, потому что
        // TelegramNotificationServiceImpl отсекает null-supplier ДО tryAcquire().
        if (!descriptionsEnabled(recordingId)) {
            logger.debug { "AI descriptions switched off at runtime; skipping describe-job for $recordingId" }
            return null
        }

        val common = descriptionProperties.common
        // Полноразмерные кадры — то же ранжирование, что у коллажа (confidence, затем число детекций),
        // и не больше видимого пользователю набора (`selectTopFrames`). Раскадровка намеренно шире:
        // она показывает модели и кадры без детекций, и соседние сегменты.
        val cap = minOf(common.maxFrames, frameVisualizationService.maxFrames)
        val keyFrames =
            frameVisualizationService
                .selectTopFrames(request.frames, cap)
                .sortedBy { it.frameIndex } // chronological order in the prompt, post-selection

        if (keyFrames.isEmpty()) {
            logger.debug { "No frames with detections for recording $recordingId; skipping describe-job" }
            return null
        }

        // Раскадровке нужны только время и детекции; байты кадров в замыкании не держим.
        val detectionFrames =
            request.frames
                .filter { it.detectResponse?.detections?.isNotEmpty() == true }
                .map { it.copy(frameBytes = ByteArray(0)) }
        val storyboardBuilder = if (storyboardProperties.enabled) storyboardBuilderProvider.getIfAvailable() else null

        return {
            descriptionScope.async {
                // Раскадровка до выключателя: он остаётся вплотную к вызову модели, а раскадровка может
                // ждать следующий сегмент — выключение за это время тоже должно подействовать.
                val built = storyboardBuilder?.build(recording, detectionFrames)
                // Вторая проверка выключателя, вплотную к вызову модели: между сборкой supplier-а и
                // его вызовом лежат фильтрация получателей и rate limiter, поэтому одной проверки
                // хватало бы лишь на «подействует со следующей записи», а кнопку жмут тогда, когда
                // что-то идёт не так прямо сейчас. Цена — поиск в процессном кэше AppSettingsService
                // на пути, который вот-вот потратит секунды и деньги на вызов модели.
                if (!descriptionsEnabled(recordingId)) {
                    logger.debug { "AI descriptions switched off at runtime; skipping describe-call for $recordingId" }
                    // Не отмена и не null: слот лимитера уже потрачен, сообщение уже ушло с
                    // плейсхолдером, и заменить его умеет только завершившийся Deferred —
                    // Result.failure даёт DescriptionState.Failed, отменённый await() не дал бы
                    // ничего, оставив плейсхолдер навсегда.
                    return@async Result.failure(IllegalStateException("AI descriptions are switched off at runtime"))
                }
                val zero = built?.zeroSeconds ?: 0.0
                val descriptionRequest =
                    DescriptionRequest(
                        recordingId = recordingId,
                        frames =
                            keyFrames.map {
                                DescriptionRequest.FrameImage(it.frameIndex, it.frameBytes, it.offsetSeconds?.minus(zero))
                            },
                        language = common.language,
                        shortMaxLength = common.shortMaxLength,
                        detailedMaxLength = common.detailedMaxLength,
                        storyboard = built?.storyboard,
                    )
                try {
                    Result.success(agent.describe(descriptionRequest))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Without this, the exception is swallowed into Result.failure and the user
                    // sees only the localized "Описание недоступно" fallback in Telegram with
                    // nothing in the logs explaining why — making AI failures invisible to ops.
                    logger.warn(e) { "AI description failed for recording $recordingId; users will see fallback" }
                    Result.failure(e)
                }
            }
        }
    }
```

`modules/core/src/main/resources/application.yaml`: `max-frames: ${APP_AI_DESCRIPTION_MAX_FRAMES:10}` → `max-frames: ${APP_AI_DESCRIPTION_MAX_FRAMES:4}`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-core:test`
Expected: PASS (весь модуль: фасад, судья, интеграционные тесты с Docker).

- [ ] **Step 5: Document it**

`.claude/rules/ai-description.md`:
- во frontmatter `paths:` добавить `,**/storyboard/**` в конец списка;
- перед заголовком `## Integration with Telegram` вставить раздел:

```markdown
## Storyboard

Besides the full-resolution frames the description model gets a timed storyboard of the event: one
grid of up to 16 frames sampled evenly around the detection, reaching into the neighbouring Frigate
segments when the event crosses a recording boundary. Without it the model saw 1–4 frames of one
segment and could not tell a parked car from one driving past. Built in `core`
(`core/storyboard/`); this module only words it (`DescriptionRequest.storyboard`, `DescriptionTask`).

| Component | Purpose |
|-----------|---------|
| `StoryboardPlanner` | Pure arithmetic: window, neighbours needed, contiguity, footage, tile moments, marks, grid layout, labels |
| `AdjacentSegmentFinder` | Previous segment (one lookup); next segment polled once a second until the deadline and read only once its file has not changed for 1 s |
| `StoryboardFrameSampler` | One ffmpeg run per segment: input `-ss` to the first moment, `fps` at the storyboard step, `scale=<tile width>:-2`, JPEGs into the temp folder, deleted in `finally` |
| `StoryboardComposer` | Java2D grid, 2560 px wide; label on a dark plate in each tile's top-left corner, the tile nearest a detection in yellow with `• detection` |
| `StoryboardBuilder` | Orchestration, `Semaphore(2)` around ffprobe/ffmpeg only, fail-open (`null` + WARN), one INFO line |

**Where it runs.** Inside the description supplier of `RecordingProcessingFacade`, so only when a
description will actually be made (after the recipient filter, the rate limiter and a judge
`PUBLISH`): storyboard, then the runtime switch, then `agent.describe`. It runs before the executor's
semaphore and spends none of the model's timeouts. The full-resolution frames stay within the
user-visible collage (`selectTopFrames`, capped by `APP_AI_DESCRIPTION_MAX_FRAMES`, default 4); the
storyboard is deliberately wider.

**Timeline.** "Recording time": the current segment is `[0, D)` (`D` from ffprobe), the previous
`[-Dp, 0)`, the next `[D, D + Dn)`. Detection moments are `FrameData.offsetSeconds` of the frames with
detections. Window: `[first − before, last + after]`; frames without times → the whole recording, no
neighbours. A neighbour is used only when it butts against the current recording within 1.5 s (Frigate
file names are second-precision) and is laid on the timeline by durations, so there are no false gaps.
Tiles: up to `tiles`, evenly over the footage minus its last 0.1 s (a frame exactly at the end of a
file is not returned), never closer than 0.5 s; fewer than 4 → no storyboard. Every time the model
sees counts from the start of the footage.

**Waiting for the next segment.** Deadline = `min(job start, fileCreationTimestamp + D) +
next-segment-wait`. The pipeline takes a recording only 30 s after its file appeared
(`findUnprocessedRecordings`: `file_creation_timestamp < now − 30 s`), so when a description starts
the next segment has usually been in the database for a while and the first lookup finds it; waiting
happens only when Frigate is late. A backlog recording gets exactly one lookup. A next segment that
starts after a gap is not waited for — Frigate skipped it.

**Fail-open.** Any failure → WARN, and the description goes out from the frames alone, captioned with
their times. A failed neighbour only drops its tiles (`missingBefore` / `missingAfter`). The model is
told when footage before or after is not available, so it does not invent that a car "left".

**Log.** One INFO line per storyboard, e.g. `Storyboard for <id>: footage -4.0..5.9 s of the recording
(prev+current), 16 tiles 0.7 s apart, built in 1.8 s`; a missing neighbour shows as `prev: missing` /
`next: missing after N s`, a found one as `waited N s for the next segment`.
```

- в разделе `## Configuration` строку `- \`APP_AI_DESCRIPTION_MAX_FRAMES\` — frames forwarded to the model per recording` заменить на `- \`APP_AI_DESCRIPTION_MAX_FRAMES\` — full-resolution frames forwarded next to the storyboard (default 4)` и сразу после неё добавить «- `APP_AI_DESCRIPTION_STORYBOARD_*` — the storyboard (`configuration.md`, "Storyboard")».

`.claude/rules/configuration.md`, строка `APP_AI_DESCRIPTION_MAX_FRAMES`:

```markdown
| `APP_AI_DESCRIPTION_MAX_FRAMES` | 4 | Full-resolution frames forwarded next to the storyboard, top-N by detection quality — the same ranking as the collage. Validated `1..50`, the effective value is `minOf(this, LOCAL_VIZ_MAX_FRAMES)`. Was 10; with the storyboard carrying the motion, these are for detail. |
```

`docker/deploy/.env.example`: две строки комментария над `# APP_AI_DESCRIPTION_MAX_FRAMES=10` заменить, значение → 4:

```
# Full-resolution frames next to the storyboard: top-N by detection quality (max confidence, then
# detection count), the same ranking as the user-visible collage.
# APP_AI_DESCRIPTION_MAX_FRAMES=4
```

`README.md`:
- в списке Features строку `- **AI description (optional)** — …` заменить на: `- **AI description (optional)** — generates short and detailed natural-language descriptions of detections via Claude Code CLI or Grok Build CLI from a timed storyboard of the event (frames around the detection, reaching into neighbouring segments) plus full-resolution frames, edited into the notification message`
- в таблице AI после строки `APP_AI_DESCRIPTION_LANGUAGE`: `| \`APP_AI_DESCRIPTION_STORYBOARD_ENABLED\` | \`true\` | Give the model a timed storyboard of the event; \`false\` describes from the frames alone |`

`CLAUDE.md`, Key Patterns, пункт `**AI description:**` — дописать в конец: «; the model gets a timed storyboard of the event (grid of frames around the detection, reaching into neighbouring segments), built fail-open in `core/storyboard`».

- [ ] **Step 6: Full verification**

1. Ревью: агент `superpowers:code-reviewer` по диффу ветки (правило `CLAUDE.md`); критичные замечания исправить, повторить до чистого.
2. Сборка через `claude-forge:build-runner`: `./gradlew build`. На ошибки ktlint — `./gradlew ktlintFormat`, затем повтор.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt \
  modules/core/src/main/resources/application.yaml \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt \
  modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/PackagedApplicationYamlTest.kt \
  .claude/rules/ai-description.md .claude/rules/configuration.md docker/deploy/.env.example README.md CLAUDE.md
git commit -m "feat(core): give the description model the storyboard"
```

---

## Перед PR

- `git rm -r docs/superpowers/` и отдельный коммит `chore: remove design and plan documents before PR` (правило владельца: в диффе PR их быть не должно, они остаются в истории ветки).
- В рабочем `.env` на стенде убрать явные `APP_AI_DESCRIPTION_MAX_FRAMES` и `APP_AI_DESCRIPTION_SHORT_MAX`, если они там заданы, иначе новые значения по умолчанию не подействуют.
- После выката проверить строки `Storyboard for …` в логе: как часто `next: missing`, сколько `built in`; посмотреть описания нескольких реальных событий с машинами.
