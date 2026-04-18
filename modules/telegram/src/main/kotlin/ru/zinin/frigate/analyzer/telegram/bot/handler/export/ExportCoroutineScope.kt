package ru.zinin.frigate.analyzer.telegram.bot.handler.export

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
 * Shared `CoroutineScope` used by `QuickExportHandler`, `ExportExecutor`, and `CancelExportHandler`
 * to launch long-running export coroutines independently of the bot's request-handler scope.
 *
 * Uses `Dispatchers.IO` (not `Default`) because:
 *  - Export coroutines call into blocking ffmpeg via `VideoMergeHelper.process.waitFor(...)`,
 *    which parks a worker thread.
 *  - Cancel fire-and-forget coroutines (`exportScope.launch { cancellable.cancel() }` in
 *    `ActiveExportRegistry.attachCancellable` and `CancelExportHandler`) must reach the vision
 *    server quickly — they can't be starved waiting for Default pool workers held by ffmpeg.
 *  - `IO` is sized for blocking work (default cap 64 threads); `Default` is CPU-bound
 *    (cores count), so it would bottleneck cancel paths under concurrent exports.
 *
 * Graceful shutdown via `@PreDestroy`: cancels in-flight exports, then blocks up to
 * [SHUTDOWN_TIMEOUT_MS] waiting for their `finally` blocks (temp-file cleanup, registry release,
 * fire-and-forget remote cancel) to complete. A short timeout is used because ffmpeg's blocking
 * `waitFor(...)` may keep a coroutine alive past the CE delivery — we don't want to hang JVM
 * shutdown indefinitely waiting for it.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
open class ExportCoroutineScope internal constructor(
    delegate: CoroutineScope,
) : CoroutineScope by delegate {
    // Production bean constructor — uses Dispatchers.IO + SupervisorJob. Tests may use the
    // internal delegate-taking constructor to inject a TestDispatcher-backed scope for virtual time.
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()))

    @PreDestroy
    open fun shutdown() {
        val job = coroutineContext[Job] ?: return
        runBlocking {
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) { job.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                logger.warn {
                    "Export coroutines did not finish cleanup within ${SHUTDOWN_TIMEOUT_MS}ms; forcing JVM shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 15_000L
    }
}
