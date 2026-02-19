package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column

data class CameraRecordingCountDto(
    @Column("cam_id")
    val camId: String,
    @Column("recordings_count")
    val recordingsCount: Long,
)
