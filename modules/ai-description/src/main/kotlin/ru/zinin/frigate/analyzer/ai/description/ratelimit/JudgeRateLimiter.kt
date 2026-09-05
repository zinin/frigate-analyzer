package ru.zinin.frigate.analyzer.ai.description.ratelimit

import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import java.time.Clock

class JudgeRateLimiter(
    clock: Clock,
    judgeProperties: JudgeProperties,
) : SlidingWindowRateLimiter("AI judge", judgeProperties.rateLimit, clock)
