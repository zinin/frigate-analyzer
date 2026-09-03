package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Path

/**
 * Собирает argv и env для одного headless-вызова Grok Build. Флаги зафиксированы spec-ом:
 * `--json-schema` даёт готовый объект в `structuredOutput`; `--tools read_file` это allowlist,
 * который отключает инъекцию инструментов по умолчанию, а `--disallowed-tools read_file` снимает
 * и этот один инструмент (кадры уже inline); `--max-turns 1` запрещает второй ход;
 * `--system-prompt-override` заменяет промпт кодового агента; `--cwd` указывает на пустой каталог.
 * Env изолирует процесс от skills, rules и плагинов Claude Code и Cursor, которые Grok иначе
 * читает из HOME.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokCommandBuilder(
    private val properties: GrokProperties,
    /** Окружение JVM; параметром — чтобы тесты не зависели от переменных машины. */
    private val environmentSource: (String) -> String? = System::getenv,
) {
    /**
     * [structuredOutput] `false` убирает `--json-schema`: эндпоинт BYOK-модели отверг схему
     * (`response_format` / grammar), и ответ будет разобран из текстового JSON.
     */
    fun build(
        promptFile: Path,
        structuredOutput: Boolean = true,
    ): GrokCommand {
        val argv =
            buildList {
                add(properties.cliPath.ifBlank { "grok" })
                add("--prompt-file")
                add(promptFile.toAbsolutePath().normalize().toString())
                if (structuredOutput) {
                    add("--json-schema")
                    add(JSON_SCHEMA)
                }
                add("--output-format")
                add("json")
                add("-m")
                add(properties.model)
                if (properties.effort.isNotBlank()) {
                    add("--effort")
                    add(properties.effort)
                }
                add("--max-turns")
                add("1")
                add("--tools")
                add("read_file")
                add("--disallowed-tools")
                add("read_file")
                add("--no-plan")
                add("--no-subagents")
                add("--disable-web-search")
                add("--permission-mode")
                add("bypassPermissions")
                add("--no-auto-update")
                add("--system-prompt-override")
                add(GrokPromptBuilder.SYSTEM_PROMPT)
                add("--cwd")
                add(properties.workingDirectoryPath.toString())
            }
        val environment =
            buildMap {
                // Первыми, чтобы опечатка в pass-through-env не перебила GROK_HOME или прокси.
                properties.passThroughEnv.forEach { name ->
                    environmentSource(name)?.let { value -> put(name, value) }
                }
                put("GROK_HOME", properties.homePath.toString())
                putAll(ISOLATION_ENV)
                val proxy = properties.proxy
                if (proxy.http.isNotBlank()) put("HTTP_PROXY", proxy.http)
                if (proxy.https.isNotBlank()) put("HTTPS_PROXY", proxy.https)
                if (proxy.noProxy.isNotBlank()) put("NO_PROXY", proxy.noProxy)
            }
        return GrokCommand(argv, environment, properties.workingDirectoryPath)
    }

    companion object {
        const val JSON_SCHEMA =
            """{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}"""

        val ISOLATION_ENV: Map<String, String> =
            buildMap {
                put("GROK_DISABLE_AUTOUPDATER", "1")
                put("GROK_MEMORY", "0")
                put("GROK_SUBAGENTS", "0")
                listOf("CLAUDE", "CURSOR").forEach { tool ->
                    listOf("AGENTS", "HOOKS", "MCPS", "RULES", "SKILLS").forEach { kind ->
                        put("GROK_${tool}_${kind}_ENABLED", "0")
                    }
                }
            }
    }
}
