package ru.zinin.frigate.analyzer.core.loadbalancer

import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties
import ru.zinin.frigate.analyzer.core.config.properties.RequestConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ServerState(
    val id: String,
    val properties: DetectServerProperties,
    val processingFrameRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingFrameExtractionRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVideoVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
) {
    /**
     * Health snapshot read/written atomically. All writes MUST go through
     * [updateHealth] or [getAndUpdateHealth]. Reads happen via [snapshot] or convenience accessors
     * [alive] / [lastCheckTimestamp] — each single-field read is safe; for
     * combined read of multiple fields use [snapshot] and work with the
     * returned [HealthSnapshot] locally. This guarantees that no reader can
     * observe a partial snapshot (e.g., the new `alive` paired with the old
     * `lastCheckTimestamp`), even under concurrent writers.
     */
    private val healthRef = AtomicReference(HealthSnapshot())

    data class HealthSnapshot(
        val alive: Boolean = false,
        val lastCheckTimestamp: Instant = Instant.EPOCH,
    )

    val alive: Boolean get() = healthRef.get().alive
    val lastCheckTimestamp: Instant get() = healthRef.get().lastCheckTimestamp

    fun snapshot(): HealthSnapshot = healthRef.get()

    /**
     * Atomic RMW returning the **post-update** snapshot. For transition-detection
     * (e.g., logging "alive→dead" on the actual edge), use [getAndUpdateHealth] instead.
     */
    fun updateHealth(transform: (HealthSnapshot) -> HealthSnapshot): HealthSnapshot = healthRef.updateAndGet(transform)

    /**
     * Atomic RMW returning the **previous** snapshot. Use this (not [updateHealth]) when the
     * caller needs to detect a transition — e.g., "was alive, now dead" — without a check-then-act
     * race against concurrent writers. ServerState has up to three potential concurrent writers
     * (health-check success callback, error callback, and `ServerHealthMonitor.markServerDead`),
     * so any transition-detection logic MUST use the pre-update snapshot returned here rather
     * than a separate `server.alive` read. Note: at the time of this refactor only two writers
     * are reachable — the only `markServerDead` call site (`DetectService.kt:113`) is commented
     * out. The contract is written for the active design so it remains correct when that call
     * site is re-enabled.
     */
    fun getAndUpdateHealth(transform: (HealthSnapshot) -> HealthSnapshot): HealthSnapshot = healthRef.getAndUpdate(transform)

    fun canAcceptRequest(requestType: RequestType): Boolean = alive && getCurrentCount(requestType) < getMaxCount(requestType)

    private fun getCurrentCount(type: RequestType): Int =
        when (type) {
            RequestType.FRAME -> processingFrameRequestsCount.get()
            RequestType.FRAME_EXTRACTION -> processingFrameExtractionRequestsCount.get()
            RequestType.VISUALIZE -> processingVisualizeRequestsCount.get()
            RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount.get()
        }

    private fun getMaxCount(type: RequestType): Int =
        when (type) {
            RequestType.FRAME -> properties.frameRequests.simultaneousCount
            RequestType.FRAME_EXTRACTION -> properties.framesExtractRequests.simultaneousCount
            RequestType.VISUALIZE -> properties.visualizeRequests.simultaneousCount
            RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests.simultaneousCount
        }

    override fun equals(other: Any?): Boolean = other is ServerState && other.id == this.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        val s = snapshot()
        return "ServerState(id=$id, alive=${s.alive}, lastCheckTimestamp=${s.lastCheckTimestamp})"
    }
}

fun ServerState.getCounter(type: RequestType): AtomicInteger =
    when (type) {
        RequestType.FRAME -> processingFrameRequestsCount
        RequestType.FRAME_EXTRACTION -> processingFrameExtractionRequestsCount
        RequestType.VISUALIZE -> processingVisualizeRequestsCount
        RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount
    }

fun ServerState.getRequestConfig(type: RequestType): RequestConfig =
    when (type) {
        RequestType.FRAME -> properties.frameRequests
        RequestType.FRAME_EXTRACTION -> properties.framesExtractRequests
        RequestType.VISUALIZE -> properties.visualizeRequests
        RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests
    }
