package ru.zinin.frigate.analyzer.telegram.dto

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates

data class AiSettingsViewState(
    val descriptionsEnabled: Boolean,
    /** Что выбрал владелец. null = ключа нет, работает `default-preset`. */
    val storedPresetId: String?,
    /** Что реально применит следующий вызов. null = каталога нет: пресеты не объявлены. */
    val effectivePresetId: String?,
    val presets: List<DescriptionPreset>,
    /**
     * Ключ — `authScopeId`, область учётных данных (`claude`, `grok:grok-4.6`), а не провайдер:
     * два grok-пресета на одной модели делят `auth.json`, а BYOK-модель ходит по собственному
     * ключу, и её успех ничего не говорит о сессии xAI. Снимок, снятый один раз при сборке
     * состояния: `ProviderAuthStates.byScope()` строит карту заново на каждый вызов.
     */
    val authByScope: Map<String, ProviderAuthStates.Health>,
    val language: String,
) {
    /**
     * Сохранённый пресет существует, но работает не он: рендер печатает строку
     * `ai.settings.active.mismatch`. Без этого владелец не видит, что его выбор перекрыт,
     * а битый id живёт в `app_settings` вечно — кликать по fallback-у незачем.
     */
    val hasMismatch: Boolean
        get() = storedPresetId != null && effectivePresetId != null && storedPresetId != effectivePresetId
}
