package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

interface ObjectTrackerService {
    /**
     * Aggregates [detections] for [recording], matches each representative bbox against the
     * camera's active tracks (within configured TTL), persists updates and inserts, and returns
     * a [DetectionDelta] summarizing the outcome.
     *
     * Also advances the camera's watch window exactly like [markObserved]. Reappearance detection
     * relies on EVERY processed recording reaching the tracker: a caller that skips detection-less
     * recordings must route them through [markObserved], or quiet periods read as processing
     * interruptions and real reappearances are suppressed as unobserved.
     *
     * Idempotent under retries within the same recording: matching is timestamp-based and
     * `last_seen_at` uses GREATEST in SQL.
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta

    /**
     * Records that [recording] was processed — the camera was being watched — without evaluating
     * detections. Cheap: in-memory bookkeeping only, no I/O.
     *
     * Detection-less recordings are the proof the tracker was watching through a quiet period. A
     * wall-clock pause between watch stamps longer than `reappear-gap` reads as a processing
     * interruption and restarts the camera's watch window, so skipping empty recordings would make
     * every quiet camera look permanently interrupted — on a camera whose only tracked object is
     * the one that left, a reappearance could then never be reported at all.
     */
    fun markObserved(recording: RecordingDto)

    /** Removes tracks with `last_seen_at < threshold`. Returns deleted row count. */
    suspend fun cleanupExpired(): Long
}
