package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated

@Validated
data class RequestConfig(
    /**
     * Количество одновременных запросов данного типа, которые сервер может обрабатывать
     */
    @field:Min(1)
    val simultaneousCount: Int,
    /**
     * Приоритет для данного типа запросов (чем меньше, тем выше приоритет)
     */
    @field:Min(0)
    val priority: Int,
)
