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
