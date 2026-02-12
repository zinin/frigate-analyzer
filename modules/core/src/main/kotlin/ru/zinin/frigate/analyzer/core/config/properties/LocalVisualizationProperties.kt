package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.local-visualization")
@Validated
data class LocalVisualizationProperties(
    @field:Min(1)
    val lineWidth: Int = 2,
    @field:Min(1)
    @field:Max(100)
    val quality: Int = 90,
    @field:Min(1)
    val referenceHeight: Int = 720,
    @field:Min(0)
    val minFontScale: Float = 0.5f,
    @field:Min(0)
    val maxFontScale: Float = 2.2f,
    @field:Min(0)
    val baseFontScale: Float = 2.0f,
    @field:Min(1)
    val baseFontSize: Int = 16,
    @field:Min(0)
    val labelPadding: Int = 4,
    @field:Min(1)
    val maxFrames: Int = 10,
)
