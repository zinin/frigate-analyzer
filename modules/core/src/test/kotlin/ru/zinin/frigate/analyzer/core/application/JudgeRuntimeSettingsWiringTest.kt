package ru.zinin.frigate.analyzer.core.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.config.AiDescriptionAutoConfiguration
import ru.zinin.frigate.analyzer.ai.description.core.InMemoryJudgeRuntimeSettings
import ru.zinin.frigate.analyzer.core.FrigateAnalyzerApplication
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проводка SPI рантайм-настроек судьи. Автоконфигурация `ai-description` регистрирует in-memory-дефолт
 * под `@ConditionalOnMissingBean`, поэтому ошибка в условиях бина из `core` ничего не ломает:
 * приложение стартует, `/ai` работает, а выбор владельца молча пропадает на каждом рестарте. Ассерт
 * на тип бина — единственное, что такую подмену удержит.
 *
 * `ApplicationContextRunner`, а не `@SpringBootTest`: нужна ровно автоконфигурация фичи и её условия,
 * без R2DBC и testcontainers — та же причина, что у `DescriptionRuntimeSettingsWiringTest`.
 */
class JudgeRuntimeSettingsWiringTest {
    @TempDir
    lateinit var tempDir: Path

    /**
     * Швы, которые в проде приходят извне модуля `ai-description`: [TempFileWriter] — из
     * `TempFileWriterAdapter`, `ObjectMapper` — из автоконфигурации Jackson, `Clock` — из
     * `:frigate-analyzer-common`. `AppSettingsService` подменён моком: тест о проводке, а не о БД.
     */
    @Configuration
    class SpiStubConfig {
        @Bean
        fun tempFileWriter(): TempFileWriter = mockk(relaxed = true)

        @Bean
        fun objectMapper(): JsonMapper = TestObjectMappers.internalMapper()

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun appSettingsService(): AppSettingsService = mockk(relaxed = true)
    }

    private val runner =
        ApplicationContextRunner()
            // Порядок как в проде: пользовательские бины сначала, автоконфигурация после —
            // именно на этом держится отступление `@ConditionalOnMissingBean`.
            .withUserConfiguration(
                SpiStubConfig::class.java,
                AppSettingsDescriptionRuntimeSettings::class.java,
                AppSettingsJudgeRuntimeSettings::class.java,
            ).withConfiguration(AutoConfigurations.of(AiDescriptionAutoConfiguration::class.java))

    /** Полный набор свойств модуля в стиле application.yaml: контекст раннера сам yaml не читает. */
    private fun properties(): Array<String> =
        arrayOf(
            "application.ai.description.enabled=true",
            "application.ai.description.provider=",
            "application.ai.description.common.language=en",
            "application.ai.description.common.short-max-length=200",
            "application.ai.description.common.detailed-max-length=1500",
            "application.ai.description.common.max-frames=10",
            "application.ai.description.common.queue-timeout=30s",
            "application.ai.description.common.timeout=60s",
            "application.ai.description.common.max-concurrent=2",
            "application.ai.description.common.rate-limit.enabled=false",
            "application.ai.description.common.rate-limit.max-requests=10",
            "application.ai.description.common.rate-limit.window=1h",
            "application.ai.description.claude.oauth-token=",
            "application.ai.description.claude.model=opus",
            "application.ai.description.claude.cli-path=",
            "application.ai.description.claude.working-directory=/tmp",
            "application.ai.description.claude.proxy.http=",
            "application.ai.description.claude.proxy.https=",
            "application.ai.description.claude.proxy.no-proxy=",
            "application.ai.description.grok.cli-path=${tempDir.resolve("missing-grok")}",
            "application.ai.description.grok.model=grok-4.6",
            "application.ai.description.grok.effort=low",
            "application.ai.description.grok.home=${tempDir.resolve("grok-home")}",
            "application.ai.description.grok.working-directory=${tempDir.resolve("grok-cwd")}",
            "application.ai.description.grok.proxy.http=",
            "application.ai.description.grok.proxy.https=",
            "application.ai.description.grok.proxy.no-proxy=",
            "application.ai.description.presets.grok-fast.provider=grok",
            "application.ai.description.presets.grok-fast.model=grok-4.6",
            "application.ai.description.presets.grok-fast.effort=low",
            "application.ai.judge.enabled=true",
            "application.ai.judge.default-preset=grok-fast",
        )

    @Test
    fun `the app_settings implementation wins over the in-memory default`() {
        runner
            .withPropertyValues(*properties())
            .run { context ->
                assertThat(context).hasNotFailed()
                // Дефолт не просто проиграл разрешение по типу — его нет в контексте вовсе.
                assertThat(context).doesNotHaveBean(InMemoryJudgeRuntimeSettings::class.java)
                val bean = context.getBean(JudgeRuntimeSettings::class.java)
                assertIs<AppSettingsJudgeRuntimeSettings>(bean)
                assertEquals("app_settings", bean.sourceName)

                val appSettings = context.getBean(AppSettingsService::class.java)
                coEvery { appSettings.getBoolean(AppSettingKeys.AI_JUDGE_ENABLED, true) } returns false
                runBlocking {
                    assertFalse(bean.judgeEnabled())
                }
                coVerify { appSettings.getBoolean(AppSettingKeys.AI_JUDGE_ENABLED, true) }
            }
    }

    /**
     * Второй способ потерять реализацию — вынести её из-под сканирования: пакет вне корня
     * `@SpringBootApplication` или потерянный стереотип. Контекст-тест этого не увидит (бин в нём
     * зарегистрирован явно), поэтому условие сканирования проверяется отдельно и напрямую.
     */
    @Test
    fun `the implementation stays inside the component-scanned tree`() {
        val type = AppSettingsJudgeRuntimeSettings::class.java
        assertNotNull(type.getAnnotation(Service::class.java), "the implementation must stay a component")
        val scanRoot = FrigateAnalyzerApplication::class.java.packageName
        assertTrue(
            type.packageName.startsWith("$scanRoot."),
            "expected a package under $scanRoot, but was ${type.packageName}",
        )
    }
}
