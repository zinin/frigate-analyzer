package ru.zinin.frigate.analyzer.telegram.bot.handler.cancel

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.StartCommandHandler
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Handles two callback prefixes:
 *  - `xc:{exportId}` — real cancellation: marks registry entry as CANCELLING, edits keyboard to
 *    "Cancelling...", cancels the export coroutine (`Job.cancel()`), and fire-and-forgets
 *    `CancellableJob.cancel()` to tell the vision server to stop processing.
 *  - `np:{exportId}` — silent no-op for the progress button; just answers the callback query
 *    so Telegram stops showing a loading spinner on the user's device.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class CancelExportHandler(
    private val bot: TelegramBot,
    private val registry: ActiveExportRegistry,
    private val exportScope: ExportCoroutineScope,
    private val authFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val msg: MessageResolver,
) {
    suspend fun handle(callback: DataCallbackQuery) {
        val data = callback.data

        if (data.startsWith(NOOP_PREFIX)) {
            answerSafely(callback)
            return
        }

        if (!data.startsWith(CANCEL_PREFIX)) {
            logger.warn { "CancelExportHandler received unexpected callback data: $data" }
            answerSafely(callback)
            return
        }

        val messageCallback = callback as? MessageDataCallbackQuery
        val message = messageCallback?.message
        if (message == null) {
            answerSafely(callback)
            return
        }

        val chatId = message.chat.id
        val user = callback.user
        val username = user.username?.withoutAt
        val lang = resolveLang(chatId.chatId.long, user.ietfLanguageCode?.code)

        if (username == null || authFilter.getRole(username) == null) {
            answerSafely(callback, msg.get("common.error.unauthorized", lang))
            return
        }

        val exportId = parseExportId(data)
        if (exportId == null) {
            answerSafely(callback, msg.get("cancel.error.format", lang))
            return
        }

        val entry = registry.get(exportId)
        if (entry == null || entry.chatId != chatId.chatId.long) {
            answerSafely(callback, msg.get("cancel.error.not.active", lang))
            return
        }

        val marked = registry.markCancelling(exportId)
        if (marked == null) {
            // Disambiguate: markCancelling returns null if the entry is already CANCELLING OR if
            // it was released between our registry.get above and now (natural completion race).
            // Show the correct user-facing message — "already cancelling" only when the entry is
            // still present.
            val stillPresent = registry.get(exportId) != null
            val errorKey = if (stillPresent) "cancel.error.already.cancelling" else "cancel.error.not.active"
            answerSafely(callback, msg.get(errorKey, lang))
            return
        }

        answerSafely(callback)
        logger.info {
            "Export cancelled by user exportId=$exportId chatId=${marked.chatId} mode=${marked.mode} " +
                "recordingId=${marked.recordingId}"
        }

        try {
            bot.editMessageReplyMarkup(
                message,
                replyMarkup = buildCancellingKeyboard(exportId, marked.recordingId, lang),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to update keyboard to cancelling state" }
        }

        marked.job.cancel(CancellationException("user cancelled"))

        marked.cancellable?.let { cancellable ->
            exportScope.launch {
                try {
                    cancellable.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Vision server cancel failed exportId=$exportId" }
                }
            }
        }
    }

    private fun buildCancellingKeyboard(
        exportId: UUID,
        recordingId: UUID?,
        lang: String,
    ): InlineKeyboardMarkup {
        // Distinguish QuickExport vs /export by presence of recordingId (NOT by ExportMode — that
        // enum has only ANNOTATED/ORIGINAL and both flows use both modes).
        val label =
            if (recordingId != null) {
                msg.get("quickexport.progress.cancelling", lang)
            } else {
                msg.get("export.progress.cancelling", lang)
            }
        return InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(label, "$NOOP_PREFIX$exportId")
                    }
                },
        )
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

    /**
     * Helper: answer the callback, swallowing only non-cancellation errors. We can't use
     * `runCatching` here because it catches `Throwable` including `CancellationException`,
     * which would break graceful shutdown propagation (iter-2 gemini BUG-2).
     */
    private suspend fun answerSafely(
        callback: DataCallbackQuery,
        text: String? = null,
    ) {
        try {
            if (text != null) bot.answer(callback, text) else bot.answer(callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to answer callback query" }
        }
    }

    companion object {
        const val CANCEL_PREFIX = "xc:"
        const val NOOP_PREFIX = "np:"

        fun parseExportId(data: String): UUID? {
            // Strict: require exactly one of the prefixes at the start. `data = "xc:np:uuid"` must
            // not parse — removePrefix-chain would silently accept it.
            val raw =
                when {
                    data.startsWith(CANCEL_PREFIX) -> data.removePrefix(CANCEL_PREFIX)
                    data.startsWith(NOOP_PREFIX) -> data.removePrefix(NOOP_PREFIX)
                    else -> return null
                }
            return try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
