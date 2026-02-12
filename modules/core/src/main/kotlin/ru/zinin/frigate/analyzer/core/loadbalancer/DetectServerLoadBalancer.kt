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
                    "priority=${props.visualizeRequests.priority})"
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
            )
        }

    private fun buildUnavailableMessage(requestType: RequestType): String {
        val statuses =
            registry.getAllServers().joinToString(", ") { server ->
                "${server.id}(alive=${server.alive}, " +
                    "frames=${server.processingFrameRequestsCount.get()}/${server.properties.frameRequests.simultaneousCount}, " +
                    "frameExtract=${server.processingFrameExtractionRequestsCount.get()}/" +
                    "${server.properties.framesExtractRequests.simultaneousCount}, " +
                    "visualize=${server.processingVisualizeRequestsCount.get()}/${server.properties.visualizeRequests.simultaneousCount})"
            }
        return "No detect server available for $requestType. Current statuses: [$statuses]"
    }
}
