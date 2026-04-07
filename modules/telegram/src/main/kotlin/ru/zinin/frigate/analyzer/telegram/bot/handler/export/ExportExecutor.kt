package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.IdChatIdentifier
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportExecutor(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
) {
    @Suppress("LongMethod")
    suspend fun execute(
        chatId: IdChatIdentifier,
        userZone: ZoneId,
        dialogResult: ExportDialogOutcome.Success,
        lang: String,
    ) {
        val (startInstant, endInstant, camId, mode) = dialogResult

        val statusMessage = bot.sendTextMessage(chatId, renderProgress(Stage.PREPARING, mode = mode, msg = msg, lang = lang))

        var lastRenderedStage: Stage? = Stage.PREPARING
        var lastRenderedPercent: Int? = null
        var hadCompressing = false

        val onProgress: suspend (VideoExportProgress) -> Unit = { progress ->
            if (progress.stage == Stage.COMPRESSING) hadCompressing = true

            val shouldUpdate =
                when {
                    progress.stage != lastRenderedStage -> {
                        true
                    }

                    progress.stage == Stage.ANNOTATING && progress.percent != null -> {
                        val lastPct = lastRenderedPercent ?: -1
                        (progress.percent - lastPct) >= 5
                    }

                    else -> {
                        false
                    }
                }

            if (shouldUpdate) {
                lastRenderedStage = progress.stage
                lastRenderedPercent = progress.percent
                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(progress.stage, progress.percent, mode, hadCompressing, msg, lang),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            }
        }

        try {
            val processingTimeout =
                if (mode == ExportMode.ANNOTATED) EXPORT_ANNOTATED_TIMEOUT_MS else EXPORT_ORIGINAL_TIMEOUT_MS
            val videoPath =
                withTimeoutOrNull(processingTimeout) {
                    videoExportService.exportVideo(startInstant, endInstant, camId, mode, onProgress)
                } ?: run {
                    bot.sendTextMessage(chatId, msg.get("export.error.processing.timeout", lang))
                    return
                }

            try {
                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(Stage.SENDING, mode = mode, compressing = hadCompressing, msg = msg, lang = lang),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }

                val localDate = startInstant.atZone(userZone).toLocalDate()
                val localStart = startInstant.atZone(userZone).toLocalTime()
                val localEnd = endInstant.atZone(userZone).toLocalTime()
                val fileName = "export_${camId}_${localDate}_$localStart-$localEnd.mp4".replace(":", "-")
                val sent =
                    withTimeoutOrNull(properties.sendVideoTimeout.toMillis()) {
                        bot.sendVideo(
                            chatId,
                            videoPath.toFile().asMultipartFile().copy(filename = fileName),
                        )
                    }

                if (sent == null) {
                    logger.error {
                        "Telegram sendVideo timed out after ${properties.sendVideoTimeout} " +
                            "for chat=$chatId, camera=$camId, file=$fileName"
                    }
                    bot.sendTextMessage(
                        chatId,
                        msg.get("export.error.send.timeout", lang, properties.sendVideoTimeout.toSeconds()),
                    )
                    return
                }

                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(Stage.DONE, mode = mode, compressing = hadCompressing, msg = msg, lang = lang),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            } finally {
                try {
                    videoExportService.cleanupExportFile(videoPath)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to delete temp file: $videoPath" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Video export failed" }
            val errorText =
                if (mode == ExportMode.ANNOTATED) {
                    msg.get("export.error.annotated", lang)
                } else {
                    msg.get("export.error.original", lang)
                }
            bot.sendTextMessage(chatId, errorText)
        }
    }
}
