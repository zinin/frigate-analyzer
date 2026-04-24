package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.CancellationException
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeExceptionMapper {
    /**
     * Маппит произвольный Throwable в иерархию DescriptionException.
     *
     * CRITICAL: CancellationException (в т.ч. TimeoutCancellationException) НЕ оборачиваем —
     * это сломает structured concurrency. Её должен поймать сам describe() на границе withTimeout.
     *
     * Сигнатура обещает возврат DescriptionException, но метод может ТАК ЖЕ выбросить
     * CancellationException — см. @throws ниже. Вызывающему коду стоит писать
     * `throw mapper.map(e)` (как в ClaudeDescriptionAgent.executeWithRetry), тогда
     * cancellation-path остаётся корректным, а возвращённые DescriptionException
     * ловятся штатными catch-ами.
     *
     * @throws CancellationException пробрасывается AS-IS, если [throwable] — её экземпляр.
     */
    fun map(throwable: Throwable): DescriptionException {
        if (throwable is CancellationException) throw throwable
        return when (throwable) {
            is DescriptionException -> {
                throwable
            }

            is JsonProcessingException -> {
                DescriptionException.InvalidResponse(throwable)
            }

            // Rate-limit check goes BEFORE the TransportException branch: CLI-side 429 errors
            // arrive from the SDK as TransportException (which extends ClaudeSDKException), and
            // without this order swap they would be re-tried as generic transport failures
            // instead of surfaced as RateLimited (which skips retry).
            is ClaudeSDKException -> {
                if (isRateLimit(throwable)) {
                    DescriptionException.RateLimited(throwable)
                } else {
                    DescriptionException.Transport(throwable)
                }
            }

            else -> {
                DescriptionException.Transport(throwable)
            }
        }
    }

    private fun isRateLimit(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        // "rate limit" — однозначный hit.
        if ("rate limit" in message) return true
        // "429" плюс любое слово, характерное для HTTP/API-контекста, чтобы избежать
        // false positive на произвольных строках вроде "code 429 offset".
        if (Regex("\\b429\\b").containsMatchIn(message) &&
            ("http" in message || "status" in message || "api" in message || "anthropic" in message)
        ) {
            return true
        }
        return false
    }
}
