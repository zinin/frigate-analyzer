package ru.zinin.frigate.analyzer.core.judge

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeZoneResolver(
    private val judgeProperties: JudgeProperties,
    private val userService: TelegramUserService,
    private val telegramProperties: TelegramProperties,
) {
    suspend fun resolve(): ZoneId {
        if (judgeProperties.zone.isNotBlank()) return ZoneId.of(judgeProperties.zone)
        return try {
            val chatId = userService.findByUsernameIgnoreCase(telegramProperties.owner)?.chatId
            if (chatId == null) ZoneId.systemDefault() else userService.getUserZone(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Cannot resolve the owner's timezone for the judge context; using ${ZoneId.systemDefault()}" }
            ZoneId.systemDefault()
        }
    }
}
