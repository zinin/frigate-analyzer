package ru.zinin.frigate.analyzer.core.judge

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
 * Scope for judge-jobs kicked off from NotificationJudgeService.
 *
 * SupervisorJob isolates errors between jobs. @PreDestroy cancels pending jobs
 * at shutdown with a short grace window.
 *
 * Gated on `application.ai.judge.enabled=true`: Task 7 injects this scope only when
 * the judge bean exists. Unlike [ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope],
 * the facade does not take it as a required constructor parameter.
 */
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
open class JudgeCoroutineScope internal constructor(
    delegate: CoroutineScope,
) : CoroutineScope by delegate {
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()))

    @PreDestroy
    open fun shutdown() {
        val job = coroutineContext[Job] ?: return
        runBlocking {
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) { job.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                logger.warn {
                    "Judge coroutines did not finish within ${SHUTDOWN_TIMEOUT_MS}ms; forcing shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 10_000L
    }
}
