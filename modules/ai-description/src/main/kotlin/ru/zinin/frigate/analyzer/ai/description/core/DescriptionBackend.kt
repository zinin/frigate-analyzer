package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * SPI провайдера описаний: одна попытка без семафора, таймаутов и повторов. Всё это делает
 * [DefaultDescriptionAgent]. Реализация обязана бросать только `DescriptionException` или
 * `CancellationException`; любое другое исключение агент оборачивает в `Transport`.
 */
interface DescriptionBackend {
    /** `claude`, `grok`. Попадает в логи повторов и в `DescriptionPreset.provider`. */
    val providerId: String

    /**
     * Область учётных данных пресета: `claude`, `grok:<model>`. Ключ состояния в
     * [ProviderAuthTracker] — авторизация принадлежит набору ключей, а не провайдеру. Значение
     * приходит от [DescriptionBackendFactory.authScopeId], чтобы backend и его строка в каталоге
     * несли по построению одну и ту же область.
     */
    val authScopeId: String

    /** Команда, которой владелец чинит авторизацию; попадает в сообщение владельцу. */
    val authRecoveryHint: String

    suspend fun describe(request: DescriptionRequest): DescriptionResult
}
