package ru.zinin.frigate.analyzer.core.config

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
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Scope for describe-jobs kicked off from RecordingProcessingFacade.
 *
 * SupervisorJob isolates errors between jobs. @PreDestroy cancels pending jobs
 * at shutdown with a short grace window.
 *
 * Scope is created unconditionally (no @ConditionalOnProperty). Facade injects it
 * as a required constructor parameter; when enabled=false the agentProvider returns
 * null and the scope simply idles (idle SupervisorJob is cheap). Removing conditionality
 * avoids NoSuchBeanDefinitionException at startup with default enabled=false.
 */
@Component
open class DescriptionCoroutineScope internal constructor(
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
                    "Description coroutines did not finish within ${SHUTDOWN_TIMEOUT_MS}ms; forcing shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 10_000L
    }
}
