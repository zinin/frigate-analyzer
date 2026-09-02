package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
    @field:Pattern(regexp = LIBX264_PRESETS, message = "must be a libx264 preset name")
    val preset: String = "fast",
    /** libx264 quality target; the bitrate cap derived from the budget still applies. */
    @field:Min(0)
    @field:Max(51)
    val crf: Int = 23,
    /** Smallest bits-per-pixel a candidate height may have before the next smaller one is tried. */
    @field:Positive
    val minBitsPerPixel: Double = 0.1,
) {
    companion object {
        /** Every name libx264 accepts; a typo would otherwise surface only on the first oversized export. */
        const val LIBX264_PRESETS = "ultrafast|superfast|veryfast|faster|fast|medium|slow|slower|veryslow|placebo"
    }
}
