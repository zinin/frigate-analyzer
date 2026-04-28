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
     * Idempotent under retries within the same recording: matching is timestamp-based and
     * `last_seen_at` uses GREATEST in SQL.
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta

    /** Removes tracks with `last_seen_at < threshold`. Returns deleted row count. */
    suspend fun cleanupExpired(): Long
}
