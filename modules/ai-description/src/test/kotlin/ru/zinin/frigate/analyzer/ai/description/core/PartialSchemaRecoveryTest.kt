package ru.zinin.frigate.analyzer.ai.description.core

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.grok.GrokBackend
import ru.zinin.frigate.analyzer.ai.description.grok.GrokCommandBuilder
import ru.zinin.frigate.analyzer.ai.description.grok.GrokExceptionMapper
import ru.zinin.frigate.analyzer.ai.description.grok.GrokHomeGuard
import ru.zinin.frigate.analyzer.ai.description.grok.GrokOutputParser
import ru.zinin.frigate.analyzer.ai.description.grok.GrokProcessResult
import ru.zinin.frigate.analyzer.ai.description.grok.GrokProcessRunner
import ru.zinin.frigate.analyzer.ai.description.grok.GrokPromptFileWriter
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Провайдер, задача и executor вместе, без подмен между ними: эндпоинт применил `--json-schema`
 * частично, а полный объект положил в текст. Ровно этот случай терялся между слоями — разбор
 * структуры годен по отдельности, разбор текста годен по отдельности, а цепочка целиком платила за
 * повтор и отдавала «описание недоступно». Тесты по одному шву этого не ловят.
 */
class PartialSchemaRecoveryTest {
    private val mapper = TestObjectMappers.internalMapper()
    private val promptFileWriter = mockk<GrokPromptFileWriter>(relaxUnitFun = true)
    private val runs = AtomicInteger()

    private val properties =
        GrokProperties(
            cliPath = "/nonexistent/grok",
            model = "grok-4.6",
            effort = "low",
            home = "/tmp/frigate-analyzer-test/home",
            workingDirectory = "/tmp/frigate-analyzer-test/cwd",
            proxy = GrokProperties.ProxySection("", "", ""),
        )

    private fun executorOver(stdout: String): VisionCallExecutor {
        coEvery { promptFileWriter.write(any()) } returns Path.of("/tmp/frigate-analyzer-test/prompt.json")
        val backend =
            GrokBackend(
                model = properties.model,
                effort = properties.effort,
                authScopeId = "grok:${properties.model}",
                promptFileWriter = promptFileWriter,
                commandBuilder = GrokCommandBuilder(properties),
                runner =
                    GrokProcessRunner {
                        runs.incrementAndGet()
                        GrokProcessResult(0, stdout, "")
                    },
                outputParser = GrokOutputParser(mapper),
                exceptionMapper = GrokExceptionMapper(),
                guard = GrokHomeGuard(),
            )
        val catalog =
            DescriptionPresetCatalog(
                listOf(
                    DescriptionPresetCatalog.Entry(
                        DescriptionPreset(
                            id = "grok-fast",
                            provider = backend.providerId,
                            model = properties.model,
                            effectiveModel = properties.model,
                            effort = properties.effort,
                            authScopeId = backend.authScopeId,
                            unavailableReason = null,
                        ),
                        backend,
                    ),
                ),
                fallbackId = "grok-fast",
            )
        return VisionCallExecutor(
            resolver =
                ActivePresetResolver(
                    catalog,
                    InMemoryDescriptionRuntimeSettings(),
                    fallbackId = "grok-fast",
                    label = "test",
                ),
            authTracker = ProviderAuthTracker(ApplicationEventPublisher { }),
            limits =
                VisionLimits(
                    queueTimeout = Duration.ofSeconds(30),
                    timeout = Duration.ofSeconds(60),
                    maxConcurrent = 1,
                    maxImageSide = 0,
                ),
            label = "test",
        )
    }

    @Test
    fun `a description survives a half-applied schema when the text carries the whole object`() =
        runTest {
            val stdout =
                """{"stopReason":"end_turn","structuredOutput":{"short":"Bike"},""" +
                    """"text":"{\"short\":\"Bike\",\"detailed\":\"A bike by the fence.\"}"}"""
            val request =
                DescriptionRequest(
                    recordingId = UUID.randomUUID(),
                    frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
                    language = "en",
                    shortMaxLength = 200,
                    detailedMaxLength = 1500,
                )
            val agent = DefaultDescriptionAgent(executorOver(stdout), DescriptionResponseParser(mapper))

            val result = agent.describe(request)

            assertEquals("Bike", result.short)
            assertEquals("A bike by the fence.", result.detailed)
            assertEquals(1, runs.get(), "the answer is already paid for; a second call must not happen")
        }

    @Test
    fun `a verdict survives a half-applied schema when the text carries the whole object`() =
        runTest {
            val stdout =
                """{"stopReason":"end_turn","structuredOutput":{"verdict":"SUPPRESS"},""" +
                    """"text":"{\"verdict\":\"SUPPRESS\",\"reason\":\"STATIC_OBJECT\",\"summary\":\"Parked car.\",""" +
                    """\"snooze_minutes\":10}"}"""
            val request =
                JudgeRequest(
                    recordingId = UUID.randomUUID(),
                    camId = "cam2",
                    frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
                    contextJson = "{}",
                    language = "en",
                    maxSnoozeMinutes = 30,
                )
            val agent = DefaultJudgeAgent(executorOver(stdout), JudgeResponseParser(mapper))

            val outcome = agent.judge(request)

            assertEquals(JudgeVerdict.Decision.SUPPRESS, outcome.verdict.decision)
            assertEquals(JudgeVerdict.Reason.STATIC_OBJECT, outcome.verdict.reason)
            assertEquals(10, outcome.verdict.snoozeMinutes)
            assertEquals(1, runs.get(), "the answer is already paid for; a second call must not happen")
        }
}
