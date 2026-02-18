package ru.zinin.frigate.analyzer.telegram.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.entity.TelegramUserEntity
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.repository.TelegramUserRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

@Service
class TelegramUserServiceImpl(
    private val repository: TelegramUserRepository,
    private val uuidGeneratorHelper: UUIDGeneratorHelper,
    private val clock: Clock,
) : TelegramUserService {
    @Transactional(readOnly = true)
    override suspend fun findByUsername(username: String): TelegramUserDto? = repository.findByUsername(username)?.toDto()

    @Transactional(readOnly = true)
    override suspend fun findActiveByUsername(username: String): TelegramUserDto? =
        repository.findByUsernameAndStatus(username, UserStatus.ACTIVE.name)?.toDto()

    @Transactional
    override suspend fun inviteUser(username: String): TelegramUserDto {
        val existing = repository.findByUsername(username)
        if (existing != null) {
            logger.warn { "User @$username already exists with status ${existing.status}" }
            return existing.toDto()
        }

        val entity =
            TelegramUserEntity(
                id = uuidGeneratorHelper.generateV1(),
                username = username,
                chatId = null,
                userId = null,
                firstName = null,
                lastName = null,
                status = UserStatus.INVITED.name,
                creationTimestamp = Instant.now(clock),
                activationTimestamp = null,
            )

        repository.save(entity)
        logger.info { "Invited user @$username" }
        return entity.toDto()
    }

    @Transactional
    override suspend fun activateUser(
        username: String,
        chatId: Long,
        userId: Long,
        firstName: String?,
        lastName: String?,
    ): TelegramUserDto? {
        val updated =
            repository.activate(
                username = username,
                chatId = chatId,
                userId = userId,
                firstName = firstName,
                lastName = lastName,
                activationTimestamp = Instant.now(clock),
            )

        if (updated == 0L) {
            logger.warn { "Failed to activate user @$username - not found or not invited" }
            return null
        }

        logger.info { "Activated user @$username (chatId: $chatId, userId: $userId)" }
        return repository.findByUsername(username)?.toDto()
    }

    @Transactional
    override suspend fun removeUser(username: String): Boolean {
        val deleted = repository.deleteByUsername(username)
        if (deleted > 0) {
            logger.info { "Removed user @$username" }
            return true
        }
        logger.warn { "User @$username not found for removal" }
        return false
    }

    @Transactional(readOnly = true)
    override suspend fun getAllUsers(): List<TelegramUserDto> = repository.findAll().map { it.toDto() }.toList()

    @Transactional(readOnly = true)
    override suspend fun getAllActiveChatIds(): List<Long> = repository.findAllActiveChatIds()

    @Transactional(readOnly = true)
    override suspend fun getUserZone(chatId: Long): ZoneId {
        val olsonCode = repository.findByChatId(chatId)?.olsonCode
        return ZoneId.of(olsonCode ?: "UTC")
    }

    @Transactional
    override suspend fun updateTimezone(chatId: Long, olsonCode: String) {
        repository.updateOlsonCode(chatId, olsonCode)
        logger.info { "Updated timezone for chatId=$chatId to $olsonCode" }
    }

    @Transactional(readOnly = true)
    override suspend fun getAuthorizedUsersWithZones(): List<Pair<Long, ZoneId>> =
        repository.findAllByStatus(UserStatus.ACTIVE.name)
            .filter { it.chatId != null }
            .map { user -> user.chatId!! to ZoneId.of(user.olsonCode ?: "UTC") }

    private fun TelegramUserEntity.toDto(): TelegramUserDto =
        TelegramUserDto(
            id = id!!,
            username = username!!,
            chatId = chatId,
            userId = userId,
            firstName = firstName,
            lastName = lastName,
            status = UserStatus.valueOf(status!!),
            creationTimestamp = creationTimestamp!!,
            activationTimestamp = activationTimestamp,
        )
}
