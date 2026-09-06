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
class GrokCommandBuilder(
    private val properties: GrokProperties,
    /** Окружение JVM; параметром — чтобы тесты не зависели от переменных машины. */
    private val environmentSource: (String) -> String? = System::getenv,
) {
    /**
     * [model] и [effort] — параметры вызова, а не свойства бина: один builder обслуживает несколько
     * пресетов. Пустой [effort] убирает `--effort` целиком — BYOK-модели без уровней рассуждения
     * отвергают флаг, а не его пустое значение.
     *
     * [structuredOutput] `false` или пустой [jsonSchema] убирают `--json-schema`: эндпоинт BYOK-модели
     * отверг схему (`response_format` / grammar), и ответ будет разобран из текстового JSON.
     */
    fun build(
        promptFile: Path,
        model: String,
        effort: String,
        structuredOutput: Boolean,
        jsonSchema: String?,
        systemPrompt: String,
    ): GrokCommand {
        val argv =
            buildList {
                add(properties.cliPath.ifBlank { "grok" })
                add("--prompt-file")
                add(promptFile.toAbsolutePath().normalize().toString())
                if (structuredOutput && jsonSchema != null) {
                    add("--json-schema")
                    add(jsonSchema)
                }
                add("--output-format")
                add("json")
                add("-m")
                add(model)
                if (effort.isNotBlank()) {
                    add("--effort")
                    add(effort)
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
                add(systemPrompt)
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
