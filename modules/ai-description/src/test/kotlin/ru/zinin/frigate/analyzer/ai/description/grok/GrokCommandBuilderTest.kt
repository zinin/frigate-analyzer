package ru.zinin.frigate.analyzer.ai.description.grok

import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrokCommandBuilderTest {
    private fun props(
        cliPath: String = "",
        model: String = "grok-4.6",
        effort: String = "low",
        http: String = "",
        https: String = "",
        noProxy: String = "",
        passThroughEnv: List<String> = emptyList(),
    ) = GrokProperties(
        cliPath = cliPath,
        model = model,
        effort = effort,
        home = "/data/grok-home",
        workingDirectory = "/data/grok-cwd",
        proxy = GrokProperties.ProxySection(http, https, noProxy),
        passThroughEnv = passThroughEnv,
    )

    private val promptFile = Path.of("/tmp/frigate-analyzer/prompt.json")

    @Test
    fun `argv matches the spec exactly`() {
        val command = GrokCommandBuilder(props()).build(promptFile, model = "grok-4.6", effort = "low")

        assertEquals(
            listOf(
                "grok",
                "--prompt-file",
                "/tmp/frigate-analyzer/prompt.json",
                "--json-schema",
                GrokCommandBuilder.JSON_SCHEMA,
                "--output-format",
                "json",
                "-m",
                "grok-4.6",
                "--effort",
                "low",
                "--max-turns",
                "1",
                "--tools",
                "read_file",
                "--disallowed-tools",
                "read_file",
                "--no-plan",
                "--no-subagents",
                "--disable-web-search",
                "--permission-mode",
                "bypassPermissions",
                "--no-auto-update",
                "--system-prompt-override",
                GrokPromptBuilder.SYSTEM_PROMPT,
                "--cwd",
                "/data/grok-cwd",
            ),
            command.argv,
        )
        assertEquals(Path.of("/data/grok-cwd"), command.workingDirectory)
    }

    @Test
    fun `blank effort omits the flag`() {
        val argv = GrokCommandBuilder(props()).build(promptFile, model = "grok-4.6", effort = "").argv
        assertFalse(argv.contains("--effort"))
    }

    @Test
    fun `model and effort come from the call, not from the properties`() {
        val builder = GrokCommandBuilder(props(model = "grok-4.6", effort = "low")) { null }

        val command = builder.build(promptFile, model = "codex-luna", effort = "")

        assertEquals("codex-luna", command.argv[command.argv.indexOf("-m") + 1])
        assertFalse(command.argv.contains("--effort"))
    }

    @Test
    fun `explicit cli path replaces the bare binary name`() {
        val argv =
            GrokCommandBuilder(props(cliPath = "/opt/grok/bin/grok"))
                .build(promptFile, model = "grok-4.6", effort = "low")
                .argv
        assertEquals("/opt/grok/bin/grok", argv.first())
    }

    @Test
    fun `environment carries GROK_HOME and the isolation variables, no proxy when blank`() {
        val env = GrokCommandBuilder(props()).build(promptFile, model = "grok-4.6", effort = "low").environment

        assertEquals("/data/grok-home", env["GROK_HOME"])
        assertEquals("1", env["GROK_DISABLE_AUTOUPDATER"])
        assertEquals("0", env["GROK_MEMORY"])
        assertEquals("0", env["GROK_SUBAGENTS"])
        listOf("AGENTS", "HOOKS", "MCPS", "RULES", "SKILLS").forEach { kind ->
            assertEquals("0", env["GROK_CLAUDE_${kind}_ENABLED"], "GROK_CLAUDE_${kind}_ENABLED")
            assertEquals("0", env["GROK_CURSOR_${kind}_ENABLED"], "GROK_CURSOR_${kind}_ENABLED")
        }
        assertFalse(env.containsKey("HTTP_PROXY"))
        assertFalse(env.containsKey("HTTPS_PROXY"))
        assertFalse(env.containsKey("NO_PROXY"))
    }

    @Test
    fun `proxy variables are passed when configured`() {
        val env =
            GrokCommandBuilder(props(http = "http://proxy:80", https = "http://proxy:443", noProxy = "localhost"))
                .build(promptFile, model = "grok-4.6", effort = "low")
                .environment

        assertEquals("http://proxy:80", env["HTTP_PROXY"])
        assertEquals("http://proxy:443", env["HTTPS_PROXY"])
        assertEquals("localhost", env["NO_PROXY"])
    }

    @Test
    fun `structured output disabled drops the json-schema flag and keeps everything else`() {
        val withSchema = GrokCommandBuilder(props()).build(promptFile, model = "grok-4.6", effort = "low").argv
        val without =
            GrokCommandBuilder(props())
                .build(promptFile, model = "grok-4.6", effort = "low", structuredOutput = false)
                .argv

        assertFalse(without.contains("--json-schema"))
        assertFalse(without.contains(GrokCommandBuilder.JSON_SCHEMA))
        assertEquals(withSchema.filterNot { it == "--json-schema" || it == GrokCommandBuilder.JSON_SCHEMA }, without)
    }

    @Test
    fun `listed environment variables are handed to the child process`() {
        val host = mapOf("MY_GATEWAY_KEY" to "secret", "DB_PASS" to "must not leak")

        val env =
            GrokCommandBuilder(props(passThroughEnv = listOf("MY_GATEWAY_KEY", "ABSENT_KEY")), host::get)
                .build(promptFile, model = "grok-4.6", effort = "low")
                .environment

        assertEquals("secret", env["MY_GATEWAY_KEY"])
        assertFalse(env.containsKey("ABSENT_KEY"))
        assertFalse(env.containsKey("DB_PASS"))
    }

    @Test
    fun `a pass-through name cannot override the grok home or the isolation flags`() {
        val host = mapOf("GROK_HOME" to "/home/developer/.grok", "GROK_MEMORY" to "1")

        val env =
            GrokCommandBuilder(props(passThroughEnv = listOf("GROK_HOME", "GROK_MEMORY")), host::get)
                .build(promptFile, model = "grok-4.6", effort = "low")
                .environment

        assertEquals("/data/grok-home", env["GROK_HOME"])
        assertEquals("0", env["GROK_MEMORY"])
    }

    @Test
    fun `json schema requires exactly short and detailed`() {
        assertTrue(GrokCommandBuilder.JSON_SCHEMA.contains("\"required\":[\"short\",\"detailed\"]"))
        assertTrue(GrokCommandBuilder.JSON_SCHEMA.contains("\"additionalProperties\":false"))
    }
}
