package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionTask
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeBackendTest {
    private val promptBuilder = mockk<ClaudePromptBuilder>()
    private val imageStager = mockk<ClaudeImageStager>()
    private val exceptionMapper = ClaudeExceptionMapper()
    private val stagedPaths: List<Path> = listOf(Path.of("/tmp/f.jpg"))
    private val budget: Duration = Duration.ofSeconds(90)
    private val descriptionRequest =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )
    private val request =
        VisionRequest(
            descriptionRequest.recordingId,
            descriptionRequest.frames,
            DescriptionTask.instructions(descriptionRequest),
        )

    init {
        coEvery { imageStager.stage(any()) } returns stagedPaths
        coEvery { imageStager.cleanup(any()) } just Runs
        every { promptBuilder.build(any(), any()) } returns "prompt"
    }

    private fun build(invoker: ClaudeInvoker) =
        ClaudeBackend(
            model = "opus",
            authScopeId = "claude",
            promptBuilder = promptBuilder,
            imageStager = imageStager,
            invoker = invoker,
            exceptionMapper = exceptionMapper,
        )

    @Test
    fun `happy path stages, invokes, parses and cleans up`() =
        runTest {
            val backend = build(ClaudeInvoker { _, _, _, _ -> """{"short": "s", "detailed": "d"}""" })
            assertEquals("""{"short": "s", "detailed": "d"}""", backend.complete(request, budget).text)
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `the preset model is handed to the invoker`() =
        runTest {
            var seenModel: String? = null
            val backend =
                build(
                    ClaudeInvoker { _, model, _, _ ->
                        seenModel = model
                        """{"short": "s", "detailed": "d"}"""
                    },
                )
            backend.complete(request, budget)
            assertEquals("opus", seenModel)
        }

    @Test
    fun `the call budget is handed to the invoker`() =
        runTest {
            var seenTimeout: Duration? = null
            val backend =
                build(
                    ClaudeInvoker { _, _, _, timeout ->
                        seenTimeout = timeout
                        """{"short": "s", "detailed": "d"}"""
                    },
                )
            backend.complete(request, budget)
            assertEquals(budget, seenTimeout)
        }

    @Test
    fun `SDK exceptions go through the exception mapper`() =
        runTest {
            val backend = build(ClaudeInvoker { _, _, _, _ -> throw ClaudeSDKException("request was rate limited") })
            assertFailsWith<DescriptionException.RateLimited> { backend.complete(request, budget) }
        }

    @Test
    fun `identifies itself as claude`() {
        val backend = build(ClaudeInvoker { _, _, _, _ -> "" })
        assertEquals("claude", backend.providerId)
        assert(backend.authRecoveryHint.contains("CLAUDE_CODE_OAUTH_TOKEN"))
    }
}
