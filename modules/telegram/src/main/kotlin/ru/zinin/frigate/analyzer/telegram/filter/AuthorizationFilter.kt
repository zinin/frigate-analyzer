package ru.zinin.frigate.analyzer.telegram.filter

import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AuthorizationFilter(
    private val properties: TelegramProperties,
    private val userService: TelegramUserService,
) {
    suspend fun getRole(message: CommonMessage<MessageContent>): UserRole? {
        val username = extractUsername(message) ?: return null

        return when {
            username == properties.owner -> {
                logger.debug { "Owner access: @$username" }
                UserRole.OWNER
            }

            userService.findActiveByUsername(username) != null -> {
                logger.debug { "User access: @$username" }
                UserRole.USER
            }

            else -> {
                logger.warn { "Unauthorized access attempt from user: @$username" }
                null
            }
        }
    }

    fun extractUsername(message: CommonMessage<MessageContent>): String? {
        val privateMessage = message as? PrivateContentMessage<*> ?: return null
        return privateMessage.user.username?.withoutAt
    }

    fun getUnauthorizedMessage(): String = properties.unauthorizedMessage
}
