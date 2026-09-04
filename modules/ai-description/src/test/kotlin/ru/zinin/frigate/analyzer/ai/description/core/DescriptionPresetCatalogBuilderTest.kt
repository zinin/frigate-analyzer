package ru.zinin.frigate.analyzer.ai.description.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptionPresetCatalogBuilderTest {
    private class FakeFactory(
        override val providerId: String,
        private val availability: DescriptionBackendFactory.Availability =
            DescriptionBackendFactory.Availability.Available,
        private val displacedModel: String? = null,
    ) : DescriptionBackendFactory {
        var availabilityCalls: Int = 0
            private set
        val createdFor: MutableList<String> = mutableListOf()

        override fun availability(): DescriptionBackendFactory.Availability {
            availabilityCalls++
            return availability
        }

        override fun effectiveModel(preset: DescriptionProperties.Preset): String = displacedModel ?: preset.model

        override fun authScopeId(preset: DescriptionProperties.Preset): String = "$providerId:${preset.model}"

        override fun create(preset: DescriptionProperties.Preset): DescriptionBackend {
            createdFor += preset.model
            return object : DescriptionBackend {
                override val providerId = this@FakeFactory.providerId
                override val authRecoveryHint = "hint"

                override suspend fun describe(request: DescriptionRequest): DescriptionResult =
                    DescriptionResult(preset.model, preset.effort)
            }
        }
    }

    private val grok = DescriptionProperties.Preset(provider = "grok", model = "grok-4.6", effort = "low")
    private val grokDeep = DescriptionProperties.Preset(provider = "grok", model = "grok-4.6", effort = "xhigh")
    private val grokMax = DescriptionProperties.Preset(provider = "grok", model = "grok-4.6", effort = "max")
    private val claude = DescriptionProperties.Preset(provider = "claude", model = "opus")
    private val claudeSonnet = DescriptionProperties.Preset(provider = "claude", model = "sonnet")

    private fun tokenlessClaude() = FakeFactory("claude", DescriptionBackendFactory.Availability.Unavailable(UnavailableReason.NoToken))

    private fun build(
        presets: Map<String, DescriptionProperties.Preset>,
        factories: List<DescriptionBackendFactory>,
        defaultPreset: String = "",
        timeout: Duration = Duration.ofSeconds(120),
    ): DescriptionPresetCatalogBuilder.Result =
        DescriptionPresetCatalogBuilder.build(
            presets = presets,
            defaultPreset = defaultPreset,
            factories = factories,
            timeout = timeout,
        )

    private fun catalogOf(result: DescriptionPresetCatalogBuilder.Result): DescriptionPresetCatalog =
        assertIs<DescriptionPresetCatalogBuilder.Result.Catalog>(result).catalog

    /** Логгер билдера — файловый, поэтому слушаем корень и отбираем по уровню. */
    private fun warningsFrom(block: () -> Unit): List<String> {
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
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `declaration order is preserved and the default preset wins`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("grok-fast" to grok, "claude-opus" to claude),
                    factories = listOf(FakeFactory("grok"), FakeFactory("claude")),
                    defaultPreset = "claude-opus",
                ),
            )

        assertEquals(listOf("grok-fast", "claude-opus"), catalog.all().map { it.id })
        assertEquals("claude-opus", catalog.fallbackId)
        assertEquals("claude-opus", catalog.fallback().view.id)
        assertNull(catalog.all().first().unavailableReason)
        assertTrue(catalog.all().all { it.available })
    }

    @Test
    fun `the first usable preset in declaration order wins a blank default`() {
        val catalog =
            catalogOf(
                build(
                    presets =
                        linkedMapOf(
                            // Объявлен первым, но непригоден.
                            "zulu-claude" to claude,
                            // Первый годный по объявлению.
                            "mike-grok" to grokDeep,
                            // Первый по алфавиту — побеждать не должен.
                            "alpha-grok" to grok,
                        ),
                    factories = listOf(tokenlessClaude(), FakeFactory("grok")),
                ),
            )

        assertEquals(listOf("zulu-claude", "mike-grok", "alpha-grok"), catalog.all().map { it.id })
        assertEquals("mike-grok", catalog.fallbackId)
    }

    @Test
    fun `an unusable preset stays listed with a typed reason and without a backend`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("claude-opus" to claude, "grok-fast" to grok),
                    factories = listOf(tokenlessClaude(), FakeFactory("grok")),
                ),
            )

        assertEquals(UnavailableReason.NoToken, catalog.all().first().unavailableReason)
        assertEquals(false, catalog.all().first().available)
        assertNull(assertNotNull(catalog.byId("claude-opus")).backend)
        assertNotNull(assertNotNull(catalog.byId("grok-fast")).backend)
    }

    @Test
    fun `an unusable default falls back to a usable preset`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("claude-opus" to claude, "grok-fast" to grok),
                    factories = listOf(tokenlessClaude(), FakeFactory("grok")),
                    defaultPreset = "claude-opus",
                ),
            )

        assertEquals("grok-fast", catalog.fallbackId)
    }

    @Test
    fun `no presets means no catalog`() {
        assertIs<DescriptionPresetCatalogBuilder.Result.NoPresets>(
            build(presets = emptyMap(), factories = listOf(FakeFactory("grok"))),
        )
    }

    @Test
    fun `presets that are all unusable report every preset with its reason`() {
        val result =
            build(
                presets = linkedMapOf("claude-opus" to claude, "claude-sonnet" to claudeSonnet),
                factories = listOf(tokenlessClaude()),
            )

        val message = assertIs<DescriptionPresetCatalogBuilder.Result.NoneUsable>(result).message
        assertTrue(message.contains("claude-opus"), message)
        assertTrue(message.contains("claude-sonnet"), message)
        assertTrue(message.contains("NoToken"), message)
    }

    @Test
    fun `a preset whose provider has no factory is unusable`() {
        val result = build(presets = linkedMapOf("grok-fast" to grok), factories = emptyList())

        val message = assertIs<DescriptionPresetCatalogBuilder.Result.NoneUsable>(result).message
        assertTrue(message.contains("grok-fast"), message)
        assertTrue(message.contains("NoFactory"), message)
    }

    @Test
    fun `a missing factory does not hide the presets that do have one`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("claude-opus" to claude, "grok-fast" to grok),
                    factories = listOf(FakeFactory("grok")),
                ),
            )

        assertEquals(UnavailableReason.NoFactory("claude"), catalog.byId("claude-opus")?.view?.unavailableReason)
        assertEquals("grok-fast", catalog.fallbackId)
    }

    /**
     * Фабрики осматривают окружение внутри [DescriptionBackendFactory.availability], поэтому спрашивать
     * её у провайдера, которого нет ни в одном пресете, нельзя: claude-деплой не должен создавать
     * каталоги Grok. Один раз на провайдер, а не на пресет — иначе осмотр повторяется впустую.
     */
    @Test
    fun `availability is asked once per declared provider and never for an absent one`() {
        val grokFactory = FakeFactory("grok")
        val claudeFactory = FakeFactory("claude")

        build(
            presets = linkedMapOf("grok-fast" to grok, "grok-deep" to grokDeep),
            factories = listOf(grokFactory, claudeFactory),
        )

        assertEquals(1, grokFactory.availabilityCalls)
        assertEquals(0, claudeFactory.availabilityCalls)
    }

    @Test
    fun `every preset gets its own backend`() {
        val grokFactory = FakeFactory("grok")

        val catalog =
            catalogOf(
                build(
                    presets =
                        linkedMapOf(
                            "grok-fast" to grok,
                            "grok-deep" to DescriptionProperties.Preset(provider = "grok", model = "grok-code"),
                        ),
                    factories = listOf(grokFactory),
                ),
            )

        assertEquals(listOf("grok-4.6", "grok-code"), grokFactory.createdFor)
        assertTrue(catalog.byId("grok-fast")?.backend !== catalog.byId("grok-deep")?.backend)
    }

    /**
     * `ANTHROPIC_MODEL` вытесняет модель из пресета, поэтому экран обязан показывать ту, что уйдёт
     * в запрос: два пресета с разными `model` иначе выглядят разными, а зовут одно и то же.
     */
    @Test
    fun `the effective model comes from the factory`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("claude-opus" to claude),
                    factories = listOf(FakeFactory("claude", displacedModel = "gpt-5-via-gateway")),
                ),
            )

        val view = assertNotNull(catalog.byId("claude-opus")).view
        assertEquals("opus", view.model)
        assertEquals("gpt-5-via-gateway", view.effectiveModel)
    }

    @Test
    fun `the auth scope comes from the factory and falls back to the provider id`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("grok-fast" to grok, "claude-opus" to claude),
                    factories = listOf(FakeFactory("grok")),
                ),
            )

        assertEquals("grok:grok-4.6", catalog.byId("grok-fast")?.view?.authScopeId)
        assertEquals("claude", catalog.byId("claude-opus")?.view?.authScopeId)
    }

    /**
     * `grok-4.6 xhigh` съедает ~48 с из 60 с, поэтому транспортный повтор (10 с бюджета плюс 5 с
     * паузы) не успевает начаться, а повтор после невалидного ответа гибнет от внешнего таймаута —
     * честный `InvalidResponse` превращается в обманчивый `Timeout`.
     */
    @Test
    fun `a slow effort under the recommended timeout warns about the retry budget`() {
        val warnings =
            warningsFrom {
                build(
                    presets = linkedMapOf("grok-deep" to grokDeep, "grok-top" to grokMax, "grok-fast" to grok),
                    factories = listOf(FakeFactory("grok")),
                    timeout = Duration.ofSeconds(60),
                )
            }

        val deep = assertNotNull(warnings.singleOrNull { it.contains("grok-deep") }, warnings.toString())
        assertTrue(deep.contains("effort=xhigh"), deep)
        assertTrue(deep.contains("timeout=60s"), deep)
        assertTrue(deep.contains("APP_AI_DESCRIPTION_TIMEOUT=120s"), deep)
        assertNotNull(warnings.singleOrNull { it.contains("grok-top") }, warnings.toString())
        assertTrue(warnings.none { it.contains("grok-fast") }, warnings.toString())
    }

    @Test
    fun `a slow effort at the recommended timeout does not warn`() {
        val warnings =
            warningsFrom {
                build(
                    presets = linkedMapOf("grok-deep" to grokDeep),
                    factories = listOf(FakeFactory("grok")),
                    timeout = Duration.ofSeconds(120),
                )
            }

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a fast effort under a short timeout does not warn`() {
        val warnings =
            warningsFrom {
                build(
                    presets = linkedMapOf("grok-fast" to grok),
                    factories = listOf(FakeFactory("grok")),
                    timeout = Duration.ofSeconds(30),
                )
            }

        assertEquals(emptyList(), warnings)
    }
}
