package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import java.time.Instant
import java.util.UUID

@Repository
interface NotificationVerdictRepository : CoroutineCrudRepository<NotificationVerdictEntity, UUID> {
    @Query(
        """
        SELECT * FROM notification_verdicts
        WHERE cam_id = :camId AND record_timestamp BETWEEN :from AND :to
        ORDER BY record_timestamp DESC
        LIMIT :limit
        """,
    )
    suspend fun findRecentForCamera(
        @Param("camId") camId: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("limit") limit: Int,
    ): List<NotificationVerdictEntity>

    @Query(
        """
        SELECT * FROM notification_verdicts
        WHERE cam_id = :camId AND verdict = 'PUBLISH'
        ORDER BY record_timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun findLastPublished(
        @Param("camId") camId: String,
    ): NotificationVerdictEntity?

    @Query("SELECT * FROM notification_verdicts ORDER BY record_timestamp DESC LIMIT :limit")
    suspend fun findLatest(
        @Param("limit") limit: Int,
    ): List<NotificationVerdictEntity>

    @Query("SELECT * FROM notification_verdicts WHERE cam_id = :camId ORDER BY record_timestamp DESC LIMIT :limit")
    suspend fun findLatestByCamera(
        @Param("camId") camId: String,
        @Param("limit") limit: Int,
    ): List<NotificationVerdictEntity>
}
