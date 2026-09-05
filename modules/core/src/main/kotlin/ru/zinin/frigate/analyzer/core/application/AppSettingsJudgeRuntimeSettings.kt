package ru.zinin.frigate.analyzer.core.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService

private val logger = KotlinLogging.logger {}

/**
 * Рантайм-настройки судьи поверх `app_settings`. Кэш `AppSettingsService` живёт на процесс и
 * сбрасывается только записью через него же: прямой SQL по таблице работающий процесс не увидит.
 */
@Service
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class AppSettingsJudgeRuntimeSettings(
    private val appSettings: AppSettingsService,
) : JudgeRuntimeSettings {
    override val sourceName = "app_settings"

    init {
        // Парная строка к in-memory-дефолту: без них дефолт может незаметно оказаться в проде (бин
        // не зарегистрировался, опечатка в пакете после рефакторинга) — приложение стартует, `/ai`
        // работает, а выбор владельца молча пропадает на каждом рестарте.
        logger.info { "Judge runtime settings: app_settings (the choice survives a restart)" }
    }

    override suspend fun activePresetId(): String? = appSettings.getString(AppSettingKeys.AI_JUDGE_PRESET_ACTIVE, null)

    override suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    ) = appSettings.setString(AppSettingKeys.AI_JUDGE_PRESET_ACTIVE, id, changedBy)

    override suspend fun judgeEnabled(): Boolean = appSettings.getBoolean(AppSettingKeys.AI_JUDGE_ENABLED, true)

    override suspend fun setJudgeEnabled(
        value: Boolean,
        changedBy: String?,
    ) = appSettings.setBoolean(AppSettingKeys.AI_JUDGE_ENABLED, value, changedBy)
}
