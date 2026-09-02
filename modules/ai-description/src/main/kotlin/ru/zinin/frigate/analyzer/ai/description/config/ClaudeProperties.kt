package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
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
    @field:Valid
    val anthropic: AnthropicSection = AnthropicSection(),
    /**
     * Потолок одного JSON-сообщения от CLI. В режиме `stream-json` CLI эхом возвращает
     * `tool_result` с base64-картинкой каждого кадра, который прочитала модель, а дефолт SDK
     * в 1 MiB не вмещает кадр уже от ~750 KB. Строка сверх лимита выбрасывается с ERROR в логе;
     * пока это эхо, описание доходит, но если под лимит попадёт итоговый ответ, `describe`
     * дождётся таймаута и уйдёт в fallback. Последний параметр конструктора, как и всё,
     * что добавляется позже: конструктор вызывается только с именованными аргументами.
     */
    val maxBufferSize: DataSize = DataSize.ofMegabytes(16),
) {
    init {
        require(maxBufferSize.toBytes() in 1..Int.MAX_VALUE.toLong()) {
            "maxBufferSize must be between 1 byte and ${Int.MAX_VALUE} bytes, got: $maxBufferSize"
        }
    }

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )

    data class AnthropicSection(
        val authToken: String = "",
        val baseUrl: String = "",
        val modelOverride: String = "",
        val defaultOpusModel: String = "",
        val defaultSonnetModel: String = "",
        val defaultHaikuModel: String = "",
    )
}
