package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table(name = "detections")
data class DetectionEntity(
    @JvmField
    @Id
    var id: UUID?,
    @Column("creation_timestamp")
    var creationTimestamp: Instant?,
    @Column("recording_id")
    var recordingId: UUID?,
    @Column("detection_timestamp")
    var detectionTimestamp: Instant?,
    @Column("frame_index")
    var frameIndex: Int,
    @Column("model")
    var model: String,
    @Column("class_id")
    var classId: Int,
    @Column("class_name")
    var className: String,
    @Column("confidence")
    var confidence: Float,
    @Column("x1")
    val x1: Float,
    @Column("y1")
    val y1: Float,
    @Column("x2")
    val x2: Float,
    @Column("y2")
    val y2: Float,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    override fun isNew(): Boolean = true
}
