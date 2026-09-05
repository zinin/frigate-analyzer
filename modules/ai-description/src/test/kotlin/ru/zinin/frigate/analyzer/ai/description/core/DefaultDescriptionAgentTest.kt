package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.test.runTest
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultDescriptionAgentTest {
    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    private class FakeBackend(
        private val handler: suspend (VisionRequest) -> String,
    ) : VisionBackend {
        override val providerId = "fake"
        override val authScopeId = "fake:model"
        override val authRecoveryHint = "run fake-login"
        val calls = AtomicInteger()

        override suspend fun complete(
            request: VisionRequest,
            timeout: Duration,
        ): String {
            calls.incrementAndGet()
            return handler(request)
        }
    }

    private fun catalogOf(backend: VisionBackend): DescriptionPresetCatalog =
        DescriptionPresetCatalog(
            listOf(
                DescriptionPresetCatalog.Entry(
                    DescriptionPreset(
                        id = "test",
                        provider = backend.providerId,
                        model = "test-model",
                        effectiveModel = "test-model",
                        effort = "",
                        authScopeId = backend.authScopeId,
                        unavailableReason = null,
                    ),
                    backend,
                ),
            ),
            fallbackId = "test",
        )

    private fun agent(
        backend: FakeBackend,
        parser: DescriptionResponseParser = DescriptionResponseParser(TestObjectMappers.internalMapper()),
    ): DefaultDescriptionAgent {
        val executor =
            VisionCallExecutor(
                resolver =
                    ActivePresetResolver(
                        catalogOf(backend),
                        InMemoryDescriptionRuntimeSettings(),
                        fallbackId = "test",
                        label = "description",
                    ),
                authTracker = ProviderAuthTracker(ApplicationEventPublisher { }),
                limits =
                    VisionLimits(
                        queueTimeout = Duration.ofSeconds(30),
                        timeout = Duration.ofSeconds(60),
                        maxConcurrent = 2,
                        maxImageSide = 0,
                    ),
                label = "description",
            )
        return DefaultDescriptionAgent(executor, parser)
    }

    @Test
    fun `description task instructions reach the backend`() =
        runTest {
            var seen: VisionRequest? = null
            val backend =
                FakeBackend {
                    seen = it
                    """{"short":"s","detailed":"d"}"""
                }

            assertEquals(DescriptionResult("s", "d"), agent(backend).describe(request))

            val vision = requireNotNull(seen)
            assertEquals(request.recordingId, vision.requestId)
            assertEquals(request.frames, vision.frames)
            assertTrue(vision.instructions.preamble.contains("Write both descriptions in English."))
            assertEquals(DescriptionTask.SYSTEM_PROMPT, vision.instructions.systemPrompt)
            assertEquals(DescriptionTask.JSON_SCHEMA, vision.instructions.jsonSchema)
        }

    @Test
    fun `parser result is returned as-is`() =
        runTest {
            val parser = DescriptionResponseParser(TestObjectMappers.internalMapper())
            val raw = """{"short":"from-parser","detailed":"detailed-from-parser"}"""
            val expected = parser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
            val backend = FakeBackend { raw }

            assertEquals(expected, agent(backend, parser).describe(request))
        }
}
