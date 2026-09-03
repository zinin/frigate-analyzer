package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Validation
import jakarta.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `effort` уезжает в argv как есть, а grok 1.0.13 проверяет уровень до вызова модели и падает с
 * exit 1 на неизвестном. Валидация должна ловить это на старте, а не на каждом описании.
 */
class GrokPropertiesTest {
    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    private fun props(effort: String) =
        GrokProperties(
            cliPath = "",
            model = "grok-4.6",
            effort = effort,
            home = "/tmp/frigate-analyzer/grok-home",
            workingDirectory = "/tmp/frigate-analyzer/grok-cwd",
            proxy = GrokProperties.ProxySection("", "", ""),
        )

    @Test
    fun `levels grok accepts pass validation`() {
        listOf("", "low", "medium", "high", "xhigh", "max").forEach { effort ->
            assertTrue(validator.validate(props(effort)).isEmpty(), "effort='$effort' must be accepted")
        }
    }

    @Test
    fun `levels grok rejects with exit 1 do not pass validation`() {
        listOf("none", "minimal", "ultra", "LOW", " low").forEach { effort ->
            val violations = validator.validate(props(effort))
            assertEquals(1, violations.size, "effort='$effort' must be rejected")
            assertTrue(violations.first().message.contains("low, medium, high, xhigh, max"))
        }
    }

    @Test
    fun `paths are absolute and normalized`() {
        val props =
            GrokProperties(
                cliPath = "",
                model = "grok-4.6",
                effort = "low",
                home = "/tmp/frigate-analyzer//grok-home",
                workingDirectory = "/tmp/frigate-analyzer/./grok-cwd",
                proxy = GrokProperties.ProxySection("", "", ""),
            )

        assertEquals("/tmp/frigate-analyzer/grok-home", props.homePath.toString())
        assertEquals("/tmp/frigate-analyzer/grok-cwd", props.workingDirectoryPath.toString())
    }
}
