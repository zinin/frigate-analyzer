package ru.zinin.frigate.analyzer.core.loadbalancer

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import ru.zinin.frigate.analyzer.core.config.properties.DetectProperties
import java.time.Clock

private val logger = KotlinLogging.logger {}

@Component
class ServerHealthMonitor(
    private val registry: DetectServerRegistry,
    private val webClient: WebClient,
    private val clock: Clock,
    private val detectProperties: DetectProperties,
) {
    @Scheduled(fixedDelayString = "\${application.detect.health-check-interval}")
    fun checkHealth() {
        if (registry.isEmpty()) return

        logger.debug { "Checking health for ${registry.getAllServers().size} server(s)" }
        registry.getAllServers().forEach { checkServerHealth(it) }
    }

    private fun checkServerHealth(server: ServerState) {
        val healthUrl = "${server.properties.buildUrl()}/health"

        webClient
            .get()
            .uri(healthUrl)
            .retrieve()
            .toBodilessEntity()
            .timeout(detectProperties.healthCheckTimeout)
            .subscribe(
                {
                    val prev =
                        server.getAndUpdateHealth {
                            it.copy(alive = true, lastCheckTimestamp = clock.instant())
                        }
                    if (!prev.alive) {
                        logger.info { "Server ${server.id} is now ALIVE" }
                    }
                },
                { error ->
                    val prev =
                        server.getAndUpdateHealth {
                            it.copy(alive = false, lastCheckTimestamp = clock.instant())
                        }
                    if (prev.alive) {
                        logger.warn(error) { "Server ${server.id} is now DEAD" }
                    }
                },
            )
    }

    fun markServerDead(id: String) {
        registry.getServer(id)?.let { server ->
            // Transition-detection: log "marked as dead" only on the actual alive→dead edge.
            // If multiple callers race to mark the same server dead, only the first one logs.
            val prev =
                server.getAndUpdateHealth {
                    it.copy(alive = false, lastCheckTimestamp = clock.instant())
                }
            if (prev.alive) {
                logger.warn { "Server $id marked as dead" }
            }
        }
    }
}
