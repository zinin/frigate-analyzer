package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * SPI провайдера описаний: одна попытка без семафора, таймаутов и повторов. Всё это делает
 * [DefaultDescriptionAgent]. Реализация обязана бросать только `DescriptionException` или
 * `CancellationException`; любое другое исключение агент оборачивает в `Transport`.
 */
interface DescriptionBackend {
    /** `claude`, `grok`. Попадает в события авторизации и логи. */
    val providerId: String

    /** Команда, которой владелец чинит авторизацию; попадает в сообщение владельцу. */
    val authRecoveryHint: String

    suspend fun describe(request: DescriptionRequest): DescriptionResult
}
