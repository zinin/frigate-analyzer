package ru.zinin.frigate.analyzer.ai.description.core

/**
 * SPI провайдера: одна попытка без семафора, таймаутов и повторов — всё это делает
 * [DefaultDescriptionAgent]. Возвращает сырой текст модели; разбор — дело задачи. Реализация обязана
 * бросать только `DescriptionException` или `CancellationException`; остальное агент оборачивает
 * в `Transport`.
 */
interface VisionBackend {
    /** `claude`, `grok`. */
    val providerId: String

    /** Область учётных данных: `claude`, `grok:<model>`. Ключ состояния в [ProviderAuthTracker]. */
    val authScopeId: String

    /** Команда, которой владелец чинит авторизацию. */
    val authRecoveryHint: String

    suspend fun complete(request: VisionRequest): String
}
