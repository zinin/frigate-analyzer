package ru.zinin.frigate.analyzer.service

interface AppSettingsService {
    suspend fun getBoolean(
        key: String,
        default: Boolean = false,
    ): Boolean

    suspend fun setBoolean(
        key: String,
        value: Boolean,
        updatedBy: String? = null,
    )

    suspend fun getString(
        key: String,
        default: String? = null,
    ): String?

    suspend fun setString(
        key: String,
        value: String,
        updatedBy: String? = null,
    )
}
