package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto

interface TelegramUserService {
    suspend fun findByUsername(username: String): TelegramUserDto?

    suspend fun findActiveByUsername(username: String): TelegramUserDto?

    suspend fun inviteUser(username: String): TelegramUserDto

    suspend fun activateUser(
        username: String,
        chatId: Long,
        userId: Long,
        firstName: String?,
        lastName: String?,
    ): TelegramUserDto?

    suspend fun removeUser(username: String): Boolean

    suspend fun getAllUsers(): List<TelegramUserDto>

    suspend fun getAllActiveChatIds(): List<Long>
}
