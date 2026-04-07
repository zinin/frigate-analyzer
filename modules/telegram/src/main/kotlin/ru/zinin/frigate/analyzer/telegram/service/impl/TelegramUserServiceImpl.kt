package ru.zinin.frigate.analyzer.telegram.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo
import ru.zinin.frigate.analyzer.telegram.entity.TelegramUserEntity
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.repository.TelegramUserRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Clock
import java.time.DateTimeException
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
        val user = repository.findByChatId(chatId)
        if (user == null) {
            logger.warn { "User with chatId=$chatId not found, falling back to UTC" }
            return ZoneId.of("UTC")
        }
        val olsonCode = user.olsonCode
        return try {
            ZoneId.of(olsonCode ?: "UTC")
        } catch (e: DateTimeException) {
            logger.warn { "Invalid olson_code='$olsonCode' for chatId=$chatId, falling back to UTC" }
            ZoneId.of("UTC")
        }
    }

    @Transactional
    override suspend fun updateTimezone(
        chatId: Long,
        olsonCode: String,
    ): Boolean {
        require(olsonCode.contains('/')) { "Offset-based zone IDs are not allowed: $olsonCode" }
        ZoneId.of(olsonCode) // throws DateTimeException if invalid
        val updated = repository.updateOlsonCode(chatId, olsonCode)
        if (updated == 0L) {
            logger.warn { "updateTimezone: no rows updated for chatId=$chatId" }
            return false
        }
        logger.info { "Updated timezone for chatId=$chatId to $olsonCode" }
        return true
    }

    @Transactional(readOnly = true)
    override suspend fun getAuthorizedUsersWithZones(): List<UserZoneInfo> =
        repository
            .findAllByStatus(UserStatus.ACTIVE.name)
            .filter { it.chatId != null }
            .map { user ->
                val zone =
                    try {
                        ZoneId.of(user.olsonCode ?: "UTC")
                    } catch (e: DateTimeException) {
                        logger.warn { "Invalid olson_code='${user.olsonCode}' for chatId=${user.chatId}, falling back to UTC" }
                        ZoneId.of("UTC")
                    }
                UserZoneInfo(user.chatId!!, zone, user.languageCode)
            }

    @Transactional(readOnly = true)
    override suspend fun getUserLanguage(chatId: Long): String {
        val user = repository.findByChatId(chatId)
        return user?.languageCode ?: "ru"
    }

    @Transactional
    override suspend fun updateLanguage(
        chatId: Long,
        languageCode: String,
    ): Boolean {
        require(languageCode in SUPPORTED_LANGUAGES) { "Unsupported language: $languageCode" }
        val updated = repository.updateLanguageCode(chatId, languageCode)
        if (updated == 0L) {
            logger.warn { "updateLanguage: no rows updated for chatId=$chatId" }
            return false
        }
        logger.info { "Updated language for chatId=$chatId to $languageCode" }
        return true
    }

    companion object {
        val SUPPORTED_LANGUAGES = setOf("ru", "en")
    }

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
            languageCode = languageCode,
        )
}
