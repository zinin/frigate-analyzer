package ru.zinin.frigate.analyzer.core.loadbalancer

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties
import java.util.concurrent.ConcurrentHashMap

@Component
class DetectServerRegistry {
    private val servers = ConcurrentHashMap<String, ServerState>()

    fun register(
        id: String,
        properties: DetectServerProperties,
    ) {
        servers[id] = ServerState(id = id, properties = properties)
    }

    fun getAllServers(): Collection<ServerState> = servers.values

    fun getServer(id: String): ServerState? = servers[id]

    fun isEmpty(): Boolean = servers.isEmpty()
}
