package ru.zinin.frigate.analyzer.telegram.queue

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Managed scope for Telegram-layer description edit jobs. Analog of `ExportCoroutineScope`.
 *
 * Conditional on `application.ai.description.enabled=true` — without descriptions, edit jobs
 * don't run at all. Kept separate from the core-layer `DescriptionCoroutineScope` because
 * the describe lifecycle lives in `core` while the edit lifecycle lives in `telegram`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
open class DescriptionEditScope internal constructor(
    delegate: CoroutineScope,
) : CoroutineScope by delegate {
    // Production bean constructor — uses Dispatchers.IO + SupervisorJob. Tests may use the
    // internal delegate-taking constructor via `forTest(...)` to inject a TestDispatcher-backed scope.
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()))

    @PreDestroy
    open fun shutdown() {
        val job = coroutineContext[Job] ?: return
        runBlocking {
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) { job.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                logger.warn {
                    "Description edit coroutines did not finish within ${SHUTDOWN_TIMEOUT_MS}ms; forcing shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 10_000L

        fun forTest(scope: CoroutineScope): DescriptionEditScope = DescriptionEditScope(scope)
    }
}
