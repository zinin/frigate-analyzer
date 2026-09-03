package ru.zinin.frigate.analyzer.ai.description.config

import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.claude.ClaudeAsyncClientFactory
import ru.zinin.frigate.analyzer.ai.description.claude.ClaudeBackend
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

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
     * в @TempDir, чтобы GrokBackend.init не создавал каталоги в /tmp/frigate-analyzer.
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
            .withPropertyValues(*properties(enabled = true, provider = "claude"))
            .run { ctx ->
                assert(ctx.getBean(DescriptionAgent::class.java) is DefaultDescriptionAgent) {
                    "the agent must be the provider-neutral DefaultDescriptionAgent"
                }
                assert(ctx.getBeansOfType(ClaudeBackend::class.java).isNotEmpty()) {
                    "ClaudeBackend should be registered for provider=claude"
                }
                // Строка в стиле application.yaml должна привязаться к DataSize: это единственное
                // место, где реальный старт может упасть, а полный build в CI его не проверяет.
                assertEquals(DataSize.ofMegabytes(32), ctx.getBean(ClaudeProperties::class.java).maxBufferSize)
            }
    }

    @Test
    fun `unknown provider registers neither backend nor agent and does not fail startup`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "unknown"))
            .run { ctx ->
                assert(ctx.startupFailure == null) { "unknown provider must not break startup: ${ctx.startupFailure}" }
                assert(ctx.getBeansOfType(DescriptionBackend::class.java).isEmpty())
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty())
                assert(ctx.getBeansOfType(ClaudeAsyncClientFactory::class.java).isEmpty()) {
                    "Claude helpers must be gated on provider=claude"
                }
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
