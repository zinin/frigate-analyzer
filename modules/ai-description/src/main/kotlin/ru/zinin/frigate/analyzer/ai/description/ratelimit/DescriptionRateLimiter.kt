package ru.zinin.frigate.analyzer.ai.description.ratelimit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Clock

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionRateLimiter(
    clock: Clock,
    descriptionProperties: DescriptionProperties,
) : SlidingWindowRateLimiter("AI description", descriptionProperties.common.rateLimit, clock)
