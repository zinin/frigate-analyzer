package ru.zinin.frigate.analyzer.ai.description.core

import java.time.Duration

data class VisionLimits(
    val queueTimeout: Duration,
    val timeout: Duration,
    val maxConcurrent: Int,
    /** 0 = фича кадры не ограничивает; потолок пресета при этом всё равно действует. */
    val maxImageSide: Int,
    /**
     * Потолок длинной стороны картинок с ролью [VisionImage.Role.STORYBOARD]. `null` — своего потолка
     * у сетки нет, она подчиняется [maxImageSide], как кадры; `0` — фича сетку не ограничивает, но
     * потолок пресета действует и тогда.
     */
    val storyboardMaxImageSide: Int? = null,
)
