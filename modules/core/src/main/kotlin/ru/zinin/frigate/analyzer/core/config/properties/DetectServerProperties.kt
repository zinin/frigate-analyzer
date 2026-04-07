package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.validation.annotation.Validated

@Validated
data class DetectServerProperties(
    @field:NotEmpty
    val schema: String = "http",
    @field:NotEmpty
    val host: String,
    @field:Min(1)
    @field:Max(65535)
    val port: Int = 80,
    /**
     * Configuration for frame detection requests
     */
    @field:NotNull
    @field:Valid
    val frameRequests: RequestConfig,
    /**
     * Configuration for frame extraction requests
     */
    @field:NotNull
    @field:Valid
    val framesExtractRequests: RequestConfig,
    /**
     * Configuration for visualization requests
     */
    @field:NotNull
    @field:Valid
    val visualizeRequests: RequestConfig,
    /**
     * Configuration for video visualization requests
     */
    @field:NotNull
    @field:Valid
    val videoVisualizeRequests: RequestConfig = RequestConfig(simultaneousCount = 1, priority = 1),
) {
    fun buildUrl(): String = "$schema://$host:$port"
}
