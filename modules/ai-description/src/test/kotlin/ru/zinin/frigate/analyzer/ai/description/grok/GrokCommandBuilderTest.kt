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
        effort: String = "low",
        http: String = "",
        https: String = "",
        noProxy: String = "",
    ) = GrokProperties(
        cliPath = cliPath,
        model = "grok-4.6",
        effort = effort,
        home = "/data/grok-home",
        workingDirectory = "/data/grok-cwd",
        proxy = GrokProperties.ProxySection(http, https, noProxy),
    )

    private val promptFile = Path.of("/tmp/frigate-analyzer/prompt.json")

    @Test
    fun `argv matches the spec exactly`() {
        val command = GrokCommandBuilder(props()).build(promptFile)

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
        val argv = GrokCommandBuilder(props(effort = "")).build(promptFile).argv
        assertFalse(argv.contains("--effort"))
    }

    @Test
    fun `explicit cli path replaces the bare binary name`() {
        val argv = GrokCommandBuilder(props(cliPath = "/opt/grok/bin/grok")).build(promptFile).argv
        assertEquals("/opt/grok/bin/grok", argv.first())
    }

    @Test
    fun `environment carries GROK_HOME and the isolation variables, no proxy when blank`() {
        val env = GrokCommandBuilder(props()).build(promptFile).environment

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
                .build(promptFile)
                .environment

        assertEquals("http://proxy:80", env["HTTP_PROXY"])
        assertEquals("http://proxy:443", env["HTTPS_PROXY"])
        assertEquals("localhost", env["NO_PROXY"])
    }

    @Test
    fun `json schema requires exactly short and detailed`() {
        assertTrue(GrokCommandBuilder.JSON_SCHEMA.contains("\"required\":[\"short\",\"detailed\"]"))
        assertTrue(GrokCommandBuilder.JSON_SCHEMA.contains("\"additionalProperties\":false"))
    }
}
