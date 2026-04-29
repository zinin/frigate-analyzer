package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table(name = "object_tracks")
data class ObjectTrackEntity(
    @JvmField
    @Id
    var id: UUID?,
    @Column("creation_timestamp")
    var creationTimestamp: Instant?,
    @Column("cam_id")
    var camId: String?,
    @Column("class_name")
    var className: String?,
    @Column("bbox_x1")
    var bboxX1: Float,
    @Column("bbox_y1")
    var bboxY1: Float,
    @Column("bbox_x2")
    var bboxX2: Float,
    @Column("bbox_y2")
    var bboxY2: Float,
    @Column("last_seen_at")
    var lastSeenAt: Instant?,
    @Column("last_recording_id")
    var lastRecordingId: UUID? = null,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    // Always treated as "new" so save() emits an INSERT; updates use ObjectTrackRepository.updateOnMatch.
    override fun isNew(): Boolean = true
}
