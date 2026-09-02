package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
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
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.cancel.CancelExportHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthResult
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class QuickExportHandler(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val authorizationFilter: AuthorizationFilter,
    private val properties: TelegramProperties,
    private val msg: MessageResolver,
    private val userService: TelegramUserService,
    private val registry: ActiveExportRegistry,
    private val exportScope: ExportCoroutineScope,
) {
    @Suppress("LongMethod")
    suspend fun handle(callback: DataCallbackQuery): Job? {
        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            bot.answer(callback)
            return null
        }
        val chatId = message.chat.id
        val chatIdLong = chatId.chatId.long

        val callbackData = callback.data
        val mode =
            if (callbackData.startsWith(CALLBACK_PREFIX_ANNOTATED)) ExportMode.ANNOTATED else ExportMode.ORIGINAL

        val recordingId = parseRecordingId(callbackData)
        if (recordingId == null) {
            val lang = resolveLang(chatIdLong, callback.user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("quickexport.error.format", lang))
            return null
        }

        val user = callback.user
        val username = user.username?.withoutAt
        if (username == null) {
            val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("quickexport.error.username", lang))
            return null
        }

        when (authorizationFilter.authorize(username)) {
            is AuthResult.Active -> {
                Unit
            }

            AuthResult.NeedsActivation, AuthResult.Unauthorized -> {
                val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
                bot.answer(callback, msg.get("common.error.unauthorized", lang))
                return null
            }
        }

        val lang = resolveLang(chatIdLong, user.ietfLanguageCode?.code)

        val exportId = UUID.randomUUID()
        val job =
            exportScope.launch(start = CoroutineStart.LAZY) {
                runExport(message, chatIdLong, chatId, recordingId, mode, lang, exportId)
            }
        // Safety net for LAZY cancel-before-body — finally in runExport won't fire if job is
        // cancelled before its first suspension point. release() is idempotent, so double-firing
        // (finally + invokeOnCompletion) is harmless.
        job.invokeOnCompletion { registry.release(exportId) }

        val startResult = registry.tryStartQuickExport(exportId, chatIdLong, mode, recordingId, job)
        when (startResult) {
            is ActiveExportRegistry.StartResult.DuplicateRecording -> {
                job.cancel()
                bot.answer(callback, msg.get("quickexport.error.concurrent", lang))
                return null
            }

            is ActiveExportRegistry.StartResult.DuplicateChat -> {
                // Impossible for QuickExport (registry doesn't check byChat for QuickExport).
                // If this ever fires, it's a programming error — fail loudly.
                job.cancel()
                error("Unexpected DuplicateChat from tryStartQuickExport")
            }

            is ActiveExportRegistry.StartResult.Success -> {
                // CRITICAL: bot.answer + editMessageReplyMarkup run BEFORE job.start(). If the
                // handler scope is cancelled during either suspend call (Telegram timeout, network
                // blip, shutdown), `throw e` leaves the LAZY job in NEW state forever — the body
                // never runs, invokeOnCompletion never fires, and the registry entry leaks,
                // permanently blocking this recordingId until restart (iter-4 gemini CRITICAL-12).
                // Fix: cancel the job explicitly before rethrowing. `invokeOnCompletion` fires even
                // for a LAZY job cancelled from NEW → Cancelled, triggering registry.release().
                try {
                    bot.answer(callback)
                } catch (e: CancellationException) {
                    job.cancel()
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to answer callback on quick-export start" }
                }
                try {
                    bot.editMessageReplyMarkup(
                        message,
                        replyMarkup =
                            createProgressKeyboard(
                                exportId,
                                msg.get("quickexport.button.processing", lang),
                                msg.get("quickexport.button.cancel", lang),
                            ),
                    )
                } catch (e: CancellationException) {
                    job.cancel()
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update button to processing state" }
                }
                job.start()
                return job
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun runExport(
        message: ContentMessage<*>,
        chatIdLong: Long,
        chatId: IdChatIdentifier,
        recordingId: UUID,
        mode: ExportMode,
        lang: String,
        exportId: UUID,
    ) {
        try {
            val timeout =
                if (mode == ExportMode.ANNOTATED) {
                    QUICK_EXPORT_ANNOTATED_TIMEOUT_MS
                } else {
                    QUICK_EXPORT_ORIGINAL_TIMEOUT_MS
                }

            var lastRenderedStage: VideoExportProgress.Stage? = null
            var lastRenderedPercent: Int? = null

            val onProgress: suspend (VideoExportProgress) -> Unit = lambda@{ progress ->
                if (registry.get(exportId)?.state == ActiveExportRegistry.State.CANCELLING) {
                    // Skip — keyboard is showing "Cancelling..." now.
                    return@lambda
                }
                val shouldUpdate =
                    when {
                        progress.stage != lastRenderedStage -> {
                            true
                        }

                        progress.stage == VideoExportProgress.Stage.ANNOTATING && progress.percent != null -> {
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
                    val text = renderProgressButton(progress.stage, progress.percent, lang)
                    try {
                        bot.editMessageReplyMarkup(
                            message,
                            replyMarkup =
                                createProgressKeyboard(
                                    exportId,
                                    text,
                                    msg.get("quickexport.button.cancel", lang),
                                ),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to update progress button" }
                    }
                }
            }

            val onJobSubmitted: suspend (CancellableJob) -> Unit =
                { cancellable -> registry.attachCancellable(exportId, cancellable) }

            val videoPath =
                withTimeoutOrNull(timeout) {
                    videoExportService.exportByRecordingId(
                        recordingId = recordingId,
                        mode = mode,
                        onProgress = onProgress,
                        onJobSubmitted = onJobSubmitted,
                    )
                }

            if (videoPath == null) {
                logger.warn {
                    "Quick export outer timeout fired (recordingId=$recordingId, mode=$mode, timeoutMs=$timeout)."
                }
                bot.sendTextMessage(chatId, msg.get("quickexport.error.timeout", lang))
                restoreButton(message, recordingId, lang)
                return
            }

            try {
                try {
                    bot.editMessageReplyMarkup(
                        message,
                        replyMarkup =
                            createProgressKeyboard(
                                exportId,
                                msg.get("quickexport.progress.sending", lang),
                                msg.get("quickexport.button.cancel", lang),
                            ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update progress button to sending" }
                }

                val sent =
                    withTimeoutOrNull(properties.sendVideoTimeout.toMillis()) {
                        bot.sendVideo(
                            chatId,
                            videoPath.toFile().asMultipartFile().copy(filename = "quick_export_$recordingId.mp4"),
                        )
                    }

                if (sent == null) {
                    try {
                        bot.sendTextMessage(chatId, msg.get("quickexport.error.send.timeout", lang))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to send quick export send-timeout message" }
                    }
                }
            } finally {
                // cleanupExportFile is suspend → any suspend call in an already-cancelled coroutine
                // instantly throws CancellationException without doing real work. Wrap in
                // NonCancellable so the temp-file is actually deleted on user-cancel/shutdown.
                withContext(NonCancellable) {
                    try {
                        videoExportService.cleanupExportFile(videoPath)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to cleanup: $videoPath" }
                    }
                }
            }

            restoreButton(message, recordingId, lang)
        } catch (e: CancellationException) {
            val entry = registry.get(exportId)
            if (entry?.state == ActiveExportRegistry.State.CANCELLING) {
                // UI updates are suspend (bot.sendTextMessage / bot.editMessageReplyMarkup inside
                // restoreButton). In a cancelled coroutine they throw CancellationException on
                // first suspension, leaving the user stuck on "⏹ Отменяется…". Wrap in
                // NonCancellable so the final "❌ Отменён" actually reaches Telegram.
                withContext(NonCancellable) {
                    try {
                        bot.sendTextMessage(chatId, msg.get("quickexport.cancelled", lang))
                    } catch (ex: Exception) {
                        logger.warn(ex) { "Failed to send cancelled message" }
                    }
                    restoreButton(message, recordingId, lang)
                }
                return
            }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Quick export failed for recording $recordingId" }
            val errorMsg =
                when (e) {
                    is IllegalArgumentException -> msg.get("quickexport.error.not.found", lang)
                    is IllegalStateException -> msg.get("quickexport.error.unavailable", lang)
                    is DetectTimeoutException -> msg.get("quickexport.error.annotation.timeout", lang)
                    is VideoTooLargeException -> msg.get("quickexport.error.too.large", lang)
                    else -> msg.get("quickexport.error.generic", lang)
                }
            try {
                bot.sendTextMessage(chatId, errorMsg)
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to send error message" }
            }
            restoreButton(message, recordingId, lang)
        } finally {
            registry.release(exportId)
        }
    }

    private suspend fun resolveLang(
        chatId: Long,
        ietf: String?,
    ): String =
        try {
            userService.getUserLanguage(chatId) ?: StartCommandHandler.detectLanguage(ietf)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StartCommandHandler.detectLanguage(ietf)
        }

    private fun renderProgressButton(
        stage: VideoExportProgress.Stage,
        percent: Int? = null,
        lang: String,
    ): String =
        when (stage) {
            VideoExportProgress.Stage.PREPARING -> {
                msg.get("quickexport.progress.preparing", lang)
            }

            VideoExportProgress.Stage.MERGING -> {
                msg.get("quickexport.progress.merging", lang)
            }

            VideoExportProgress.Stage.COMPRESSING -> {
                msg.get("quickexport.progress.compressing", lang)
            }

            VideoExportProgress.Stage.ANNOTATING -> {
                if (percent != null) {
                    msg.get("quickexport.progress.annotating.percent", lang, percent)
                } else {
                    msg.get("quickexport.progress.annotating", lang)
                }
            }

            VideoExportProgress.Stage.COMPRESSING_RESULT -> {
                msg.get("quickexport.progress.compressing.result", lang)
            }

            VideoExportProgress.Stage.SENDING -> {
                msg.get("quickexport.progress.sending", lang)
            }

            VideoExportProgress.Stage.DONE -> {
                msg.get("quickexport.progress.done", lang)
            }
        }

    fun createExportKeyboard(
        recordingId: UUID,
        lang: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get("quickexport.button.original", lang),
                            "$CALLBACK_PREFIX$recordingId",
                        )
                        +CallbackDataInlineKeyboardButton(
                            msg.get("quickexport.button.annotated", lang),
                            "$CALLBACK_PREFIX_ANNOTATED$recordingId",
                        )
                    }
                },
        )

    /**
     * Клавиатура, которую сообщение уведомления обязано нести ПРЯМО СЕЙЧАС.
     *
     * Правка rich-сообщения переобъявляет `reply_markup` целиком — опустить его нельзя, Telegram
     * тогда снимет клавиатуру совсем. Поэтому всякий, кто правит уведомление по своему поводу
     * (правка после ответа модели), обязан спросить здесь, а не помнить клавиатуру с момента
     * отправки: пока идёт экспорт, на сообщении висят «прогресс» и «Отмена», и возврат кнопок
     * выбора убрал бы единственный способ отменить аннотированный экспорт с его 50-минутным
     * таймаутом.
     *
     * Текст прогресса при этом откатывается к обобщённому «обрабатывается» — конкретную стадию
     * знает только сам экспорт, и ближайшее его обновление снова её проставит. Кнопка отмены
     * важнее точности подписи.
     *
     * Экспорт ищется по записи, но реестр общий для всех чатов, а одна запись уходит каждому
     * подписчику: экспорт из чужого чата этому получателю не принадлежит — его «Отмену» отклонил бы
     * `CancelExportHandler`, — поэтому ему остаются кнопки выбора. Пока экспорт отменяется,
     * возвращается та же одиночная строка «Отменяется…», что ставит `CancelExportHandler`:
     * клавиатура прогресса вернула бы поверх неё живую «Отмену».
     */
    fun currentKeyboard(
        recordingId: UUID,
        chatId: Long,
        lang: String,
    ): InlineKeyboardMarkup {
        val exportId = registry.activeExportIdFor(recordingId) ?: return createExportKeyboard(recordingId, lang)
        val entry = registry.get(exportId)
        if (entry == null || entry.chatId != chatId) return createExportKeyboard(recordingId, lang)
        return when (entry.state) {
            ActiveExportRegistry.State.CANCELLING -> {
                createCancellingKeyboard(exportId, msg.get("quickexport.progress.cancelling", lang))
            }

            ActiveExportRegistry.State.ACTIVE -> {
                createProgressKeyboard(
                    exportId,
                    msg.get("quickexport.button.processing", lang),
                    msg.get("quickexport.button.cancel", lang),
                )
            }
        }
    }

    private suspend fun restoreButton(
        message: ContentMessage<*>?,
        recordingId: UUID,
        lang: String,
    ) {
        if (message == null) return
        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = createExportKeyboard(recordingId, lang),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to restore button" }
        }
    }

    companion object {
        const val CALLBACK_PREFIX = "qe:"
        const val CALLBACK_PREFIX_ANNOTATED = "qea:"

        // 12 minutes: ffprobe (30 s) + two ffmpeg encodes of VideoMergeHelper.FFMPEG_TIMEOUT_SECONDS
        // (300 s) when the merged file has to be fitted into the Telegram limit, plus the merge.
        private const val QUICK_EXPORT_ORIGINAL_TIMEOUT_MS = 720_000L

        // 75 minutes. Must exceed application.detect.video-visualize.timeout (default 45m) plus
        // everything the export does around the annotation, so the inner annotation timeout
        // surfaces DetectTimeoutException instead of being masked by this outer one: the merge
        // (300 s) and two fitting passes of ffprobe (30 s) plus two libx264 encodes of 300 s each.
        private const val QUICK_EXPORT_ANNOTATED_TIMEOUT_MS = 4_500_000L

        internal fun parseRecordingId(callbackData: String): UUID? {
            // Order matters: check the annotated prefix first because it shares the "qe:" stem
            // with the original prefix. Strict single-prefix match — "qe:qea:uuid" must not parse.
            val raw =
                when {
                    callbackData.startsWith(CALLBACK_PREFIX_ANNOTATED) -> {
                        callbackData.removePrefix(CALLBACK_PREFIX_ANNOTATED)
                    }

                    callbackData.startsWith(CALLBACK_PREFIX) -> {
                        callbackData.removePrefix(CALLBACK_PREFIX)
                    }

                    else -> {
                        return null
                    }
                }
            return try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Single-row "cancelling…" keyboard (`np:` noop only). The same shape `CancelExportHandler`
         * sets when the user taps Cancel, shared so a description edit landing in the cancel window
         * re-declares this row rather than a live Cancel button.
         */
        fun createCancellingKeyboard(
            exportId: UUID,
            text: String,
        ): InlineKeyboardMarkup =
            InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton(text, "${CancelExportHandler.NOOP_PREFIX}$exportId")
                        }
                    },
            )

        /**
         * Two-row keyboard: progress-button (row 1, `np:` noop callback) + cancel-button (row 2, `xc:` cancel).
         * Key is `exportId` — progress button payload `np:{exportId}` and cancel button payload
         * `xc:{exportId}`. `recordingId` is intentionally NOT a parameter: the initial keyboard row
         * (original vs annotated choice) is rebuilt separately via `restoreButton(message, recordingId, ...)`
         * when the export completes or errors.
         */
        fun createProgressKeyboard(
            exportId: UUID,
            progressText: String,
            cancelText: String,
        ): InlineKeyboardMarkup =
            InlineKeyboardMarkup(
                keyboard =
                    matrix {
                        row {
                            +CallbackDataInlineKeyboardButton(
                                progressText,
                                "${CancelExportHandler.NOOP_PREFIX}$exportId",
                            )
                        }
                        row {
                            +CallbackDataInlineKeyboardButton(
                                cancelText,
                                "${CancelExportHandler.CANCEL_PREFIX}$exportId",
                            )
                        }
                    },
            )
    }
}
