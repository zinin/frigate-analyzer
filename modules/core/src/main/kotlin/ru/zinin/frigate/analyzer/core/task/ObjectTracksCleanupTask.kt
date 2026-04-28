package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.ObjectTrackerService

private val logger = KotlinLogging.logger {}

@Component
class ObjectTracksCleanupTask(
    private val tracker: ObjectTrackerService,
) {
    /**
     * Hourly housekeeping job that deletes tracks last seen before
     * `now - cleanupRetention`. The single suspending DELETE is wrapped in
     * `runBlocking` so it can be invoked from Spring's classic `@Scheduled`
     * thread; this is acceptable for a single short DELETE that runs once an
     * hour. If the cleanup grows (multiple queries, batching), revisit by
     * scheduling on a coroutine scope and consuming a `SmartLifecycle` for
     * graceful shutdown.
     */
    @Scheduled(fixedDelayString = "\${application.notifications.tracker.cleanup-interval-ms:3600000}")
    fun cleanup() {
        try {
            runBlocking {
                tracker.cleanupExpired()
            }
        } catch (e: Exception) {
            logger.warn(e) { "ObjectTracker cleanup task failed" }
        }
    }
}
