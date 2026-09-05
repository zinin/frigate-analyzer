package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/**
 * Мини-RW-lock на корутинах для GROK_HOME: запуски `grok` берут [shared], sweeper берёт
 * [exclusive]. Если уже есть in-flight `grok`, `exclusive` бросает [ExclusiveBusyException] сразу —
 * этот час пропускается, а начатые описания не ждут уборки. Обратный порядок платный: запуск,
 * начавшийся во время уборки, ждёт её конца на мьютексе (иначе `grok` писал бы свою сессию в
 * каталог, который в этот момент удаляют). `shared` держит мьютекс только на инкремент счётчика,
 * поэтому запуски друг друга не ждут.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
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
            if (inFlight.get() > 0) {
                throw ExclusiveBusyException(inFlight.get())
            }
            block()
        }

    class ExclusiveBusyException(
        inFlight: Int,
    ) : IllegalStateException("GROK_HOME exclusive skipped: $inFlight grok run(s) in flight")
}
