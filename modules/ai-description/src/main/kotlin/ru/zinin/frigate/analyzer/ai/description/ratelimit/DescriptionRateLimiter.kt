package ru.zinin.frigate.analyzer.ai.description.ratelimit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Sliding-window rate limiter for AI description requests.
 *
 * Caller logs on `false` return — the limiter intentionally stays domain-agnostic.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionRateLimiter(
    private val clock: Clock,
    descriptionProperties: DescriptionProperties,
) {
    private val rateLimit = descriptionProperties.common.rateLimit
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Instant>(rateLimit.maxRequests)

    init {
        if (rateLimit.enabled) {
            logger.info {
                "AI description rate limiter enabled: max=${rateLimit.maxRequests}, window=${rateLimit.window}"
            }
        } else {
            logger.info { "AI description rate limiter disabled (rate-limit.enabled=false)" }
        }
    }

    suspend fun tryAcquire(): Boolean {
        if (!rateLimit.enabled) return true

        return mutex.withLock {
            val now = clock.instant()
            val cutoff = now.minus(rateLimit.window)

            while (timestamps.isNotEmpty() && !timestamps.first().isAfter(cutoff)) {
                timestamps.removeFirst()
            }

            if (timestamps.size < rateLimit.maxRequests) {
                timestamps.addLast(now)
                true
            } else {
                false
            }
        }
    }
}
