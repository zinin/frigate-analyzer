package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

/**
 * Биндит `application.ai.description` из production-yaml. Пресеты в базовом файле не объявлены —
 * их место в смонтированном `application-docker.yaml`, поэтому здесь проверяются дефолты и то,
 * что объявленная снаружи карта доезжает до типа целиком.
 */
class DescriptionPresetsBindingTest {
    @Test
    fun `presets are empty and default-preset is blank out of the box`() {
        val props = bind()

        assertThat(props.presets).isEmpty()
        assertThat(props.defaultPreset).isEmpty()
        assertThat(props.provider).isEqualTo("claude")
    }

    @Test
    fun `a declared map binds together with APP_AI_DESCRIPTION_DEFAULT_PRESET`() {
        val props =
            bind(
                env = mapOf("APP_AI_DESCRIPTION_DEFAULT_PRESET" to "grok-fast"),
                properties =
                    mapOf(
                        "application.ai.description.presets.grok-fast.provider" to "grok",
                        "application.ai.description.presets.grok-fast.model" to "grok-4.6",
                        "application.ai.description.presets.grok-fast.effort" to "low",
                        "application.ai.description.presets.claude-opus.provider" to "claude",
                        "application.ai.description.presets.claude-opus.model" to "opus",
                    ),
            )

        assertThat(props.defaultPreset).isEqualTo("grok-fast")
        // containsExactly, а не InAnyOrder: правило "fallbackId = первый годный" опирается
        // именно на порядок объявления в yaml, и без этого ассерта он ничем не зафиксирован.
        assertThat(props.presets.keys).containsExactly("grok-fast", "claude-opus")
        assertThat(props.presets.getValue("grok-fast").model).isEqualTo("grok-4.6")
        assertThat(props.presets.getValue("grok-fast").effort).isEqualTo("low")
        assertThat(props.presets.getValue("claude-opus").effort).isEmpty()
    }

    @Test
    fun `a default-preset outside the map fails the binding`() {
        val thrown =
            catchThrowable {
                bind(
                    env = mapOf("APP_AI_DESCRIPTION_DEFAULT_PRESET" to "missing"),
                    properties =
                        mapOf(
                            "application.ai.description.presets.grok-fast.provider" to "grok",
                            "application.ai.description.presets.grok-fast.model" to "grok-4.6",
                        ),
                )
            }

        assertThat(thrown).hasStackTraceContaining("default-preset")
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): DescriptionProperties = ProductionYamlBinder.bind("application.ai.description", DescriptionProperties::class.java, env, properties)
}
