package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultJudgeAgentTest {
    private val parser = JudgeResponseParser(TestObjectMappers.internalMapper())

    private class FakeBackend(
        private val answer: String,
    ) : VisionBackend {
        override val providerId = "fake"
        override val authScopeId = "fake:model"
        override val authRecoveryHint = "hint"
        var lastRequest: VisionRequest? = null

        override suspend fun complete(request: VisionRequest): String {
            lastRequest = request
            return answer
        }
    }

    private fun agent(backend: VisionBackend): DefaultJudgeAgent {
        val view = DescriptionPreset("judge", "fake", "m", "m-effective", "", "fake:model", null)
        val catalog = DescriptionPresetCatalog(listOf(DescriptionPresetCatalog.Entry(view, backend)), "judge")
        val resolver = ActivePresetResolver(catalog, InMemoryJudgeRuntimeSettings(), fallbackId = "judge", label = "judge")
        val executor =
            VisionCallExecutor(
                resolver,
                ProviderAuthTracker { },
                VisionLimits(Duration.ofSeconds(5), Duration.ofSeconds(10), 1, 0),
                label = "judge",
            )
        return DefaultJudgeAgent(executor, parser)
    }

    @Test
    fun `judge hands the task instructions to the backend and returns the verdict with preset and model`() =
        runTest {
            val backend = FakeBackend("""{"verdict":"SUPPRESS","reason":"STATIC_OBJECT","summary":"car","snooze_minutes":10}""")
            val request = JudgeRequest(UUID.randomUUID(), "cam2", listOf(DescriptionRequest.FrameImage(0, ByteArray(1))), "{}", "en", 30)

            val outcome = agent(backend).judge(request)

            assertEquals(JudgeVerdict.Decision.SUPPRESS, outcome.verdict.decision)
            assertEquals(10, outcome.verdict.snoozeMinutes)
            assertEquals("judge", outcome.presetId)
            assertEquals("m-effective", outcome.model)
            val seen = backend.lastRequest!!
            assertEquals(JudgeTask.SYSTEM_PROMPT, seen.instructions.systemPrompt)
            assertTrue(seen.instructions.preamble.contains("cam2"))
        }
}
