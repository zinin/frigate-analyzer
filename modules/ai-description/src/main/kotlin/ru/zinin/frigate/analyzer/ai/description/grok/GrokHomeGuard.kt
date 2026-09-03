package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/**
 * Мини-RW-lock на корутинах для GROK_HOME: запуски `grok` берут [shared], sweeper берёт
 * [exclusive]. `exclusive` держит мьютекс, чем не пускает новые запуски, и ждёт, пока текущие
 * не завершатся, но не дольше 60 с — иначе бросает [ExclusiveWaitTimeoutException], и sweeper
 * пропускает этот час. `shared` берёт мьютекс только
 * на инкремент счётчика, поэтому запуски друг друга не ждут.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokHomeGuard {
    private val mutex = Mutex()
    private val inFlight = AtomicInteger(0)

    suspend fun <T> shared(block: suspend () -> T): T {
        mutex.withLock { inFlight.incrementAndGet() }
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T =
        mutex.withLock {
            var waited = 0L
            while (inFlight.get() > 0) {
                if (waited >= EXCLUSIVE_WAIT_TIMEOUT_MS) {
                    throw ExclusiveWaitTimeoutException(EXCLUSIVE_WAIT_TIMEOUT_MS)
                }
                delay(DRAIN_POLL_MS)
                waited += DRAIN_POLL_MS
            }
            block()
        }

    class ExclusiveWaitTimeoutException(
        timeoutMs: Long,
    ) : IllegalStateException("GROK_HOME exclusive wait timed out after ${timeoutMs}ms")

    private companion object {
        const val DRAIN_POLL_MS = 100L
        const val EXCLUSIVE_WAIT_TIMEOUT_MS = 60_000L
    }
}
