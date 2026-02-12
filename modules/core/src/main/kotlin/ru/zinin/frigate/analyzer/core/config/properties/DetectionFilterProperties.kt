package ru.zinin.frigate.analyzer.core.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.detection-filter")
@Validated
data class DetectionFilterProperties(
    val enabled: Boolean = true,
    val allowedClasses: List<String> = emptyList(),
)
