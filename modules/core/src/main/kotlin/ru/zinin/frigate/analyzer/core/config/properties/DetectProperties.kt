package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
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
    @field:Valid
    val videoVisualize: VideoVisualizeConfig = VideoVisualizeConfig(),
)

/**
 * The client half of the `/extract/frames` contract of vision-api 3.0, which selects frames by
 * motion: a grid every [maxGap] seconds plus every frame whose largest changed region exceeds
 * [motionThreshold] of the frame area.
 *
 * Every range below is the server's own. Outside it the server answers 422, which
 * `FrameExtractorProducer` records as processed-with-error — so a typo in the environment has to
 * fail the boot instead of burying recordings one by one.
 */
data class FrameExtractionConfig(
    @field:DecimalMin("0.5")
    @field:DecimalMax("30.0")
    val maxGap: Double = 4.0,
    @field:DecimalMin("0.0001")
    @field:DecimalMax("0.1")
    val motionThreshold: Double = 0.001,
    @field:DecimalMin("0.1")
    @field:DecimalMax("30.0")
    val minInterval: Double = 1.0,
    /** A cap, not a target: the server returns what the motion metric found, up to this many. */
    @field:Min(1)
    @field:Max(200)
    val maxFrames: Int = 6,
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

data class VideoVisualizeConfig(
    // Must stay below Telegram QuickExport/Export annotated outer timeouts (50m each) so that
    // an annotation timeout surfaces as DetectTimeoutException with a dedicated user message
    // instead of being masked by the outer withTimeoutOrNull.
    val timeout: Duration = Duration.ofMinutes(45),
    val cancelTimeout: Duration = Duration.ofSeconds(10),
    val pollInterval: Duration = Duration.ofSeconds(3),
    @field:Min(1)
    val maxDet: Int = 100,
    @field:Min(1)
    val detectEvery: Int? = null,
    @field:Min(1)
    val lineWidth: Int = 2,
    val showLabels: Boolean = true,
    val showConf: Boolean = true,
)
