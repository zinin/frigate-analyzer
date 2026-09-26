package ru.zinin.frigate.analyzer.ai.description.core

import java.time.Duration

data class VisionLimits(
    val queueTimeout: Duration,
    val timeout: Duration,
    val maxConcurrent: Int,
    /** 0 = фича кадры не ограничивает; потолок пресета при этом всё равно действует. */
    val maxImageSide: Int,
)
