package ru.zinin.frigate.analyzer.model.dto

/** Result of one ObjectTrackerService.evaluate call: how many tracks were new vs matched vs stale. */
data class DetectionDelta(
    val newTracksCount: Int,
    val matchedTracksCount: Int,
    val staleTracksCount: Int,
    val newClasses: List<String>,
    /**
     * Subset of [matchedTracksCount]: matches whose previous `lastSeenAt` was at least
     * `ObjectTrackerProperties.reappearGap` behind the recording — the object came back after a long
     * absence. Counted inside [matchedTracksCount] on purpose, so "the tracker did nothing" stays
     * expressible as new == matched == stale == 0.
     */
    val reappearedTracksCount: Int = 0,
    /** Classes behind [reappearedTracksCount]; may repeat when several tracks of a class reappear. */
    val reappearedClasses: List<String> = emptyList(),
)
