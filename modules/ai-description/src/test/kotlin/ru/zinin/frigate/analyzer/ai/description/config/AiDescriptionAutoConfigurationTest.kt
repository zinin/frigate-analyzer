package ru.zinin.frigate.analyzer.ai.description.config

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.claude.ClaudeBackend
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalog
import ru.zinin.frigate.analyzer.ai.description.grok.GrokBackend
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AiDescriptionAutoConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiDescriptionAutoConfiguration::class.java))
            .withUserConfiguration(TestStubConfig::class.java)

    @Configuration
    class TestStubConfig {
        // TempFileWriter is an SPI — in production provided by the core module.
        @Bean
        fun tempFileWriter(): TempFileWriter = mockk(relaxed = true)

        // ObjectMapper is provided by Spring Boot's JacksonAutoConfiguration in production
        // (via spring-boot-jackson on the runtime classpath of the main application).
        // This module does not depend on spring-boot-jackson, so we supply a plain mapper here.
        // Return type is tools.jackson JsonMapper so Spring registers the bean as a
        // tools.jackson.databind.ObjectMapper (its supertype).
        @Bean
        fun objectMapper(): JsonMapper = TestObjectMappers.internalMapper()

        // Clock is provided in production by `:frigate-analyzer-common`'s ClockConfig.
        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    /**
     * Полный набор свойств модуля в стиле application.yaml. Обе провайдерские секции биндятся
     * всегда, поэтому присутствуют при любом provider; grok.home и working-directory указывают
     * в @TempDir, чтобы осмотр окружения в GrokBackendFactory не создавал каталоги в
     * /tmp/frigate-analyzer.
     */
    private fun properties(
        enabled: Boolean,
        provider: String,
        rateLimitEnabled: Boolean = false,
        grokModel: String = "grok-4.6",
    ): Array<String> =
        arrayOf(
            "application.ai.description.enabled=$enabled",
            "application.ai.description.provider=$provider",
            "application.ai.description.common.language=en",
            "application.ai.description.common.short-max-length=200",
            "application.ai.description.common.detailed-max-length=1500",
            "application.ai.description.common.max-frames=10",
            "application.ai.description.common.queue-timeout=30s",
            "application.ai.description.common.timeout=60s",
            "application.ai.description.common.max-concurrent=2",
            "application.ai.description.common.rate-limit.enabled=$rateLimitEnabled",
            "application.ai.description.common.rate-limit.max-requests=10",
            "application.ai.description.common.rate-limit.window=1h",
            "application.ai.description.claude.oauth-token=fake",
            "application.ai.description.claude.model=opus",
            "application.ai.description.claude.cli-path=",
            "application.ai.description.claude.working-directory=/tmp",
            "application.ai.description.claude.proxy.http=",
            "application.ai.description.claude.proxy.https=",
            "application.ai.description.claude.proxy.no-proxy=",
            "application.ai.description.claude.anthropic.auth-token=",
            "application.ai.description.claude.anthropic.base-url=",
            "application.ai.description.claude.anthropic.model-override=",
            "application.ai.description.claude.anthropic.default-opus-model=",
            "application.ai.description.claude.anthropic.default-sonnet-model=",
            "application.ai.description.claude.anthropic.default-haiku-model=",
            "application.ai.description.claude.max-buffer-size=32MB",
            "application.ai.description.grok.cli-path=${tempDir.resolve("missing-grok")}",
            "application.ai.description.grok.model=$grokModel",
            "application.ai.description.grok.effort=low",
            "application.ai.description.grok.home=${tempDir.resolve("grok-home")}",
            "application.ai.description.grok.working-directory=${tempDir.resolve("grok-cwd")}",
            "application.ai.description.grok.proxy.http=",
            "application.ai.description.grok.proxy.https=",
            "application.ai.description.grok.proxy.no-proxy=",
        )

    private fun catalog(context: AssertableApplicationContext): DescriptionPresetCatalog =
        context.getBean(DescriptionPresetCatalog::class.java)

    @Test
    fun `DescriptionProperties registered even when enabled=false`() {
        // Критично: facade инжектит DescriptionProperties безусловно — бин должен быть всегда.
        runner
            .withPropertyValues(*properties(enabled = false, provider = "claude"))
            .run { ctx ->
                assert(ctx.getBeansOfType(DescriptionProperties::class.java).isNotEmpty()) {
                    "DescriptionProperties must be available when enabled=false (facade inject)"
                }
                assert(ctx.getBeansOfType(GrokProperties::class.java).isNotEmpty()) {
                    "GrokProperties binds regardless of provider"
                }
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty()) {
                    "DescriptionAgent must NOT be registered when enabled=false"
                }
            }
    }

    @Test
    fun `autoconfig activates beans when enabled=true, provider=claude`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                // Модель непохожа ни на дефолт, ни на модель grok-секции: подстановка чужого поля
                // или потеря синтеза тогда не пройдёт мимо ассерта.
                "application.ai.description.claude.model=claude-legacy-model",
            ).run { ctx ->
                assert(ctx.getBean(DescriptionAgent::class.java) is DefaultDescriptionAgent) {
                    "the agent must be the provider-neutral DefaultDescriptionAgent"
                }
                // Backend больше не бин: он живёт в каталоге, по одному на пресет.
                val catalog = catalog(ctx)
                assertIs<ClaudeBackend>(assertNotNull(catalog.byId("claude")).backend)
                // Legacy-путь синтезирует пресет из секции провайдера — здесь живёт обещание
                // обратной совместимости, поэтому проверяются значения, а не только id.
                val preset = catalog.all().single()
                assertEquals("claude", preset.id)
                assertEquals("claude", preset.provider)
                assertEquals("claude-legacy-model", preset.model)
                assertEquals("", preset.effort, "claude has no effort; a non-empty one would be a configuration error")
                // Строка в стиле application.yaml должна привязаться к DataSize: это единственное
                // место, где реальный старт может упасть, а полный build в CI его не проверяет.
                assertEquals(DataSize.ofMegabytes(32), ctx.getBean(ClaudeProperties::class.java).maxBufferSize)
            }
    }

    @Test
    fun `autoconfig builds a grok preset when provider=grok`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                // Модель и effort намеренно не равны ни дефолтам, ни друг другу: потерянное или
                // перепутанное поле синтеза тогда не пройдёт мимо ассерта.
                "application.ai.description.grok.model=grok-code-fast",
                "application.ai.description.grok.effort=xhigh",
            ).run { ctx ->
                assert(ctx.startupFailure == null) { "grok context must start: ${ctx.startupFailure}" }
                assert(ctx.getBean(DescriptionAgent::class.java) is DefaultDescriptionAgent)
                // Коллаборанты обоих провайдеров теперь существуют при enabled=true; изоляция
                // провайдеров переехала на уровень каталога — в нём только объявленные пресеты.
                val catalog = catalog(ctx)
                assertIs<GrokBackend>(assertNotNull(catalog.byId("grok")).backend)
                // Legacy-путь переносит в пресет обе настройки grok-секции, а не только модель.
                val preset = catalog.all().single()
                assertEquals("grok", preset.id)
                assertEquals("grok", preset.provider)
                assertEquals("grok-code-fast", preset.model)
                assertEquals("xhigh", preset.effort)
            }
    }

    @Test
    fun `unknown provider registers neither catalog nor agent and does not fail startup`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "unknown"))
            .run { ctx ->
                assert(ctx.startupFailure == null) { "unknown provider must not break startup: ${ctx.startupFailure}" }
                assert(ctx.getBeansOfType(DescriptionPresetCatalog::class.java).isEmpty())
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty())
            }
    }

    @Test
    fun `DescriptionRateLimiter bean registered when ai-description and rate-limit both enabled`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "claude", rateLimitEnabled = true))
            .run { ctx ->
                assert(ctx.getBeansOfType(DescriptionRateLimiter::class.java).isNotEmpty()) {
                    "DescriptionRateLimiter must be registered when ai-description.enabled=true (regardless of rate-limit.enabled)"
                }
            }
    }

    @Test
    fun `two presets give two usable entries and the default preset wins`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                "application.ai.description.default-preset=grok-deep",
                "application.ai.description.presets.grok-fast.provider=grok",
                "application.ai.description.presets.grok-fast.model=grok-4.6",
                "application.ai.description.presets.grok-fast.effort=low",
                "application.ai.description.presets.grok-deep.provider=grok",
                "application.ai.description.presets.grok-deep.model=grok-4.6",
                "application.ai.description.presets.grok-deep.effort=xhigh",
            ).run { context ->
                val catalog = catalog(context)
                assertEquals(listOf("grok-fast", "grok-deep"), catalog.all().map { it.id })
                assertEquals("grok-deep", catalog.fallbackId)
                assertEquals(2, catalog.all().count { it.available })
                assertNotNull(context.getBean(DescriptionAgent::class.java))
            }
    }

    @Test
    fun `a claude preset without a token stays listed while grok keeps working`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.presets.claude-opus.provider=claude",
                "application.ai.description.presets.claude-opus.model=opus",
                "application.ai.description.presets.grok-fast.provider=grok",
                "application.ai.description.presets.grok-fast.model=grok-4.6",
            ).run { context ->
                val catalog = catalog(context)
                assertEquals(UnavailableReason.NoToken, catalog.all().first { it.id == "claude-opus" }.unavailableReason)
                assertNull(assertNotNull(catalog.byId("claude-opus")).backend)
                assertEquals("grok-fast", catalog.fallbackId)
                assertNotNull(context.getBean(DescriptionAgent::class.java))
            }
    }

    @Test
    fun `a single unusable preset fails the startup`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.presets.claude-opus.provider=claude",
                "application.ai.description.presets.claude-opus.model=opus",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("No usable description preset")
            }
    }

    @Test
    fun `an unknown legacy provider without presets leaves the agent out`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "gemini"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DescriptionAgent::class.java)
                assertThat(context).doesNotHaveBean(DescriptionPresetCatalog::class.java)
            }
    }

    /**
     * Обещание обратной совместимости: `@ConditionalOnProperty(havingValue = "claude")` сравнивал
     * без учёта регистра, поэтому работающий деплой с `APP_AI_DESCRIPTION_PROVIDER=CLAUDE` обязан
     * получить claude-пресет и после перехода на каталог.
     */
    @Test
    fun `a mixed-case legacy provider still activates its provider`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "ClAuDe"))
            .run { context ->
                assertThat(context).hasNotFailed()
                val catalog = catalog(context)
                assertEquals(listOf("claude"), catalog.all().map { it.id })
                assertIs<ClaudeBackend>(assertNotNull(catalog.byId("claude")).backend)
            }
    }

    @Test
    fun `blank grok model fails binding even for provider=claude`() {
        // GrokProperties биндится всегда: пустой GROK_MODEL валит старт любого деплоя,
        // ровно как пустой CLAUDE_MODEL. Тест делает это свойство явным.
        runner
            .withPropertyValues(*properties(enabled = true, provider = "claude", grokModel = ""))
            .run { ctx ->
                assert(ctx.startupFailure != null) { "blank grok.model must fail validation" }
            }
    }
}
