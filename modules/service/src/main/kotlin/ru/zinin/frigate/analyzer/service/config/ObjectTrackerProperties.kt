package ru.zinin.frigate.analyzer.service.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.notifications.tracker")
@Validated
data class ObjectTrackerProperties(
    val ttl: Duration = Duration.ofMinutes(30),
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val iouThreshold: Float = 0.3f,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val innerIou: Float = 0.5f,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val confidenceFloor: Float = 0.3f,
    val cleanupIntervalMs: Long = 3_600_000,
    val cleanupRetention: Duration = Duration.ofHours(1),
    /**
     * A matched track whose previous `lastSeenAt` is further behind the current recording than this
     * counts as a REAPPEARANCE and notifies, even though it reuses the existing track row.
     *
     * Separates two things a plain IoU match cannot tell apart under a long [ttl]: an object that is
     * physically always there (parked car, static false positive — detected in nearly every
     * recording, so its gaps stay at the recording cadence) from one that left and came back hours
     * later. Only the latter is an event worth a notification.
     *
     * Defaults to [ttl], which is a no-op: [ttl] also bounds how far back
     * `ObjectTrackRepository.findActive` looks, and it does so inclusively — the largest absence a
     * matched track can show is exactly [ttl], which the strict comparison then rejects. Set it
     * below [ttl] to enable reappearance notifications; pick a value above the observed detector
     * flakiness gap for static objects, otherwise they re-notify.
     */
    val reappearGap: Duration = ttl,
    /**
     * Classes allowed to produce a REAPPEARANCE notification. Empty — the default — means all of
     * them, which is what keeps this a no-op out of the box.
     *
     * Gates *returns* only. A class left out still notifies the first time it is seen: "a new cow"
     * is an event, "the cow is back" is not. Deliberately separate from
     * `application.detection-filter.allowed-classes`, which decides what is detected at all and
     * must not be narrowed to achieve this.
     *
     * Static objects are what it exists for. A bicycle the detector loses at dusk and finds again
     * at dawn shows an absence that no [reappearGap] can tell apart from a person who left in the
     * evening and came back in the morning — the durations are the same. The class can.
     */
    val reappearClasses: List<String> = emptyList(),
) {
    /** [reappearClasses] prepared for lookup: trimmed, lower-cased, blanks dropped. */
    val reappearClassesNormalized: Set<String> =
        reappearClasses.mapNotNull { it.trim().lowercase().ifEmpty { null } }.toSet()

    /** `true` when [className] may produce a reappearance. An empty list allows every class. */
    fun reappearAllows(className: String): Boolean =
        reappearClassesNormalized.isEmpty() || className.trim().lowercase() in reappearClassesNormalized

    init {
        require(!ttl.isZero && !ttl.isNegative) {
            "application.notifications.tracker.ttl must be > 0, got $ttl"
        }
        require(!reappearGap.isZero && !reappearGap.isNegative) {
            "application.notifications.tracker.reappear-gap must be > 0, got $reappearGap"
        }
        require(reappearGap <= ttl) {
            "application.notifications.tracker.reappear-gap must be <= ttl (a larger gap can never " +
                "be reached, since ttl bounds the findActive window), got reappearGap=$reappearGap ttl=$ttl"
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
        require(reappearClasses.isEmpty() || reappearClassesNormalized.isNotEmpty()) {
            "application.notifications.tracker.reappear-classes was set but holds no usable class " +
                "name; an all-blank list would silently mean \"every class\", got $reappearClasses"
        }
    }
}
