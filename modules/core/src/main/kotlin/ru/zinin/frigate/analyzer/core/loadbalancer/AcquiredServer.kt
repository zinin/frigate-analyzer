package ru.zinin.frigate.analyzer.core.loadbalancer

import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties

/**
 * Wrapper containing both server ID and properties.
 * Returned by acquire methods to provide both pieces of information.
 */
data class AcquiredServer(
    val id: String,
    val properties: DetectServerProperties,
) {
    val schema: String get() = properties.schema
    val host: String get() = properties.host
    val port: Int get() = properties.port

    fun buildUrl(): String = properties.buildUrl()
}
