package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

/**
 * SPI провайдера: пригодность (есть ли учётные данные и всё, без чего вызов заведомо не пройдёт) и
 * создание backend-а под конкретный пресет. Проверки, которые раньше жили в `init` backend-ов,
 * принадлежат фабрике: backend-ов на один провайдер теперь столько, сколько пресетов.
 */
interface VisionBackendFactory {
    val providerId: String

    /**
     * Вычисляется один раз на старте: и токен, и наличие CLI приходят из окружения процесса. Здесь
     * же живёт осмотр окружения — создание каталогов и предупреждения, — поэтому
     * [DescriptionPresetCatalogBuilder] зовёт метод только у провайдеров, встречающихся хотя бы в
     * одном объявленном пресете.
     */
    fun availability(): Availability

    /**
     * Модель, которая реально уйдёт в запрос. По умолчанию — объявленная: вытеснить её умеет только
     * сам провайдер (у claude это `ANTHROPIC_MODEL`), а знать об этом снаружи неоткуда.
     */
    fun effectiveModel(preset: DescriptionProperties.Preset): String = preset.model

    /**
     * Область учётных данных пресета. Без умолчания: только провайдер знает, чем ограничена его
     * авторизация — общей сессией (`claude`) или парой «провайдер плюс модель» (`grok:<model>`,
     * где BYOK-модель ходит по собственному ключу).
     */
    fun authScopeId(preset: DescriptionProperties.Preset): String

    fun create(preset: DescriptionProperties.Preset): VisionBackend

    sealed interface Availability {
        data object Available : Availability

        data class Unavailable(
            val reason: UnavailableReason,
        ) : Availability
    }
}
