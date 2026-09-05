package ru.zinin.frigate.analyzer.ai.description.core

import java.time.Duration

/**
 * Ответ одной попытки. [text] — то, что провайдер считает ответом. [fallback] — второе
 * представление того же ответа, если провайдер вернул оба (Grok кладёт объект по схеме в
 * `structuredOutput`, а его же текстом в `text`): [VisionCallExecutor] разбирает его, только если
 * задача отвергла [text], и делает это в пределах той же попытки — без второго вызова модели.
 */
data class VisionResponse(
    val text: String,
    val fallback: String? = null,
)

/**
 * SPI провайдера: одна попытка без семафора, таймаутов и повторов — всё это делает
 * [VisionCallExecutor]. Возвращает сырой ответ модели; разбор — дело задачи. Реализация обязана
 * бросать только `DescriptionException` или `CancellationException`; остальное executor оборачивает
 * в `Transport`.
 */
interface VisionBackend {
    /** `claude`, `grok`. */
    val providerId: String

    /** Область учётных данных: `claude`, `grok:<model>`. Ключ состояния в [ProviderAuthTracker]. */
    val authScopeId: String

    /** Команда, которой владелец чинит авторизацию. */
    val authRecoveryHint: String

    /**
     * @param timeout бюджет, отпущенный вызову задачей: [VisionCallExecutor] меряет им свой
     * `withTimeout`, и провайдер со своей обвязкой таймаутов обязан считать её от этого значения,
     * а не от чужой настройки — у описаний и судьи бюджеты разные.
     */
    suspend fun complete(
        request: VisionRequest,
        timeout: Duration,
    ): VisionResponse
}
