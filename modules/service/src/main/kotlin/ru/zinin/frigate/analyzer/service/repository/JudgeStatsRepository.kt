package ru.zinin.frigate.analyzer.service.repository

import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.dto.StaticScore
import ru.zinin.frigate.analyzer.model.dto.VerdictCountRow
import java.time.Instant
import java.util.UUID

@Repository
class JudgeStatsRepository(
    private val databaseClient: DatabaseClient,
) {
    suspend fun staticScore(
        camId: String,
        className: String,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        from: Instant,
        to: Instant,
        excludeRecordingId: UUID,
        iou: Double,
        zone: String,
    ): StaticScore =
        databaseClient
            .sql(STATIC_SCORE_SQL)
            .bind("camId", camId)
            .bind("className", className)
            .bind("x1", x1)
            .bind("y1", y1)
            .bind("x2", x2)
            .bind("y2", y2)
            .bind("from", from)
            .bind("to", to)
            .bind("excludeRecordingId", excludeRecordingId)
            .bind("iou", iou)
            .bind("zone", zone)
            .map { row ->
                StaticScore(
                    recordings = row.get("recordings", java.lang.Long::class.java)?.toLong() ?: 0L,
                    days = row.get("days", java.lang.Long::class.java)?.toLong() ?: 0L,
                    firstSeen = row.get("first_seen", Instant::class.java),
                    lastSeen = row.get("last_seen", Instant::class.java),
                )
            }.awaitSingle()

    suspend fun recordingsInWindow(
        camId: String,
        from: Instant,
        to: Instant,
    ): Long =
        databaseClient
            .sql(
                "SELECT count(*) AS cnt FROM recordings WHERE cam_id = :camId AND record_timestamp >= :from AND record_timestamp < :to",
            ).bind("camId", camId)
            .bind("from", from)
            .bind("to", to)
            .map { row -> row.get("cnt", java.lang.Long::class.java)?.toLong() ?: 0L }
            .awaitSingle()

    suspend fun verdictCounters(since: Instant): List<VerdictCountRow> =
        databaseClient
            .sql(
                "SELECT stage, verdict, reason, count(*) AS cnt FROM notification_verdicts WHERE created_at >= :since GROUP BY stage, verdict, reason",
            ).bind("since", since)
            .map { row ->
                VerdictCountRow(
                    stage = row.get("stage", String::class.java)!!,
                    verdict = row.get("verdict", String::class.java)!!,
                    reason = row.get("reason", String::class.java)!!,
                    count = row.get("cnt", java.lang.Long::class.java)?.toLong() ?: 0L,
                )
            }.flow()
            .toList()

    companion object {
        /**
         * IoU в SQL: пересечение / (сумма площадей − пересечение). Индекс — idx_detections_detection_timestamp,
         * остальное фильтр; на проде ~160 мс на неделю cam2. Собственная запись исключена, чтобы
         * кандидат не считал сам себя доказательством статичности.
         */
        val STATIC_SCORE_SQL =
            """
            WITH inter AS (
              SELECT d.recording_id, d.detection_timestamp,
                     GREATEST(0, LEAST(d.x2, :x2) - GREATEST(d.x1, :x1)) * GREATEST(0, LEAST(d.y2, :y2) - GREATEST(d.y1, :y1)) AS i,
                     (d.x2 - d.x1) * (d.y2 - d.y1) + (:x2 - :x1) * (:y2 - :y1) AS sum_areas
              FROM detections d
              JOIN recordings r ON r.id = d.recording_id
              WHERE d.detection_timestamp >= :from AND d.detection_timestamp < :to
                AND r.cam_id = :camId
                AND d.class_name = :className
                AND d.recording_id <> :excludeRecordingId
            )
            SELECT count(DISTINCT recording_id) AS recordings,
                   count(DISTINCT (detection_timestamp AT TIME ZONE :zone)::date) AS days,
                   min(detection_timestamp) AS first_seen,
                   max(detection_timestamp) AS last_seen
            FROM inter
            WHERE sum_areas - i > 0 AND i / (sum_areas - i) >= :iou
            """.trimIndent()
    }
}
