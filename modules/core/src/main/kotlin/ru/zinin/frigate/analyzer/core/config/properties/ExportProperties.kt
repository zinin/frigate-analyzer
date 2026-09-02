package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.export")
@Validated
data class ExportProperties(
    @field:Valid
    val compress: CompressProperties = CompressProperties(),
)

/** Tunables of the budget-driven re-encode that fits an export into the Telegram size limit. */
data class CompressProperties(
    /** libx264 preset: speed versus compression on the host CPU. */
    @field:NotBlank
    val preset: String = "fast",
    /** libx264 quality target; the bitrate cap derived from the budget still applies. */
    @field:Min(0)
    @field:Max(51)
    val crf: Int = 23,
    /** Smallest bits-per-pixel a candidate height may have before the next smaller one is tried. */
    @field:Positive
    val minBitsPerPixel: Double = 0.1,
)
