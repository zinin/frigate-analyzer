package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

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
 * Graceful shutdown via `@PreDestroy`: any in-flight export coroutines receive a
 * `CancellationException`, their `finally` blocks (temp-file cleanup, registry release) run.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportCoroutineScope : CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {
    @PreDestroy
    fun shutdown() {
        cancel()
    }
}
