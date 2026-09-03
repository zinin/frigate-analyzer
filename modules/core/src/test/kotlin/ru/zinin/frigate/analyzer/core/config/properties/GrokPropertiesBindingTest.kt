package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Path

/**
 * Binds `application.ai.description.grok` out of the production yaml via [ProductionYamlBinder].
 * The section binds on every deployment, Claude ones included, so a defaulting mistake here would
 * stop a container that never asked for Grok.
 */
class GrokPropertiesBindingTest {
    @Test
    fun `defaults follow the spec`() {
        val props = bind()

        assertThat(props.cliPath).isEmpty()
        assertThat(props.model).isEqualTo("grok-4.6")
        assertThat(props.effort).isEqualTo("low")
        assertThat(props.homePath).isEqualTo(Path.of("/tmp/frigate-analyzer/grok-home"))
        assertThat(props.workingDirectoryPath).isEqualTo(Path.of("/tmp/frigate-analyzer/grok-cwd"))
        assertThat(props.proxy.http).isEmpty()
        assertThat(props.proxy.https).isEmpty()
        assertThat(props.proxy.noProxy).isEmpty()
        assertThat(props.passThroughEnv).isEmpty()
    }

    @Test
    fun `GROK_PASS_THROUGH_ENV binds to the list of names`() {
        val props = bind(env = mapOf("GROK_PASS_THROUGH_ENV" to "MY_GATEWAY_KEY,SECOND_KEY"))

        assertThat(props.passThroughEnv).containsExactly("MY_GATEWAY_KEY", "SECOND_KEY")
    }

    @Test
    fun `an empty GROK_EFFORT binds to an empty string, not the default`() {
        val props = bind(env = mapOf("GROK_EFFORT" to ""))

        assertThat(props.effort).isEmpty()
    }

    @Test
    fun `GROK_HOME and GROK_MODEL override the defaults`() {
        val props = bind(env = mapOf("GROK_HOME" to "/application/grok-home", "GROK_MODEL" to "dks-vision"))

        assertThat(props.homePath).isEqualTo(Path.of("/application/grok-home"))
        assertThat(props.model).isEqualTo("dks-vision")
    }

    @Test
    fun `TEMP_FOLDER moves both default directories`() {
        val props = bind(env = mapOf("TEMP_FOLDER" to "/var/tmp/fa"))

        assertThat(props.homePath).isEqualTo(Path.of("/var/tmp/fa/grok-home"))
        assertThat(props.workingDirectoryPath).isEqualTo(Path.of("/var/tmp/fa/grok-cwd"))
    }

    private fun bind(env: Map<String, Any> = emptyMap()): GrokProperties =
        ProductionYamlBinder.bind("application.ai.description.grok", GrokProperties::class.java, env)
}
