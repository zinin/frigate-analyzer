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
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeBackendTest {
    private val promptBuilder = mockk<ClaudePromptBuilder>()
    private val responseParser = ClaudeResponseParser(TestObjectMappers.internalMapper())
    private val imageStager = mockk<ClaudeImageStager>()
    private val exceptionMapper = ClaudeExceptionMapper()
    private val stagedPaths: List<Path> = listOf(Path.of("/tmp/f.jpg"))
    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    init {
        coEvery { imageStager.stage(any()) } returns stagedPaths
        coEvery { imageStager.cleanup(any()) } just Runs
        every { promptBuilder.build(any(), any()) } returns "prompt"
    }

    private fun build(invoker: ClaudeInvoker) =
        ClaudeBackend(
            model = "opus",
            promptBuilder = promptBuilder,
            responseParser = responseParser,
            imageStager = imageStager,
            invoker = invoker,
            exceptionMapper = exceptionMapper,
        )

    @Test
    fun `happy path stages, invokes, parses and cleans up`() =
        runTest {
            val backend = build(ClaudeInvoker { _, _ -> """{"short": "s", "detailed": "d"}""" })
            assertEquals(DescriptionResult("s", "d"), backend.describe(request))
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `the preset model is handed to the invoker`() =
        runTest {
            var seenModel: String? = null
            val backend =
                build(
                    ClaudeInvoker { _, model ->
                        seenModel = model
                        """{"short": "s", "detailed": "d"}"""
                    },
                )
            backend.describe(request)
            assertEquals("opus", seenModel)
        }

    @Test
    fun `invalid JSON is InvalidResponse and still cleans up`() =
        runTest {
            val backend = build(ClaudeInvoker { _, _ -> "not json" })
            assertFailsWith<DescriptionException.InvalidResponse> { backend.describe(request) }
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `SDK exceptions go through the exception mapper`() =
        runTest {
            val backend = build(ClaudeInvoker { _, _ -> throw ClaudeSDKException("request was rate limited") })
            assertFailsWith<DescriptionException.RateLimited> { backend.describe(request) }
        }

    @Test
    fun `identifies itself as claude`() {
        val backend = build(ClaudeInvoker { _, _ -> "" })
        assertEquals("claude", backend.providerId)
        assert(backend.authRecoveryHint.contains("CLAUDE_CODE_OAUTH_TOKEN"))
    }
}
