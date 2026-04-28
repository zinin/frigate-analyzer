package ru.zinin.frigate.analyzer.model.dto

/** Result of one ObjectTrackerService.evaluate call: how many tracks were new vs matched vs stale. */
data class DetectionDelta(
    val newTracksCount: Int,
    val matchedTracksCount: Int,
    val staleTracksCount: Int,
    val newClasses: List<String>,
)
