package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Рантайм-настройки описаний: какой пресет активен и включены ли описания вообще. Модуль
 * `ai-description` не знает про БД, поэтому это шов — как [TempFileWriter]. Реализация поверх
 * `app_settings` живёт в модуле `core`; при её отсутствии автоконфигурация даёт in-memory дефолт.
 */
interface DescriptionRuntimeSettings : PresetChoiceSource {
    suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    )

    /** Отсутствие настройки означает «включено»: статический выключатель фичи главнее. */
    suspend fun descriptionsEnabled(): Boolean

    suspend fun setDescriptionsEnabled(
        value: Boolean,
        changedBy: String?,
    )
}
