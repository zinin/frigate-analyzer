package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties(prefix = "application.records-watcher")
@Validated
data class RecordsWatcherProperties(
    val disableFirstScan: Boolean = false,
    @field:NotNull
    val folder: Path,
    val watchPeriod: Duration = Duration.ofDays(1),
    val cleanupInterval: Duration = Duration.ofHours(1),
)
