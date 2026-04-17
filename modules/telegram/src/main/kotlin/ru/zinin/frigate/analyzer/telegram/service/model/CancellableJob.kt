package ru.zinin.frigate.analyzer.telegram.service.model

/**
 * Abstraction over cancelling a remote vision-server job.
 *
 * Published from `VideoVisualizationService` via `onJobSubmitted` once the job is accepted
 * by a vision server. Consumed by `CancelExportHandler` via `ActiveExportRegistry`.
 *
 * Hides `AcquiredServer` inside the core module, so the telegram module does not need to
 * reference core/loadbalancer types.
 */
fun interface CancellableJob {
    suspend fun cancel()
}
