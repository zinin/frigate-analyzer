package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.ai.description.claude")
@Validated
data class ClaudeProperties(
    val oauthToken: String,
    @field:NotBlank
    val model: String,
    val cliPath: String, // пусто = SDK ищет через `which claude`
    @field:NotBlank
    val workingDirectory: String, // обязателен для SDK 1.0.0
    @field:Valid
    val proxy: ProxySection,
) {
    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
