package ru.zinin.frigate.analyzer.ai.description.core

import java.time.Duration

/**
 * SPI провайдера: одна попытка без семафора, таймаутов и повторов — всё это делает
 * [VisionCallExecutor]. Возвращает сырой текст модели; разбор — дело задачи. Реализация обязана
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
    ): String
}
