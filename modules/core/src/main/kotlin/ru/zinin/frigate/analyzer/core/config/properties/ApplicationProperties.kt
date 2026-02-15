package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties(prefix = "application")
@Validated
data class ApplicationProperties(
    @field:NotNull
    val tempFolder: Path,
    @field:NotNull
    val ffmpegPath: Path,
    @field:NotNull
    val connectionTimeout: Duration,
    @field:NotNull
    val readTimeout: Duration,
    @field:NotNull
    val writeTimeout: Duration,
    @field:NotNull
    val responseTimeout: Duration,
    @field:NotEmpty
    val detectServers: Map<String, DetectServerProperties> = emptyMap(),
)
