package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.MessageIsNotModifiedException
import dev.inmo.tgbotapi.bot.exceptions.MessageToEditNotFoundException
import dev.inmo.tgbotapi.extensions.api.edit.caption.editMessageCaption
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Target of a description edit — holds the message IDs that were sent with placeholder
 * text and must be rewritten once the AI call resolves.
 *
 * `captionMessageId` is null for the media-group case (no photo caption was written;
 * the whole short+detailed text lives in the `detailsMessageId` reply instead).
 */
data class EditTarget(
    val chatId: ChatIdentifier,
    val captionMessageId: MessageId?,
    val detailsMessageId: MessageId,
    /** Raw (un-escaped) base text. The formatter handles HTML escape + truncation. */
    val baseText: String,
    /** Budget for the final HTML caption (1024 − placeholder overhead). */
    val captionBudget: Int,
    val exportKeyboard: InlineKeyboardMarkup,
    val language: String,
    val isMediaGroup: Boolean,
)

/**
 * Launches the "wait for AI → edit placeholders" background job. Extracted from
 * `TelegramNotificationSender` so tests can stub it and so graceful shutdown is managed
 * separately from the notification queue.
 *
 * Uses [DescriptionEditScope] (conditional on `application.ai.description.enabled=true`)
 * for structured concurrency — its `@PreDestroy` cancels in-flight edits on shutdown.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionEditJobRunner(
    private val bot: TelegramBot,
    private val formatter: DescriptionMessageFormatter,
    private val scope: DescriptionEditScope,
) {
    // Tests observe the most-recently launched job via [lastLaunchedJobForTests]. Production code
    // never reads this — it's a write-only side-effect from the runner's own launches.
    private val lastJob = AtomicReference<Job?>(null)

    /** Test-only hook. Returns the most recently launched edit job (may be null if none yet). */
    internal fun lastLaunchedJobForTests(): Job? = lastJob.get()

    fun launchEditJob(
        targets: List<EditTarget>,
        handleOutcome: suspend () -> Result<DescriptionResult>,
    ): Job =
        scope
            .launch {
                val outcome = handleOutcome()
                targets.forEach { target ->
                    editOne(target, outcome)
                }
            }.also { lastJob.set(it) }

    private suspend fun editOne(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        if (target.isMediaGroup) {
            editMediaGroup(target, outcome)
        } else {
            editSinglePhotoCaption(target, outcome)
            editSinglePhotoDetails(target, outcome)
        }
    }

    private suspend fun editMediaGroup(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        val newText =
            outcome.fold(
                onSuccess = { result ->
                    val short = formatter.captionSuccess(target.baseText, result, target.language, target.captionBudget)
                    "$short\n\n${formatter.expandableBlockquoteSuccess(result, target.language)}"
                },
                onFailure = {
                    val short = formatter.captionFallback(target.baseText, target.language, target.captionBudget)
                    "$short\n\n${formatter.expandableBlockquoteFallback(target.language)}"
                },
            )
        runEdit("media group details", target) {
            bot.editMessageText(
                chatId = target.chatId,
                messageId = target.detailsMessageId,
                text = newText,
                parseMode = HTMLParseMode,
                replyMarkup = target.exportKeyboard,
            )
        }
    }

    private suspend fun editSinglePhotoCaption(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        val captionText =
            outcome.fold(
                onSuccess = { formatter.captionSuccess(target.baseText, it, target.language, target.captionBudget) },
                onFailure = { formatter.captionFallback(target.baseText, target.language, target.captionBudget) },
            )
        runEdit("single-photo caption", target) {
            bot.editMessageCaption(
                chatId = target.chatId,
                messageId = target.captionMessageId!!,
                text = captionText,
                parseMode = HTMLParseMode,
                replyMarkup = target.exportKeyboard,
            )
        }
    }

    private suspend fun editSinglePhotoDetails(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        val detailsText =
            outcome.fold(
                onSuccess = { formatter.expandableBlockquoteSuccess(it, target.language) },
                onFailure = { formatter.expandableBlockquoteFallback(target.language) },
            )
        runEdit("single-photo details", target) {
            bot.editMessageText(
                chatId = target.chatId,
                messageId = target.detailsMessageId,
                text = detailsText,
                parseMode = HTMLParseMode,
            )
        }
    }

    /**
     * Wraps a single edit call in the expected-failure-tolerant try/catch.
     *
     * - `CancellationException` is re-thrown (structured concurrency — never swallow).
     * - `MessageIsNotModifiedException` / `MessageToEditNotFoundException` are logged as DEBUG
     *   because they are normal (message already has this text / user deleted it).
     * - Any other failure is logged at WARN and swallowed so a failing caption edit does NOT
     *   block a subsequent details edit on the same target.
     */
    private suspend fun runEdit(
        label: String,
        target: EditTarget,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MessageIsNotModifiedException) {
            logger.debug { "Edit skipped for $label (chat=${target.chatId}): message is not modified — ${e.message}" }
        } catch (e: MessageToEditNotFoundException) {
            logger.debug { "Edit skipped for $label (chat=${target.chatId}): message not found — ${e.message}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to edit $label for chat=${target.chatId}; continuing" }
        }
    }
}
