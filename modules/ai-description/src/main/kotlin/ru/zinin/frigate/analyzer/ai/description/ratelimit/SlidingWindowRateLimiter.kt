package ru.zinin.frigate.analyzer.ai.description.ratelimit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Sliding-window rate limiter. Каждый экземпляр держит своё окно: лимиты описаний и судьи
 * не делят счётчик.
 *
 * Caller logs on `false` return — the limiter intentionally stays domain-agnostic.
 */
open class SlidingWindowRateLimiter(
    private val name: String,
    private val rateLimit: DescriptionProperties.RateLimit,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Instant>(rateLimit.maxRequests)

    init {
        if (rateLimit.enabled) {
            logger.info { "$name rate limiter enabled: max=${rateLimit.maxRequests}, window=${rateLimit.window}" }
        } else {
            logger.info { "$name rate limiter disabled (rate-limit.enabled=false)" }
        }
    }

    suspend fun tryAcquire(): Boolean {
        if (!rateLimit.enabled) return true

        return mutex.withLock {
            val now = clock.instant()
            val cutoff = now.minus(rateLimit.window)

            // Drop timestamps where timestamp <= cutoff (i.e. !isAfter(cutoff)).
            // A timestamp exactly at the cutoff (now − window) is treated as OUT of the window —
            // boundary tests in SlidingWindowRateLimiterTest pin this contract.
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
