package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportExecutor(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
    private val registry: ActiveExportRegistry,
    private val exportScope: ExportCoroutineScope,
) {
    @Suppress("LongMethod")
    suspend fun execute(
        chatId: IdChatIdentifier,
        userZone: ZoneId,
        dialogResult: ExportDialogOutcome.Success,
        lang: String,
    ) {
        val (startInstant, endInstant, camId, mode) = dialogResult

        val exportId = UUID.randomUUID()
        val job: Job =
            exportScope.launch(start = CoroutineStart.LAZY) {
                runExport(chatId, userZone, camId, mode, startInstant, endInstant, lang, exportId)
            }
        // Safety net for LAZY cancel-before-body. Idempotent with finally inside runExport.
        job.invokeOnCompletion { registry.release(exportId) }

        val startResult =
            registry.tryStartDialogExport(exportId, chatId.chatId.long, mode, job)
        when (startResult) {
            is ActiveExportRegistry.StartResult.DuplicateChat -> {
                job.cancel()
                bot.sendTextMessage(chatId, msg.get("export.error.concurrent", lang))
                return
            }

            is ActiveExportRegistry.StartResult.DuplicateRecording -> {
                // Impossible for /export (registry doesn't check byRecordingId for /export).
                // Programming error — fail loudly.
                job.cancel()
                error("Unexpected DuplicateRecording from tryStartDialogExport")
            }

            is ActiveExportRegistry.StartResult.Success -> {
                // Fire-and-forget: start the LAZY job and return immediately. execute() MUST NOT
                // block on job.join() — ExportCommandHandler releases ActiveExportTracker in its
                // finally block, and blocking here would collapse the two-tier lock model (tracker
                // would remain acquired for the entire export, up to 50 min). Registry's byChat
                // index then owns execution-phase dedup independently of tracker.
                job.start()
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun runExport(
        chatId: IdChatIdentifier,
        userZone: ZoneId,
        camId: String,
        mode: ExportMode,
        startInstant: Instant,
        endInstant: Instant,
        lang: String,
        exportId: UUID,
    ) {
        val cancelKeyboardMarkup = cancelKeyboard(exportId, lang)
        val statusMessage =
            bot.sendTextMessage(
                chatId,
                renderProgress(Stage.PREPARING, mode = mode, msg = msg, lang = lang),
                replyMarkup = cancelKeyboardMarkup,
            )

        var lastRenderedStage: Stage? = Stage.PREPARING
        var lastRenderedPercent: Int? = null
        var hadCompressing = false

        val onProgress: suspend (VideoExportProgress) -> Unit = lambda@{ progress ->
            if (registry.get(exportId)?.state == ActiveExportRegistry.State.CANCELLING) {
                return@lambda
            }
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
                        replyMarkup = cancelKeyboardMarkup,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            }
        }

        val onJobSubmitted: suspend (CancellableJob) -> Unit = { cancellable ->
            registry.attachCancellable(exportId, cancellable)
        }

        try {
            val processingTimeout =
                if (mode == ExportMode.ANNOTATED) EXPORT_ANNOTATED_TIMEOUT_MS else EXPORT_ORIGINAL_TIMEOUT_MS
            val videoPath =
                withTimeoutOrNull(processingTimeout) {
                    videoExportService.exportVideo(
                        startInstant = startInstant,
                        endInstant = endInstant,
                        camId = camId,
                        mode = mode,
                        onProgress = onProgress,
                        onJobSubmitted = onJobSubmitted,
                    )
                } ?: run {
                    // Drop the cancel keyboard on the status message — otherwise a dead "✖ Отмена"
                    // button remains on the final error screen.
                    // try/catch + explicit CancellationException rethrow (NOT runCatching) —
                    // consistent with answerSafely pattern (iter-2 BUG-8): runCatching catches
                    // Throwable including CancellationException, which would break graceful
                    // shutdown / user-cancel propagation if the outer coroutine is cancelled during
                    // the bot.editMessageText call.
                    try {
                        bot.editMessageText(
                            statusMessage,
                            msg.get("export.error.processing.timeout", lang),
                            replyMarkup = null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to clear cancel keyboard on processing-timeout final screen" }
                        bot.sendTextMessage(chatId, msg.get("export.error.processing.timeout", lang))
                    }
                    return
                }

            try {
                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(Stage.SENDING, mode = mode, compressing = hadCompressing, msg = msg, lang = lang),
                        replyMarkup = cancelKeyboardMarkup,
                    )
                } catch (e: CancellationException) {
                    throw e
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
                    // Drop keyboard on final error. try/catch + rethrow CancellationException —
                    // NOT runCatching, for the same reason as the processing-timeout path above.
                    try {
                        bot.editMessageText(
                            statusMessage,
                            msg.get("export.error.send.timeout", lang, properties.sendVideoTimeout.toSeconds()),
                            replyMarkup = null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to clear cancel keyboard on send-timeout final screen" }
                        bot.sendTextMessage(
                            chatId,
                            msg.get("export.error.send.timeout", lang, properties.sendVideoTimeout.toSeconds()),
                        )
                    }
                    return
                }

                try {
                    bot.editMessageText(
                        statusMessage,
                        renderProgress(Stage.DONE, mode = mode, compressing = hadCompressing, msg = msg, lang = lang),
                        replyMarkup = null,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update export progress message" }
                }
            } finally {
                // cleanupExportFile is suspend → instantly rethrows CancellationException in a
                // cancelled coroutine without doing the actual IO. Must run under NonCancellable
                // so the temp file is really deleted on user-cancel/shutdown.
                withContext(NonCancellable) {
                    try {
                        videoExportService.cleanupExportFile(videoPath)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to delete temp file: $videoPath" }
                    }
                }
            }
        } catch (e: CancellationException) {
            val entry = registry.get(exportId)
            if (entry?.state == ActiveExportRegistry.State.CANCELLING) {
                // bot.editMessageText is suspend; in a cancelled coroutine the final "❌ Отменён"
                // would never reach Telegram. NonCancellable wraps the UI update so the user sees
                // the final state instead of getting stuck on "⏹ Отменяется…".
                withContext(NonCancellable) {
                    try {
                        bot.editMessageText(
                            statusMessage,
                            msg.get("export.cancelled.by.user", lang),
                            replyMarkup = null,
                        )
                    } catch (ex: Exception) {
                        logger.warn(ex) { "Failed to render cancelled state" }
                    }
                }
                return
            }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Video export failed" }
            val errorText =
                if (mode == ExportMode.ANNOTATED) {
                    msg.get("export.error.annotated", lang)
                } else {
                    msg.get("export.error.original", lang)
                }
            try {
                bot.editMessageText(statusMessage, errorText, replyMarkup = null)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                try {
                    bot.sendTextMessage(chatId, errorText)
                } catch (ex2: CancellationException) {
                    throw ex2
                } catch (ex2: Exception) {
                    logger.warn(ex2) { "Failed to send export error message fallback" }
                }
            }
        } finally {
            registry.release(exportId)
        }
    }

    private fun cancelKeyboard(
        exportId: UUID,
        lang: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get("export.button.cancel", lang),
                            "${CancelExportHandler.CANCEL_PREFIX}$exportId",
                        )
                    }
                },
        )
}
