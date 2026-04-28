package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table(name = "app_settings")
data class AppSettingEntity(
    @JvmField
    @Id
    @Column("setting_key")
    var settingKey: String?,
    @Column("setting_value")
    var settingValue: String?,
    @Column("updated_at")
    var updatedAt: Instant?,
    @Column("updated_by")
    var updatedBy: String? = null,
) : Persistable<String> {
    override fun getId(): String? = settingKey

    // Always treated as "new" so save() emits an INSERT; we use upsert SQL via the repo for updates.
    override fun isNew(): Boolean = true
}
