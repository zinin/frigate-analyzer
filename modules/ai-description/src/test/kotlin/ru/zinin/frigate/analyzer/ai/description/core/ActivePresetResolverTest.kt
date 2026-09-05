package ru.zinin.frigate.analyzer.ai.description.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivePresetResolverTest {
    private fun backend(id: String) =
        object : VisionBackend {
            override val providerId = "grok"
            override val authScopeId = "grok:grok-4.6"
            override val authRecoveryHint = "hint"

            override suspend fun complete(request: VisionRequest) = id
        }

    private fun entry(
        id: String,
        available: Boolean = true,
    ) = DescriptionPresetCatalog.Entry(
        DescriptionPreset(
            id = id,
            provider = "grok",
            model = "grok-4.6",
            effectiveModel = "grok-4.6",
            effort = "low",
            authScopeId = "grok:grok-4.6",
            unavailableReason = if (available) null else UnavailableReason.NoToken,
        ),
        if (available) backend(id) else null,
    )

    private val catalog = DescriptionPresetCatalog(listOf(entry("fast"), entry("deep"), entry("broken", false)), "fast")

    /** Настройки, чьё чтение всегда падает: проверка fail-open и проброса отмены. */
    private class FailingSettings(
        private val failure: () -> Throwable,
    ) : DescriptionRuntimeSettings {
        override val sourceName = "failing settings"

        override suspend fun activePresetId(): String? = throw failure()

        override suspend fun setActivePresetId(
            id: String,
            changedBy: String?,
        ) = throw failure()

        override suspend fun descriptionsEnabled(): Boolean = throw failure()

        override suspend fun setDescriptionsEnabled(
            value: Boolean,
            changedBy: String?,
        ) = throw failure()
    }

    /** Настройки, чьё чтение не возвращается никогда: проверка потолка на чтение. */
    private class HangingSettings : DescriptionRuntimeSettings {
        private val never = CompletableDeferred<String?>()

        override val sourceName = "hanging settings"

        override suspend fun activePresetId(): String? = never.await()

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

    /** Логгер резолвера — файловый, поэтому слушаем корень и отбираем по уровню. */
    private suspend fun logsFrom(
        level: Level,
        block: suspend () -> Unit,
    ): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == level }.map { it.formattedMessage }
    }

    @Test
    fun `an absent setting resolves to the fallback`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, InMemoryDescriptionRuntimeSettings())

            assertEquals("fast", resolver.resolve().view.id)
        }

    @Test
    fun `a stored id wins`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("deep", changedBy = "owner")

            assertEquals("deep", ActivePresetResolver(catalog, settings).resolve().view.id)
        }

    @Test
    fun `an unknown stored id falls back`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("gone", changedBy = null)

            assertEquals("fast", ActivePresetResolver(catalog, settings).resolve().view.id)
        }

    @Test
    fun `an unavailable stored id falls back`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("broken", changedBy = null)

            assertEquals("fast", ActivePresetResolver(catalog, settings).resolve().view.id)
        }

    @Test
    fun `a blank stored id resolves to the fallback`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("  ", changedBy = null)
            val resolver = ActivePresetResolver(catalog, settings)

            assertEquals("fast", resolver.resolve().view.id)
            assertNull(resolver.storedId(), "a blank value means 'nothing chosen', not a broken preset id")
        }

    @Test
    fun `resolving twice keeps returning the same entry`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("gone", changedBy = null)
            val resolver = ActivePresetResolver(catalog, settings)

            assertEquals(resolver.resolve().view.id, resolver.resolve().view.id)
            assertEquals("fast", resolver.effective().id)
        }

    /**
     * Экран обязан различать выбор владельца и то, что реально работает: иначе `/ai` рисовал бы ✅ на
     * подменённом пресете, а сломанный id жил бы в `app_settings` вечно.
     */
    @Test
    fun `the stored id is reported unresolved`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("gone", changedBy = "owner")
            val resolver = ActivePresetResolver(catalog, settings)

            assertEquals("gone", resolver.storedId())
            assertEquals("fast", resolver.effective().id)
        }

    @Test
    fun `a failing read falls back instead of propagating`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, FailingSettings { IllegalStateException("pool is down") })

            val warnings = logsFrom(Level.WARN) { assertEquals("fast", resolver.resolve().view.id) }

            assertTrue(warnings.any { it.contains("pool is down") }, "the failure must be visible in the log: $warnings")
        }

    @Test
    fun `a failing read leaves the stored id unknown instead of propagating`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, FailingSettings { IllegalStateException("pool is down") })

            assertNull(resolver.storedId())
        }

    @Test
    fun `cancellation is rethrown, not swallowed`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, FailingSettings { CancellationException("shutting down") })

            assertFailsWith<CancellationException> { resolver.resolve() }
            assertFailsWith<CancellationException> { resolver.storedId() }
        }

    @Test
    fun `the same warning is logged once`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("gone", changedBy = null)
            val resolver = ActivePresetResolver(catalog, settings)

            val warnings =
                logsFrom(Level.WARN) {
                    repeat(3) { resolver.resolve() }
                }

            assertEquals(1, warnings.size, "one line per distinct problem, not one per recording: $warnings")
            assertTrue(warnings.single().contains("gone"))
        }

    @Test
    fun `the source of the active preset is logged once, naming the overridden default`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("deep", changedBy = "owner")
            val resolver = ActivePresetResolver(catalog, settings)

            val lines =
                logsFrom(Level.INFO) {
                    repeat(3) { resolver.resolve() }
                }.filter { it.startsWith("Active description preset") }

            assertEquals(1, lines.size, "the source is reported once, not per recording: $lines")
            assertTrue(lines.single().contains("'deep' (grok/grok-4.6/low)"), lines.single())
            assertTrue(lines.single().contains("from in-memory settings"), lines.single())
            assertTrue(lines.single().contains("overriding default-preset='fast'"), lines.single())
        }

    /**
     * Переключение пресета в `/ai` рестарта не требует, а запись настроек кладёт в лог только id и
     * только на DEBUG. Без этой строки после переключения ни одна строка лога не называет работающую
     * модель — то есть лог перестаёт отвечать на вопрос «что работает сейчас» ровно тогда, когда его
     * задают.
     */
    @Test
    fun `a switched preset is logged again`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("deep", changedBy = "owner")
            val resolver = ActivePresetResolver(catalog, settings)

            val lines =
                logsFrom(Level.INFO) {
                    resolver.resolve()
                    settings.setActivePresetId("fast", changedBy = "owner")
                    resolver.resolve()
                }.filter { it.startsWith("Active description preset") }

            assertEquals(2, lines.size, "the switch must leave a trace: $lines")
            assertTrue(lines[0].contains("'deep' (grok/grok-4.6/low)"), lines[0])
            assertTrue(lines[1].contains("'fast' (grok/grok-4.6/low)"), lines[1])
        }

    /** Строка на КАЖДУЮ запись превратила бы INFO в шум: она печатается на смену, а не на вызов. */
    @Test
    fun `an unchanged preset is logged once however often it resolves`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("deep", changedBy = "owner")
            val resolver = ActivePresetResolver(catalog, settings)

            val lines =
                logsFrom(Level.INFO) {
                    repeat(5) { resolver.resolve() }
                }.filter { it.startsWith("Active description preset") }

            assertEquals(1, lines.size, "one line per change, not per resolution: $lines")
        }

    @Test
    fun `the default preset is named as the source when nothing is stored`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, InMemoryDescriptionRuntimeSettings())

            val lines =
                logsFrom(Level.INFO) { resolver.resolve() }.filter { it.startsWith("Active description preset") }

            assertEquals(1, lines.size)
            assertTrue(lines.single().endsWith("from default-preset"), lines.single())
        }

    /**
     * Агент зовёт резолвер вне обоих своих `withTimeout`, поэтому потолок на чтение — единственное,
     * что отделяет зависшую БД от подвисшего `describe`. Истечение потолка обязано дать пресет по
     * умолчанию и предупреждение, а не отмену вызова и не `DescriptionException.Timeout`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read that never returns falls back on the bound`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, HangingSettings())
            val startedAt = testScheduler.currentTime

            val warnings =
                logsFrom(Level.WARN) {
                    assertEquals("fast", resolver.resolve().view.id)
                    assertNull(resolver.storedId(), "storedId has the same exposure through the /ai screen")
                }

            assertEquals(1, warnings.size, "one line per distinct problem: $warnings")
            assertTrue(warnings.single().contains("timed out"), warnings.single())
            assertEquals(
                10_000,
                testScheduler.currentTime - startedAt,
                "both reads are bounded, 5 s each, and the wait is virtual",
            )
        }

    @Test
    fun `the resolved entry carries the backend of its preset`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("deep", changedBy = null)

            val entry = ActivePresetResolver(catalog, settings).resolve()

            assertSame(catalog.byId("deep")?.backend, entry.backend)
        }
}
