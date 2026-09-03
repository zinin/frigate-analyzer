package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.CancellationException
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
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class DefaultDescriptionAgentTest {
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

    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    private val ok = DescriptionResult("s", "d")
    private val events = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> events.add(event) }

    private class FakeBackend(
        private val handler: suspend (DescriptionRequest) -> DescriptionResult,
    ) : DescriptionBackend {
        override val providerId = "fake"
        override val authRecoveryHint = "run fake-login"
        val calls = AtomicInteger()

        override suspend fun describe(request: DescriptionRequest): DescriptionResult {
            calls.incrementAndGet()
            return handler(request)
        }
    }

    private fun build(
        backend: FakeBackend,
        customCommon: DescriptionProperties.CommonSection = common,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) = DefaultDescriptionAgent(
        backend = backend,
        descriptionProperties = DescriptionProperties(enabled = true, provider = "fake", common = customCommon),
        eventPublisher = publisher,
        timeSource = timeSource,
    )

    private fun jpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        repeat(40) { i ->
            graphics.color = Color(i * 6 % 256, (i * 13) % 256, (i * 29) % 256)
            graphics.fillRect(i * width / 40, 0, width / 40 + 1, height)
        }
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    private fun authEvents() = events.filterIsInstance<DescriptionProviderAuthEvent>()

    @Test
    fun `happy path returns backend result and publishes nothing`() =
        runTest {
            val agent = build(FakeBackend { ok })
            assertEquals(ok, agent.describe(request))
            assertTrue(authEvents().isEmpty())
        }

    @Test
    fun `frames are downscaled once before the backend sees them`() =
        runTest {
            val big = jpeg(1920, 1080)
            val seen = mutableListOf<DescriptionRequest>()
            var first = true
            val backend =
                FakeBackend { request ->
                    seen += request
                    if (first) {
                        first = false
                        throw DescriptionException.InvalidResponse(detail = "retry me")
                    }
                    ok
                }

            val agent = build(backend, common.copy(maxImageSide = 1568))
            agent.describe(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

            assertEquals(2, seen.size)
            val delivered = seen.map { it.frames.single().bytes }
            assertFalse(delivered.first().contentEquals(big), "backend must get the resized frame")
            // Повтор идёт по тем же байтам: уменьшение живёт до цикла попыток.
            assertTrue(delivered[0].contentEquals(delivered[1]))
            assertEquals(1568, ImageIO.read(ByteArrayInputStream(delivered.first())).width)
        }

    @Test
    fun `frames are left alone when the limit is disabled`() =
        runTest {
            val big = jpeg(1920, 1080)
            var seen: ByteArray? = null
            val agent =
                build(
                    FakeBackend {
                        seen = it.frames.single().bytes
                        ok
                    },
                )

            agent.describe(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

            assertSame(big, seen)
        }

    @Test
    fun `retries once on InvalidResponse then succeeds`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        throw DescriptionException.InvalidResponse()
                    }
                    ok
                }
            val agent = build(backend)
            agent.describe(request)
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `fails with InvalidResponse after two invalid responses`() =
        runTest {
            val agent = build(FakeBackend { throw DescriptionException.InvalidResponse() })
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `InvalidResponse retry gives up when budget exhausted`() =
        runTest {
            // timeout=10s, INVALID_RESPONSE_RETRY_MIN_BUDGET=5s. Первый вызов спит 8с виртуального
            // времени, остаток ~2с < 5с: агент отдаёт InvalidResponse без второго вызова, а не
            // уходит в повтор, который поймал бы внешний withTimeout как Timeout.
            val backend =
                FakeBackend {
                    delay(8_000)
                    throw DescriptionException.InvalidResponse()
                }
            val agent =
                build(
                    backend,
                    customCommon = common.copy(timeout = Duration.ofSeconds(10)),
                    timeSource = (this as TestScope).testTimeSource,
                )
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
            assertEquals(1, backend.calls.get(), "second attempt must be skipped when remaining budget < threshold")
        }

    @Test
    fun `retries once on Transport then succeeds (virtual time)`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        throw DescriptionException.Transport()
                    }
                    ok
                }
            val agent = build(backend)
            agent.describe(request)
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `fails with Transport after two Transport errors`() =
        runTest {
            val agent = build(FakeBackend { throw DescriptionException.Transport() })
            assertFailsWith<DescriptionException.Transport> { agent.describe(request) }
        }

    @Test
    fun `unexpected backend exception is wrapped into Transport and retried once`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        throw IllegalStateException("boom")
                    }
                    ok
                }
            val agent = build(backend)
            assertEquals(ok, agent.describe(request))
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `RateLimited does not retry`() =
        runTest {
            val backend = FakeBackend { throw DescriptionException.RateLimited() }
            val agent = build(backend)
            assertFailsWith<DescriptionException.RateLimited> { agent.describe(request) }
            assertEquals(1, backend.calls.get())
        }

    @Test
    fun `Unauthorized does not retry and publishes LOST once per outage`() =
        runTest {
            val backend = FakeBackend { throw DescriptionException.Unauthorized("Not signed in") }
            val agent = build(backend)
            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }
            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }
            assertEquals(2, backend.calls.get())
            val lost = authEvents()
            assertEquals(1, lost.size)
            assertEquals(DescriptionProviderAuthEvent.State.LOST, lost.single().state)
            assertEquals("fake", lost.single().provider)
            assertEquals("Not signed in", lost.single().detail)
            assertEquals("run fake-login", lost.single().recoveryHint)
        }

    @Test
    fun `success after LOST publishes RESTORED once`() =
        runTest {
            var failing = true
            val backend =
                FakeBackend {
                    if (failing) throw DescriptionException.Unauthorized("Not signed in") else ok
                }
            val agent = build(backend)
            runCatching { agent.describe(request) }
            failing = false
            agent.describe(request)
            agent.describe(request)
            val states = authEvents().map { it.state }
            assertEquals(
                listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
                states,
            )
        }

    @Test
    fun `concurrent Unauthorized failures publish a single LOST`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val entered = AtomicInteger()
            val allEntered = CompletableDeferred<Unit>()
            val backend =
                FakeBackend {
                    if (entered.incrementAndGet() == 5) allEntered.complete(Unit)
                    gate.await()
                    throw DescriptionException.Unauthorized("Not signed in")
                }
            val agent = build(backend, customCommon = common.copy(maxConcurrent = 5))
            coroutineScope {
                repeat(5) { launch { runCatching { agent.describe(request) } } }
                // Не advanceUntilIdle(): он докрутит виртуальное время до withTimeout и
                // отменит вызовы как Timeout, так и не будет ни одного LOST.
                allEntered.await()
                gate.complete(Unit)
            }
            assertEquals(1, authEvents().size)
        }

    @Test
    fun `work timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val agent =
                build(
                    FakeBackend {
                        gate.await()
                        ok
                    },
                    customCommon = common.copy(timeout = Duration.ofMillis(500)),
                )
            val job = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1_000)
            advanceUntilIdle()
            assertFailsWith<DescriptionException.Timeout> { job.await().getOrThrow() }
        }

    @Test
    fun `queue timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val agent =
                build(
                    FakeBackend {
                        blocker.await()
                        ok
                    },
                    customCommon =
                        common.copy(
                            maxConcurrent = 1,
                            queueTimeout = Duration.ofMillis(100),
                            timeout = Duration.ofSeconds(60),
                        ),
                )
            val first = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1)
            val second = async { runCatching { agent.describe(request) } }
            advanceTimeBy(200)
            advanceUntilIdle()
            assertFailsWith<DescriptionException.Timeout> { second.await().getOrThrow() }
            blocker.complete(Unit)
            first.await()
        }

    @Test
    fun `CancellationException from the backend is not wrapped as Transport`() =
        runTest {
            val agent = build(FakeBackend { throw CancellationException("cancelled by caller") })
            assertFailsWith<CancellationException> { agent.describe(request) }
        }

    @Test
    fun `third call waits for semaphore permit with maxConcurrent=2`() =
        runTest {
            val inFlight = AtomicInteger()
            val maxSeen = AtomicInteger()
            val agent =
                build(
                    FakeBackend {
                        val current = inFlight.incrementAndGet()
                        maxSeen.updateAndGet { kotlin.math.max(it, current) }
                        delay(100)
                        inFlight.decrementAndGet()
                        ok
                    },
                )
            coroutineScope {
                repeat(3) { launch { agent.describe(request) } }
            }
            // Ровно 2: и верхняя граница (лимит соблюдён), и нижняя (оба слота используются).
            assertEquals(2, maxSeen.get())
        }
}
