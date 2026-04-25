package ru.zinin.frigate.analyzer.ai.description.ratelimit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class MutableClock(
    initial: Instant,
) : Clock() {
    @Volatile var current: Instant = initial

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)

    override fun instant(): Instant = current
}

class DescriptionRateLimiterTest {
    private val baseInstant = Instant.parse("2026-04-25T12:00:00Z")

    private fun props(
        enabled: Boolean,
        maxRequests: Int,
        window: Duration = Duration.ofHours(1),
    ) = DescriptionProperties(
        enabled = true,
        provider = "claude",
        common =
            DescriptionProperties.CommonSection(
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
                maxFrames = 10,
                queueTimeout = Duration.ofSeconds(30),
                timeout = Duration.ofSeconds(60),
                maxConcurrent = 2,
                rateLimit = DescriptionProperties.RateLimit(enabled = enabled, maxRequests = maxRequests, window = window),
            ),
    )

    @Test
    fun `disabled rate-limit always returns true`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = false, maxRequests = 1))
            repeat(100) {
                assertTrue(limiter.tryAcquire(), "iteration $it should be allowed when disabled")
            }
        }

    @Test
    fun `under limit allows`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 3))
            assertTrue(limiter.tryAcquire())
            assertTrue(limiter.tryAcquire())
            assertTrue(limiter.tryAcquire())
        }

    @Test
    fun `at limit blocks fourth call within same window`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 3))
            repeat(3) { assertTrue(limiter.tryAcquire()) }
            assertFalse(limiter.tryAcquire(), "4th call must be denied within window")
        }

    @Test
    fun `boundary - t plus window minus 1ms still blocks`() =
        runBlocking {
            // Implementation drops timestamps when `!timestamp.isAfter(cutoff)`,
            // i.e. timestamp <= cutoff. cutoff = now - window.
            // At now = t + window - 1ms, cutoff = t - 1ms, timestamp = t > cutoff -> keep -> block.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 1))
            assertTrue(limiter.tryAcquire())

            clock.current = baseInstant.plus(Duration.ofHours(1)).minusMillis(1)
            assertFalse(limiter.tryAcquire(), "old timestamp still inside window -> must block")
        }

    @Test
    fun `boundary - exactly t plus window releases slot`() =
        runBlocking {
            // At now = t + window, cutoff = t. timestamp = t, !t.isAfter(t) = !false = true -> drop.
            // Documented design choice: a timestamp older or equal to cutoff is OUT of the window.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 1))
            assertTrue(limiter.tryAcquire())

            clock.current = baseInstant.plus(Duration.ofHours(1))
            assertTrue(limiter.tryAcquire(), "old timestamp == cutoff -> dropped -> new slot free")
            assertFalse(limiter.tryAcquire(), "deque full again")
        }

    @Test
    fun `boundary - t plus window plus 1ms releases slot`() =
        runBlocking {
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 1))
            assertTrue(limiter.tryAcquire())

            clock.current = baseInstant.plus(Duration.ofHours(1)).plusMillis(1)
            assertTrue(limiter.tryAcquire(), "well past cutoff -> slot free")
            assertFalse(limiter.tryAcquire(), "now full again")
        }

    @Test
    fun `concurrent acquisitions never exceed limit`() =
        runBlocking {
            // Use Dispatchers.Default to get real thread parallelism — without it,
            // runBlocking single-threaded event-loop would serialize coroutines and the test
            // would pass even if Mutex were absent, providing false confidence.
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 10))

            val results: List<Boolean> =
                coroutineScope {
                    (1..50)
                        .map { async(Dispatchers.Default) { limiter.tryAcquire() } }
                        .awaitAll()
                }

            assertEquals(10, results.count { it }, "exactly 10 must succeed")
            assertEquals(40, results.count { !it }, "exactly 40 must be rejected")
        }

    @Test
    fun `cleanup keeps deque bounded - never grows past maxRequests across many iterations`() =
        runBlocking {
            // Move time forward by window + 1ms before EACH call so every prior timestamp
            // becomes <= cutoff and is dropped. If cleanup were broken, the deque would grow
            // and after maxRequests calls the limiter would start denying — the test would
            // fail. Using `(i+1)*window+1ms` guarantees strict monotonic progress.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 5))
            val window = Duration.ofHours(1)

            repeat(100) { i ->
                clock.current = baseInstant.plus(window.multipliedBy((i + 1).toLong())).plusMillis(1)
                assertTrue(limiter.tryAcquire(), "iteration $i should always succeed because window slid")
            }
        }

    @Test
    fun `rate-limit window must be positive - validated at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            DescriptionProperties.RateLimit(enabled = true, maxRequests = 10, window = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DescriptionProperties.RateLimit(enabled = true, maxRequests = 10, window = Duration.ofSeconds(-1))
        }
    }
}
