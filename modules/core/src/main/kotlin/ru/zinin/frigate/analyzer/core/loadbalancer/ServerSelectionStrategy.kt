package ru.zinin.frigate.analyzer.core.loadbalancer

import org.springframework.stereotype.Component

@Component
class ServerSelectionStrategy {
    /**
     * Selects the optimal server based on priority and load
     */
    fun selectServer(
        servers: Collection<ServerState>,
        requestType: RequestType,
    ): ServerState? =
        servers
            .filter { it.alive }
            .sortedWith(
                compareBy(
                    { it.getRequestConfig(requestType).priority },
                    { it.getCounter(requestType).get() },
                ),
            ).firstOrNull { it.canAcceptRequest(requestType) }
}
