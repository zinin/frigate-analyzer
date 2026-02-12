package ru.zinin.frigate.analyzer.core.loadbalancer

import org.springframework.stereotype.Component

@Component
class ServerSelectionStrategy {
    /**
     * Выбирает оптимальный сервер на основе приоритета и загрузки
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
