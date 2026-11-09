package ru.zinin.frigate.analyzer.core.judge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.core.storyboard.BuiltStoryboard
import ru.zinin.frigate.analyzer.core.storyboard.RecordingStoryboard
import ru.zinin.frigate.analyzer.core.storyboard.StoryboardBuilder
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Окончательный запрос judge: прежний отбор крупных кадров, раскадровка события и `visual_input` в контексте.
 * Раскадровка настоящая ([RecordingStoryboard]), мок — только builder за ней: память, бюджет и флаг ожидания
 * работают так же, как в проде. Её часы — виртуальные часы теста, поэтому `preparation_ms` детерминирован.
 */
class JudgeRequestFactoryTest {
    private val builder = mockk<StoryboardBuilder>()
    private val mapper = TestObjectMappers.internalMapper()
    private val recording =
        RecordingDto(
            id = UUID.randomUUID(),
            creationTimestamp = Instant.parse("2026-11-08T10:00:12Z"),
            filePath = "/rec/cam1/10.00.00.mp4",
            fileCreationTimestamp = Instant.parse("2026-11-08T10:00:12Z"),
            camId = "cam1",
            recordDate = LocalDate.of(2026, 11, 8),
            recordTime = LocalTime.of(10, 0),
            recordTimestamp = Instant.parse("2026-11-08T10:00:00Z"),
            startProcessingTimestamp = null,
            processTimestamp = null,
            processAttempts = 0,
            detectionsCount = 3,
            analyzeTime = 0,
            analyzedFramesCount = 3,
            errorMessage = null,
        )
    private val built =
        BuiltStoryboard(
            DescriptionRequest.Storyboard(
                image = "grid-jpeg".toByteArray(),
                tiles = 16,
                stepSeconds = 0.66,
                durationSeconds = 10.0,
                detections = listOf(DescriptionRequest.DetectionMark("car", 0.9, 5.0)),
                missingBefore = false,
                missingAfter = false,
            ),
            zeroSeconds = -3.0,
        )

    /** Время кадров детектора от начала записи: кадр 3 — 2.0 s, кадр 1 — неизвестно, кадр 2 — 8.0 s. */
    private val frames = listOf(frame(1, null), frame(2, 8.0), frame(3, 2.0))

    /** Порядок ранжирования визуализации — 3, 1, 2, не хронологический. */
    private val visualized = listOf(visualized(3), visualized(1), visualized(2))
    private val context = JudgeContextResult("""{"recording":{"cam":"cam1"},"camera_notes":""}""", emptyList())
    private val descriptionProperties =
        DescriptionProperties(
            enabled = true,
            provider = "claude",
            common =
                DescriptionProperties.CommonSection(
                    language = "en",
                    shortMaxLength = 200,
                    detailedMaxLength = 1500,
                    maxFrames = 10,
                    queueTimeout = Duration.ofSeconds(30),
                    timeout = Duration.ofSeconds(60),
                    maxConcurrent = 2,
                ),
        )

    private fun frame(
        index: Int,
        offset: Double?,
    ) = FrameData(
        recordId = recording.id,
        frameIndex = index,
        frameBytes = "raw-$index".toByteArray(),
        detectResponse =
            DetectResponse(listOf(Detection(2, "car", 0.9, BBox(10.0, 20.0, 110.0, 220.0))), 0, ImageSize(2880, 1620), "m"),
        offsetSeconds = offset,
    )

    private fun visualized(index: Int) = VisualizedFrameData(index, "frame-$index".toByteArray(), 1)

    private fun candidate(
        storyboard: RecordingStoryboard?,
        visualizedFrames: List<VisualizedFrameData> = visualized,
    ) = JudgeCandidate(
        recording = recording,
        detections = emptyList(),
        decision = NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS),
        frames = frames,
        visualizedFrames = visualizedFrames,
        descriptionSupplier = null,
        storyboard = storyboard,
    )

    /** Раскадровка события на виртуальных часах теста: её `elapsed` — ровно то, что builder пролежал в `delay`. */
    private fun TestScope.handle() = RecordingStoryboard(recording, frames, builder, testScheduler.timeSource)

    private fun factory(
        maxFrames: Int = 2,
        storyboardEnabled: Boolean = true,
        storyboardTimeout: Duration = Duration.ofSeconds(10),
    ): JudgeRequestFactory {
        val properties =
            JudgeProperties(
                maxFrames = maxFrames,
                maxSnooze = Duration.ofMinutes(45),
                storyboard = JudgeProperties.StoryboardSettings(enabled = storyboardEnabled, timeout = storyboardTimeout),
            )
        // Настоящий builder контекста на строгих моках репозиториев: visual_input обязан обойтись без базы.
        val contextBuilder = JudgeContextBuilder(mockk(), mockk(), mockk(), properties, ObjectTrackerProperties(), mapper)
        return JudgeRequestFactory(properties, descriptionProperties, contextBuilder)
    }

    private fun visualInputOf(request: JudgeRequest): JsonNode = mapper.readTree(request.contextJson)["visual_input"]

    /**
     * Прежний отбор — первые `maxFrames` по ранжированию, затем по индексу — и один ноль с сеткой: кадр 3 снят на
     * 2.0 s записи, сетка начинается на −3.0 s, значит его время — 5.0 s. Неизвестное время кадра 1 неизвестным и
     * остаётся, а байты — визуализированные, с рамками.
     */
    @Test
    fun `selected frames share storyboard zero`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns built

            val result = factory().create(candidate(handle()), context)

            val json = mapper.readTree(result.contextJson)
            assertEquals(listOf(1, 3), result.frames.map { it.frameIndex })
            assertEquals(listOf(null, 5.0), result.frames.map { it.offsetSeconds })
            assertEquals(listOf("frame-1", "frame-3"), result.frames.map { it.bytes.decodeToString() })
            assertSame(built.storyboard, result.storyboard)
            assertEquals(3, json["visual_input"]["image_count"].asInt())
            assertEquals("available", json["visual_input"]["storyboard"]["status"].asString())
            assertEquals(-3.0, json["visual_input"]["storyboard"]["zero_recording_seconds"].asDouble())
        }

    /**
     * Индекс — не время: кадр без `offsetSeconds` и кадр, которого среди кадров детектора нет вовсе, остаются без
     * времени и в запросе, и в `visual_input` (JSON null), хотя ноль сетки сдвинут.
     */
    @Test
    fun `unknown offsets stay unknown`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns built
            val candidate = candidate(handle(), visualizedFrames = listOf(visualized(7), visualized(1)))

            val result = factory().create(candidate, context)

            assertEquals(listOf(1, 7), result.frames.map { it.frameIndex })
            assertEquals(listOf(null, null), result.frames.map { it.offsetSeconds })
            val selected = visualInputOf(result)["frames"].values()
            assertEquals(listOf(1, 7), selected.map { it["index"].asInt() })
            assertTrue(selected.all { it.has("offset_seconds") && it["offset_seconds"].isNull })
        }

    /** Сетки нет — время считается от начала записи: кадр 3 сохраняет свои 2.0 s без сдвига. */
    @Test
    fun `without storyboard times start at recording zero`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns null

            val result = factory().create(candidate(handle()), context)

            assertNull(result.storyboard)
            assertEquals(listOf(null, 2.0), result.frames.map { it.offsetSeconds })
            val visualInput = visualInputOf(result)
            assertEquals(2.0, visualInput["frames"][1]["offset_seconds"].asDouble())
            assertFalse(visualInput["storyboard"].has("zero_recording_seconds"))
        }

    /**
     * `judge.storyboard.enabled=false` — прежний запрос по одним крупным кадрам со статусом `disabled`. К раскадровке
     * события judge не обращается вовсе: ни одной сборки, которую унаследовало бы описание.
     */
    @Test
    fun `judge storyboard switch does not access handle`() =
        runTest {
            val result = factory(storyboardEnabled = false).create(candidate(handle()), context)

            coVerify(exactly = 0) { builder.build(any(), any(), any()) }
            assertNull(result.storyboard)
            assertEquals(recording.id, result.recordingId)
            assertEquals("cam1", result.camId)
            assertEquals(listOf(1, 3), result.frames.map { it.frameIndex })
            assertEquals(listOf(null, 2.0), result.frames.map { it.offsetSeconds })
            assertEquals("en", result.language)
            assertEquals(45, result.maxSnoozeMinutes)
            val visualInput = visualInputOf(result)
            assertEquals("disabled", visualInput["storyboard"]["status"].asString())
            assertEquals(listOf("status"), visualInput["storyboard"].propertyNames().toList())
            assertEquals(0L, visualInput["preparation_ms"].asLong())
            assertEquals(2, visualInput["image_count"].asInt())
        }

    /** Раскадровки у события нет: это `unavailable`, а не исключение, и judge решает по кадрам. Попытки не было — 0 ms. */
    @Test
    fun `missing handle is unavailable`() =
        runTest {
            val result = factory().create(candidate(storyboard = null), context)

            assertNull(result.storyboard)
            assertEquals(listOf(1, 3), result.frames.map { it.frameIndex })
            val visualInput = visualInputOf(result)
            assertEquals("unavailable", visualInput["storyboard"]["status"].asString())
            assertEquals(listOf("status"), visualInput["storyboard"].propertyNames().toList())
            assertEquals(0L, visualInput["preparation_ms"].asLong())
            assertEquals(2, visualInput["image_count"].asInt())
        }

    /**
     * Оба исхода без сетки дают обычный запрос по кадрам: builder отказал через 1.5 s — `unavailable`; не уложился в
     * бюджет `judge.storyboard.timeout` (здесь 4 s) — `timeout`. `preparation_ms` — время той попытки.
     */
    @Test
    fun `unavailable and timed out build still produce judge request`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                delay(1_500)
                null
            }
            val unavailable = factory().create(candidate(handle()), context)
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                delay(5_000)
                built
            }
            val timedOut = factory(storyboardTimeout = Duration.ofSeconds(4)).create(candidate(handle()), context)

            val outcomes = listOf(Triple(unavailable, "unavailable", 1_500L), Triple(timedOut, "timeout", 4_000L))
            outcomes.forEach { (result, status, millis) ->
                assertNull(result.storyboard, status)
                assertEquals(listOf(1, 3), result.frames.map { it.frameIndex }, status)
                assertEquals(listOf(null, 2.0), result.frames.map { it.offsetSeconds }, status)
                val visualInput = visualInputOf(result)
                assertEquals(status, visualInput["storyboard"]["status"].asString())
                assertEquals(listOf("status"), visualInput["storyboard"].propertyNames().toList(), status)
                assertEquals(millis, visualInput["preparation_ms"].asLong(), status)
                assertEquals(2, visualInput["image_count"].asInt(), status)
            }
        }

    /**
     * `visual_input` описывает именно отправленный запрос: те же индексы и времена кадров, число изображений вместе
     * с сеткой, её клетки, шаг, длительность и флаги, время подготовки. Ни байтов, ни base64, ни путей — только эта
     * форма.
     */
    @Test
    fun `visual input matches actual selection`() =
        runTest {
            val grid = built.copy(storyboard = built.storyboard.copy(missingAfter = true))
            coEvery { builder.build(any(), any(), any()) } coAnswers {
                delay(2_000)
                grid
            }

            val result = factory().create(candidate(handle()), context)

            val visualInput = visualInputOf(result)
            assertEquals(listOf("frames", "storyboard", "preparation_ms", "image_count"), visualInput.propertyNames().toList())
            val selected = visualInput["frames"].values()
            assertTrue(selected.all { it.propertyNames().toList() == listOf("index", "offset_seconds") })
            assertEquals(result.frames.map { it.frameIndex }, selected.map { it["index"].asInt() })
            val offsets = selected.map { it["offset_seconds"].takeUnless { node -> node.isNull }?.asDouble() }
            assertEquals(result.frames.map { it.offsetSeconds }, offsets)
            assertEquals(result.frames.size + 1, visualInput["image_count"].asInt())
            assertEquals(2_000L, visualInput["preparation_ms"].asLong())
            val storyboard = visualInput["storyboard"]
            assertEquals(
                listOf("status", "tiles", "step_seconds", "duration_seconds", "zero_recording_seconds", "missing_before", "missing_after"),
                storyboard.propertyNames().toList(),
            )
            assertEquals(16, storyboard["tiles"].asInt())
            assertEquals(0.66, storyboard["step_seconds"].asDouble())
            assertEquals(10.0, storyboard["duration_seconds"].asDouble())
            assertFalse(storyboard["missing_before"].asBoolean())
            assertTrue(storyboard["missing_after"].asBoolean())
            val images = listOf(grid.storyboard.image) + visualized.map { it.visualizedBytes }
            images.forEach { assertFalse(result.contextJson.contains(Base64.getEncoder().encodeToString(it))) }
            assertFalse(result.contextJson.contains(recording.filePath))
        }

    /** Timeout родителя — не бюджет раскадровки: из create выходит отмена, а не запрос без сетки. */
    @Test
    fun `parent cancellation escapes factory`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } coAnswers { awaitCancellation() }
            val factory = factory()
            val candidate = candidate(handle())

            var returned: JudgeRequest? = null
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { returned = factory.create(candidate, context) }
            }
            assertNull(returned, "the parent's cancellation must leave create as a cancellation, not as a request")
        }

    /**
     * Factory есть ровно там же, где `NotificationJudgeService`, — при `application.ai.judge.enabled=true`. Ни один
     * тест core не поднимает контекст с бинами пакета judge, и разошедшееся условие проявилось бы только падением
     * старта прода с включённым judge.
     */
    @Test
    fun `exists only with judge enabled`() {
        val runner =
            ApplicationContextRunner().withUserConfiguration(FactoryDependencies::class.java, JudgeRequestFactory::class.java)

        runner.withPropertyValues("application.ai.judge.enabled=true").run { app ->
            assertThat(app).hasNotFailed()
            assertThat(app).hasSingleBean(JudgeRequestFactory::class.java)
        }
        listOf(emptyArray<String>(), arrayOf("application.ai.judge.enabled=false")).forEach { properties ->
            runner.withPropertyValues(*properties).run { app ->
                assertThat(app).hasNotFailed()
                assertThat(app).doesNotHaveBean(JudgeRequestFactory::class.java)
            }
        }
    }

    /** Judge не ждёт следующий сегмент: builder проверяет его один раз, как у записи из бэклога. */
    @Test
    fun `judge does not wait for next segment`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns built

            factory().create(candidate(handle()), context)

            coVerify(exactly = 1) { builder.build(recording, any(), false) }
            coVerify(exactly = 1) { builder.build(any(), any(), any()) }
        }

    /** Сломанный контекст ломает только диагностику: запрос уходит с исходным JSON, а кадры и сетка готовы как обычно. */
    @Test
    fun `broken diagnostic context still prepares images`() =
        runTest {
            coEvery { builder.build(any(), any(), any()) } returns built
            val broken = JudgeContextResult("{\"recording\":", listOf("recent_verdicts"))

            val result = factory().create(candidate(handle()), broken)

            assertEquals(broken.json, result.contextJson)
            assertEquals(listOf(1, 3), result.frames.map { it.frameIndex })
            assertEquals(listOf(null, 5.0), result.frames.map { it.offsetSeconds })
            assertSame(built.storyboard, result.storyboard)
        }

    /** Зависимости factory — моки: тест о её условии, а не о них. */
    @Configuration
    class FactoryDependencies {
        @Bean
        fun judgeProperties(): JudgeProperties = mockk(relaxed = true)

        @Bean
        fun descriptionProperties(): DescriptionProperties = mockk(relaxed = true)

        @Bean
        fun judgeContextBuilder(): JudgeContextBuilder = mockk(relaxed = true)
    }
}
