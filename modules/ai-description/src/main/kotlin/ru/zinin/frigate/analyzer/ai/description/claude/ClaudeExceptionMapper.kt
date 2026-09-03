package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.CancellationException
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import tools.jackson.core.JacksonException

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeExceptionMapper {
    /**
     * Маппит произвольный Throwable в иерархию DescriptionException.
     *
     * CRITICAL: CancellationException (в т.ч. TimeoutCancellationException) НЕ оборачиваем —
     * это сломает structured concurrency. Её должен поймать сам describe() на границе withTimeout.
     *
     * Сигнатура обещает возврат DescriptionException, но метод может ТАК ЖЕ выбросить
     * CancellationException — см. @throws ниже. Вызывающему коду стоит писать
     * `throw mapper.map(e)` (как в ClaudeBackend.describe), тогда
     * cancellation-path остаётся корректным, а возвращённые DescriptionException
     * ловятся штатными catch-ами.
     *
     * Jackson branch: ловим И Jackson 2 [JsonProcessingException] (Claude SDK всё ещё может
     * эмитить их при парсинге stream-json), И tools.jackson [JacksonException]. Ветка
     * `is JacksonException` — defensive: [ClaudeResponseParser.parse] оборачивает
     * `readTree(...)` в `try/catch (e: Exception)`, поэтому tools.jackson исключение сегодня
     * до маппера не доходит. Ветка существует для будущих call-sites, которые могут вызывать
     * `internalObjectMapper` напрямую без локального try-catch.
     *
     * @throws CancellationException пробрасывается AS-IS, если [throwable] — её экземпляр.
     */
    fun map(throwable: Throwable): DescriptionException {
        if (throwable is CancellationException) throw throwable
        return when (throwable) {
            is DescriptionException -> {
                throwable
            }

            is JsonProcessingException, is JacksonException -> {
                DescriptionException.InvalidResponse(throwable)
            }

            // Авторизация проверяется раньше rate limit, а rate limit раньше общего Transport:
            // Unauthorized не повторяется и поднимает событие, RateLimited не повторяется,
            // Transport повторяется один раз.
            is ClaudeSDKException -> {
                when {
                    isUnauthorized(throwable) -> {
                        DescriptionException.Unauthorized(
                            throwable.message ?: "authentication error",
                            throwable,
                        )
                    }

                    isRateLimit(throwable) -> {
                        DescriptionException.RateLimited(throwable)
                    }

                    else -> {
                        DescriptionException.Transport(throwable)
                    }
                }
            }

            else -> {
                DescriptionException.Transport(throwable)
            }
        }
    }

    private fun isUnauthorized(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        return AUTH_MARKERS.any { it in message }
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

    private companion object {
        val AUTH_MARKERS = listOf("authentication_error", "invalid api key", "oauth token")
    }
}
