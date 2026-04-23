package ru.zinin.frigate.analyzer.ai.description.config

import io.mockk.mockk
import org.junit.jupiter.api.Disabled
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import kotlin.test.Test

class AiDescriptionAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiDescriptionAutoConfiguration::class.java))
            .withUserConfiguration(TempFileWriterStubConfig::class.java)

    @Configuration
    class TempFileWriterStubConfig {
        @Bean
        fun tempFileWriter(): TempFileWriter = mockk(relaxed = true)
    }

    @Test
    fun `DescriptionProperties registered even when enabled=false`() {
        // Критично: facade инжектит DescriptionProperties безусловно — бин должен быть всегда.
        runner
            .withPropertyValues(
                "application.ai.description.enabled=false",
                "application.ai.description.provider=claude",
                "application.ai.description.common.language=en",
                "application.ai.description.common.short-max-length=200",
                "application.ai.description.common.detailed-max-length=1500",
                "application.ai.description.common.max-frames=10",
                "application.ai.description.common.queue-timeout=30s",
                "application.ai.description.common.timeout=60s",
                "application.ai.description.common.max-concurrent=2",
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.claude.model=opus",
                "application.ai.description.claude.cli-path=",
                "application.ai.description.claude.working-directory=/tmp",
                "application.ai.description.claude.proxy.http=",
                "application.ai.description.claude.proxy.https=",
                "application.ai.description.claude.proxy.no-proxy=",
            ).run { ctx ->
                assert(ctx.getBeansOfType(DescriptionProperties::class.java).isNotEmpty()) {
                    "DescriptionProperties must be available when enabled=false (facade inject)"
                }
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty()) {
                    "DescriptionAgent must NOT be registered when enabled=false"
                }
            }
    }

    @Test
    @Disabled("Re-enabled after Task 9 adds ClaudeDescriptionAgent — bean does not exist yet")
    fun `autoconfig activates beans when enabled=true, provider=claude`() {
        runner
            .withPropertyValues(
                "application.ai.description.enabled=true",
                "application.ai.description.provider=claude",
                "application.ai.description.common.language=en",
                "application.ai.description.common.short-max-length=200",
                "application.ai.description.common.detailed-max-length=1500",
                "application.ai.description.common.max-frames=10",
                "application.ai.description.common.queue-timeout=30s",
                "application.ai.description.common.timeout=60s",
                "application.ai.description.common.max-concurrent=2",
                "application.ai.description.claude.oauth-token=fake",
                "application.ai.description.claude.model=opus",
                "application.ai.description.claude.cli-path=",
                "application.ai.description.claude.working-directory=/tmp",
                "application.ai.description.claude.proxy.http=",
                "application.ai.description.claude.proxy.https=",
                "application.ai.description.claude.proxy.no-proxy=",
            ).run { ctx ->
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isNotEmpty()) {
                    "DescriptionAgent should be registered"
                }
            }
    }
}
