package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path

/**
 * Провайдер `grok`: headless-вызов бинарника Grok Build. Биндится всегда, как [ClaudeProperties],
 * поэтому дефолты в yaml обязаны быть валидными и при `provider=claude`.
 */
@ConfigurationProperties(prefix = "application.ai.description.grok")
@Validated
data class GrokProperties(
    /** Пусто = `grok` ищется по PATH. */
    val cliPath: String,
    /** Модель xAI или имя `[model.<name>]` BYOK-записи из `config.toml` в [home]. */
    @field:NotBlank
    val model: String,
    /** Пусто = флаг `--effort` не передаётся (BYOK-модели без уровней reasoning). */
    @field:Pattern(
        regexp = "|none|minimal|low|medium|high|xhigh",
        message = "must be empty or one of none, minimal, low, medium, high, xhigh",
    )
    val effort: String,
    /** `GROK_HOME` дочернего процесса: `auth.json`, `config.toml`, сессии. В контейнере это том. */
    @field:NotBlank
    val home: String,
    /** Пустой каталог для `--cwd`: Grok читает из cwd AGENTS.md, CLAUDE.md, `.claude/rules`, `.grok`. */
    @field:NotBlank
    val workingDirectory: String,
    @field:Valid
    val proxy: ProxySection,
) {
    val homePath: Path
        get() = Path.of(home).toAbsolutePath().normalize()

    val workingDirectoryPath: Path
        get() = Path.of(workingDirectory).toAbsolutePath().normalize()

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
