package ru.zinin.frigate.analyzer.model.dto

import java.time.Duration

/** Result of one ObjectTrackerService.evaluate call: how many tracks were new vs matched vs stale. */
data class DetectionDelta(
    val newTracksCount: Int,
    val matchedTracksCount: Int,
    val staleTracksCount: Int,
    val newClasses: List<String>,
    /**
     * Subset of [matchedTracksCount]: matches whose previous `lastSeenAt` was further behind the
     * recording than `ObjectTrackerProperties.reappearGap` — the object came back after a long
     * absence. Counted inside [matchedTracksCount] on purpose, so "the tracker did nothing" stays
     * expressible as new == matched == stale == 0.
     */
    val reappearedTracksCount: Int = 0,
    /** Classes behind [reappearedTracksCount]; may repeat when several tracks of a class reappear. */
    val reappearedClasses: List<String> = emptyList(),
    /**
     * Largest absence among **all** matched tracks, including those that never reached
     * `ObjectTrackerProperties.reappearGap`, and `null` when nothing measurable matched.
     *
     * Diagnostic rather than behavioural: no decision is taken from it. It is what `reappear-gap`
     * gets tuned against — the absences that stayed below the threshold are the ones that say where
     * the threshold could move to, and they are invisible everywhere else.
     */
    val maxAbsence: Duration? = null,
)
