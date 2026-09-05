package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ActiveJudgePreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Единая точка сборки состояния экрана: команда и перерисовка после коллбэка читают одно и то же.
 *
 * Зависимости через [ObjectProvider] потому, что **пресеты могут быть не объявлены** — тогда бинов
 * каталога нет вовсе, а бот обязан стартовать. Сам класс условен только на
 * `application.telegram.enabled`; гейт на флаг фичи стоит у команды.
 *
 * Зависимость только на `api`: резолвер из `core` за пределы модуля `ai-description` не выходит.
 *
 * Все чтения настроек здесь fail-open: экран открывают ровно тогда, когда что-то сломалось, и отказ
 * `app_settings` не должен уносить с собой список пресетов и состояние авторизации, которые
 * читаются мимо базы.
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
    private val judgeRuntimeSettingsProvider: ObjectProvider<JudgeRuntimeSettings>,
    private val activeJudgePresetProvider: ObjectProvider<ActiveJudgePreset>,
) {
    /**
     * Шов, оставленный сознательно: [ActiveDescriptionPreset.storedId] и
     * [ActiveDescriptionPreset.effective] — два независимых прохода через резолвер, каждый со своим
     * потолком в 5 с и своим fail-open. Пара «сохранённый + эффективный» поэтому может не
     * соответствовать ни одному мгновению: мигающий `app_settings` или чужая запись между двумя
     * чтениями дают целый и годный сохранённый пресет рядом с другим эффективным — ровно то, что
     * описывает `ai.settings.reason.unknown`. Второй счёт: на отказе хранилища `/ai` рисуется до
     * 10 с, потолки складываются.
     *
     * Одно согласованное чтение (`resolve()` считает обе величины разом, и резолверу достаточно
     * выставить эту пару наружу) убрало бы и несогласованность, и половину задержки, но меняет
     * контракт `api`, замороженный в Task 4. Правку сюда стоит начинать с него.
     */
    suspend fun build(language: String): AiSettingsViewState {
        val presets = presetsProvider.getIfAvailable()?.all().orEmpty()
        val active = activePresetProvider.getIfAvailable()
        val activeJudge = activeJudgePresetProvider.getIfAvailable()
        return AiSettingsViewState(
            descriptionsEnabled = descriptionsEnabled(),
            storedPresetId = active?.storedId(),
            // Резолюция требует каталога: без пресетов эффективного просто нет.
            effectivePresetId = if (presets.isEmpty()) null else active?.effective()?.id,
            presets = presets,
            // Один раз на сборку состояния: byScope() строит карту заново на каждый вызов.
            authByScope = authStatesProvider.getIfAvailable()?.byScope().orEmpty(),
            language = language,
            judgeAvailable = judgeRuntimeSettingsProvider.getIfAvailable() != null,
            judgeEnabled = judgeEnabled(),
            judgeStoredPresetId = activeJudge?.storedId(),
            judgeEffectivePresetId = if (presets.isEmpty()) null else activeJudge?.effective()?.id,
        )
    }

    /**
     * Флаг читается fail-open в `true` — дословно как `RecordingProcessingFacade.descriptionsEnabled`,
     * и по той же причине: `true` — документированное умолчание самого ключа, поэтому во время
     * отказа `app_settings` экран и конвейер говорят об описаниях одно и то же. Исключение отсюда
     * закрывало бы `/ai` целиком — экран, который и открывают, чтобы разобраться в таком отказе.
     */
    private suspend fun descriptionsEnabled(): Boolean =
        flagFailOpen("AI description switch") {
            runtimeSettingsProvider.getIfAvailable()?.descriptionsEnabled() ?: true
        }

    private suspend fun judgeEnabled(): Boolean =
        flagFailOpen("AI judge switch") {
            judgeRuntimeSettingsProvider.getIfAvailable()?.judgeEnabled() ?: true
        }

    private suspend fun flagFailOpen(
        label: String,
        read: suspend () -> Boolean,
    ): Boolean =
        try {
            withTimeout(SETTINGS_READ_TIMEOUT) { read() }
        } catch (e: TimeoutCancellationException) {
            logger.warn {
                "Reading the $label for the /ai screen timed out after $SETTINGS_READ_TIMEOUT; failing open"
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the $label for the /ai screen; failing open" }
            true
        }

    private companion object {
        val SETTINGS_READ_TIMEOUT = 5.seconds
    }
}
