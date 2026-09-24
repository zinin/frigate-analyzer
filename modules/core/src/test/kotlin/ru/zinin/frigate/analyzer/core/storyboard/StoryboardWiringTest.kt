package ru.zinin.frigate.analyzer.core.storyboard

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties
import ru.zinin.frigate.analyzer.core.config.properties.RequestConfig
import ru.zinin.frigate.analyzer.core.config.properties.StoryboardProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import ru.zinin.frigate.analyzer.core.video.VideoProbe
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.Test

/**
 * Проводка бинов раскадровки. Они существуют только при `application.ai.description.enabled=true`
 * (спека, 5.7), и ни один другой тест их контекстом не поднимает: сломанный граф конструкторов или
 * потерянное условие всплыли бы только на старте прода с включёнными описаниями.
 *
 * `ApplicationContextRunner`, а не `@SpringBootTest`: нужны ровно пакет раскадровки и его условия, без
 * R2DBC и testcontainers — та же причина, что у `DescriptionRuntimeSettingsWiringTest`.
 */
class StoryboardWiringTest {
    /**
     * Пакет раскадровки и его швы наружу: ffprobe, ffmpeg, временные файлы и база — моки, тест о проводке,
     * а не о них. `Clock` в проде приходит из `:frigate-analyzer-common`.
     */
    @Configuration
    @ComponentScan(basePackageClasses = [StoryboardBuilder::class])
    @EnableConfigurationProperties(StoryboardProperties::class)
    class StoryboardScanConfig {
        @Bean
        fun videoProbe(): VideoProbe = mockk(relaxed = true)

        @Bean
        fun ffmpegProcessRunner(): FfmpegProcessRunner = mockk(relaxed = true)

        @Bean
        fun tempFileHelper(): TempFileHelper = mockk(relaxed = true)

        /**
         * Настоящий объект, не мок: `@EnableConfigurationProperties` привязывает и валидирует каждый бин с
         * `@ConfigurationProperties`, а поля мока MockK пусты — `@NotNull` уронил бы контекст. Ради той же
         * валидации `detectServers` не пуст (`@NotEmpty`).
         */
        @Bean
        fun applicationProperties(): ApplicationProperties {
            val requests = RequestConfig(simultaneousCount = 1, priority = 0)
            val server =
                DetectServerProperties(
                    host = "localhost",
                    frameRequests = requests,
                    framesExtractRequests = requests,
                    visualizeRequests = requests,
                )
            return ApplicationProperties(
                tempFolder = Path.of("/tmp/frigate-analyzer-test"),
                ffmpegPath = Path.of("/usr/bin/ffmpeg"),
                connectionTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
                writeTimeout = Duration.ofSeconds(5),
                responseTimeout = Duration.ofSeconds(5),
                detectServers = mapOf("vision" to server),
            )
        }

        @Bean
        fun recordingEntityService(): RecordingEntityService = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(StoryboardScanConfig::class.java)

    @Test
    fun `with descriptions on the context holds each storyboard bean once`() {
        runner
            .withPropertyValues("application.ai.description.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(AdjacentSegmentFinder::class.java)
                assertThat(context).hasSingleBean(StoryboardFrameSampler::class.java)
                assertThat(context).hasSingleBean(StoryboardComposer::class.java)
                assertThat(context).hasSingleBean(StoryboardBuilder::class.java)
            }
    }

    @Test
    fun `with descriptions off or unset the context holds no storyboard bean`() {
        listOf(emptyArray<String>(), arrayOf("application.ai.description.enabled=false")).forEach { properties ->
            runner
                .withPropertyValues(*properties)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).doesNotHaveBean(AdjacentSegmentFinder::class.java)
                    assertThat(context).doesNotHaveBean(StoryboardFrameSampler::class.java)
                    assertThat(context).doesNotHaveBean(StoryboardComposer::class.java)
                    assertThat(context).doesNotHaveBean(StoryboardBuilder::class.java)
                }
        }
    }
}
