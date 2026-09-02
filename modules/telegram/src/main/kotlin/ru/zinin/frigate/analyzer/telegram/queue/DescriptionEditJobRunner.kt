package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.MessageIsNotModifiedException
import dev.inmo.tgbotapi.bot.exceptions.MessageToEditNotFoundException
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.requests.edit.reply_markup.EditChatMessageReplyMarkup
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageRichText
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.rich.InputRichMessageHTML
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.RichNotificationRenderer
import ru.zinin.frigate.analyzer.telegram.service.model.DescriptionState
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Цель правки: сообщение, ушедшее с плейсхолдерами, и всё, что нужно, чтобы собрать его заново.
 *
 * `fileIds` обязательны: rich-сообщение при правке переобъявляет медиа целиком, иначе Telegram
 * отвечает `RICH_MESSAGE_PHOTO_INVALID`.
 *
 * `keyboard` — функция, а не готовая разметка, и это не стилистика. Между отправкой и правкой
 * проходят десятки секунд, за которые пользователь успевает запустить экспорт: на сообщении в этот
 * момент висят «прогресс» и «Отмена». Клавиатура при правке переобъявляется целиком (опустить её
 * нельзя — Telegram снимет её совсем), поэтому запомненная при отправке разметка вернула бы кнопки
 * выбора поверх идущего экспорта и отняла бы единственную кнопку отмены. Функция спрашивает
 * актуальное состояние в момент правки.
 */
data class EditTarget(
    val chatId: ChatIdentifier,
    val messageId: MessageId,
    val data: RecordingNotificationData,
    val fileIds: List<FileId>,
    val keyboard: () -> InlineKeyboardMarkup,
    val language: String,
)

/**
 * Launches the "wait for AI → edit placeholders" background job. Extracted from
 * `TelegramNotificationSender` so tests can stub it and so graceful shutdown is managed
 * separately from the notification queue.
 *
 * Uses [DescriptionEditScope] (conditional on `application.ai.description.enabled=true`)
 * for structured concurrency — its `@PreDestroy` cancels in-flight edits on shutdown.
 *
 * `@DependsOn("aiDescriptionTelegramGuard")` forces [AiDescriptionTelegramGuard] to validate the
 * AI+Telegram flag combination before Spring attempts to autowire [TelegramBot] here; on
 * `telegram.enabled=false` the guard throws with an actionable message rather than letting Spring
 * surface a bare `NoSuchBeanDefinitionException` for `TelegramBot`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@DependsOn("aiDescriptionTelegramGuard")
class DescriptionEditJobRunner(
    private val bot: TelegramBot,
    private val renderer: RichNotificationRenderer,
    private val scope: DescriptionEditScope,
) {
    // Tests observe the most-recently launched job via [lastLaunchedJobForTests]. Production code
    // never reads this — it's a write-only side-effect from the runner's own launches.
    private val lastJob = AtomicReference<Job?>(null)

    /** Test-only hook. Returns the most recently launched edit job (may be null if none yet). */
    internal fun lastLaunchedJobForTests(): Job? = lastJob.get()

    /**
     * Одна цель на вызов. Раньше принимался список — фан-аут, которого никогда не было: каждый
     * получатель зовёт метод сам, и `forEach` внутри обходил ровно один элемент, обещая при этом
     * последовательность, которой не существовало.
     */
    fun launchEditJob(
        target: EditTarget,
        handleOutcome: suspend () -> Result<DescriptionResult>,
    ): Job {
        if (!scope.isActive) {
            // launch на отменённом scope возвращает мёртвый Job молча: тело не начнётся, исключения
            // не будет, и сообщение навсегда останется с плейсхолдером. Гонка реальная — очередь
            // останавливается без join, а порядок уничтожения бинов между ней и этим scope Spring
            // не задаёт, потому что связь идёт через ObjectProvider.
            logger.warn {
                "Description edit scope is already closed; the placeholder in chat=${target.chatId} " +
                    "message=${target.messageId} will not be rewritten"
            }
        }
        return scope
            .launch {
                editOne(target, handleOutcome())
            }.also { lastJob.set(it) }
    }

    private suspend fun editOne(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        val state =
            outcome.fold(
                onSuccess = { DescriptionState.Ready(it) },
                onFailure = { DescriptionState.Failed },
            )
        val html = renderer.render(target.data, state, target.fileIds.size, target.language)
        val media = RichNotificationRenderer.mediaFrom(target.fileIds)
        var sentKeyboard: InlineKeyboardMarkup? = null
        val landed =
            runEdit("rich notification", target) {
                // Клавиатура спрашивается на каждой попытке, но внутри попытки она заморожена на весь
                // сетевой вызов — включая повторы лимитера на 429, то есть на секунды.
                val keyboard = target.keyboard()
                sentKeyboard = keyboard
                bot.execute(
                    EditChatMessageRichText(
                        target.chatId,
                        target.messageId,
                        InputRichMessageHTML(html, media = media.ifEmpty { null }),
                        replyMarkup = keyboard,
                    ),
                )
            }
        if (!landed) return
        // Правка переобъявляет reply_markup целиком, а пока она летела, экспорт мог закончиться —
        // restoreButton уже вернул кнопки выбора — или начаться. Тогда наша клавиатура легла последней,
        // и исправить её некому: runExport завершился, а описание больше не правится. Поэтому состояние
        // перечитывается после того, как правка легла, и при расхождении актуальная клавиатура ставится
        // отдельной markup-правкой. Остаточное окно — один вызов API вместо всего полёта rich-правки.
        val current = target.keyboard()
        if (current == sentKeyboard) return
        runEdit("keyboard re-apply", target) {
            bot.execute(EditChatMessageReplyMarkup(target.chatId, target.messageId, replyMarkup = current))
        }
    }

    /**
     * Wraps a single edit call in the expected-failure-tolerant try/catch with bounded retry.
     *
     * - `CancellationException` is re-thrown (structured concurrency — never swallow).
     * - `MessageIsNotModifiedException` / `MessageToEditNotFoundException` are terminal — logged
     *   as DEBUG and NOT retried (message already has this text / user deleted it).
     * - Any other failure retries with backoff up to [EDIT_MAX_ATTEMPTS]. This symmetrises the
     *   initial-send path (which uses `RetryHelper.retryIndefinitely`) so a transient 429 or
     *   network blip on the edit call does not leave the user stuck with the hourglass.
     *   Bounded (not indefinite) because edit is best-effort: after ~3.75 minutes of retries
     *   (see [editBackoffMs]) the job gives up so scope shutdown completes and the placeholder
     *   stays as fallback.
     *
     * @return `true` when [block] completed, i.e. the edit landed; `false` when it was skipped as
     *   terminal or given up — nothing of ours reached the message, so there is nothing to follow up.
     */
    private suspend fun runEdit(
        label: String,
        target: EditTarget,
        block: suspend () -> Unit,
    ): Boolean {
        var attempt = 0
        while (true) {
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: MessageIsNotModifiedException) {
                logger.debug { "Edit skipped for $label (chat=${target.chatId}): message is not modified — ${e.message}" }
                return false
            } catch (e: MessageToEditNotFoundException) {
                logger.debug { "Edit skipped for $label (chat=${target.chatId}): message not found — ${e.message}" }
                return false
            } catch (e: Exception) {
                attempt++
                if (attempt >= EDIT_MAX_ATTEMPTS) {
                    logger.warn(e) { "Failed to edit $label for chat=${target.chatId} after $attempt attempts; giving up" }
                    return false
                }
                val delayMs = editBackoffMs(attempt)
                logger.warn(e) { "Edit $label failed for chat=${target.chatId} (attempt $attempt); retrying in ${delayMs / 1000}s" }
                delay(delayMs)
            }
        }
    }

    companion object {
        private const val EDIT_MAX_ATTEMPTS = 5

        // Backoff schedule for edit retries: 15s, 30s, 60s, 120s → ~3.75 min total before giving up.
        // Tighter than `RetryHelper.calculateBackoff` because edit is best-effort and we do not
        // want the describe-edit-job to linger past reasonable notification freshness.
        internal fun editBackoffMs(attempt: Int): Long {
            val baseMs = 15_000L
            val maxMs = 120_000L
            val d = baseMs * (1L shl minOf(attempt - 1, 4))
            return minOf(d, maxMs)
        }
    }
}
