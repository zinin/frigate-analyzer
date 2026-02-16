package ru.zinin.frigate.analyzer.core.loadbalancer

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.model.exception.DetectServerUnavailableException
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.ServerLoad
import ru.zinin.frigate.analyzer.model.response.ServerStatus

private val logger = KotlinLogging.logger {}

@Component
class DetectServerLoadBalancer(
    private val applicationProperties: ApplicationProperties,
    private val registry: DetectServerRegistry,
    private val selectionStrategy: ServerSelectionStrategy,
    private val healthMonitor: ServerHealthMonitor,
) {
    private val acquireMutex = Mutex()

    @PostConstruct
    fun init() {
        val detectServers = applicationProperties.detectServers

        if (detectServers.isEmpty()) {
            logger.warn { "No detect servers configured!" }
            return
        }

        detectServers.forEach { (id, props) ->
            registry.register(id, props)
            logger.info {
                "Registered detect server: $id at ${props.buildUrl()}, " +
                    "frameRequests(count=${props.frameRequests.simultaneousCount}, priority=${props.frameRequests.priority}), " +
                    "frameExtractionRequests(count=${props.framesExtractRequests.simultaneousCount}, " +
                    "priority=${props.framesExtractRequests.priority}), " +
                    "visualizeRequests(count=${props.visualizeRequests.simultaneousCount}, " +
                    "priority=${props.visualizeRequests.priority}), " +
                    "videoVisualizeRequests(count=${props.videoVisualizeRequests.simultaneousCount}, " +
                    "priority=${props.videoVisualizeRequests.priority})"
            }
        }

        healthMonitor.checkHealth()
    }

    suspend fun acquireServer(requestType: RequestType): AcquiredServer =
        acquireMutex.withLock {
            val server =
                selectionStrategy.selectServer(registry.getAllServers(), requestType)
                    ?: throw DetectServerUnavailableException(buildUnavailableMessage(requestType))

            server.getCounter(requestType).incrementAndGet()
            AcquiredServer(id = server.id, properties = server.properties)
        }

    fun releaseServer(
        id: String,
        requestType: RequestType,
    ) {
        registry.getServer(id)?.let { server ->
            val newCount = server.getCounter(requestType).decrementAndGet()
            if (newCount < 0) {
                server.getCounter(requestType).set(0)
            }
        }
    }

    fun markServerDead(id: String) {
        healthMonitor.markServerDead(id)
    }

    fun getTotalCapacity(requestType: RequestType): Int =
        registry.getAllServers().sumOf {
            it.getRequestConfig(requestType).simultaneousCount
        }

    fun getAllServersStatistics(): List<DetectServerStatistics> =
        registry.getAllServers().map { server ->
            DetectServerStatistics(
                id = server.id,
                status = if (server.alive) ServerStatus.ALIVE else ServerStatus.DEAD,
                frameRequests =
                    ServerLoad(
                        current = server.processingFrameRequestsCount.get(),
                        maximum = server.properties.frameRequests.simultaneousCount,
                    ),
                frameExtractionRequests =
                    ServerLoad(
                        current = server.processingFrameExtractionRequestsCount.get(),
                        maximum = server.properties.framesExtractRequests.simultaneousCount,
                    ),
                visualizeRequests =
                    ServerLoad(
                        current = server.processingVisualizeRequestsCount.get(),
                        maximum = server.properties.visualizeRequests.simultaneousCount,
                    ),
                videoVisualizeRequests =
                    ServerLoad(
                        current = server.processingVideoVisualizeRequestsCount.get(),
                        maximum = server.properties.videoVisualizeRequests.simultaneousCount,
                    ),
            )
        }

    private fun buildUnavailableMessage(requestType: RequestType): String {
        val statuses =
            registry.getAllServers().joinToString(", ") { server ->
                val frameRequestsCount = server.processingFrameRequestsCount.get()
                val frameRequestsMax = server.properties.frameRequests.simultaneousCount
                val frameExtractCount = server.processingFrameExtractionRequestsCount.get()
                val frameExtractMax = server.properties.framesExtractRequests.simultaneousCount
                val visualizeCount = server.processingVisualizeRequestsCount.get()
                val visualizeMax = server.properties.visualizeRequests.simultaneousCount
                val videoVisualizeCount = server.processingVideoVisualizeRequestsCount.get()
                val videoVisualizeMax = server.properties.videoVisualizeRequests.simultaneousCount
                "${server.id}(alive=${server.alive}, " +
                    "frames=$frameRequestsCount/$frameRequestsMax, " +
                    "frameExtract=$frameExtractCount/$frameExtractMax, " +
                    "visualize=$visualizeCount/$visualizeMax, " +
                    "videoVisualize=$videoVisualizeCount/$videoVisualizeMax)"
            }
        return "No detect server available for $requestType. Current statuses: [$statuses]"
    }
}
