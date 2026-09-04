package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState

/**
 * Единая точка сборки состояния экрана: команда и перерисовка после коллбэка читают одно и то же.
 *
 * Зависимости через [ObjectProvider] потому, что **пресеты могут быть не объявлены** — тогда бинов
 * каталога нет вовсе, а бот обязан стартовать. Сам класс условен только на
 * `application.telegram.enabled`; гейт на флаг фичи стоит у команды.
 *
 * Зависимость только на `api`: резолвер из `core` за пределы модуля `ai-description` не выходит.
 *
 * Принятый зазор: [ActiveDescriptionPreset.storedId] читает настройки fail-open, поэтому при отказе
 * хранилища он вернёт null и экран нарисует ✅ на пресете по умолчанию, как будто его и выбрали.
 * Третьего состояния «прочитать не удалось» здесь нет сознательно — оно означало бы правку
 * контракта `api`, — а вероятная реакция владельца (выбрать пресет заново) идемпотентна.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AiSettingsViewStateFactory(
    private val presetsProvider: ObjectProvider<DescriptionPresets>,
    private val activePresetProvider: ObjectProvider<ActiveDescriptionPreset>,
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
    private val authStatesProvider: ObjectProvider<ProviderAuthStates>,
) {
    suspend fun build(language: String): AiSettingsViewState {
        val presets = presetsProvider.getIfAvailable()?.all().orEmpty()
        val active = activePresetProvider.getIfAvailable()
        return AiSettingsViewState(
            descriptionsEnabled = runtimeSettingsProvider.getIfAvailable()?.descriptionsEnabled() ?: true,
            storedPresetId = active?.storedId(),
            // Резолюция требует каталога: без пресетов эффективного просто нет.
            effectivePresetId = if (presets.isEmpty()) null else active?.effective()?.id,
            presets = presets,
            // Один раз на сборку состояния: byScope() строит карту заново на каждый вызов.
            authByScope = authStatesProvider.getIfAvailable()?.byScope().orEmpty(),
            language = language,
        )
    }
}
