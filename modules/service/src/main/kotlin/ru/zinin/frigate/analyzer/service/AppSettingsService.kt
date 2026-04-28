package ru.zinin.frigate.analyzer.service

interface AppSettingsService {
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean

    suspend fun setBoolean(key: String, value: Boolean, updatedBy: String? = null)
}
