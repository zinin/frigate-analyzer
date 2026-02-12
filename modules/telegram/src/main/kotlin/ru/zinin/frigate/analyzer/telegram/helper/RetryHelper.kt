package ru.zinin.frigate.analyzer.telegram.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val logger = KotlinLogging.logger {}

object RetryHelper {
    suspend fun <T> retryIndefinitely(
        operationName: String,
        chatId: Long,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val delayMs = calculateBackoff(attempt)
                logger.warn { "$operationName failed for chat $chatId (attempt $attempt): ${e.message}" }
                logger.info { "Retrying $operationName for chat $chatId in ${delayMs / 1000}s" }
                delay(delayMs)
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseDelay = 30_000L // 30 секунд
        val maxDelay = 300_000L // 5 минут
        val delay = baseDelay * (1L shl minOf(attempt - 1, 4))
        return minOf(delay, maxDelay)
    }
}
