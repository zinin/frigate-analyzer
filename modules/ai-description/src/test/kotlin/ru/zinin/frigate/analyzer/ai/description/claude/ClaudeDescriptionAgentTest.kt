package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource

class ClaudeDescriptionAgentTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private val claudeProps =
        ClaudeProperties(
            oauthToken = "token",
            model = "opus",
            cliPath = "",
            workingDirectory = "/tmp",
            proxy = ClaudeProperties.ProxySection("", "", ""),
            anthropic = ClaudeProperties.AnthropicSection(),
        )

    private val promptBuilder = mockk<ClaudePromptBuilder>()
    private val responseParser = ClaudeResponseParser(ObjectMapper().registerKotlinModule())
    private val imageStager = mockk<ClaudeImageStager>()
    private val exceptionMapper = ClaudeExceptionMapper()

    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    private val stagedPaths: List<Path> = listOf(Path.of("/tmp/f.jpg"))
    private val okJson = """{"short": "s", "detailed": "d"}"""

    init {
        coEvery { imageStager.stage(any()) } returns stagedPaths
        coEvery { imageStager.cleanup(any()) } just Runs
        every { promptBuilder.build(any(), any()) } returns "prompt"
    }

    private fun build(
        invoker: ClaudeInvoker,
        customCommon: DescriptionProperties.CommonSection = common,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) = ClaudeDescriptionAgent(
        claudeProps,
        DescriptionProperties(enabled = true, provider = "claude", common = customCommon),
        promptBuilder,
        responseParser,
        imageStager,
        invoker,
        exceptionMapper,
        timeSource,
    )

    @Test
    fun `happy path returns parsed result and cleans up`() =
        runTest {
            val agent = build(ClaudeInvoker { okJson })
            val result = agent.describe(request)
            assertEquals(DescriptionResult("s", "d"), result)
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `retries once on invalid JSON then succeeds`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    if (calls == 1) "not json" else okJson
                }
            val agent = build(invoker)
            agent.describe(request)
            assertEquals(2, calls)
        }

    @Test
    fun `fails with InvalidResponse after two invalid JSONs`() =
        runTest {
            val agent = build(ClaudeInvoker { "not json" })
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `InvalidResponse retry gives up when budget exhausted (avoids mid-retry Timeout)`() =
        runTest {
            // timeout=10s, budget-check threshold INVALID_RESPONSE_RETRY_MIN_BUDGET=5s.
            // First call sleeps 8s virtual-time → ~2s remaining < 5s → agent throws InvalidResponse
            // right away (no second invoke), instead of retrying and getting caught by the outer
            // withTimeout → Timeout (the bug this fix addresses).
            val shortTimeoutCommon = common.copy(timeout = Duration.ofSeconds(10))
            var calls = 0
            val agent =
                build(
                    ClaudeInvoker {
                        calls++
                        delay(8_000)
                        "not json"
                    },
                    customCommon = shortTimeoutCommon,
                    timeSource = (this as TestScope).testTimeSource,
                )
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
            assertEquals(1, calls, "second invoke must be skipped when remaining budget < threshold")
        }

    @Test
    fun `retries once on Transport then succeeds (virtual time)`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    if (calls == 1) throw DescriptionException.Transport() else okJson
                }
            val agent = build(invoker)
            agent.describe(request)
            assertEquals(2, calls)
        }

    @Test
    fun `fails with Transport after two Transport errors`() =
        runTest {
            val agent = build(ClaudeInvoker { throw DescriptionException.Transport() })
            assertFailsWith<DescriptionException.Transport> { agent.describe(request) }
        }

    @Test
    fun `RateLimited does not retry`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    throw DescriptionException.RateLimited()
                }
            val agent = build(invoker)
            assertFailsWith<DescriptionException.RateLimited> { agent.describe(request) }
            assertEquals(1, calls)
        }

    @Test
    fun `cleanup runs even when describe throws`() =
        runTest {
            val agent = build(ClaudeInvoker { throw DescriptionException.RateLimited() })
            runCatching { agent.describe(request) }
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `work timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val shortTimeoutCommon = common.copy(timeout = Duration.ofMillis(500))
            val agent =
                build(
                    ClaudeInvoker {
                        gate.await()
                        okJson
                    },
                    customCommon = shortTimeoutCommon,
                )
            val job = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1_000)
            advanceUntilIdle()
            val outcome = job.await()
            assertFailsWith<DescriptionException.Timeout> { outcome.getOrThrow() }
        }

    @Test
    fun `queue timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val busyCommon =
                common.copy(
                    maxConcurrent = 1,
                    queueTimeout = Duration.ofMillis(100),
                    timeout = Duration.ofSeconds(60),
                )
            val blocker = CompletableDeferred<Unit>()
            val agent =
                build(
                    ClaudeInvoker {
                        blocker.await()
                        okJson
                    },
                    customCommon = busyCommon,
                )
            val first = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1)
            val second = async { runCatching { agent.describe(request) } }
            advanceTimeBy(200)
            advanceUntilIdle()
            val outcome = second.await()
            assertFailsWith<DescriptionException.Timeout> { outcome.getOrThrow() }
            blocker.complete(Unit)
            first.await()
        }

    @Test
    fun `third call waits for semaphore permit with maxConcurrent=2`() =
        runTest {
            val inFlight = AtomicInteger()
            val maxSeen = AtomicInteger()
            val agent =
                build(
                    ClaudeInvoker {
                        val current = inFlight.incrementAndGet()
                        maxSeen.updateAndGet { kotlin.math.max(it, current) }
                        delay(100)
                        inFlight.decrementAndGet()
                        okJson
                    },
                )
            coroutineScope {
                repeat(3) { launch { agent.describe(request) } }
            }
            // Exactly 2 — both upper bound (cap respected) and lower bound (permits actually used).
            // Weak `<= 2` would pass even if a regression silently capped concurrency to 1.
            assertEquals(2, maxSeen.get())
        }
}
