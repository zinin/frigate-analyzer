package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table(name = "notification_verdicts")
data class NotificationVerdictEntity(
    @JvmField
    @Id
    var id: UUID?,
    @Column("created_at")
    var createdAt: Instant,
    @Column("recording_id")
    var recordingId: UUID,
    @Column("cam_id")
    var camId: String,
    @Column("record_timestamp")
    var recordTimestamp: Instant,
    @Column("stage")
    var stage: String,
    @Column("verdict")
    var verdict: String,
    @Column("reason")
    var reason: String,
    @Column("tracker_reason")
    var trackerReason: String,
    @Column("classes")
    var classes: String,
    @Column("confidence")
    var confidence: Float?,
    @Column("summary")
    var summary: String?,
    @Column("wanted")
    var wanted: String?,
    @Column("snooze_until")
    var snoozeUntil: Instant?,
    @Column("preset_id")
    var presetId: String?,
    @Column("model")
    var model: String?,
    @Column("latency_ms")
    var latencyMs: Int?,
    @Column("context_json")
    var contextJson: String?,
    @Column("error")
    var error: String?,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    override fun isNew(): Boolean = true
}
