package ru.zinin.frigate.analyzer.service.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.notifications.tracker")
@Validated
data class ObjectTrackerProperties(
    val ttl: Duration = Duration.ofSeconds(120),
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val iouThreshold: Float = 0.3f,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val innerIou: Float = 0.5f,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val confidenceFloor: Float = 0.3f,
    val cleanupIntervalMs: Long = 3_600_000,
    val cleanupRetention: Duration = Duration.ofHours(1),
) {
    init {
        require(!ttl.isZero && !ttl.isNegative) {
            "application.notifications.tracker.ttl must be > 0, got $ttl"
        }
        require(!cleanupRetention.isNegative && !cleanupRetention.isZero) {
            "application.notifications.tracker.cleanup-retention must be > 0, got $cleanupRetention"
        }
        require(cleanupRetention >= ttl) {
            "application.notifications.tracker.cleanup-retention must be >= ttl, got retention=$cleanupRetention ttl=$ttl"
        }
        require(cleanupIntervalMs > 0) {
            "application.notifications.tracker.cleanup-interval-ms must be > 0, got $cleanupIntervalMs"
        }
    }
}
