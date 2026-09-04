package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
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
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
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
    private companion object {
        /** Слушатель занят дольше, чем параллельному вызову нужно, чтобы обогнать публикацию. */
        const val LISTENER_DELAY_MS = 200L
    }

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

    /**
     * Первое чтение настроек ждёт ворот, остальные мгновенны: так видно, держит ли вызов пермит,
     * пока читает настройки.
     */
    private class GatedSettings(
        private val firstRead: CompletableDeferred<Unit>,
    ) : DescriptionRuntimeSettings {
        private val reads = AtomicInteger()

        override val sourceName = "gated settings"

        override suspend fun activePresetId(): String? {
            if (reads.incrementAndGet() == 1) firstRead.await()
            return null
        }

        override suspend fun setActivePresetId(
            id: String,
            changedBy: String?,
        ) = Unit

        override suspend fun descriptionsEnabled(): Boolean = true

        override suspend fun setDescriptionsEnabled(
            value: Boolean,
            changedBy: String?,
        ) = Unit
    }

    private fun build(
        backend: FakeBackend,
        customCommon: DescriptionProperties.CommonSection = common,
        timeSource: TimeSource = TimeSource.Monotonic,
        eventPublisher: ApplicationEventPublisher = publisher,
        // Пресет по умолчанию — "test" с этим backend; остальные нужны только тестам про резолюцию.
        extraPresets: List<Pair<String, DescriptionBackend>> = emptyList(),
        settings: DescriptionRuntimeSettings = InMemoryDescriptionRuntimeSettings(),
    ) = DefaultDescriptionAgent(
        resolver = ActivePresetResolver(catalogOf("test" to backend, *extraPresets.toTypedArray()), settings),
        descriptionProperties = DescriptionProperties(enabled = true, provider = "fake", common = customCommon),
        eventPublisher = eventPublisher,
        timeSource = timeSource,
    )

    /** По пресету на backend; первый объявленный — пресет по умолчанию, он же fallback каталога. */
    private fun catalogOf(vararg backends: Pair<String, DescriptionBackend>): DescriptionPresetCatalog =
        DescriptionPresetCatalog(
            backends.map { (id, backend) ->
                DescriptionPresetCatalog.Entry(
                    DescriptionPreset(
                        id = id,
                        provider = backend.providerId,
                        model = "$id-model",
                        effectiveModel = "$id-model",
                        effort = "",
                        authScopeId = backend.providerId,
                        unavailableReason = null,
                    ),
                    backend,
                )
            },
            fallbackId = backends.first().first,
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
    fun `a slow listener cannot reorder concurrent auth transitions`() =
        // Реальные потоки, не виртуальное время: проверяется сериализация перехода с публикацией.
        runBlocking(Dispatchers.IO) {
            val delivered = Collections.synchronizedList(mutableListOf<DescriptionProviderAuthEvent.State>())
            val lostListenerEntered = CountDownLatch(1)
            val slowListener =
                ApplicationEventPublisher { event ->
                    val state = (event as DescriptionProviderAuthEvent).state
                    if (state == DescriptionProviderAuthEvent.State.LOST) {
                        lostListenerEntered.countDown()
                        Thread.sleep(LISTENER_DELAY_MS)
                    }
                    delivered.add(state)
                }
            val entered = AtomicInteger()
            val backend =
                FakeBackend {
                    if (entered.incrementAndGet() == 1) throw DescriptionException.Unauthorized("Not signed in")
                    // Успех попадает ровно в окно, где отказ уже переключил состояние, но ещё публикуется.
                    lostListenerEntered.await()
                    ok
                }
            val agent = build(backend, eventPublisher = slowListener)

            val first = launch { runCatching { agent.describe(request) } }
            val second = launch { runCatching { agent.describe(request) } }
            first.join()
            second.join()

            assertEquals(
                listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
                delivered.toList(),
            )
        }

    @Test
    fun `a throwing listener does not swallow the transition`() =
        runTest {
            val delivered = mutableListOf<DescriptionProviderAuthEvent>()
            val brokenListener =
                ApplicationEventPublisher { event ->
                    delivered.add(event as DescriptionProviderAuthEvent)
                    throw IllegalStateException("listener is down")
                }
            val backend = FakeBackend { throw DescriptionException.Unauthorized("Not signed in") }
            val agent = build(backend, eventPublisher = brokenListener)

            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }
            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }

            // Состояние откатилось, поэтому владелец узнает об отказе на следующей попытке, а не никогда.
            assertEquals(2, delivered.size)
            assertTrue(delivered.all { it.state == DescriptionProviderAuthEvent.State.LOST })
        }

    @Test
    fun `a throwing listener does not discard a successful description`() =
        runTest {
            var unauthorized = true
            val backend =
                FakeBackend {
                    if (unauthorized) throw DescriptionException.Unauthorized("Not signed in") else ok
                }
            val agent = build(backend, eventPublisher = { throw IllegalStateException("listener is down") })

            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }
            unauthorized = false
            assertEquals(ok, agent.describe(request))
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

    /**
     * Резолюция — один раз на вызов: повтор обязан идти в тот же пресет, что и первая попытка,
     * иначе лог одной записи назвал бы двух разных провайдеров, а стоимость вызова стала бы
     * непредсказуемой. Смена действует со следующего `describe`, а не задним числом.
     */
    @Test
    fun `the preset is resolved once per call, not per attempt`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            val other = FakeBackend { ok }
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        // Владелец переключает пресет ровно между попытками одного вызова.
                        settings.setActivePresetId("other", changedBy = "owner")
                        throw DescriptionException.InvalidResponse()
                    }
                    ok
                }
            val agent = build(backend, extraPresets = listOf("other" to other), settings = settings)

            assertEquals(ok, agent.describe(request))

            assertEquals(2, backend.calls.get(), "the retry must stay on the preset the call started with")
            assertEquals(0, other.calls.get(), "a mid-call switch must not move the retry to another provider")

            agent.describe(request)
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
            val backend = FakeBackend { ok }
            val agent = build(backend, customCommon = common.copy(maxConcurrent = 1), settings = GatedSettings(gate))

            val stuck = async { agent.describe(request) }
            runCurrent()
            val overtaking = async { agent.describe(request) }
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
                    ok
                }
            val other = FakeBackend { ok }
            val agent =
                build(
                    holder,
                    customCommon = common.copy(maxConcurrent = 1),
                    extraPresets = listOf("other" to other),
                    settings = settings,
                )

            val first = async { agent.describe(request) }
            entered.await()
            settings.setActivePresetId("other", changedBy = "owner")
            val second = async { agent.describe(request) }
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
