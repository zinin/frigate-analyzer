package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.persistent.AppSettingEntity
import java.time.Instant

@Repository
interface AppSettingRepository : CoroutineCrudRepository<AppSettingEntity, String> {
    suspend fun findBySettingKey(settingKey: String): AppSettingEntity?

    @Modifying
    @Query(
        """
        INSERT INTO app_settings (setting_key, setting_value, updated_at, updated_by)
        VALUES (:settingKey, :settingValue, :updatedAt, :updatedBy)
        ON CONFLICT (setting_key) DO UPDATE
          SET setting_value = EXCLUDED.setting_value,
              updated_at    = EXCLUDED.updated_at,
              updated_by    = EXCLUDED.updated_by
        """,
    )
    suspend fun upsert(
        @Param("settingKey") settingKey: String,
        @Param("settingValue") settingValue: String,
        @Param("updatedAt") updatedAt: Instant,
        @Param("updatedBy") updatedBy: String?,
    ): Long
}
