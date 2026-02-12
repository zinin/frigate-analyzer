package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Table(name = "recordings")
data class RecordingEntity(
    @JvmField
    @Id
    var id: UUID?,
    @Column("creation_timestamp")
    var creationTimestamp: Instant?,
    @Column("file_path")
    var filePath: String?,
    @Column("file_creation_timestamp")
    var fileCreationTimestamp: Instant?,
    @Column("cam_id")
    var camId: String?,
    @Column("record_date")
    var recordDate: LocalDate?,
    @Column("record_time")
    var recordTime: LocalTime?,
    @Column("record_timestamp")
    var recordTimestamp: Instant?,
    @Column("start_processing_timestamp")
    var startProcessingTimestamp: Instant?,
    @Column("process_timestamp")
    var processTimestamp: Instant?,
    @Column("process_attempts")
    var processAttempts: Int?,
    @Column("detections_count")
    var detectionsCount: Int?,
    @Column("analyze_time")
    var analyzeTime: Int?,
    @Column("analyzed_frames_count")
    var analyzedFramesCount: Int?,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    override fun isNew(): Boolean = true
}
