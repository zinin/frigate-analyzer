package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.pipeline")
@Validated
data class PipelineProperties(
    @field:Valid
    val frame: FramePipelineConfig = FramePipelineConfig(),
    @field:Valid
    val producer: ProducerConfig = ProducerConfig(),
)

data class FramePipelineConfig(
    @field:Min(1)
    val channelBufferSize: Int = 500,
    @field:Min(1)
    val minConsumers: Int = 1,
    @field:Min(1)
    val producersCount: Int = 6,
)

data class ProducerConfig(
    val idleDelay: Duration = Duration.ofSeconds(1),
    val errorDelay: Duration = Duration.ofSeconds(5),
    @field:Min(1)
    val batchSize: Int = 10,
)
