package ru.zinin.frigate.analyzer.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import ru.zinin.frigate.analyzer.core.config.properties.DetectionFilterProperties
import ru.zinin.frigate.analyzer.core.config.properties.LocalVisualizationProperties
import ru.zinin.frigate.analyzer.core.config.properties.PipelineProperties

private val logger = KotlinLogging.logger {}

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(
    ApplicationProperties::class,
    DetectionFilterProperties::class,
    DetectProperties::class,
    PipelineProperties::class,
    LocalVisualizationProperties::class,
)
@EnableR2dbcRepositories(basePackages = ["ru.zinin.frigate.analyzer.service.repository", "ru.zinin.frigate.analyzer.telegram.repository"])
class FrigateAnalyzerApplication : CommandLineRunner {
    override fun run(vararg args: String) {
        logger.info { "Starting the application..." }
    }
}

fun main(args: Array<String>) {
    runApplication<FrigateAnalyzerApplication>(*args)
}
