package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated

@Validated
data class RequestConfig(
    /**
     * Number of simultaneous requests of this type that the server can handle
     */
    @field:Min(1)
    val simultaneousCount: Int,
    /**
     * Priority for this request type (lower value means higher priority)
     */
    @field:Min(0)
    val priority: Int,
)
