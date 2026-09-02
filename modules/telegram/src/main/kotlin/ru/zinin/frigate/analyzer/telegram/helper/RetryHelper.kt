package ru.zinin.frigate.analyzer.telegram.helper

import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * Повтор отправки в Telegram с двумя порогами вместо бесконечного цикла.
 *
 * Бесконечный повтор превращал один отвергнутый навсегда запрос — 400 на битую разметку, 403 от
 * заблокировавшего бота пользователя — в остановку единственного потребителя очереди: уведомления
 * переставали приходить всем, по всем камерам, до перезапуска. Пороги делят сбои на два вида:
 * - **ответ есть** — тот же запрос получит тот же ответ, повтор лишь задерживает всех, кто стоит
 *   в очереди следом: [MAX_ANSWERED_FAILURES] попытки, полторы минуты;
 * - **ответа нет** — простой сети или Telegram, который обычно проходит, а тревога с опозданием
 *   лучше, чем никакой: [MAX_ATTEMPTS] попыток, около 83 минут.
 * Исчерпание порога — исключение наружу: `TelegramNotificationQueue` пишет ERROR и берёт следующую
 * задачу. Числа — константы намеренно, как пороги `TelegramBotSupervisor`: лишний рычаг в
 * конфигурации здесь не нужен.
 */
object RetryHelper {
    /** Ответ повторится тем же: 30с + 60с между тремя попытками. */
    internal const val MAX_ANSWERED_FAILURES = 3

    /** Ответа нет: 30 + 60 + 120 + 240 с, дальше по 300 с — 4950 с до последней попытки. */
    internal const val MAX_ATTEMPTS = 20

    suspend fun <T> retryBounded(
        operationName: String,
        chatId: Long,
        block: suspend () -> T,
    ): T {
        var attempts = 0
        var answeredFailures = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts++
                if (e.isAnswered()) answeredFailures++
                if (answeredFailures >= MAX_ANSWERED_FAILURES || attempts >= MAX_ATTEMPTS) {
                    logger.warn {
                        "$operationName failed for chat $chatId (attempt $attempts, " +
                            "$answeredFailures error answers): ${e.message}; giving up"
                    }
                    throw e
                }
                val delayMs = calculateBackoff(attempts)
                logger.warn { "$operationName failed for chat $chatId (attempt $attempts): ${e.message}" }
                logger.info { "Retrying $operationName for chat $chatId in ${delayMs / 1000}s" }
                delay(delayMs)
            }
        }
    }

    /**
     * «Ответ есть» значит: повтор того же запроса даст тот же результат. Это любой [RequestException],
     * кроме флуд-контроля, и всё, у чего в причинах нет сети — неразобранный ответ (исполнитель
     * библиотеки заворачивает его в тот же `CommonBotException`, что и сетевой сбой), ошибка в нашем
     * собственном коде отправки. «Ответа нет» — 429 (штатно его повторяет лимитер библиотеки, сюда он
     * не доходит), HTTP-ошибка без конверта Bot API вроде страницы 502 от шлюза и [IOException] в
     * цепочке причин.
     */
    private fun Throwable.isAnswered(): Boolean =
        when {
            this is TooMuchRequestsException -> false
            this is RequestException -> true
            this is ApiException -> false
            else -> !isCausedByTransport()
        }

    /** [BotException] сам наследует [IOException], поэтому у него цепочка причин смотрится ниже. */
    private fun Throwable.isCausedByTransport(): Boolean =
        generateSequence(if (this is BotException) cause else this) { it.cause }
            .any { it is IOException && it !is BotException }

    private fun calculateBackoff(attempt: Int): Long {
        val baseDelay = 30_000L // 30 seconds
        val maxDelay = 300_000L // 5 minutes
        val delay = baseDelay * (1L shl minOf(attempt - 1, 4))
        return minOf(delay, maxDelay)
    }
}
