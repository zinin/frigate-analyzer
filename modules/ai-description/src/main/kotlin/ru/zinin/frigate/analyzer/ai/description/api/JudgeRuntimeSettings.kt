package ru.zinin.frigate.analyzer.ai.description.api

interface JudgeRuntimeSettings : PresetChoiceSource {
    suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    )

    /** Отсутствие настройки означает «включён»; статический флаг фичи главнее. */
    suspend fun judgeEnabled(): Boolean

    suspend fun setJudgeEnabled(
        value: Boolean,
        changedBy: String?,
    )
}
