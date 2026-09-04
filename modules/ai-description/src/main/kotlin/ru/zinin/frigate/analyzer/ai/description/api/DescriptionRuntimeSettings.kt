package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Рантайм-настройки описаний: какой пресет активен и включены ли описания вообще. Модуль
 * `ai-description` не знает про БД, поэтому это шов — как [TempFileWriter]. Реализация поверх
 * `app_settings` живёт в модуле `core`; при её отсутствии автоконфигурация даёт in-memory дефолт.
 */
interface DescriptionRuntimeSettings {
    /**
     * Куда настройки записаны — для одной INFO-строки об источнике активного пресета. Значение, а
     * не константа в резолвере: `ai-description` не знает ни про `app_settings`, ни про любое другое
     * хранилище, а строка «from app_settings» под in-memory-дефолтом была бы прямой ложью в логе.
     * Абстрактное, а не со значением по умолчанию: забытое переопределение обязано падать на
     * компиляции, иначе оператор молча получит безымянный источник.
     */
    val sourceName: String

    /** null = владелец ничего не выбирал: берётся пресет по умолчанию. */
    suspend fun activePresetId(): String?

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
