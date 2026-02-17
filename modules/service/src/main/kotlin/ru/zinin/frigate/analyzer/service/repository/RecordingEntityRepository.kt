package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import ru.zinin.frigate.analyzer.model.dto.CameraStatisticsDto
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Repository
interface RecordingEntityRepository : CoroutineCrudRepository<RecordingEntity, UUID> {
    suspend fun findByFilePath(filePath: String): RecordingEntity?

    @Query(
        """
        SELECT *
        FROM recordings
        WHERE process_timestamp IS NULL
          AND file_creation_timestamp < :createdBefore
          AND (
                start_processing_timestamp IS NULL
                OR start_processing_timestamp < :stuckBefore
              )
        ORDER BY file_creation_timestamp DESC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """,
    )
    suspend fun findUnprocessedForUpdate(
        @Param("stuckBefore") stuckBefore: Instant,
        @Param("createdBefore") createdBefore: Instant,
        @Param("limit") limit: Int,
    ): List<RecordingEntity>

    @Modifying
    @Query(
        """
    UPDATE recordings
    SET start_processing_timestamp = :now
    WHERE id = :id
""",
    )
    suspend fun startProcessing(
        @Param("id") id: UUID,
        @Param("now") now: Instant,
    ): Long

    @Modifying
    @Query(
        """
        UPDATE recordings
        SET process_timestamp = :processTimestamp,
            process_attempts = process_attempts + 1,
            detections_count = :detectionsCount,
            analyze_time = analyze_time + :analyzeTime,
            analyzed_frames_count = :analyzedFramesCount
        WHERE id = :recordingId
        """,
    )
    suspend fun markProcessed(
        @Param("recordingId") recordingId: UUID,
        @Param("processTimestamp") processTimestamp: Instant,
        @Param("detectionsCount") detectionsCount: Int,
        @Param("analyzeTime") analyzeTime: Int,
        @Param("analyzedFramesCount") analyzedFramesCount: Int,
    ): Long

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun countAll(): Long

    @Query("SELECT COUNT(*) FROM recordings WHERE process_timestamp IS NOT NULL")
    suspend fun countProcessed(): Long

    @Query("SELECT COUNT(*) FROM recordings WHERE process_timestamp IS NULL")
    suspend fun countUnprocessed(): Long

    @Query(
        """
        SELECT
            cam_id as cam_id,
            COUNT(*) as recordings_count,
            COUNT(CASE WHEN process_timestamp IS NOT NULL THEN 1 END) as recordings_processed,
            COALESCE(SUM(detections_count), 0) as detections_count
        FROM recordings
        WHERE cam_id IS NOT NULL
        GROUP BY cam_id
        ORDER BY cam_id
        """,
    )
    suspend fun getStatisticsByCameras(): List<CameraStatisticsDto>

    @Query(
        """
        SELECT *
        FROM recordings
        WHERE cam_id = :camId
          AND record_date = :recordDate
          AND record_time >= :startTime - INTERVAL '10 seconds'
          AND record_time <= :endTime
          AND file_path IS NOT NULL
        ORDER BY record_time ASC
        """,
    )
    suspend fun findByCamIdAndDateAndTimeRange(
        @Param("camId") camId: String,
        @Param("recordDate") recordDate: LocalDate,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
    ): List<RecordingEntity>

    @Query(
        """
        SELECT cam_id, COUNT(*) as recordings_count
        FROM recordings
        WHERE record_date = :recordDate
          AND record_time >= :startTime - INTERVAL '10 seconds'
          AND record_time <= :endTime
          AND file_path IS NOT NULL
          AND cam_id IS NOT NULL
        GROUP BY cam_id
        ORDER BY cam_id
        """,
    )
    suspend fun findCamerasWithRecordings(
        @Param("recordDate") recordDate: LocalDate,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
    ): List<CameraRecordingCountDto>

    @Query(
        """
        SELECT COALESCE(CAST(COUNT(*) AS DOUBLE PRECISION) / 5.0, 0.0) as processing_rate
        FROM recordings
        WHERE process_timestamp BETWEEN (NOW() - INTERVAL '10 minutes') AND (NOW() - INTERVAL '5 minutes')
        """,
    )
    suspend fun getProcessingRatePerMinuteLast5Minutes(): Double
}
