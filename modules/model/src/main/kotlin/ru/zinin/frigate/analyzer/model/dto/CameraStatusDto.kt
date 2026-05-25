package ru.zinin.frigate.analyzer.model.dto

import java.time.Duration
import java.time.Instant

data class CameraStatusDto(
    val camId: String,
    val state: CameraState,
    val lastSeenAt: Instant,
    val offlineFor: Duration?,
)

enum class CameraState {
    HEALTHY,
    OFFLINE,
}
