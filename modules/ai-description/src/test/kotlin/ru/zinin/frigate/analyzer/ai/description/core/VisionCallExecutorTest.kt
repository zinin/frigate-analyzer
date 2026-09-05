package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.PresetChoiceSource
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

class VisionCallExecutorTest {
    private val limits =
        VisionLimits(
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
            maxImageSide = 0,
        )
    private val instructions = VisionInstructions("sys", "pre", "epi", null)
    private val request =
        VisionRequest(
            UUID.randomUUID(),
            listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            instructions,
        )
    private val events = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> events.add(event) }

    private class FakeBackend(
        private val handler: suspend (VisionRequest) -> String,
    ) : VisionBackend {
        override val providerId = "fake"

        // Намеренно НЕ равен providerId: executor обязан отдавать трекеру область, а не провайдера, и
        // при совпадающих строках подмена одного другим осталась бы незамеченной — а это ровно тот
        // дефект, из-за которого успех BYOK-пресета снимал бы LOST у пресета на протухшем OAuth.
        override val authScopeId = "fake:model"
        override val authRecoveryHint = "run fake-login"
        val calls = AtomicInteger()

        override suspend fun complete(request: VisionRequest): String {
            calls.incrementAndGet()
            return handler(request)
        }
    }

    /**
     * Первое чтение настроек ждёт ворот, остальные мгновенны: так видно, держит ли вызов пермит,
     * пока читает настройки.
     */
    private class GatedSettings(
        private val firstRead: CompletableDeferred<Unit>,
    ) : PresetChoiceSource {
        private val reads = AtomicInteger()

        override val sourceName = "gated settings"

        override suspend fun activePresetId(): String? {
            if (reads.incrementAndGet() == 1) firstRead.await()
            return null
        }
    }

    private fun build(
        backend: FakeBackend,
        customLimits: VisionLimits = limits,
        timeSource: TimeSource = TimeSource.Monotonic,
        eventPublisher: ApplicationEventPublisher = publisher,
        extraPresets: List<Pair<String, VisionBackend>> = emptyList(),
        settings: PresetChoiceSource = InMemoryDescriptionRuntimeSettings(),
    ) = VisionCallExecutor(
        resolver =
            ActivePresetResolver(
                catalogOf("test" to backend, *extraPresets.toTypedArray()),
                settings,
                fallbackId = "test",
                label = "test",
            ),
        authTracker = ProviderAuthTracker(eventPublisher),
        limits = customLimits,
        label = "test",
        timeSource = timeSource,
    )

    /** По пресету на backend; первый объявленный — пресет по умолчанию, он же fallback каталога. */
    private fun catalogOf(vararg backends: Pair<String, VisionBackend>): DescriptionPresetCatalog =
        DescriptionPresetCatalog(
            backends.map { (id, backend) ->
                DescriptionPresetCatalog.Entry(
                    DescriptionPreset(
                        id = id,
                        provider = backend.providerId,
                        model = "$id-model",
                        effectiveModel = "$id-model",
                        effort = "",
                        authScopeId = backend.authScopeId,
                        unavailableReason = null,
                    ),
                    backend,
                )
            },
            fallbackId = backends.first().first,
        )

    private fun parse(raw: String): String {
        if (raw == "invalid" || raw == "not json") {
            throw DescriptionException.InvalidResponse(detail = "test")
        }
        return raw
    }

    private suspend fun VisionCallExecutor.call(request: VisionRequest = this@VisionCallExecutorTest.request): String =
        execute(request, ::parse).value

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
    fun `happy path returns the parsed value with the preset and the elapsed time`() =
        runTest {
            val backend = FakeBackend { "ok" }
            val outcome = build(backend).execute(request) { it.uppercase() }
            assertEquals("OK", outcome.value)
            assertEquals("test", outcome.preset.id)
            assertFalse(outcome.elapsed.isNegative())
            assertEquals(1, backend.calls.get())
        }

    @Test
    fun `happy path returns backend result and publishes nothing`() =
        runTest {
            assertEquals("ok", build(FakeBackend { "ok" }).call())
            assertTrue(authEvents().isEmpty())
        }

    @Test
    fun `InvalidResponse thrown by the parser is retried once`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        "invalid"
                    } else {
                        "ok"
                    }
                }
            assertEquals("ok", build(backend).call())
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `frames are downscaled once before the backend sees them`() =
        runTest {
            val big = jpeg(1920, 1080)
            val seen = mutableListOf<VisionRequest>()
            var first = true
            val backend =
                FakeBackend { request ->
                    seen += request
                    if (first) {
                        first = false
                        throw DescriptionException.InvalidResponse(detail = "retry me")
                    }
                    "ok"
                }

            build(backend, limits.copy(maxImageSide = 1568))
                .call(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

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
            val executor =
                build(
                    FakeBackend {
                        seen = it.frames.single().bytes
                        "ok"
                    },
                )

            executor.call(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

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
                        "not json"
                    } else {
                        "ok"
                    }
                }
            build(backend).call()
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `fails with InvalidResponse after two invalid responses`() =
        runTest {
            val executor = build(FakeBackend { "not json" })
            assertFailsWith<DescriptionException.InvalidResponse> { executor.call() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `InvalidResponse retry gives up when budget exhausted`() =
        runTest {
            // timeout=10s, INVALID_RESPONSE_RETRY_MIN_BUDGET=5s. Первый вызов спит 8с виртуального
            // времени, остаток ~2с < 5с: executor отдаёт InvalidResponse без второго вызова, а не
            // уходит в повтор, который поймал бы внешний withTimeout как Timeout.
            val backend =
                FakeBackend {
                    delay(8_000)
                    "not json"
                }
            val executor =
                build(
                    backend,
                    customLimits = limits.copy(timeout = Duration.ofSeconds(10)),
                    timeSource = testTimeSource,
                )
            assertFailsWith<DescriptionException.InvalidResponse> { executor.call() }
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
                    "ok"
                }
            build(backend).call()
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `fails with Transport after two Transport errors`() =
        runTest {
            val executor = build(FakeBackend { throw DescriptionException.Transport() })
            assertFailsWith<DescriptionException.Transport> { executor.call() }
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
                    "ok"
                }
            assertEquals("ok", build(backend).call())
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `RateLimited does not retry`() =
        runTest {
            val backend = FakeBackend { throw DescriptionException.RateLimited() }
            val executor = build(backend)
            assertFailsWith<DescriptionException.RateLimited> { executor.call() }
            assertEquals(1, backend.calls.get())
        }

    @Test
    fun `Unauthorized does not retry and publishes LOST once per outage`() =
        runTest {
            val backend = FakeBackend { throw DescriptionException.Unauthorized("Not signed in") }
            val executor = build(backend)
            assertFailsWith<DescriptionException.Unauthorized> { executor.call() }
            assertFailsWith<DescriptionException.Unauthorized> { executor.call() }
            assertEquals(2, backend.calls.get())
            val lost = authEvents()
            assertEquals(1, lost.size)
            assertEquals(DescriptionProviderAuthEvent.State.LOST, lost.single().state)
            assertEquals("fake:model", lost.single().authScopeId)
            assertEquals("Not signed in", lost.single().detail)
            assertEquals("run fake-login", lost.single().recoveryHint)
        }

    @Test
    fun `a throwing listener does not discard a successful description`() =
        runTest {
            var unauthorized = true
            val backend =
                FakeBackend {
                    if (unauthorized) throw DescriptionException.Unauthorized("Not signed in") else "ok"
                }
            val executor = build(backend, eventPublisher = { throw IllegalStateException("listener is down") })

            assertFailsWith<DescriptionException.Unauthorized> { executor.call() }
            unauthorized = false
            assertEquals("ok", executor.call())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `work timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val executor =
                build(
                    FakeBackend {
                        gate.await()
                        "ok"
                    },
                    customLimits = limits.copy(timeout = Duration.ofMillis(500)),
                )
            val job = async { runCatching { executor.call() } }
            advanceTimeBy(1_000)
            advanceUntilIdle()
            assertFailsWith<DescriptionException.Timeout> { job.await().getOrThrow() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `queue timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val executor =
                build(
                    FakeBackend {
                        blocker.await()
                        "ok"
                    },
                    customLimits =
                        limits.copy(
                            maxConcurrent = 1,
                            queueTimeout = Duration.ofMillis(100),
                            timeout = Duration.ofSeconds(60),
                        ),
                )
            val first = async { runCatching { executor.call() } }
            advanceTimeBy(1)
            val second = async { runCatching { executor.call() } }
            advanceTimeBy(200)
            advanceUntilIdle()
            assertFailsWith<DescriptionException.Timeout> { second.await().getOrThrow() }
            blocker.complete(Unit)
            first.await()
        }

    @Test
    fun `CancellationException from the backend is not wrapped as Transport`() =
        runTest {
            val executor = build(FakeBackend { throw CancellationException("cancelled by caller") })
            assertFailsWith<CancellationException> { executor.call() }
        }

    /**
     * Пермит отпускается и когда отмена приходит уже после acquire (работа), не только по TCE
     * очереди. Иначе maxConcurrent слотов залипает навсегда.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a caller that holds the permit releases it for the next call`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var blocking = true
            val executor =
                build(
                    FakeBackend {
                        if (blocking) {
                            entered.complete(Unit)
                            delay(60_000)
                        }
                        "ok"
                    },
                    customLimits =
                        limits.copy(
                            maxConcurrent = 1,
                            queueTimeout = Duration.ofSeconds(5),
                        ),
                )
            val holder = launch { executor.call() }
            entered.await()
            holder.cancel()
            holder.join()
            blocking = false
            assertEquals("ok", executor.call())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a queued waiter does not leak a permit`() =
        runTest {
            val releaseHolder = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val executor =
                build(
                    FakeBackend {
                        if (entered.complete(Unit)) {
                            releaseHolder.await()
                        }
                        "ok"
                    },
                    customLimits = limits.copy(maxConcurrent = 1),
                )
            val holder = async { executor.call() }
            entered.await()
            val waiter = launch { executor.call() }
            runCurrent()
            waiter.cancel()
            waiter.join()
            releaseHolder.complete(Unit)
            assertEquals("ok", holder.await())
            assertEquals("ok", executor.call())
        }

    /**
     * Резолюция — один раз на вызов: повтор обязан идти в тот же пресет, что и первая попытка,
     * иначе лог одной записи назвал бы двух разных провайдеров, а стоимость вызова стала бы
     * непредсказуемой. Смена действует со следующего `execute`, а не задним числом.
     */
    @Test
    fun `the preset is resolved once per call, not per attempt`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            val other = FakeBackend { "ok" }
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        // Владелец переключает пресет ровно между попытками одного вызова.
                        settings.setActivePresetId("other", changedBy = "owner")
                        "not json"
                    } else {
                        "ok"
                    }
                }
            val executor = build(backend, extraPresets = listOf("other" to other), settings = settings)

            assertEquals("ok", executor.call())

            assertEquals(2, backend.calls.get(), "the retry must stay on the preset the call started with")
            assertEquals(0, other.calls.get(), "a mid-call switch must not move the retry to another provider")

            executor.call()
            assertEquals(1, other.calls.get(), "the next call does pick the new preset up")
        }

    /**
     * Чтение настроек — ввод-вывод, и оно обязано случиться ДО захвата пермита: иначе при
     * maxConcurrent=2 и зависшем пуле R2DBC оба слота заняли бы корутины, ждущие одно чтение, а
     * внешний withTimeout, который начинается позже, ничем бы не помог.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a slow settings read does not hold a permit`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val backend = FakeBackend { "ok" }
            val executor =
                build(backend, customLimits = limits.copy(maxConcurrent = 1), settings = GatedSettings(gate))

            val stuck = async { executor.call() }
            runCurrent()
            val overtaking = async { executor.call() }
            runCurrent()

            assertEquals(1, backend.calls.get(), "a call stuck reading the settings must not occupy the only permit")

            gate.complete(Unit)
            overtaking.await()
            stuck.await()
            assertEquals(2, backend.calls.get())
        }

    /**
     * Семафор ограничивает машину и расходы, а не пресет: два пресета делят те же пермиты, иначе
     * переключение множило бы число одновременных процессов провайдера.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the semaphore is shared across presets`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                FakeBackend {
                    entered.complete(Unit)
                    release.await()
                    "ok"
                }
            val other = FakeBackend { "ok" }
            val executor =
                build(
                    holder,
                    customLimits = limits.copy(maxConcurrent = 1),
                    extraPresets = listOf("other" to other),
                    settings = settings,
                )

            val first = async { executor.call() }
            entered.await()
            settings.setActivePresetId("other", changedBy = "owner")
            val second = async { executor.call() }
            runCurrent()

            assertEquals(0, other.calls.get(), "the single permit belongs to the machine, not to a preset")

            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, other.calls.get())
        }

    @Test
    fun `third call waits for semaphore permit with maxConcurrent=2`() =
        runTest {
            val inFlight = AtomicInteger()
            val maxSeen = AtomicInteger()
            val executor =
                build(
                    FakeBackend {
                        val current = inFlight.incrementAndGet()
                        maxSeen.updateAndGet { kotlin.math.max(it, current) }
                        delay(100)
                        inFlight.decrementAndGet()
                        "ok"
                    },
                )
            coroutineScope {
                repeat(3) { launch { executor.call() } }
            }
            // Ровно 2: и верхняя граница (лимит соблюдён), и нижняя (оба слота используются).
            assertEquals(2, maxSeen.get())
        }
}
