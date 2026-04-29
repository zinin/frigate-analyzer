package ru.zinin.frigate.analyzer.telegram.entity

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table(name = "telegram_users")
data class TelegramUserEntity(
    @JvmField
    @Id
    var id: UUID?,
    @Column("username")
    var username: String?,
    @Column("chat_id")
    var chatId: Long?,
    @Column("user_id")
    var userId: Long?,
    @Column("first_name")
    var firstName: String?,
    @Column("last_name")
    var lastName: String?,
    @Column("status")
    var status: String?,
    @Column("creation_timestamp")
    var creationTimestamp: Instant?,
    @Column("activation_timestamp")
    var activationTimestamp: Instant?,
    @Column("olson_code")
    var olsonCode: String? = null,
    @Column("language_code")
    var languageCode: String? = null,
    @Column("notifications_recording_enabled")
    var notificationsRecordingEnabled: Boolean = true,
    @Column("notifications_signal_enabled")
    var notificationsSignalEnabled: Boolean = true,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    override fun isNew(): Boolean = true
}
