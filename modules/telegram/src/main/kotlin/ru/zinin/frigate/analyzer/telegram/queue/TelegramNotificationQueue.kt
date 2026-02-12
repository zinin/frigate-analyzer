package ru.zinin.frigate.analyzer.telegram.queue

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationQueue(
    private val sender: TelegramNotificationSender,
    telegramProperties: TelegramProperties,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val channel = Channel<NotificationTask>(telegramProperties.queueCapacity)

    @PostConstruct
    fun start() {
        logger.info { "Starting Telegram notification queue" }
        scope.launch { consumeNotifications() }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Stopping Telegram notification queue" }
        channel.close()
        scope.cancel()
    }

    suspend fun enqueue(task: NotificationTask) {
        channel.send(task)
        logger.debug { "Enqueued task ${task.id} for chat ${task.chatId}" }
    }

    private suspend fun consumeNotifications() {
        for (task in channel) {
            try {
                sender.send(task)
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.warn { "Notification consumer cancelled; task ${task.id} for chat ${task.chatId} may be lost" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to send notification task ${task.id} for chat ${task.chatId}" }
            }
        }
    }
}
