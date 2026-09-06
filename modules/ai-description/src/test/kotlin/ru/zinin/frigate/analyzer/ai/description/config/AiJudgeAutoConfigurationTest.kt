package ru.zinin.frigate.analyzer.ai.description.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.zinin.frigate.analyzer.ai.description.api.ActiveJudgePreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.JudgeAgent
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalog
import ru.zinin.frigate.analyzer.ai.description.core.InMemoryJudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.JudgePresetResolver
import ru.zinin.frigate.analyzer.ai.description.core.VisionCallExecutor
import ru.zinin.frigate.analyzer.ai.description.ratelimit.JudgeRateLimiter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AiJudgeAutoConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiDescriptionAutoConfiguration::class.java))
            .withUserConfiguration(AiDescriptionAutoConfigurationTest.TestStubConfig::class.java)

    /**
     * Тот же набор свойств модуля, что в [AiDescriptionAutoConfigurationTest]: обе провайдерские
     * секции биндятся всегда, grok.home указывает в @TempDir.
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
    fun `judge beans exist when judge is enabled and both vision executors are named`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                "application.ai.judge.enabled=true",
                "application.ai.judge.default-preset=claude",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertNotNull(context.getBean(JudgeAgent::class.java))
                assertNotNull(context.getBean(DescriptionAgent::class.java))
                assertNotNull(context.getBean(ActiveJudgePreset::class.java))
                assertNotNull(context.getBean(JudgeRateLimiter::class.java))
                assertIs<InMemoryJudgeRuntimeSettings>(context.getBean(JudgeRuntimeSettings::class.java))
                assertEquals(2, context.getBeansOfType(VisionCallExecutor::class.java).size)
                assertNotNull(context.getBean("descriptionVisionCallExecutor", VisionCallExecutor::class.java))
                assertNotNull(context.getBean("judgeVisionCallExecutor", VisionCallExecutor::class.java))
            }
    }

    @Test
    fun `judge beans are absent when judge is disabled but JudgeProperties still binds`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                "application.ai.judge.enabled=false",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(JudgeAgent::class.java)
                assertThat(context).doesNotHaveBean(ActiveJudgePreset::class.java)
                assertThat(context).doesNotHaveBean(JudgeRateLimiter::class.java)
                assertThat(context).doesNotHaveBean(JudgeRuntimeSettings::class.java)
                assertThat(context.getBeansOfType(JudgeProperties::class.java)).isNotEmpty()
            }
    }

    @Test
    fun `an unknown judge default-preset fails startup`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                "application.ai.judge.enabled=true",
                "application.ai.judge.default-preset=missing",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("judge default-preset 'missing'")
            }
    }

    @Test
    fun `blank judge default-preset uses the description catalog fallback`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                "application.ai.judge.enabled=true",
                "application.ai.judge.default-preset=",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val catalog = context.getBean(DescriptionPresetCatalog::class.java)
                val resolver = context.getBean(JudgePresetResolver::class.java)
                assertEquals(catalog.fallbackId, resolver.resolver.fallbackId)
            }
    }

    @Test
    fun `an unusable judge default-preset fails startup`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.presets.claude-opus.provider=claude",
                "application.ai.description.presets.claude-opus.model=opus",
                "application.ai.description.presets.grok-fast.provider=grok",
                "application.ai.description.presets.grok-fast.model=grok-4.6",
                "application.ai.judge.enabled=true",
                "application.ai.judge.default-preset=claude-opus",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("judge default-preset 'claude-opus' is unavailable")
            }
    }
}
