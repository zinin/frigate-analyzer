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
                    val wasAlive = server.alive
                    server.alive = true
                    server.lastCheckTimestamp = clock.instant()

                    if (!wasAlive) {
                        logger.info { "Server ${server.id} is now ALIVE" }
                    }
                },
                { error ->
                    val wasAlive = server.alive
                    server.alive = false
                    server.lastCheckTimestamp = clock.instant()

                    if (wasAlive) {
                        logger.warn(error) { "Server ${server.id} is now DEAD" }
                    }
                },
            )
    }

    fun markServerDead(id: String) {
        registry.getServer(id)?.let { server ->
            server.alive = false
            server.lastCheckTimestamp = clock.instant()
            logger.warn { "Server $id marked as dead" }
        }
    }
}
