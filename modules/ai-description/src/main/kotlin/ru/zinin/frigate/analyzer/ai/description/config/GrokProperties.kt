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
    /**
     * Пусто = флаг `--effort` не передаётся (BYOK-модели без уровней reasoning). Набор уровней
     * grok 1.0.13 проверяет до вызова модели: `none` и `minimal` он отвергает с exit 1, `max`
     * принимает у BYOK-моделей, объявивших его в `reasoning_efforts`.
     */
    @field:Pattern(
        regexp = "|low|medium|high|xhigh|max",
        message = "must be empty or one of low, medium, high, xhigh, max",
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
    /**
     * Имена переменных окружения JVM, которые нужно передать дочернему `grok` дословно. Дочернее
     * окружение собирается с нуля, и из хоста наследуются только `GROK_*` и `XAI_*`, поэтому BYOK-модель
     * с `env_key = "MY_GATEWAY_KEY"` в `config.toml` иначе осталась бы без ключа. Всё, что здесь не
     * перечислено и не начинается с этих префиксов, до `grok` не доходит — включая `DB_PASS` и
     * `TELEGRAM_BOT_TOKEN`.
     */
    val passThroughEnv: List<String> = emptyList(),
) {
    init {
        passThroughEnv.forEach { name ->
            require(ENV_NAME.matches(name)) { "pass-through-env entry '$name' is not an environment variable name" }
        }
    }

    val homePath: Path
        get() = Path.of(home).toAbsolutePath().normalize()

    val workingDirectoryPath: Path
        get() = Path.of(workingDirectory).toAbsolutePath().normalize()

    companion object {
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
