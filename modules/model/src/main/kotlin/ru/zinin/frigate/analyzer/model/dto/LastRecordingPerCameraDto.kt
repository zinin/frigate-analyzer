package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

data class LastRecordingPerCameraDto(
    @Column("cam_id")
    val camId: String,
    @Column("last_record_timestamp")
    val lastRecordTimestamp: Instant,
)
