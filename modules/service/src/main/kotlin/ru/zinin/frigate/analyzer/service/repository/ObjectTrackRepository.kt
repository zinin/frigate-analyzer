package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import java.time.Instant
import java.util.UUID

@Repository
interface ObjectTrackRepository : CoroutineCrudRepository<ObjectTrackEntity, UUID> {
    @Query(
        """
        SELECT * FROM object_tracks
        WHERE cam_id = :camId
          AND last_seen_at >= :minLastSeen
        """,
    )
    suspend fun findActive(
        @Param("camId") camId: String,
        @Param("minLastSeen") minLastSeen: Instant,
    ): List<ObjectTrackEntity>

    @Modifying
    @Query(
        """
        UPDATE object_tracks
        SET bbox_x1 = CASE WHEN :lastSeenAt >= last_seen_at THEN :x1 ELSE bbox_x1 END,
            bbox_y1 = CASE WHEN :lastSeenAt >= last_seen_at THEN :y1 ELSE bbox_y1 END,
            bbox_x2 = CASE WHEN :lastSeenAt >= last_seen_at THEN :x2 ELSE bbox_x2 END,
            bbox_y2 = CASE WHEN :lastSeenAt >= last_seen_at THEN :y2 ELSE bbox_y2 END,
            last_seen_at = GREATEST(last_seen_at, :lastSeenAt),
            last_recording_id = CASE WHEN :lastSeenAt >= last_seen_at THEN :lastRecordingId ELSE last_recording_id END
        WHERE id = :id
        """,
    )
    suspend fun updateOnMatch(
        @Param("id") id: UUID,
        @Param("x1") x1: Float,
        @Param("y1") y1: Float,
        @Param("x2") x2: Float,
        @Param("y2") y2: Float,
        @Param("lastSeenAt") lastSeenAt: Instant,
        @Param("lastRecordingId") lastRecordingId: UUID,
    ): Long

    @Modifying
    @Query("DELETE FROM object_tracks WHERE last_seen_at < :threshold")
    suspend fun deleteExpired(
        @Param("threshold") threshold: Instant,
    ): Long
}
