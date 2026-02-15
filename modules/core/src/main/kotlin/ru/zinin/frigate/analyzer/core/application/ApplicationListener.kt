package ru.zinin.frigate.analyzer.core.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import ru.zinin.frigate.analyzer.core.task.FirstTimeScanTask
import ru.zinin.frigate.analyzer.core.task.WatchRecordsTask

private val logger = KotlinLogging.logger {}

@Component
class ApplicationListener(
    val gitProperties: GitProperties,
    val buildProperties: BuildProperties,
    val watchRecordsTask: WatchRecordsTask,
    val firstTimeScanTask: FirstTimeScanTask,
    val recordsWatcherProperties: RecordsWatcherProperties,
    val springProfileHelper: SpringProfileHelper,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun initializeApplication() {
        logger.info { "Application started." }

        logger.info { "${"Git version: ${gitProperties.getCommitId()}"} " }
        logger.info { "${"Git commit time: ${gitProperties.getCommitTime()}"} " }
        logger.info { "${"Build version: ${buildProperties.getVersion()}"} " }
        logger.info { "${"Build time: ${buildProperties.getTime()}"} " }

        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. Watch records task skipped." }
        } else {
            watchRecordsTask.run()
        }
        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. First time scan task skipped." }
        } else if (!recordsWatcherProperties.disableFirstScan) {
            firstTimeScanTask.run()
        } else {
            logger.info { "First time scan task skipped." }
        }

        logger.info { "Tasks started." }
    }

    @EventListener(ContextClosedEvent::class)
    fun shutdownApplication() {
        logger.info { "Application shutting down..." }

        watchRecordsTask.shutdown()

        logger.info { "Application shut down completed." }
    }
}
