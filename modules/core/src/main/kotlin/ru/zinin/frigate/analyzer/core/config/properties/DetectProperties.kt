package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.detect")
@Validated
data class DetectProperties(
    val retryDelay: Duration = Duration.ofMillis(500),
    val frameTimeout: Duration = Duration.ofSeconds(60),
    val frameExtractionTimeout: Duration = Duration.ofMinutes(5),
    val visualizeTimeout: Duration = Duration.ofSeconds(60),
    val healthCheckTimeout: Duration = Duration.ofSeconds(5),
    val healthCheckInterval: Duration = Duration.ofSeconds(30),
    @field:Min(0)
    @field:Max(1)
    val defaultConfidence: Double = 0.6,
    @field:Min(320)
    val defaultImgSize: Int = 2016,
    val defaultModel: String = "yolo26s.pt",
    val goodModel: String = "yolo26x.pt",
    @field:Valid
    val frameExtraction: FrameExtractionConfig = FrameExtractionConfig(),
    @field:Valid
    val visualize: VisualizeConfig = VisualizeConfig(),
)

data class FrameExtractionConfig(
    @field:Min(0)
    @field:Max(1)
    val sceneThreshold: Double = 0.05,
    @field:Min(0)
    val minInterval: Double = 1.0,
    @field:Min(1)
    val maxFrames: Int = 50,
    @field:Min(1)
    @field:Max(100)
    val quality: Int = 85,
)

data class VisualizeConfig(
    @field:Min(1)
    val maxDet: Int = 100,
    @field:Min(1)
    val lineWidth: Int = 2,
    val showLabels: Boolean = true,
    val showConf: Boolean = true,
    @field:Min(1)
    @field:Max(100)
    val quality: Int = 90,
)
