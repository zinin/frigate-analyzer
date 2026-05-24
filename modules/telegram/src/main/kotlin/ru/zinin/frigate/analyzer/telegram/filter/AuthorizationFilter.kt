package ru.zinin.frigate.analyzer.telegram.filter

import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AuthorizationFilter(
    private val properties: TelegramProperties,
    private val userService: TelegramUserService,
) {
    suspend fun authorize(message: CommonMessage<MessageContent>): AuthResult {
        val username = extractUsername(message) ?: return AuthResult.Unauthorized
        return authorize(username)
    }

    suspend fun authorize(username: String): AuthResult {
        val record = userService.findByUsername(username)
        val isOwner = userService.isOwner(username) // case-insensitive

        return when {
            record?.status == UserStatus.ACTIVE && isOwner -> {
                logger.debug { "Owner access: @$username" }
                AuthResult.Active(UserRole.OWNER, record)
            }

            record?.status == UserStatus.ACTIVE && !isOwner -> {
                logger.debug { "User access: @$username" }
                AuthResult.Active(UserRole.USER, record)
            }

            record?.status == UserStatus.INVITED -> {
                logger.debug { "Invited (not yet activated) user: @$username" }
                AuthResult.NeedsActivation
            }

            record == null && isOwner -> {
                logger.info { "Configured owner without DB record (waiting for /start): @$username" }
                AuthResult.NeedsActivation
            }

            else -> {
                logger.warn { "Unauthorized access attempt from user: @$username" }
                AuthResult.Unauthorized
            }
        }
    }

    fun extractUsername(message: CommonMessage<MessageContent>): String? {
        val privateMessage = message as? PrivateContentMessage<*> ?: return null
        return privateMessage.user.username?.withoutAt
    }
}
