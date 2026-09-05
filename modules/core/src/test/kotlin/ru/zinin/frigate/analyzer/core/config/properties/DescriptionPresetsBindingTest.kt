package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ByteArrayResource
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

    /**
     * Порядок пресетов приходит из настоящего YAML-документа, а не из `mapOf`: правило
     * «fallback — первый годный пресет» опирается на порядок объявления, и держать его должна вся
     * цепочка YamlPropertySourceLoader -> Binder -> LinkedHashMap, а не тот порядок, в котором тест
     * сам сложил Map. Ключи объявлены не по алфавиту, поэтому сортировка или хеширование в любом
     * звене этой цепочки даст другую последовательность и уронит containsExactly.
     */
    @Test
    fun `declaration order in a yaml document survives the binder`() {
        val props =
            bindYaml(
                """
                application:
                  ai:
                    description:
                      presets:
                        zeta:
                          provider: grok
                          model: grok-4.6
                          effort: high
                        alpha:
                          provider: claude
                          model: opus
                        middle:
                          provider: grok
                          model: grok-4.6-fast
                """.trimIndent(),
            )

        assertThat(props.presets.keys).containsExactly("zeta", "alpha", "middle")
        assertThat(props.presets.getValue("zeta").effort).isEqualTo("high")
    }

    /**
     * Кладёт разобранный документ поверх production-yaml тем же загрузчиком, которым Spring Boot
     * читает `application.yaml` на старте: только так путь ключей до карты совпадает с боевым.
     */
    private fun bindYaml(yaml: String): DescriptionProperties {
        val environment = ProductionYamlBinder.environment()
        YamlPropertySourceLoader()
            .load("presets.yaml", ByteArrayResource(yaml.toByteArray()))
            .forEach { environment.propertySources.addFirst(it) }
        return Binder.get(environment).bind("application.ai.description", DescriptionProperties::class.java).get()
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): DescriptionProperties = ProductionYamlBinder.bind("application.ai.description", DescriptionProperties::class.java, env, properties)
}
