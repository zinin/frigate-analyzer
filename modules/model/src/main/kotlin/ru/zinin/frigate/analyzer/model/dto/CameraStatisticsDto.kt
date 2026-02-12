package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column

data class CameraStatisticsDto(
    @Column("cam_id")
    val camId: String,
    @Column("recordings_count")
    val recordingsCount: Long,
    @Column("recordings_processed")
    val recordingsProcessed: Long,
    @Column("detections_count")
    val detectionsCount: Long,
)
