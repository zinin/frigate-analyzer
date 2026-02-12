package ru.zinin.frigate.analyzer.telegram.dto

import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import java.time.Instant
import java.util.UUID

data class TelegramUserDto(
    val id: UUID,
    val username: String,
    val chatId: Long?,
    val userId: Long?,
    val firstName: String?,
    val lastName: String?,
    val status: UserStatus,
    val creationTimestamp: Instant,
    val activationTimestamp: Instant?,
)
