package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class StoryboardPropertiesBindingTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `with nothing set the storyboard is on with the documented defaults`() {
        val props = bind()

        assertThat(props).isEqualTo(
            StoryboardProperties(
                enabled = true,
                before = Duration.ofSeconds(5),
                after = Duration.ofSeconds(5),
                tiles = 16,
                nextSegmentWait = Duration.ofSeconds(30),
            ),
        )
        assertThat(validator.validate(props)).isEmpty()
    }

    @Test
    fun `every knob follows its environment variable`() {
        val props =
            bind(
                env =
                    mapOf(
                        "APP_AI_DESCRIPTION_STORYBOARD_ENABLED" to "false",
                        "APP_AI_DESCRIPTION_STORYBOARD_BEFORE" to "3s",
                        "APP_AI_DESCRIPTION_STORYBOARD_AFTER" to "8s",
                        "APP_AI_DESCRIPTION_STORYBOARD_TILES" to "9",
                        "APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT" to "60s",
                    ),
            )

        assertThat(props).isEqualTo(
            StoryboardProperties(
                enabled = false,
                before = Duration.ofSeconds(3),
                after = Duration.ofSeconds(8),
                tiles = 9,
                nextSegmentWait = Duration.ofSeconds(60),
            ),
        )
    }

    @Test
    fun `a tile count outside 4 to 25 is rejected by validation`() {
        val violations = validator.validate(bind(env = mapOf("APP_AI_DESCRIPTION_STORYBOARD_TILES" to "26")))

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("tiles")
    }

    @Test
    fun `a margin above ten seconds fails the binding`() {
        assertThatThrownBy { bind(env = mapOf("APP_AI_DESCRIPTION_STORYBOARD_BEFORE" to "11s")) }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a wait above two minutes fails the binding`() {
        assertThatThrownBy { bind(env = mapOf("APP_AI_DESCRIPTION_STORYBOARD_NEXT_SEGMENT_WAIT" to "121s")) }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bind(env: Map<String, Any> = emptyMap()): StoryboardProperties =
        ProductionYamlBinder.bind("application.ai.description.storyboard", StoryboardProperties::class.java, env = env)
}
