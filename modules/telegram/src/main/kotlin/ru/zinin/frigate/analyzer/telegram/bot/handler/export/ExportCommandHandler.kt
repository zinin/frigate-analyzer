package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportCommandHandler(
    private val userService: TelegramUserService,
    private val exportDialogRunner: ExportDialogRunner,
    private val exportExecutor: ExportExecutor,
    private val activeExportTracker: ActiveExportTracker,
) : CommandHandler,
    DisposableBean {
    private val exportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val command: String = "export"
    override val description: String = "Выгрузить видео"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 3

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        role: UserRole?,
    ) {
        val chatId = message.chat.id
        val chatIdLong = chatId.chatId.long

        if (!activeExportTracker.tryAcquire(chatIdLong)) {
            reply(message, "Экспорт уже выполняется. Дождитесь завершения текущего экспорта.")
            return
        }

        var releaseNeeded = true

        try {
            val userZone = userService.getUserZone(chatIdLong)
            val outcome = with(exportDialogRunner) { runDialog(chatId, userZone) }

            when (outcome) {
                is ExportDialogOutcome.Success -> {
                    releaseNeeded = false
                    exportScope.launch {
                        try {
                            exportExecutor.execute(chatId, userZone, outcome)
                        } finally {
                            activeExportTracker.release(chatIdLong)
                        }
                    }
                }

                is ExportDialogOutcome.Cancelled -> {
                }

                is ExportDialogOutcome.Timeout -> {
                    sendTextMessage(chatId, "Время ожидания истекло. Попробуйте снова /export.")
                }
            }
        } finally {
            if (releaseNeeded) {
                activeExportTracker.release(chatIdLong)
            }
        }
    }

    override fun destroy() {
        exportScope.cancel()
    }
}
